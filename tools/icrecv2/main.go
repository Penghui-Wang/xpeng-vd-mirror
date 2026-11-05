//go:build linux

// icrecv —— 仪表侧帧接收 / 合成 / 显示程序
// 目标平台: linux/arm (i.MX6, kernel 3.0.35, 无 CGO)
//
// 用法:
//
//	icrecv info [fb]                     打印 fb 几何与通道位域
//	icrecv restore                       恢复出厂 fb1 几何
//	icrecv fill <rgb565hex> <hold>       纯色测试
//	icrecv paint <hold> <x,y,w,h,color>...
//	                                     分区绘制：色块自绘，未覆盖区域透出 fb0
//	                                     color = hole | #RRGGBB | RRRR(RGB565)
//	icrecv file <raw> <w> <h> <hold>     显示整幅 RGB565 裸帧
//	icrecv compose <port> <hold> <top> <bot>
//	                                     接收中间带帧流, 与 fb0 上下条合成后显示
//	icrecv demo <hold> [top] [bot]       不接流，直接铺版式（地图底渐变 + 卡片 + fb0 上下条）
//	icrecv dump <path> <div> <fb0|fb1>   把 fb0/fb1 存成 PNG（div 为缩小倍数）
//
// 帧协议 (小端):
//
//	magic "ICF1" | w u32 | h u32 | fmt u32 | complen u32 | rawlen u32 | zlib(raw)
package main

import (
	"bufio"
	"compress/zlib"
	"encoding/binary"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

var flushN int
var panOK, panFail, pwN, subFail int

// 落屏策略开关（默认都开，都是 v1.0 的帧率优化）：
//
//	fbMirror   ICEBCOPY=1  —— 写完 pwrite 之后再把同一段 copy 进 mmap 的 fb。
//	                          上屏只靠 pwrite，这份 copy 是纯浪费，默认关。
//	narrowWrite ICROWS=0   —— 关掉「只写矩形行宽」，退回整宽行一次 pwrite。
//	                          默认开：1920 宽里只有 550 宽是投屏内容。
var (
	fbMirror    = os.Getenv("ICFBCOPY") == "1" || os.Getenv("ICWB") == "skip"
	narrowWrite = os.Getenv("ICROWS") != "0"
	// keyFix: ICKEY=1 让 1:1 贴图后再逐像素把 0 抬成 1。
	// 正常不需要（发送端已修），只在对面是旧版发送端时兜底。
	keyFix = os.Getenv("ICKEY") == "1"
)

const (
	ioctlGetVar = 0x4600
	ioctlGetFix = 0x4602
	ioctlPutVar = 0x4601
	ioctlBlank  = 0x4611
	ioctlPan    = 0x460A
	ioctlAlpha  = 0x40084621
	ioctlKey    = 0x40084622
	ioctlGBlank = 0x8004462B
	ioctlChan   = 0x80044625

	targetW = 1920
	targetH = 720

	fb1Path = "/dev/fb1"
	fb0Path = "/dev/fb0"

	sysModes  = "/sys/class/graphics/fb1/modes"
	sysMode   = "/sys/class/graphics/fb1/mode"
	sysVSize  = "/sys/class/graphics/fb1/virtual_size"
	wideStr   = "U:1920x720p-60\n"
	narrowStr = "U:240x320p-60\n"
	narrowVS  = "240,960\n"

	baseW, baseH, baseVW, baseVH = 240, 320, 240, 960
	baseStride                   = 480
	minSmem                      = 460800
	maxSmem                      = 8388608
)

// 帧载荷编码方式（ICF1 头里的 fmt 字段）
const (
	fmtZlib = 0
	fmtRaw  = 1
	fmtLZ4  = 2
)

type fbVar struct {
	Xres, Yres, XresVirtual, YresVirtual uint32
	Xoffset, Yoffset                     uint32
	BitsPerPixel, Grayscale              uint32
	Red, Green, Blue, Transp             [3]uint32
	Nonstd, Activate, Height, Width      uint32
	AccelFlags                           uint32
	Pixclock, LeftMargin, RightMargin    uint32
	UpperMargin, LowerMargin             uint32
	HsyncLen, VsyncLen, Sync, Vmode      uint32
	Rotate, Colorspace                   uint32
	Reserved                             [4]uint32
}

func ioctl(fd int, req uintptr, arg unsafe.Pointer) error {
	_, _, e := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), req, uintptr(arg))
	if e != 0 {
		return e
	}
	return nil
}

func getVar(fd int) (*fbVar, error) {
	v := &fbVar{}
	if err := ioctl(fd, ioctlGetVar, unsafe.Pointer(v)); err != nil {
		return nil, err
	}
	return v, nil
}

func putVar(fd int, v *fbVar) error {
	return ioctl(fd, ioctlPutVar, unsafe.Pointer(v))
}

func describe(v *fbVar) string {
	return fmt.Sprintf("%dx%d virt=%dx%d off=%d,%d bpp=%d rotate=%d",
		v.Xres, v.Yres, v.XresVirtual, v.YresVirtual, v.Xoffset, v.Yoffset,
		v.BitsPerPixel, v.Rotate)
}

func strideOf(v *fbVar) int { return int(v.XresVirtual) * int(v.BitsPerPixel) / 8 }

func openFB(path string) int {
	fd, err := syscall.Open(path, syscall.O_RDWR, 0)
	if err != nil {
		die("open " + path + ": " + err.Error())
	}
	return fd
}

func die(msg string) {
	fmt.Fprintln(os.Stderr, "FATAL:", msg)
	os.Exit(1)
}

func logf(format string, a ...interface{}) {
	fmt.Printf(format+"\n", a...)
}

func atoiDefault(s string, d int) int {
	if s == "" {
		return d
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return d
	}
	return n
}

// arg 安全取第 i 个参数，越界返回空串
func arg(i int) string {
	if i < len(os.Args) {
		return os.Args[i]
	}
	return ""
}

func argInt(i, d int) int { return atoiDefault(arg(i), d) }

// ---------- 像素通道 ----------

type packer struct {
	rOff, rLen uint32
	gOff, gLen uint32
	bOff, bLen uint32
	lutR       [256]uint16
	lutG       [256]uint16
	lutB       [256]uint16
}

func maskOf(n uint32) uint32 {
	if n >= 32 {
		return 0xffffffff
	}
	if n == 0 {
		return 0
	}
	return (1 << n) - 1
}

func newPacker(v *fbVar) *packer {
	p := &packer{
		rOff: v.Red[0], rLen: v.Red[1],
		gOff: v.Green[0], gLen: v.Green[1],
		bOff: v.Blue[0], bLen: v.Blue[1],
	}
	for i := 0; i < 256; i++ {
		x := uint32(i)
		p.lutR[i] = uint16((x * 31) / 255)
		p.lutG[i] = uint16((x * 63) / 255)
		p.lutB[i] = uint16((x * 31) / 255)
	}
	return p
}

func (p *packer) idx(px uint32, off, length uint32) uint32 {
	m := maskOf(length)
	if m == 0 {
		return 0
	}
	val := (px >> off) & m
	return (val * 255) / m
}

// fast: fb0 是最常见的 8/8/8 位域（XRGB8888），此时可以完全绕过乘除法。
// 逐个通道 (val*255)/m 在 ARM 上要走 __aeabi_uidiv，每像素三次除法是这个
// 函数慢掉一个数量级的主因。
func (p *packer) fast() bool {
	return p.rLen == 8 && p.gLen == 8 && p.bLen == 8 &&
		p.rOff%8 == 0 && p.gOff%8 == 0 && p.bOff%8 == 0 &&
		p.rOff < 32 && p.gOff < 32 && p.bOff < 32
}

// byteIdx: 位域 offset 换算成小端内存里的字节下标
func byteIdx(off uint32) int { return int(off / 8) }

func (p *packer) to565(px uint32) uint16 {
	if p.fast() {
		return p.to565_888(px)
	}
	r := p.lutR[p.idx(px, p.rOff, p.rLen)]
	g := p.lutG[p.idx(px, p.gOff, p.gLen)]
	b := p.lutB[p.idx(px, p.bOff, p.bLen)]
	return r<<11 | g<<5 | b
}

func (p *packer) to565_888(px uint32) uint16 {
	r := (px >> p.rOff) & 0xff
	g := (px >> p.gOff) & 0xff
	b := (px >> p.bOff) & 0xff
	return uint16(r>>3)<<11 | uint16(g>>2)<<5 | uint16(b>>3)
}

// asU16 / asU32: framebuffer 是 uncached 映射，逐字节访问等于每字节一次总线
// 事务，比按字访问慢一个数量级。凡是整行读写的热路径都走这两个视图。
func asU16(b []byte) []uint16 {
	if len(b) < 2 {
		return nil
	}
	return unsafe.Slice((*uint16)(unsafe.Pointer(&b[0])), len(b)/2)
}

func asU32(b []byte) []uint32 {
	if len(b) < 4 {
		return nil
	}
	return unsafe.Slice((*uint32)(unsafe.Pointer(&b[0])), len(b)/4)
}

