package main

import "errors"

// LZ4 block 格式解码器（无帧头、无校验和，纯 block）。
//
// 为什么不用 compress/zlib：在 i.MX6 上 zlib 解 2764800 字节要 90-140ms，
// 直接把整条链路锁死在 5-8fps（instrument-projection 的实测结论）。
// LZ4 block 解码是纯顺序遍历 + 长距离 memcpy，同尺寸约 20-30ms。
//
// block 结构：
//   token(1B) = 高4位 literal 长度 | 低4位 match 长度
//   长度 >= 15 时后续每字节累加，遇 255 继续
//   literal 字节
//   offset(2B, 小端)  —— 仅当后面还有数据时存在
//   match 扩展长度
// 末尾最后一个序列只有 literal，没有 offset。

var (
	errLZ4Short    = errors.New("lz4: truncated input")
	errLZ4Overflow = errors.New("lz4: output overflow")
	errLZ4Offset   = errors.New("lz4: bad match offset")
)

// fixZeroPixels 把 RGB565 里纯零的像素抬成 0x0001（前景层色键 key=0 会把零当透明）。
// 逐字节比较即可：像素两字节同时为 0 才需要抬最低位。
func fixZeroPixels(b []byte) {
	for i := 0; i+1 < len(b); i += 2 {
		if b[i] == 0 && b[i+1] == 0 {
			b[i] = 1
		}
	}
}

// lz4DecodeBlock 解压 src 到 dst，返回写入字节数。
func lz4DecodeBlock(src, dst []byte) (int, error) {
	return lz4Decode(src, dst, false)
}

// lz4DecodeFix 同 lz4DecodeBlock，但写入的 literal 顺带修零。
// 关键性质：LZ4 的 match 永远引用「已输出」区域，因此只要所有 literal
// 都修过零，整块输出必然已修零——省掉独立的一趟修零扫描。
func lz4DecodeFix(src, dst []byte) (int, error) {
	return lz4Decode(src, dst, true)
}

// lz4Decode 解压 src 到 dst，返回写入字节数。
// dst 必须足够大；解压后通常应当正好等于目标帧大小。
func lz4Decode(src, dst []byte, fix bool) (int, error) {
	var ip, op int
	n := len(src)

	for ip < n {
		token := src[ip]
		ip++

		litLen := int(token >> 4)
		if litLen == 15 {
			for {
				if ip >= n {
					return op, errLZ4Short
				}
				b := int(src[ip])
				ip++
				litLen += b
				if b != 255 {
					break
				}
			}
		}

		if litLen > 0 {
			if ip+litLen > n || op+litLen > len(dst) {
				return op, errLZ4Overflow
			}
			copy(dst[op:op+litLen], src[ip:ip+litLen])
			if fix {
				fixZeroPixels(dst[op : op+litLen])
			}
			ip += litLen
			op += litLen
		}

		// 最后一个序列的 literal 之后直接结束
		if ip >= n {
			break
		}

		if ip+2 > n {
			return op, errLZ4Short
		}
		offset := int(src[ip]) | int(src[ip+1])<<8
		ip += 2
		if offset == 0 || offset > op {
			return op, errLZ4Offset
		}

		matchLen := int(token & 15)
		if matchLen == 15 {
			for {
				if ip >= n {
					return op, errLZ4Short
				}
				b := int(src[ip])
				ip++
				matchLen += b
				if b != 255 {
					break
				}
			}
		}
		matchLen += 4 // MINMATCH

		if op+matchLen > len(dst) {
			return op, errLZ4Overflow
		}
		// 重叠匹配（offset < matchLen）按 offset 步进逐字节搬。
		// 曾试过「按 offset 分段 memmove」，在 ARMv7 上反而更慢——小长度
		// memmove 的调用开销盖过了收益（实测 15.8ms -> 22.6ms）。
		m := op - offset
		if offset >= matchLen {
			copy(dst[op:op+matchLen], dst[m:m+matchLen])
			op += matchLen
		} else {
			for i := 0; i < matchLen; i++ {
				dst[op+i] = dst[m+i]
			}
			op += matchLen
		}
	}
	return op, nil
}