func mmapFB(fd int, size int) []byte {
	buf, err := syscall.Mmap(fd, 0, size,
		syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
	if err != nil {
		die("mmap: " + err.Error())
	}
	return buf
}

func mmapFBRO(fd int, size int) []byte {
	buf, err := syscall.Mmap(fd, 0, size,
		syscall.PROT_READ, syscall.MAP_SHARED)
	if err != nil {
		die("mmap ro: " + err.Error())
	}
	return buf
}

// xmapCache: 最近邻缩放的横坐标映射表。
// 原来在像素循环里算 x*sw/dw，每像素一次整数除法，在 ARM 上是致命开销。
var (
	xmapMu   sync.Mutex
	xmapKey  [2]int
	xmapData []uint32
)

func xmap(sw, dw int) []uint32 {
	xmapMu.Lock()
	defer xmapMu.Unlock()
	if xmapKey == [2]int{sw, dw} && xmapData != nil {
		return xmapData
	}
	m := make([]uint32, dw)
	for x := 0; x < dw; x++ {
		sx := x * sw / dw
		if sx >= sw {
			sx = sw - 1
		}
		m[x] = uint32(sx)
	}
	xmapKey = [2]int{sw, dw}
	xmapData = m
	return m
}

// scaleInto: 最近邻缩放；同时把纯黑(0)抬到 1。
// 因为前景层开了色键(key=0 透明)，纯黑像素会漏出后背层的车速表。
// fixZeroWords 把 RGB565 的 0x0000 改成 0x0001（按 uint32 一次处理两个像素）。
// 前景层色键 key=0 会把纯零当透明，所以纯黑必须抬成近黑，否则会透出 fb0。
func fixZeroWords(b []uint32) {
	for i, v := range b {
		if v == 0 {
			b[i] = 0x00010001
			continue
		}
		if v&0xFFFF == 0 {
			v |= 0x0001
		}
		if v>>16 == 0 {
			v |= 0x00010000
		}
		b[i] = v
	}
}

func scaleInto(dst []byte, dstStride, dw, dh int, src []byte, sw, sh int) {
	if dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0 || len(src) < sw*sh*2 {
		return
	}
	if len(dst) < dh*dstStride || dstStride < dw*2 {
		return
	}
	// 同宽快路径：单趟融合「拷贝 + 修零」，一次读一次写
	// （先 memmove 再单独扫一遍会把目标缓冲的读写各做两次，实测慢一倍）
	if sw == dw {
		rowBytes := dw * 2 &^ 3
		if rowBytes < 4 {
			rowBytes = 4
		}
		for y := 0; y < dh; y++ {
			sy := y * sh / dh
			if sy >= sh {
				sy = sh - 1
			}
			doff := y * dstStride
			soff := sy * sw * 2
			sw32 := asU32(src[soff : soff+rowBytes])
			dw32 := asU32(dst[doff : doff+rowBytes])
			for i, v := range sw32 {
				if v == 0 {
					dw32[i] = 0x00010001
					continue
				}
				if v&0xFFFF == 0 {
					v |= 0x0001
				}
				if v>>16 == 0 {
					v |= 0x00010000
				}
				dw32[i] = v
			}
		}
		return
	}
	srcU := asU16(src)
	dx := xmap(sw, dw)

	for y := 0; y < dh; y++ {
		sy := y * sh / dh
		if sy >= sh {
			sy = sh - 1
		}
		srow := srcU[sy*sw : sy*sw+sw]
		doff := y * dstStride
		drow := asU16(dst[doff : doff+dw*2])
		if len(drow) < dw {
			return
		}
		for x := 0; x < dw; x++ {
			v := srow[dx[x]]
			if v == 0 {
				v = 1
			}
			drow[x] = v
		}
	}
}

// ---------- 几何 ----------

type geom struct {
	W, H, Wv, Hv, Bpp uint32
}

func snapshot(fd int) geom {
	v, err := getVar(fd)
	if err != nil {
		die("get var: " + err.Error())
	}
	return geom{v.Xres, v.Yres, v.XresVirtual, v.YresVirtual, v.BitsPerPixel}
}

func applyGeom(fd int, g geom) error {
	v, err := getVar(fd)
	if err != nil {
		return err
	}
	v.Xres = g.W
	v.Yres = g.H
	v.XresVirtual = g.Wv
	v.YresVirtual = g.Hv
	v.BitsPerPixel = g.Bpp
	v.Xoffset = 0
	v.Yoffset = 0
	return putVar(fd, v)
}

// fixScreenInfo 对应内核 struct fb_fix_screeninfo（68 字节）
type fixScreenInfo struct {
	ID                                [16]byte
	SmemStart, SmemLen, Type, TypeAux uint32
	Visual                            uint32
	Xpanstep, Ypanstep, Ywrapstep     uint16
	LineLength                        uint32
	MmioStart, MmioLen, Accel         uint32
	Reserved                          [3]uint16
}

// videoMode 对应内核 struct fb_videomode（56 字节）
type videoMode struct {
	Name, Refresh, Xres, Yres, Pixclock uint32
	Left, Right, Upper, Lower           uint32
	Hsync, Vsync, Sync, Vmode, Flag     uint32
}

func getFix(fd int) (*fixScreenInfo, error) {
	f := &fixScreenInfo{}
	if err := ioctl(fd, ioctlGetFix, unsafe.Pointer(f)); err != nil {
		return nil, err
	}
	return f, nil
}

func bytesOf(p unsafe.Pointer, n int) []byte {
	return unsafe.Slice((*byte)(p), n)
}

// sysWrite: zeroOK=true 时接受 write() 返回 0。
// mxc_sdc_fb 的 modes store 回调返回 0 而不是 count，但内容其实已经生效，
// 必须容忍，否则会误判失败而跳过后续的 mode 字符串写入。
func sysWrite(path string, b []byte, zeroOK bool) error {
	fd, err := syscall.Open(path, syscall.O_WRONLY, 0)
	if err != nil {
		return fmt.Errorf("open %s: %v", path, err)
	}
	defer syscall.Close(fd)
	n, err := syscall.Write(fd, b)
	if err != nil {
		return fmt.Errorf("write %s: %v", path, err)
	}
	if n != len(b) && !(zeroOK && n == 0) {
		return fmt.Errorf("short write %s: %d/%d", path, n, len(b))
	}
	return nil
}

func videoOf(v *fbVar) videoMode {
	return videoMode{
		Name: 0, Refresh: 60,
		Xres: v.Xres, Yres: v.Yres, Pixclock: v.Pixclock,
		Left: v.LeftMargin, Right: v.RightMargin,
		Upper: v.UpperMargin, Lower: v.LowerMargin,
		Hsync: v.HsyncLen, Vsync: v.VsyncLen,
		Sync: v.Sync, Vmode: v.Vmode, Flag: 0,
	}
}

// setModeWide: 把 fb0 的模式注入 fb1 的模式表，再切到 1920x720
// 这是这个驱动唯一可行的宽屏方式（FBIOPUT_VSCREENINFO 会被静默忽略）
func setModeWide(fgV, bgV *fbVar) error {
	m := [2]videoMode{videoOf(fgV), videoOf(bgV)}
	if err := sysWrite(sysModes, bytesOf(unsafe.Pointer(&m[0]), 112), true); err != nil {
		return err
	}
	return sysWrite(sysMode, []byte(wideStr), false)
}

// setModeNarrow: 恢复窄屏并还原 3 缓冲。
//
// 必须和 setModeWide 一样写满 112 字节（两个 videoMode）。
// 只写 56 字节会把模式表第 1 槽覆盖成当前宽屏模式，240x320 从此不在表里，
// 紧接着写 mode="U:240x320p-60" 驱动必定返回 EINVAL，仪表就卡在宽屏。
// 第 2 槽必须带 fb0 模式，否则 fb0 的显示通路也会失去时序参照。
func setModeNarrow(cur, bgV *fbVar) error {
	t := *cur
	t.Xres, t.Yres = baseW, baseH
	t.XresVirtual, t.YresVirtual = baseVW, baseVH
	m := [2]videoMode{videoOf(&t)}
	n := 56
	if bgV != nil {
		m[1] = videoOf(bgV)
		n = 112
	}
	if err := sysWrite(sysModes, bytesOf(unsafe.Pointer(&m[0]), n), true); err != nil {
		return err
	}
	if err := sysWrite(sysMode, []byte(narrowStr), false); err != nil {
		return err
	}
	return sysWrite(sysVSize, []byte(narrowVS), false)
}

func setBlank(fd int, lvl uintptr) error {
	return ioctlRaw(fd, ioctlBlank, lvl)
}

func setAlpha(fd int, enable, val int32) error {
	a := [2]int32{enable, val}
	return ioctl(fd, ioctlAlpha, unsafe.Pointer(&a))
}

func setKey(fd int, enable, val int32) error {
	k := [2]int32{enable, val}
	return ioctl(fd, ioctlKey, unsafe.Pointer(&k))
}

// ---------- 显示核心 ----------

type display struct {
	fd          int
	bgFd        int
	orig        geom
	origVar     *fbVar
	origFix     *fixScreenInfo
	origPx      []byte
	fb          []byte
	stride      int
	mappedStart uint32 // 当前 mmap 对应的 fb1 显存物理起始地址

	fb0     []byte
	fb0Var  *fbVar
	packer0 *packer
	back    []byte // cache 内后台缓冲，所有绘制先落这里再一次性 flush
	rowbuf  []byte // 复用的 fb0 行缓冲
	comp    []byte // 复用的压缩载荷缓冲（避免每帧 mmap 大对象）
	raw     []byte // 复用的解压结果缓冲
	top     int
	bot     int
	midTop  int
	midH    int
	// 投屏目标矩形（fb1 坐标）。默认 = 整条横带 (0,midTop,targetW,midH)，
	// 与改造前行为完全一致；传了自定义区域就变成带内/跨带的任意子矩形。
	dstX  int
	dstY  int
	dstW  int
	dstH  int
	ready bool
}

func mustFix(fd int) *fixScreenInfo {
	f, err := getFix(fd)
	if err != nil {
		die("get fix: " + err.Error())
	}
	return f
}

func (d *display) atBaseline() bool {
	v := mustVar(d.fd)
	f := mustFix(d.fd)
	if int(v.Xres) != baseW || int(v.Yres) != baseH || int(v.BitsPerPixel) != 16 ||
		int(v.XresVirtual) != baseVW || int(v.YresVirtual) != baseVH ||
		int(f.LineLength) != baseStride {
		return false
	}
	var blank uint32 = 99
	if err := ioctl(d.fd, ioctlGBlank, unsafe.Pointer(&blank)); err != nil {
		return false
	}
	return blank == 4
}

func newDisplay(top, bot int) *display {
	d := &display{top: top, bot: bot}
	d.midTop = top
	d.midH = targetH - top - bot
	// 默认投屏区 = 整条横带（老行为）
	d.dstX, d.dstY, d.dstW, d.dstH = 0, d.midTop, targetW, d.midH

	d.fd = openFB(fb1Path)

	// fb0（原车画面）只读映射，用于取上下条
	fd0, err := syscall.Open(fb0Path, syscall.O_RDWR, 0)
	if err != nil {
		fd0, err = syscall.Open(fb0Path, syscall.O_RDONLY, 0)
	}
	if err == nil {
		d.bgFd = fd0
		v0, err0 := getVar(fd0)
		if err0 == nil {
			d.fb0Var = v0
			d.packer0 = newPacker(v0)
			size0 := strideOf(v0) * int(v0.YresVirtual)
			d.fb0 = mmapFBRO(fd0, size0)
			logf("fb0: %s stride=%d R(%d/%d) G(%d/%d) B(%d/%d)",
				describe(v0), strideOf(v0),
				v0.Red[0], v0.Red[1], v0.Green[0], v0.Green[1], v0.Blue[0], v0.Blue[1])
		} else {
			logf("fb0 get var fail: %v", err0)
		}
	} else {
		logf("fb0 open fail: %v", err)
	}
	if d.fb0Var == nil {
		die("fb0 unavailable")
	}

	// 若基线已被破坏（vy 不是 960 / blank 不是 4），先强制复位
	if !d.atBaseline() {
		logf("baseline drift -> force narrow")
		_ = setBlank(d.fd, 4)
		_ = setModeNarrow(mustVar(d.fd), d.fb0Var)
		time.Sleep(250 * time.Millisecond)
		_ = setBlank(d.fd, 4)
		time.Sleep(150 * time.Millisecond)
	}

	d.origVar = mustVar(d.fd)
	d.orig = snapshot(d.fd)
	d.origFix = mustFix(d.fd)
	logf("orig fb1: %s stride=%d smem=%d",
		describe(d.origVar), d.origFix.LineLength, d.origFix.SmemLen)

	// 备份原像素
	if int(d.origFix.SmemLen) > 0 && int(d.origFix.SmemLen) < maxSmem {
		base := mmapFBRO(d.fd, int(d.origFix.SmemLen))
		d.origPx = make([]byte, d.origFix.SmemLen)
		copy(d.origPx, base)
		logf("backup %d bytes", len(d.origPx))
	}

	// 切宽屏：注入模式表再切模式
	if err := setModeWide(mustVar(d.fd), d.fb0Var); err != nil {
		logf("SETMODE_WIDE_FAIL %v", err)
		d.restore()
		die("setModeWide failed")
	}
	v := mustVar(d.fd)
	f := mustFix(d.fd)
	logf("wide fb1: %s stride=%d smem=%d", describe(v), f.LineLength, f.SmemLen)
	if int(v.Xres) != targetW || int(v.Yres) != targetH || int(v.BitsPerPixel) != 16 ||
		int(f.LineLength) != targetW*2 || int(f.SmemLen) < targetW*targetH*2 {
		d.restore()
		die(fmt.Sprintf("REFUSED_WIDE_GEOMETRY: %dx%d bpp=%d stride=%d smem=%d",
			v.Xres, v.Yres, v.BitsPerPixel, f.LineLength, f.SmemLen))
	}
	d.stride = int(f.LineLength)

	d.fb = mmapFB(d.fd, int(f.SmemLen))
	d.mappedStart = f.SmemStart
	d.back = make([]byte, d.stride*targetH)
	d.rowbuf = make([]byte, strideOf(d.fb0Var))
	for i := range d.back {
		d.back[i] = 0
	}

	d.ready = true
	return d
}

// setDstRect: 设定投屏目标矩形（fb1 坐标），越界自动裁剪到画面内。
func (d *display) setDstRect(x, y, w, h int) {
	if w <= 0 || h <= 0 {
		return
	}
	if x < 0 {
		x = 0
	}
	if y < 0 {
		y = 0
	}
	if x+w > targetW {
		w = targetW - x
	}
	if y+h > targetH {
		h = targetH - y
	}
	if w <= 0 || h <= 0 {
		return
	}
	d.dstX, d.dstY, d.dstW, d.dstH = x, y, w, h
}

// isFullBand: 目标矩形是否就是默认的整条横带。是的话所有老快路径原样生效。
func (d *display) isFullBand() bool {
	return d.dstX == 0 && d.dstY == d.midTop && d.dstW == targetW && d.dstH == d.midH
}

// dstInBand: 投屏矩形是否完全落在中间横带内（决定能否只刷中带）。
func (d *display) dstInBand() bool {
	return d.dstY >= d.midTop && d.dstY+d.dstH <= d.midTop+d.midH
}

// repaintOutside: 用原车画面(fb0)重铺投屏矩形之外的区域。
// 自定义小区域投屏时，仪表其余部分（车模、胎压、里程）要照常显示，
// 否则那些位置会停在上一帧甚至变成花屏。默认满宽横带时直接返回。
func (d *display) repaintOutside() {
	if d.fb0 == nil || d.fb0Var == nil || d.isFullBand() {
		return
	}
	bandBot := d.midTop + d.midH
	for y := d.midTop; y < bandBot; y++ {
		if y >= d.dstY && y < d.dstY+d.dstH {
			if d.dstX > 0 {
				d.blitRowFromFB0Range(y, y, 0, d.dstX)
			}
			if right := d.dstX + d.dstW; right < targetW {
				d.blitRowFromFB0Range(y, y, right, targetW)
			}
			continue
		}
		d.blitRowFromFB0Range(y, y, 0, targetW)
	}
}

// scaleFrame: 把 sw×sh 的 565 帧缩放搬进 d.back 的目标矩形。
// 与 scaleInto 的区别：目标不再是「从 0 开始的整条带」，而是带内任意位置，
// 所以逐行按 (y0+y)*stride + x0*2 定位；asU32 走快路径时要求起始地址 4 字节对齐
// （x0 为偶数），否则退回 u16 逐像素（ARM 上非对齐 u32 访问会炸）。
//
// 尺寸完全一致时（发送端已经按矩形大小发帧）走 1:1 整行拷贝：
// 不做重采样、不做 xmap，只把 0 像素修掉（仪表前景层的色键 key=0）。
func (d *display) scaleFrame(src []byte, sw, sh int) {
	x0, y0, dw, dh, stride := d.dstX, d.dstY, d.dstW, d.dstH, d.stride
	if dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0 || len(src) < sw*sh*2 {
		return
	}
	if sw == dw && sh == dh {
		d.blitExact(src, sw, sh)
		return
	}
	if sw == dw && x0&1 == 0 {
		rowBytes := dw * 2 &^ 3
		if rowBytes < 4 {
			rowBytes = 4
		}
		for y := 0; y < dh; y++ {
			sy := y * sh / dh
			if sy >= sh {
				sy = sh - 1
			}
			soff := sy * sw * 2
			doff := (y0+y)*stride + x0*2
			if soff+rowBytes > len(src) || doff+rowBytes > len(d.back) {
				continue
			}
			sw32 := asU32(src[soff : soff+rowBytes])
			dw32 := asU32(d.back[doff : doff+rowBytes])
			if len(sw32) != len(dw32) {
				continue
			}
			for i, v := range sw32 {
				if v == 0 {
					dw32[i] = 0x00010001
					continue
				}
				if v&0xFFFF == 0 {
					v |= 0x0001
				}
				if v>>16 == 0 {
					v |= 0x00010000
				}
				dw32[i] = v
			}
		}
		return
	}
	srcU := asU16(src)
	if srcU == nil {
		return
	}
	var xm []uint32
	if sw != dw {
		xm = xmap(sw, dw)
	}
	for y := 0; y < dh; y++ {
		sy := y * sh / dh
		if sy >= sh {
			sy = sh - 1
		}
		srow := srcU[sy*sw : sy*sw+sw]
		doff := (y0+y)*stride + x0*2
		if doff+dw*2 > len(d.back) {
			continue
		}
		drow := asU16(d.back[doff : doff+dw*2])
		if len(drow) < dw {
			continue
		}
		if xm == nil {
			for x := 0; x < dw; x++ {
				v := srow[x]
				if v == 0 {
					v = 1
				}
				drow[x] = v
			}
			continue
		}
		for x := 0; x < dw; x++ {
			v := srow[xm[x]]
			if v == 0 {
				v = 1
			}
			drow[x] = v
		}
	}
}

// blitExact: 帧尺寸与投屏矩形完全一致时，整行原样搬过去。
//
// 发送端已经按矩形大小出帧（SF 侧的缩放质量比这里好得多），色键（0→1）也在
// 发送端修过了（VdSend 的 565/ARGB 两条路径都修），所以这里纯粹是搬字节，
// 不需要逐像素检查。原来那一趟 21 万次 u16 比较在 i.MX6 上要 3~4ms，
// 60fps 就是四分之一预算。
// ICKEY=1 可退回「搬完再逐像素修零」的老行为（万一对面是旧版发送端）。
func (d *display) blitExact(src []byte, sw, sh int) {
	x0, y0, dw, dh, stride := d.dstX, d.dstY, d.dstW, d.dstH, d.stride
	if sw != dw || sh != dh || len(src) < sw*sh*2 {
		return
	}
	rowBytes := dw * 2
	for y := 0; y < dh; y++ {
		sb := y * sw * 2
		doff := (y0+y)*stride + x0*2
		if sb+rowBytes > len(src) || doff+rowBytes > len(d.back) {
			continue
		}
		copy(d.back[doff:doff+rowBytes], src[sb:sb+rowBytes])
	}
	if keyFix {
		fixZeroPixels(d.back[y0*stride+x0*2 : y0*stride+x0*2+dw*2])
		for y := 1; y < dh; y++ {
			off := (y0+y)*stride + x0*2
			fixZeroPixels(d.back[off : off+dw*2])
		}
	}
}

// blitRowFromFB0Range: 把 fb0 一行里 [x0,x1) 这段转成 RGB565 写进后台缓冲的 dy 行。
// 只在自定义投屏区域时需要「补左右两侧」，默认满宽走 x0=0,x1=targetW。
func (d *display) blitRowFromFB0Range(dy, sy, x0, x1 int) {
	if d.fb0 == nil || d.fb0Var == nil || x1 <= x0 {
		return
	}
	if x0 < 0 {
		x0 = 0
	}
	if x1 > targetW {
		x1 = targetW
	}
	n := x1 - x0
	s0 := int(d.fb0Var.XresVirtual) * 4
	sy += int(d.fb0Var.Yoffset)
	base := sy*s0 + int(d.fb0Var.Xoffset)*4
	if base < 0 || base+s0 > len(d.fb0) || s0 > len(d.rowbuf) {
		return
	}
	doff := dy*d.stride + x0*2
	if doff+n*2 > len(d.back) {
		return
	}
	copy(d.rowbuf, d.fb0[base:base+s0])
	src := asU32(d.rowbuf)
	dst := asU16(d.back[doff : doff+n*2])
	if len(src) < x1 || len(dst) < n {
		return
	}
	p := d.packer0
	if p.fast() {
		ro, go_, bo := p.rOff, p.gOff, p.bOff
		for x := x0; x < x1; x++ {
			px := src[x]
			r := (px >> ro) & 0xff
			g := (px >> go_) & 0xff
			b := (px >> bo) & 0xff
			v := uint16(r>>3)<<11 | uint16(g>>2)<<5 | uint16(b>>3)
			if v == 0 {
				v = 1
			}
			dst[x-x0] = v
		}
		return
	}
	for x := x0; x < x1; x++ {
		v := p.to565(src[x])
		if v == 0 {
			v = 1
		}
		dst[x-x0] = v
	}
}

func (d *display) blitRowFromFB0(dy, sy int) {
	d.blitRowFromFB0Range(dy, sy, 0, targetW)
}

// flush: 把后台缓冲一次性提交到 fb1。
// 2.7MB 的批量写远比 138 万次 uncached 读改写便宜。
//
// 不再先 copy 到 mmap 的 d.fb：真正让画面上屏的是 writeBack 的 pwrite
// （见 writeBack 注释，mmap 区域与硬件扫描的显存可能不一致）。
// 每帧多写一遍 2.7MB 纯属白烧带宽，ICFBCOPY=1 可恢复旧行为做对照。
func (d *display) flush() {
	n := len(d.back)
	if n > len(d.fb) {
		n = len(d.fb)
	}
	if fbMirror {
		copy(d.fb[:n], d.back[:n])
	}
	d.writeBack(0, n)
}

// flushMid 只把中间带刷到 fb1（上下条带仅在 refreshBands 后需要刷）。
// 单帧省下 152 行 uncached 写，约 0.58MB。
// ensureMapped: 原车程序会周期性重置 fb1（日志里的 baseline drift），
// 重置后物理显存起始地址会变，此时旧的 mmap 就成了孤儿——我们照常写、
// 进程内也读得回来（back 与 fb 完全一致），但屏幕上显示的还是旧内容。
// 每次刷新条带时比对 SmemStart，变了就重新映射。
func (d *display) ensureMapped() {
	f, err := getFix(d.fd)
	if err != nil {
		return
	}
	if f.SmemStart == d.mappedStart {
		return
	}
	v := mustVar(d.fd)
	if int(v.Xres) != targetW || int(v.Yres) != targetH {
		logf("REMAP geometry drift %dx%d (want %dx%d)", v.Xres, v.Yres, targetW, targetH)
		return
	}
	logf("REMAP smem %x -> %x", d.mappedStart, f.SmemStart)
	_ = syscall.Munmap(d.fb)
	d.fb = mmapFB(d.fd, int(f.SmemLen))
	d.mappedStart = f.SmemStart
	logf("REMAP ok len=%d", len(d.fb))
}

// submit: 把写进 fb1 的内存「交给」硬件。
// FBIOBLANK(0) 在设备已经点亮时会被 s3cfb 直接返回（blank 状态未变），
// 等于 no-op —— 这就是「启动后第一帧生效、之后画面停住」的真正原因。
// 正确做法是 FBIOPAN_DISPLAY：驱动会重设窗口缓冲区地址并等 vsync，
// 无闪烁，且每帧都能让新内容立刻上屏。
func (d *display) submit() {
	switch os.Getenv("ICSUBMIT") {
	case "pan":
		d.pan()
		return
	case "blank":
		_, _ = setBlankCycle(d.fd)
		return
	case "none":
		return
	case "putvar":
		// 每帧重设一次 var（参数不变），触发 s3cfb_set_par 重新编程窗口寄存器。
		v := mustVar(d.fd)
		v.Activate = 0
		if err := ioctl(d.fd, ioctlPutVar, unsafe.Pointer(v)); err != nil {
			subFail++
			if subFail%20 == 1 {
				logf("PUTVAR fail %v", err)
			}
		} else if subFail%60 == 1 {
			subFail++
			logf("PUTVAR ok n=%d", subFail)
		}
		return
	}
	// 默认：FBIOBLANK(0)。实测这是本平台唯一能让写入的内容上屏的提交方式
	// （pan 恒返回 EINVAL，因为 virtual 尺寸等于 visible，没有可 pan 的偏移）。
	if err := setBlank(d.fd, 0); err != nil {
		subFail++
		if subFail%20 == 1 {
			logf("SUBMIT fail %v", err)
		}
	}
}

func (d *display) pan() {
	v, err := getVar(d.fd)
	if err != nil {
		panFail++
		if panFail%20 == 1 {
			logf("PAN getvar %v", err)
		}
		return
	}
	v.Yoffset = 0
	v.Activate = 0 // FB_ACTIVATE_NOW
	if err := ioctl(d.fd, ioctlPan, unsafe.Pointer(v)); err != nil {
		panFail++
		if panFail%20 == 1 {
			logf("PAN fail %v", err)
		}
		return
	}
	panOK++
	if panOK%60 == 1 {
		logf("PAN ok n=%d yoff=%d", panOK, v.Yoffset)
	}
}

func setBlankCycle(fd int) (int, error) {
	_ = setBlank(fd, 1)
	return 0, setBlank(fd, 0)
}

// writeBack: 把后台缓冲的一段落到 fb1。
// 这里不用 mmap 的 copy：该平台 fb1 的 mmap 区域与硬件实际扫描的显存
// 可能不一致（进程内 back/fb 完全一致、dump 也读得到，但屏幕始终不动）。
// 直接 pwrite 设备节点，由驱动负责搬进真正的显存。
func (d *display) writeBack(off, end int) {
	if off < 0 || end > len(d.back) || off >= end {
		return
	}
	if os.Getenv("ICWB") == "skip" {
		return // 只走 mmap(copy 已在 flushMid 完成)，复现 fill 的写入路径
	}
	want := end - off
	n, err := syscall.Pwrite(d.fd, d.back[off:end], int64(off))
	pwN++
	if pwN <= 6 || pwN%300 == 0 || err != nil || n != want {
		logf("PWRITE#%d off=%d want=%d got=%d err=%v smem=%x mm=%x",
			pwN, off, want, n, err, d.mappedStart, len(d.fb))
	}
	if err != nil || n != want {
		if end <= len(d.fb) {
			copy(d.fb[off:end], d.back[off:end])
		}
	}
}

func (d *display) flushMid() {
	// 投屏矩形跨出中间带（盖到上下状态栏）时只刷中带不够，必须整幅刷
	if !d.dstInBand() {
		d.flush()
		return
	}
	off := d.midTop * d.stride
	end := off + d.midH*d.stride
	if end > len(d.fb) {
		end = len(d.fb)
	}
	if off >= end || end > len(d.back) {
		d.flush()
		return
	}
	if fbMirror {
		copy(d.fb[off:end], d.back[off:end])
	}
	flushN++
	if flushN%20 == 1 {
		logf("FLUSH#%d off=%d end=%d back=%x", flushN, off, end, d.back[off])
	}
	d.writeBack(off, end)
}

// writeBackRows: 只把 dstW 宽的那几行落到 fb1（narrow 模式）。
// 整宽写会把左右两侧的原仪表内容一起搬一遍：1920 宽里只有 550 宽有用，
// 写量 1.47MB → 0.42MB（550x384）。代价是 384 次 pwrite，每次 ~3~5us，
// 约 1.5ms 系统调用开销，换回 ~10ms 的显存带宽，净赚。
// 写失败时回退整宽 writeBack，保证画面不丢。
func (d *display) writeBackRows(y0, y1 int) {
	if y1 <= y0 || d.dstW <= 0 {
		return
	}
	x0 := d.dstX
	if x0 < 0 {
		x0 = 0
	}
	rowBytes := d.dstW * 2
	if x0*2+rowBytes > d.stride {
		rowBytes = d.stride - x0*2
	}
	if rowBytes <= 0 {
		return
	}
	bad := 0
	for y := y0; y < y1; y++ {
		off := y*d.stride + x0*2
		end := off + rowBytes
		if off < 0 || end > len(d.back) {
			continue
		}
		n, err := syscall.Pwrite(d.fd, d.back[off:end], int64(off))
		if err != nil || n != rowBytes {
			bad++
		}
	}
	pwN++
	if bad > 0 {
		logf("PWRITEROWS y=%d..%d x=%d w=%d bad=%d", y0, y1, x0, d.dstW, bad)
		// 有一行没写成就整块重来一遍，宁可慢也不要留花屏
		d.writeBack(y0*d.stride, y1*d.stride)
	}
}

// flushFrame: 有流时的落屏策略。
//
//	默认满宽横带 → 老路径 flushMid（中带整幅）
//	自定义矩形  → 只刷矩形那几行（narrow 模式按 dstW 窄写，否则整宽行一次 pwrite）
//	跨出中带    → 整幅 flush
//
// 小矩形逐行只写 dstW 宽：384 次 pwrite 换 1.05MB 带宽，实测划算（见 writeBackRows）。
func (d *display) flushFrame() {
	if d.isFullBand() {
		d.flushMid()
		return
	}
	if !d.dstInBand() {
		d.flush()
		return
	}
	y0, y1 := d.dstY, d.dstY+d.dstH
	if y0 < d.midTop {
		y0 = d.midTop
	}
	if y1 > d.midTop+d.midH {
		y1 = d.midTop + d.midH
	}
	if y1 <= y0 {
		d.flushMid()
		return
	}
	if narrowWrite && d.dstX >= 0 && d.dstW > 0 && d.dstW*2 < d.stride {
		d.writeBackRows(y0, y1)
		return
	}
	off := y0 * d.stride
	end := y1 * d.stride
	if end > len(d.fb) {
		end = len(d.fb)
	}
	if off >= end || end > len(d.back) {
		d.flush()
		return
	}
	if fbMirror {
		copy(d.fb[off:end], d.back[off:end])
	}
	d.writeBack(off, end)
}

func (d *display) refreshBands() {
	if d.fb0 == nil {
		return
	}
	for y := 0; y < d.top; y++ {
		d.blitRowFromFB0(y, y)
	}
	for y := 0; y < d.bot; y++ {
		d.blitRowFromFB0(targetH-d.bot+y, int(d.fb0Var.Yres)-d.bot+y)
	}
	// 自定义投屏区域时，把矩形之外的中间带也补回原车画面
	d.repaintOutside()
}

// enableBlend: 打开前景层混合并点亮
// 顺序与 iclock 一致：先设 fg 全局 alpha=255、色键 key=0，
// 校验 com&15==14（混合预置态），再 FBIOBLANK(0) 点亮，校验 com&15==15。
func (d *display) enableBlend() bool {
	if err := setAlpha(d.fd, 1, 255); err != nil {
		logf("ERROR FG_ALPHA %v", err)
		return false
	}
	if err := setKey(d.fd, 1, 0); err != nil {
		logf("ERROR BLACK_KEY %v", err)
		return false
	}
	if m := mapRegs(); m != nil {
		logf("BLEND_ARMED com=%d gw=0x%08x pos=%d", regAt(m, 0)&15, regAt(m, 1), regAt(m, 2))
		if regAt(m, 0)&15 != 14 || regAt(m, 1) != 0xff000000 || regAt(m, 2) != 0 {
			logf("WARN blend armed unexpected (com=%d)", regAt(m, 0)&15)
		}
	}
	if err := setBlank(d.fd, 0); err != nil {
		logf("ERROR ENABLE %v", err)
		return false
	}
	if m := mapRegs(); m != nil {
		logf("BLEND_ENABLED com=%d gw=0x%08x pos=%d", regAt(m, 0)&15, regAt(m, 1), regAt(m, 2))
		if regAt(m, 0)&15 != 15 || regAt(m, 1) != 0xff000000 {
			logf("WARN blend state unexpected")
		}
	} else {
		logf("BLEND_ENABLED (no regs)")
	}
	return true
}

func (d *display) restore() {
	logf("RESTORING")
	_ = setBlank(d.fd, 4)
	if d.origVar != nil {
		if err := setModeNarrow(d.origVar, d.fb0Var); err != nil {
			logf("ERROR RESTORE_MODE %v", err)
		}
	}
	time.Sleep(250 * time.Millisecond)

	v := mustVar(d.fd)
	f := mustFix(d.fd)
	logf("AFTER_RESTORE xres=%d yres=%d vx=%d vy=%d bpp=%d stride=%d smem=%d",
		v.Xres, v.Yres, v.XresVirtual, v.YresVirtual, v.BitsPerPixel, f.LineLength, f.SmemLen)

	if len(d.origPx) > 0 && int(f.SmemLen) <= maxSmem && int(f.SmemLen) > 0 {
		n := int(f.SmemLen)
		if n > len(d.origPx) {
			n = len(d.origPx)
		}
		buf := mmapFB(d.fd, n)
		copy(buf, d.origPx[:n])
		logf("pixels restored %d bytes", n)
	}

	_ = setKey(d.fd, 0, 0)
	if d.bgFd > 0 {
		_ = setAlpha(d.bgFd, 1, 128)
	}
	_ = setBlank(d.fd, 4)
	time.Sleep(200 * time.Millisecond)

	var blank uint32 = 99
	_ = ioctl(d.fd, ioctlGBlank, unsafe.Pointer(&blank))
	logf("RESTORED blank=%d", blank)
	logf("RESTORE_OK")
}

// ---------- 子命令 ----------

func cmdInfo(path string) {
	fd := openFB(path)
	v, err := getVar(fd)
	if err != nil {
		die("get var: " + err.Error())
	}
	logf("cur: %s stride=%d smem=%d", describe(v), strideOf(v), strideOf(v)*int(v.YresVirtual))
	logf("bits: R off=%d len=%d | G off=%d len=%d | B off=%d len=%d | A off=%d len=%d",
		v.Red[0], v.Red[1], v.Green[0], v.Green[1], v.Blue[0], v.Blue[1], v.Transp[0], v.Transp[1])
	logf("vmode=%d activate=%d nonstd=%d", v.Vmode, v.Activate, v.Nonstd)
}

func mustVar(fd int) *fbVar {
	v, err := getVar(fd)
	if err != nil {
		die("get var: " + err.Error())
	}
	return v
}

// cmdClone: 把 fb0 的完整时序/位域复制到 fb1，只改几何与 bpp
func cmdClone(w, h, bpp int) {
	fd1 := openFB(fb1Path)
	fd0, err := syscall.Open(fb0Path, syscall.O_RDONLY, 0)
	if err != nil {
		die("open fb0: " + err.Error())
	}
	v0, err := getVar(fd0)
	if err != nil {
		die("fb0 var: " + err.Error())
	}
	orig := snapshot(fd1)
	logf("orig fb1: %s", describe(mustVar(fd1)))
	logf("src  fb0: %s", describe(v0))

	v := *v0
	v.Xres = uint32(w)
	v.Yres = uint32(h)
	v.XresVirtual = uint32(w)
	v.YresVirtual = uint32(h)
	v.Xoffset = 0
	v.Yoffset = 0
	v.BitsPerPixel = uint32(bpp)
	if bpp == 16 {
		v.Red = [3]uint32{11, 5, 0}
		v.Green = [3]uint32{5, 6, 0}
		v.Blue = [3]uint32{0, 5, 0}
		v.Transp = [3]uint32{0, 0, 0}
	}
	v.Activate = 0
	err = putVar(fd1, &v)
	r := mustVar(fd1)
	logf("clone(bpp=%d) err=%v -> %s stride=%d smem=%d",
		bpp, err, describe(r), strideOf(r), strideOf(r)*int(r.YresVirtual))
	if int(r.Xres) == w && int(r.Yres) == h {
		logf("CLONE_OK")
	} else {
		logf("CLONE_REFUSED")
		_ = applyGeom(fd1, orig)
	}
}

func ioctlRaw(fd int, req, arg uintptr) error {
	_, _, e := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), req, arg)
	if e != 0 {
		return e
	}
	return nil
}

// 显示控制寄存器（只读），基址 0x02740000
func mapRegs() []byte {
	fd, err := syscall.Open("/dev/mem", syscall.O_RDONLY, 0)
	if err != nil {
		return nil
	}
	defer syscall.Close(fd)
	m, err := syscall.Mmap(fd, 0x02740000, 4096, syscall.PROT_READ, syscall.MAP_SHARED)
	if err != nil {
		return nil
	}
	return m
}

func regAt(m []byte, i int) uint32 {
	if m == nil || (i+1)*4 > len(m) {
		return 0
	}
	return *(*uint32)(unsafe.Pointer(&m[i*4]))
}

func cmdRegs() {
	m := mapRegs()
	if m == nil {
		die("map /dev/mem failed")
	}
	logf("com=%d (low4=%d) gw=0x%08x pos=%d r3=0x%08x",
		regAt(m, 0), regAt(m, 0)&15, regAt(m, 1), regAt(m, 2), regAt(m, 3))
}

// cmdBlank: 直接设置 fb 的 blank 级别
func cmdBlank(lvl int) {
	fd := openFB(fb1Path)
	err := ioctlRaw(fd, 0x4611, uintptr(lvl))
	logf("FBIOBLANK(%d) err=%v", lvl, err)
	v := mustVar(fd)
	logf("after: %s", describe(v))
}

// cmdUnblank: 用 FBIOBLANK(0) 唤醒 fb1，再尝试设置几何
func cmdUnblank(w, h int) {
	fd := openFB(fb1Path)
	v := mustVar(fd)
	logf("before: %s", describe(v))
	for _, lvl := range []uintptr{0, 1, 0} {
		err := ioctlRaw(fd, 0x4611, lvl)
		logf("FBIOBLANK(%d) err=%v", lvl, err)
		time.Sleep(80 * time.Millisecond)
	}
	v = mustVar(fd)
	logf("after unblank: %s", describe(v))
	if w > 0 {
		_ = applyGeom(fd, geom{uint32(w), uint32(h), uint32(w), uint32(h), 16})
		v = mustVar(fd)
		logf("after setpar %dx%d: %s", w, h, describe(v))
	}
}

func cmdRestore() {
	fd := openFB(fb1Path)
	logf("before: %s", describe(mustVar(fd)))
	_ = setBlank(fd, 4)
	// fb0 模式是窄屏切换的第 2 个 videoMode，缺了它会丢掉 fb0 的时序参照
	var bgV *fbVar
	if bg, err := syscall.Open(fb0Path, syscall.O_RDWR, 0); err == nil {
		if v, e := getVar(bg); e == nil {
			bgV = v
		}
		syscall.Close(bg)
	}
	if err := setModeNarrow(mustVar(fd), bgV); err != nil {
		logf("ERROR RESTORE_MODE %v", err)
	}
	time.Sleep(300 * time.Millisecond)
	_ = setBlank(fd, 4)
	v := mustVar(fd)
	f := mustFix(fd)
	logf("after: %s stride=%d smem=%d", describe(v), f.LineLength, f.SmemLen)
	m := mapRegs()
	if m != nil {
		logf("regs com=%d gw=0x%08x", regAt(m, 0)&15, regAt(m, 1))
	}
	if int(v.Xres) == baseW && int(v.Yres) == baseH && int(v.YresVirtual) == baseVH {
		logf("BASELINE_OK")
	} else {
		logf("BASELINE_MISMATCH")
	}
}

// cmdSetsize: 尝试设置 fb1 几何并报告驱动实际接受的结果
func cmdSetsize(w, h, hv, act int) {
	fd := openFB(fb1Path)
	orig := snapshot(fd)
	logf("orig: %s", describe(mustVar(fd)))

	try := func(name string, g geom, activate uint32) {
		v, _ := getVar(fd)
		v.Xres = g.W
		v.Yres = g.H
		v.XresVirtual = g.Wv
		v.YresVirtual = g.Hv
		v.BitsPerPixel = g.Bpp
		v.Xoffset = 0
		v.Yoffset = 0
		v.Activate = activate
		err := putVar(fd, v)
		r, _ := getVar(fd)
		logf("%-22s err=%v -> %s smem=%d", name, err, describe(r), strideOf(r)*int(r.YresVirtual))
		time.Sleep(120 * time.Millisecond)
		_ = applyGeom(fd, orig)
		time.Sleep(120 * time.Millisecond)
	}

	hvv := uint32(h)
	if hv > 0 {
		hvv = uint32(hv)
	}
	try("req wide act=128", geom{uint32(w), uint32(h), uint32(w), hvv, 16}, 128)
	try("req wide act=0", geom{uint32(w), uint32(h), uint32(w), hvv, 16}, 0)
	try("req wide act=1", geom{uint32(w), uint32(h), uint32(w), hvv, 16}, 1)
	logf("final: %s", describe(mustVar(fd)))
}

// cmdProbe: 扫描一批几何，找出驱动允许的上限
func cmdProbe() {
	fd := openFB(fb1Path)
	orig := snapshot(fd)
	logf("orig: %s smem=%d", describe(mustVar(fd)), strideOf(mustVar(fd))*int(orig.Hv))
	sizes := [][2]int{{1920, 720}, {1280, 720}, {960, 360}, {480, 360}, {320, 320}, {240, 640}, {240, 320}}
	for _, s := range sizes {
		v, _ := getVar(fd)
		v.Xres = uint32(s[0])
		v.Yres = uint32(s[1])
		v.XresVirtual = uint32(s[0])
		v.YresVirtual = uint32(s[1])
		v.BitsPerPixel = 16
		v.Xoffset = 0
		v.Yoffset = 0
		v.Activate = 0
		err := putVar(fd, v)
		r, _ := getVar(fd)
		ok := "REJ"
		if int(r.Xres) == s[0] && int(r.Yres) == s[1] {
			ok = "OK "
		}
		logf("%s req %dx%d err=%v -> %dx%d virt=%dx%d smem=%d",
			ok, s[0], s[1], err, r.Xres, r.Yres, r.XresVirtual, r.YresVirtual,
			strideOf(r)*int(r.YresVirtual))
		time.Sleep(150 * time.Millisecond)
		_ = applyGeom(fd, orig)
		time.Sleep(150 * time.Millisecond)
	}
	logf("final: %s", describe(mustVar(fd)))
}

func cmdFill(hexColor string, hold int) {
	var c uint32
	_, err := fmt.Sscanf(hexColor, "0x%x", &c)
	if err != nil {
		_, err = fmt.Sscanf(hexColor, "%x", &c)
		if err != nil {
			die("bad color " + hexColor)
		}
	}
	frame := make([]byte, targetW*targetH*2)
	for i := 0; i < targetW*targetH; i++ {
		frame[i*2] = byte(c)
		frame[i*2+1] = byte(c >> 8)
	}
	runStatic(frame, targetW, targetH, hold, 0, 0)
}

// ---------- paint：分区绘制 ----------
// 验证「自绘面板 + 原厂画面透出」同屏共存的真实观感。
// 未被任何矩形覆盖的区域一律写 0x0000，靠前景层色键抠成透明，直接透出 fb0。
// 这条路径不经过投屏链路，纯粹验证 fb1 的合成能力。

type paintRect struct {
	x, y, w, h int
	c          uint16
	name       string
}

// parsePaintColor 支持三种写法：hole（色键透明）、#RRGGBB（转 565）、RRRR（直接 565）。
func parsePaintColor(s string) (uint16, string) {
	if s == "hole" || s == "HOLE" {
		return 0x0000, "hole"
	}
	if len(s) == 7 && s[0] == '#' {
		var r, g, b uint32
		if _, err := fmt.Sscanf(s[1:], "%02x%02x%02x", &r, &g, &b); err != nil {
			die("bad color " + s)
		}
		v := uint16(r>>3)<<11 | uint16(g>>2)<<5 | uint16(b>>3)
		if v == 0 {
			logf("WARN %s 转 565 后为 0x0000，会被色键抠成透明洞", s)
		}
		return v, s
	}
	var v uint64
	if _, err := fmt.Sscanf(s, "%x", &v); err != nil || v > 0xffff {
		die("bad color " + s)
	}
	return uint16(v), s
}

func fillBackRect(back []byte, stride, x, y, w, h int, c uint16) {
	if w <= 0 || h <= 0 {
		return
	}
	for ry := y; ry < y+h; ry++ {
		off := ry*stride + x*2
		if off < 0 || off+w*2 > len(back) {
			continue
		}
		row := asU16(back[off : off+w*2])
		for i := range row {
			row[i] = c
		}
	}
}

// cmdPaint: 全屏铺纯黑（= 整屏透明），再按矩形填色块。
func cmdPaint(hold int, specs []string) {
	var rects []paintRect
	for _, s := range specs {
		f := strings.Split(s, ",")
		if len(f) != 5 {
			die("bad rect (want x,y,w,h,color): " + s)
		}
		var n [4]int
		for i := 0; i < 4; i++ {
			v, err := strconv.Atoi(strings.TrimSpace(f[i]))
			if err != nil {
				die("bad rect number in " + s)
			}
			n[i] = v
		}
		c, name := parsePaintColor(strings.TrimSpace(f[4]))
		rects = append(rects, paintRect{n[0], n[1], n[2], n[3], c, name})
	}

	d := newDisplay(0, 0)
	for i := range d.back {
		d.back[i] = 0
	}

	painted, holes := 0, 0
	for _, r := range rects {
		x0, y0 := r.x, r.y
		x1, y1 := r.x+r.w, r.y+r.h
		if x0 < 0 {
			x0 = 0
		}
		if y0 < 0 {
			y0 = 0
		}
		if x1 > targetW {
			x1 = targetW
		}
		if y1 > targetH {
			y1 = targetH
		}
		if x1 <= x0 || y1 <= y0 {
			logf("SKIP offscreen rect %d,%d %dx%d", r.x, r.y, r.w, r.h)
			continue
		}
		fillBackRect(d.back, d.stride, x0, y0, x1-x0, y1-y0, r.c)
		if r.c == 0 {
			holes++
			logf("RECT %d,%d %dx%d HOLE show-fb0", x0, y0, x1-x0, y1-y0)
		} else {
			painted += (x1 - x0) * (y1 - y0)
			logf("RECT %d,%d %dx%d color=%04X %s", x0, y0, x1-x0, y1-y0, r.c, r.name)
		}
	}

	d.flush()
	d.enableBlend()
	d.flush()

	total := float64(targetW * targetH)
	logf("PAINT rects=%d hole_rect=%d selfdraw=%.1f%% seethrough=%.1f%%",
		len(rects), holes, float64(painted)*100/total, (total-float64(painted))*100/total)
	logf("VISIBLE paint hold=%ds", hold)
	waitHold(d, hold)
}

func cmdShow(path string, hold int) {
	raw, err := os.ReadFile(path)
	if err != nil {
		die(err.Error())
	}
	want := targetW * targetH * 2
	if len(raw) < want {
		die(fmt.Sprintf("raw too small: %d < %d (want %dx%d RGB565)", len(raw), want, targetW, targetH))
	}
	d := newDisplay(0, 0)
	// 整屏 1:1 贴入，不做缩放：设计稿里的 0x0000 就是色键透明，保持原样
	rowBytes := targetW * 2
	for y := 0; y < targetH; y++ {
		copy(d.back[y*d.stride:y*d.stride+rowBytes], raw[y*rowBytes:(y+1)*rowBytes])
	}
	d.flush()
	d.enableBlend()
	d.flush()

	// 统计透出比例，便于和设计稿对照
	holes := 0
	px := asU16(d.back)
	for i := 0; i < targetW*targetH; i++ {
		if px[i] == 0 {
			holes++
		}
	}
	logf("SHOW %dx%d hole=%.1f%% hold=%ds", targetW, targetH,
		float64(holes)*100/float64(targetW*targetH), hold)
	waitHold(d, hold)
}

func cmdFile(path string, sw, sh, hold, top, bot int) {
	raw, err := os.ReadFile(path)
	if err != nil {
		die(err.Error())
	}
	if len(raw) < sw*sh*2 {
		die(fmt.Sprintf("raw too small: %d < %d", len(raw), sw*sh*2))
	}
	runStatic(raw, sw, sh, hold, top, bot)
}

func runStatic(raw []byte, sw, sh, hold, top, bot int) {
	d := newDisplay(top, bot)
	d.refreshBands()
	target := d.fb[d.midTop*d.stride : (d.midTop+d.midH)*d.stride]
	scaleInto(target, d.stride, targetW, d.midH, raw, sw, sh)
	d.enableBlend()
	logf("VISIBLE %dx%d (mid %d..%d) hold=%ds", targetW, targetH, d.midTop, d.midTop+d.midH, hold)
	waitHold(d, hold)
}

func cmdDump(path string, div, which int) {
	var w, h, stride, bpp int
	var buf []byte
	var fd int
	if which == 0 {
		fd = openFB(fb0Path)
		v := mustVar(fd)
		w, h, bpp = int(v.Xres), int(v.Yres), int(v.BitsPerPixel)
		stride = strideOf(v)
		buf = mmapFBRO(fd, stride*int(v.YresVirtual))
	} else {
		fd = openFB(fb1Path)
		v := mustVar(fd)
		w, h, bpp = int(v.Xres), int(v.Yres), int(v.BitsPerPixel)
		stride = strideOf(v)
		buf = mmapFBRO(fd, stride*int(v.YresVirtual))
	}
	if div < 1 {
		div = 1
	}
	ow, oh := w/div, h/div
	img := image.NewRGBA(image.Rect(0, 0, ow, oh))
	byt := bpp / 8
	for y := 0; y < oh; y++ {
		row := buf[(y*div)*stride:]
		for x := 0; x < ow; x++ {
			o := (x * div) * byt
			var r, g, b uint8
			if byt == 4 {
				b = row[o]
				g = row[o+1]
				r = row[o+2]
			} else {
				v := uint16(row[o]) | uint16(row[o+1])<<8
				r5 := uint8((v >> 11) & 0x1f)
				g6 := uint8((v >> 5) & 0x3f)
				b5 := uint8(v & 0x1f)
				r = r5<<3 | r5>>2
				g = g6<<2 | g6>>4
				b = b5<<3 | b5>>2
			}
			img.Set(x, y, color.RGBA{r, g, b, 255})
		}
	}
	f, err := os.Create(path)
	if err != nil {
		die(err.Error())
	}
	bw := bufio.NewWriter(f)
	png.Encode(bw, img)
	bw.Flush()
	f.Close()
	logf("dumped %s %dx%d div=%d fb%d", path, ow, oh, div, which)
}

func waitHold(d *display, hold int) {
	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	select {
	case <-sig:
		logf("signal -> restore")
	case <-time.After(time.Duration(hold) * time.Second):
		logf("hold timeout -> restore")
	}
	d.restore()
	os.Exit(0)
}

// ---------- 卡片精灵 ----------
// fillMidGradient: 无流时的占位底图，深蓝竖向渐变 + 极淡水平网格，
// 让卡片有可读的对比背景，也便于肉眼确认中间带范围。
func (d *display) fillMidGradient() {
	for y := 0; y < d.midH; y++ {
		dy := d.midTop + y
		t := uint32(y) * 255 / uint32(d.midH)
		// 深海军蓝 -> 略提亮的蓝，模拟暗色导航底图
		r := 4 + t*6/255
		g := 12 + t*14/255
		b := 22 + t*26/255
		v := uint16(r>>3)<<11 | uint16(g>>2)<<5 | uint16(b>>3)
		if v == 0 {
			v = 1
		}
		doff := dy * d.stride
		row := asU16(d.back[doff : doff+targetW*2])
		for x := range row {
			row[x] = v
		}
	}
}

// cmdBench: 在真机上分段量出接收端的预算，用来判断帧率天花板。
// 依次测：LZ4 解压、后台缓冲刷到 fb1、fb0 上下条采样融合、以及三者合计。
func cmdBench(lz4Path string, iter int) {
	if iter < 1 {
		iter = 5
	}
	comp, err := os.ReadFile(lz4Path)
	if err != nil {
		die("read lz4: " + err.Error())
	}
	logf("bench src=%s comp=%d iters=%d", lz4Path, len(comp), iter)

	d := newDisplay(72, 80)
	defer func() { d.restore() }()

	frameLen := targetW * targetH * 2
	if len(d.back) < frameLen {
		die(fmt.Sprintf("back buffer %d < frame %d", len(d.back), frameLen))
	}

	// 1) LZ4 解压到后台缓冲
	best := time.Hour
	var worst time.Duration
	var sum time.Duration
	for i := 0; i < iter; i++ {
		s := time.Now()
		n, derr := lz4DecodeBlock(comp, d.back[:frameLen])
		el := time.Since(s)
		if derr != nil {
			die("lz4: " + derr.Error())
		}
		if n != frameLen {
			die(fmt.Sprintf("decoded %d != %d", n, frameLen))
		}
		sum += el
		if el < best {
			best = el
		}
		if el > worst {
			worst = el
		}
	}
	logf("decode  avg=%v min=%v max=%v  %.1f MB/s  -> %.1ffps",
		sum/time.Duration(iter), best, worst,
		float64(frameLen)/1e6/(float64(sum)/float64(iter)/1e9),
		float64(time.Second)/float64(sum/time.Duration(iter)))

	// 2) 后台缓冲整帧刷到 fb1（uncached 写）
	best, worst, sum = time.Hour, 0, 0
	for i := 0; i < iter; i++ {
		s := time.Now()
		d.flush()
		el := time.Since(s)
		sum += el
		if el < best {
			best = el
		}
		if el > worst {
			worst = el
		}
	}
	logf("flush   avg=%v min=%v max=%v  %.1f MB/s  -> %.1ffps",
		sum/time.Duration(iter), best, worst,
		float64(frameLen)/1e6/(float64(sum)/float64(iter)/1e9),
		float64(time.Second)/float64(sum/time.Duration(iter)))

	// 3) 上下条采样融合（读 fb0 + 转 565 + 写 back）
	best, worst, sum = time.Hour, 0, 0
	for i := 0; i < iter; i++ {
		s := time.Now()
		d.refreshBands()
		el := time.Since(s)
		sum += el
		if el < best {
			best = el
		}
		if el > worst {
			worst = el
		}
	}
	logf("bands   avg=%v min=%v max=%v (%d rows)",
		sum/time.Duration(iter), best, worst, d.top+d.bot)

	// 4) 端到端一帧
	best, worst, sum = time.Hour, 0, 0
	for i := 0; i < iter; i++ {
		s := time.Now()
		if _, derr := lz4DecodeBlock(comp, d.back[:frameLen]); derr != nil {
			die("lz4: " + derr.Error())
		}
		d.flush()
		d.refreshBands()
		el := time.Since(s)
		sum += el
		if el < best {
			best = el
		}
		if el > worst {
			worst = el
		}
	}
	avg := sum / time.Duration(iter)
	logf("FRAME   avg=%v min=%v max=%v  -> %.1ffps",
		avg, best, worst, float64(time.Second)/float64(avg))
	logf("BENCH_OK")
}

// cmdBenchICF: 用真实 ICF1 帧回放 compose 的每帧热路径，分段计时。
// 与 handleStream 完全同一条代码路径（复用缓冲 + 同宽快路径 + flushMid）。
func cmdBenchICF(path string, iter int) {
	if iter < 1 {
		iter = 10
	}
	blob, err := os.ReadFile(path)
	if err != nil {
		die("read icf: " + err.Error())
	}
	if len(blob) < 24 || string(blob[0:4]) != "ICF1" {
		die("not ICF1")
	}
	w := int(binary.LittleEndian.Uint32(blob[4:]))
	h := int(binary.LittleEndian.Uint32(blob[8:]))
	fmtv := binary.LittleEndian.Uint32(blob[12:])
	complen := int(binary.LittleEndian.Uint32(blob[16:]))
	rawlen := int(binary.LittleEndian.Uint32(blob[20:]))
	if 24+complen > len(blob) {
		die("truncated payload")
	}
	comp := blob[24 : 24+complen]
	logf("benchicf %s %dx%d fmt=%d comp=%d raw=%d iters=%d",
		path, w, h, fmtv, complen, rawlen, iter)

	d := newDisplay(72, 80)
	defer func() { d.restore() }()
	d.refreshBands()

	d.comp = make([]byte, complen)
	d.raw = make([]byte, rawlen)
	copy(d.comp, comp)
	if fmtv != fmtLZ4 {
		die("benchicf 只支持 fmt=2 (LZ4)")
	}

	target := d.back[d.midTop*d.stride : (d.midTop+d.midH)*d.stride]

	type seg struct {
		name string
		fn   func()
	}
	segs := []seg{
		{"decode", func() {
			if _, e := lz4DecodeBlock(d.comp, d.raw[:rawlen]); e != nil {
				die("lz4: " + e.Error())
			}
		}},
		{"scale", func() {
			scaleInto(target, d.stride, targetW, d.midH, d.raw[:rawlen], w, h)
		}},
		{"flushMid", func() { d.flushMid() }},
		{"bands", func() { d.refreshBands() }},
	}
	for _, s := range segs {
		sum := time.Duration(0)
		best := time.Hour
		for i := 0; i < iter; i++ {
			t := time.Now()
			s.fn()
			el := time.Since(t)
			sum += el
			if el < best {
				best = el
			}
		}
		avg := sum / time.Duration(iter)
		logf("  %-8s avg=%v min=%v  -> cap %.1ffps", s.name, avg, best,
			float64(time.Second)/float64(avg))
	}

	// 端到端一帧（去掉每 250ms 一次的 bands）
	sum := time.Duration(0)
	best := time.Hour
	worst := time.Duration(0)
	for i := 0; i < iter; i++ {
		t := time.Now()
		if _, e := lz4DecodeBlock(d.comp, d.raw[:rawlen]); e != nil {
			die("lz4: " + e.Error())
		}
		scaleInto(target, d.stride, targetW, d.midH, d.raw[:rawlen], w, h)
		d.flushMid()
		el := time.Since(t)
		sum += el
		if el < best {
			best = el
		}
		if el > worst {
			worst = el
		}
	}
	avg := sum / time.Duration(iter)
	logf("FRAME avg=%v min=%v max=%v -> %.1ffps (payload %d KB, %.2f MB/s 入)",
		avg, best, worst, float64(time.Second)/float64(avg),
		complen/1024, float64(complen)/1e6/(float64(avg)/1e9))

	// 直写路径：解压直接落目标带（几何匹配时 handleStream 实际走的就是这条）
	bandOff := d.midTop * d.stride
	band := d.back[bandOff : bandOff+d.midH*d.stride]
	if w == targetW && h == d.midH && d.stride == targetW*2 {
		sum2 := time.Duration(0)
		best2 := time.Hour
		worst2 := time.Duration(0)
		for i := 0; i < iter; i++ {
			t := time.Now()
			if _, e := lz4DecodeFix(d.comp, band); e != nil {
				die("lz4fix: " + e.Error())
			}
			d.flushMid()
			el := time.Since(t)
			sum2 += el
			if el < best2 {
				best2 = el
			}
			if el > worst2 {
				worst2 = el
			}
		}
		a2 := sum2 / time.Duration(iter)
		logf("DIRECT avg=%v min=%v max=%v -> %.1ffps (省掉缩放整趟)",
			a2, best2, worst2, float64(time.Second)/float64(a2))

		// 校验：直写结果必须与「解压到临时缓冲 + 缩放」逐字节一致
		ref := make([]byte, len(band))
		if _, e := lz4DecodeBlock(d.comp, d.raw[:rawlen]); e != nil {
			die("lz4: " + e.Error())
		}
		scaleInto(ref, targetW*2, targetW, d.midH, d.raw[:rawlen], w, h)
		if _, e := lz4DecodeFix(d.comp, band); e != nil {
			die("lz4fix: " + e.Error())
		}
		diff := 0
		for i := range band {
			if band[i] != ref[i] {
				diff++
				if diff <= 4 {
					logf("  DIFF at %d: direct=%d scaled=%d", i, band[i], ref[i])
				}
			}
		}
		logf("DIRECT_VERIFY diff_bytes=%d (0 = 与缩放结果完全一致)", diff)
	} else {
		logf("DIRECT skipped (geom %dx%d stride=%d)", w, h, d.stride)
	}
	logf("BENCHICF_OK")
}

// cmdDemo: 不接任何流，直接把最终形态画到 fb1。
// 中间带填深色渐变当占位底图，叠加卡片，上下条取真实 fb0。
// 用于在没有车机推流的情况下确认版式与混合是否正常。
func cmdDemo(hold, top, bot int) {
	d := newDisplay(top, bot)

	t0 := time.Now()
	d.fillMidGradient()
	logf("gradient %v", time.Since(t0))

	t0 = time.Now()
	d.refreshBands()
	logf("bands(top=%d bot=%d) %v", d.top, d.bot, time.Since(t0))

	t0 = time.Now()
	d.flush()
	logf("flush %v", time.Since(t0))

	// 跑几轮完整合成，量出稳态每帧成本（首轮含 cache 冷启动，单独看）
	for i := 0; i < 8; i++ {
		s := time.Now()
		d.fillMidGradient()
		d.refreshBands()
		d.flush()
		el := time.Since(s)
		if i >= 3 {
			logf("round%d %v -> %.1ffps", i, el, float64(time.Second)/float64(el))
		}
	}

	d.enableBlend()
	logf("DEMO_VISIBLE %dx%d mid=%d..%d hold=%ds", targetW, targetH, d.midTop, d.midTop+d.midH, hold)
	waitHold(d, hold)
}

// ---------- compose ----------

func cmdCompose(port, hold, top, bot, cards, rx, ry, rw, rh int) {
	d := newDisplay(top, bot)
	// cards 参数保留仅为兼容车机上 APK 已固化的命令行：
	//   compose <port> <hold> <top> <bot> <cards> [x y w h]
	// 本仓库已剥离自绘卡片（那属于另一套自绘 UI 的产物），这里恒不绘制。
	_ = cards
	// 可选自定义投屏区：compose <port> <hold> <top> <bot> <cards> [x y w h]
	// 不传或传 0 → 保持原来的满宽横带
	if rw > 0 && rh > 0 {
		d.setDstRect(rx, ry, rw, rh)
	}
	d.refreshBands()
	d.enableBlend()

	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sig
		logf("signal -> restore")
		d.restore()
		os.Exit(0)
	}()

	ln, err := net.Listen("tcp", fmt.Sprintf("0.0.0.0:%d", port))
	if err != nil {
		die("listen: " + err.Error())
	}
	logf("LISTEN :%d mid=%d..%d hold=%ds", port, d.midTop, d.midTop+d.midH, hold)
	logf("DST %d,%d %dx%d band=%v", d.dstX, d.dstY, d.dstW, d.dstH, d.isFullBand())

	deadline := time.NewTimer(time.Duration(hold) * time.Second)
	go func() {
		<-deadline.C
		logf("hold timeout -> restore")
		d.restore()
		os.Exit(0)
	}()

	logf("VISIBLE %dx%d", targetW, targetH)

	served := false
	for {
		// 没来过客户端时无限等；服务过之后断流只给 2 秒重连窗口，
		// 超时即复位仪表退出——用户点「停止投屏」后应当很快回到原仪表。
		if served {
			_ = ln.(*net.TCPListener).SetDeadline(time.Now().Add(2 * time.Second))
		} else {
			_ = ln.(*net.TCPListener).SetDeadline(time.Time{})
		}
		conn, err := ln.Accept()
		if err != nil {
			if served {
				logf("IDLE 2s after last frame -> restore")
				d.restore()
				logf("RESTORE_DONE (resume original cluster)")
				os.Exit(0)
			}
			continue
		}
		logf("client %s", conn.RemoteAddr())
		handleStream(d, conn, hold, deadline)
		conn.Close()
		logf("client gone")
		served = true
	}
}

func handleStream(d *display, conn net.Conn, hold int, deadline *time.Timer) {
	hdr := make([]byte, 24)
	br := bufio.NewReaderSize(conn, 1<<16)
	frames := 0
	t0 := time.Now()
	bandAt := time.Now()
	// 分段计时累加，用来定位每帧到底慢在哪（read / decode / draw+flush / submit）
	var sumRead, sumDec, sumDraw, sumFlush, sumTotal time.Duration
	// 上下条（fb0 拷贝条）刷新间隔：整带宽刷新一次要 15ms 左右，
	// 250ms 一次相当于每 5 帧白吃一帧，可以按需放大（ICBANDS 毫秒）
	bandGap := 250 * time.Millisecond
	if v := os.Getenv("ICBANDS"); v != "" {
		if ms, err := strconv.Atoi(v); err == nil && ms > 0 {
			bandGap = time.Duration(ms) * time.Millisecond
		}
	}

	// ---------- 解码 / 写帧缓冲 流水线 ----------
	// i.MX6 是双核：LZ4 解码 2.18MB 约 25ms、把整带写进帧缓冲约 22ms，
	// 串行执行就把帧率死死锁在 20fps（实测 dec=24.6ms draw=21.9ms → 47.7ms/帧）。
	// 这两个活几乎不依赖彼此，拆到两个核上并行后每帧 ≈ max(25, 22) ≈ 26ms。
	// 写 fb 必须串行（两个 goroutine 同时写同一块 fb 会撕裂），所以用单 worker + 队列，
	// 双缓冲保证 worker 正在写的那块不会被下一帧解码覆盖。
	pipeBand := d.midTop * d.stride
	pipeEnd := pipeBand + d.midH*d.stride
	pipeOK := pipeBand >= 0 && pipeEnd <= len(d.fb) && pipeEnd <= len(d.back) && pipeEnd > pipeBand
	pipeOut := [2][]byte{nil, nil}
	freeCh := make(chan int, 2)
	freeCh <- 0
	freeCh <- 1
	jobs := make(chan int, 2)
	if pipeOK {
		go func() {
			for idx := range jobs {
				copy(d.fb[pipeBand:pipeEnd], pipeOut[idx])
				d.submit()
				freeCh <- idx
			}
		}()
		defer func() {
			close(jobs)
			<-freeCh
			<-freeCh // 等两帧拷贝都写完，避免返回后还有 goroutine 往 fb 里写
		}()
	}

	// 帧间隔超时：发送端主动停止/掉线时，这里必须能自己醒过来去复位仪表，
	// 不能一直阻塞在 ReadFull 上等对方关连接。
	idle := 2500 * time.Millisecond
	for {
		tR := time.Now()
		_ = conn.SetReadDeadline(time.Now().Add(idle))
		if _, err := io.ReadFull(br, hdr); err != nil {
			logf("stream idle/end: %v (frames=%d)", err, frames)
			return
		}
		if string(hdr[0:4]) != "ICF1" {
			logf("bad magic %x", hdr[0:4])
			return
		}
		w := int(binary.LittleEndian.Uint32(hdr[4:]))
		h := int(binary.LittleEndian.Uint32(hdr[8:]))
		fmtv := binary.LittleEndian.Uint32(hdr[12:])
		complen := int(binary.LittleEndian.Uint32(hdr[16:]))
		rawlen := int(binary.LittleEndian.Uint32(hdr[20:]))
		if complen <= 0 || complen > 32<<20 {
			logf("bad complen %d", complen)
			return
		}
		// 复用缓冲：complen 超过 32KB 时 Go 会走大对象直接 mmap，
		// 每帧新建 2.3MB 会带来几十毫秒的分配+清零+GC 开销。
		if cap(d.comp) < complen {
			d.comp = make([]byte, complen)
		}
		if cap(d.raw) < rawlen {
			d.raw = make([]byte, rawlen)
		}
		comp := d.comp[:complen]
		if _, err := io.ReadFull(br, comp); err != nil {
			logf("read payload: %v", err)
			return
		}
		t1 := time.Now()
		// 目标是「投屏矩形」。默认等于原来的满宽横带，此时 fullBand=true，
		// 下面直写/流水线的判定条件与改造前逐字相同，行为零变化。
		fullBand := d.isFullBand()
		bandOff := d.dstY*d.stride + d.dstX*2
		bandEnd := bandOff + d.dstH*d.stride
		// 几何完全对上时解压直写目标带：省掉「解压到临时缓冲 + 整带缩放」
		// 这一整趟读写（2.17MB 读 + 2.18MB 写），实测能省 ~20ms。
		direct := fullBand && fmtv == fmtLZ4 && w == targetW && h == d.midH &&
			d.stride == targetW*2 && rawlen == d.midH*d.stride &&
			bandEnd <= len(d.back)

		// 走流水线：几何完全对上、没有演示卡片、fb 长度也够
		usePipe := direct && pipeOK
		pipeIdx := -1
		if usePipe {
			pipeIdx = <-freeCh // 拿一块空闲缓冲（等上一轮拷贝写完）
			if cap(pipeOut[pipeIdx]) < rawlen {
				pipeOut[pipeIdx] = make([]byte, rawlen)
			}
		}

		var raw []byte
		switch fmtv {
		case fmtRaw:
			raw = comp
		case fmtLZ4:
			var out []byte
			if usePipe {
				out = pipeOut[pipeIdx][:rawlen]
			} else if direct {
				out = d.back[bandOff:bandEnd]
			} else {
				out = d.raw[:rawlen]
			}
			var got int
			var derr error
			if direct || usePipe {
				got, derr = lz4DecodeFix(comp, out)
			} else {
				got, derr = lz4DecodeBlock(comp, out)
			}
			if derr != nil {
				logf("lz4: %v", derr)
				if usePipe {
					freeCh <- pipeIdx
				}
				continue
			}
			if got != rawlen {
				logf("lz4 size %d != %d", got, rawlen)
				if usePipe {
					freeCh <- pipeIdx
				}
				continue
			}
			raw = out
		default:
			zr, err := zlib.NewReader(&sliceReader{b: comp})
			if err != nil {
				logf("zlib: %v", err)
				continue
			}
			raw = d.raw[:rawlen]
			if _, err := io.ReadFull(zr, raw); err != nil {
				logf("inflate: %v", err)
				zr.Close()
				continue
			}
			zr.Close()
		}
		if w <= 0 || h <= 0 || len(raw) < w*h*2 {
			logf("short frame %d < %d", len(raw), w*h*2)
			continue
		}
		t2 := time.Now()

		if frames == 0 {
			mode := "scale"
			if w == d.dstW && h == d.dstH {
				mode = "1:1"
			}
			logf("frame %dx%d -> dst %d,%d %dx%d (%s) raw=%d",
				w, h, d.dstX, d.dstY, d.dstW, d.dstH, mode, rawlen)
		}

		bandsFresh := false
		if time.Since(bandAt) > bandGap {
			d.ensureMapped()
			d.refreshBands()
			bandAt = time.Now()
			bandsFresh = true
		}

		// 流水线分支：解码已把整带写进 pipeOut[pipeIdx]，交给 worker 去写 fb。
		if usePipe {
			if bandsFresh {
				// 上下条刚刷新过：等 worker 收尾，把本帧并进 d.back 后整幅上屏
				other := <-freeCh
				copy(d.back[bandOff:bandEnd], pipeOut[pipeIdx][:rawlen])
				d.flush()
				d.submit()
				freeCh <- other
				freeCh <- pipeIdx
			} else {
				jobs <- pipeIdx
			}
			sumRead += t1.Sub(tR)
			sumDec += t2.Sub(t1)
			frames++
			if frames == 1 {
				// B5-A：第一帧就报一声，别等到第 60 帧（35fps 下要 1.7s）。
				// 上位机（APK 的 [7/8]）就是靠日志里出现 "frames=" 判断「真的收到帧了」。
				logf("frames=1 first frame (recv %dx%d)", w, h)
			}
			deadline.Reset(time.Duration(hold) * time.Second)
			if frames%60 == 0 {
				el := time.Since(t0).Seconds()
				logf("frames=%d %.1ffps (recv %dx%d) pipe read=%v dec=%v",
					frames, float64(frames)/el, w, h, sumRead/60, sumDec/60)
				sumRead, sumDec = 0, 0
			}
			continue
		}

		if !direct {
			// 任意矩形：尺寸一致走 1:1 整行拷贝，不一致才重采样（发送端没跟上矩形大小时）
			d.scaleFrame(raw, w, h)
		}
		t2b := time.Now()
		if bandsFresh {
			d.flush()
		} else {
			d.flushFrame()
		}
		t3 := time.Now()
		d.submit()
		t4 := time.Now()

		sumRead += t1.Sub(tR)
		sumDec += t2.Sub(t1)
		sumDraw += t2b.Sub(t2)
		sumFlush += t3.Sub(t2b)
		sumTotal += t4.Sub(tR)

		frames++
		if frames == 1 {
			// B5-A：同上，首帧即报，省掉 1.7s 的「等第 60 帧」。
			logf("frames=1 first frame (recv %dx%d)", w, h)
		}
		deadline.Reset(time.Duration(hold) * time.Second)
		if frames%60 == 0 {
			el := time.Since(t0).Seconds()
			// 只打「按帧边界算的整体耗时」和四项主要成本。
			// 之前那个 sub（t4-t3，卡在 submit 上）在这台仪表上会打出几十秒的
			// 荒唐值 —— 内核 3.0.35 的时钟源偶发跳变，单次 time.Now() 差分不可信。
			// total 是 frames/elapsed 的同一量纲，和 fps 自洽，用它判断就够了。
			logf("frames=%d %.1ffps (recv %dx%d) read=%v dec=%v draw=%v flush=%v | total=%v",
				frames, float64(frames)/el, w, h,
				sumRead/60, sumDec/60, sumDraw/60, sumFlush/60, sumTotal/60)
			sumRead, sumDec, sumDraw, sumFlush, sumTotal = 0, 0, 0, 0, 0
		}
	}
}

type sliceReader struct {
	b []byte
	i int
}

func (r *sliceReader) Read(p []byte) (int, error) {
	if r.i >= len(r.b) {
		return 0, io.EOF
	}
	n := copy(p, r.b[r.i:])
	r.i += n
	return n, nil
}

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: icrecv info [fb] | restore | fill <hex> <hold> | file <raw> <w> <h> <hold> | compose <port> <hold> <top> <bot> | dump <path> <div> <fb0|fb1>")
		return
	}
	switch os.Args[1] {
	case "info":
		p := fb1Path
		if len(os.Args) > 2 {
			switch os.Args[2] {
			case "0", "fb0":
				p = fb0Path
			case "1", "fb1":
				p = fb1Path
			default:
				p = os.Args[2]
			}
		}
		cmdInfo(p)
	case "restore":
		cmdRestore()
	case "probe":
		cmdProbe()
	case "unblank":
		cmdUnblank(argInt(2, 0), argInt(3, 720))
	case "blank":
		cmdBlank(argInt(2, 4))
	case "regs":
		cmdRegs()
	case "clone":
		cmdClone(argInt(2, 1920), argInt(3, 720), argInt(4, 16))
	case "setsize":
		cmdSetsize(argInt(2, 1920), argInt(3, 720), argInt(4, 0), argInt(5, 128))
	case "fill":
		cmdFill(arg(2), argInt(3, 20))
	case "paint":
		if len(os.Args) < 4 {
			die("paint <hold> <x,y,w,h,color>...  color=hole|#RRGGBB|RRRR")
		}
		cmdPaint(argInt(2, 20), os.Args[3:])
	case "show":
		cmdShow(arg(2), argInt(3, 20))
	case "file":
		cmdFile(arg(2), argInt(3, 0), argInt(4, 0), argInt(5, 20), argInt(6, 0), argInt(7, 0))
	case "compose":
		cmdCompose(argInt(2, 5000), argInt(3, 60), argInt(4, 72), argInt(5, 80), argInt(6, 1),
			argInt(7, 0), argInt(8, 0), argInt(9, 0), argInt(10, 0))
	case "demo":
		cmdDemo(argInt(2, 30), argInt(3, 72), argInt(4, 80))
	case "bench":
		cmdBench(arg(2), argInt(3, 5))
	case "benchicf":
		cmdBenchICF(arg(2), argInt(3, 10))
	case "dump":
		which := 1
		if arg(4) == "fb0" {
			which = 0
		}
		cmdDump(arg(2), argInt(3, 2), which)
	default:
		die("unknown cmd " + os.Args[1])
	}
}
