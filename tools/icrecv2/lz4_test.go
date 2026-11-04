package main

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

// 用 Python lz4 库生成的向量做交叉验证，避免解码器自证。
// 向量由 gen_lz4_vectors.py 生成，覆盖纯 literal、offset=1 重叠、
// 长度扩展 255 链、以及真实帧尺寸。
func TestLZ4DecodeVectors(t *testing.T) {
	lz4s, err := filepath.Glob("testdata/*.lz4")
	if err != nil {
		t.Fatal(err)
	}
	if len(lz4s) == 0 {
		t.Skip("no testdata, run gen_lz4_vectors.py first")
	}
	for _, lz := range lz4s {
		name := lz[:len(lz)-len(".lz4")]
		t.Run(filepath.Base(name), func(t *testing.T) {
			comp, err := os.ReadFile(lz)
			if err != nil {
				t.Fatal(err)
			}
			want, err := os.ReadFile(name + ".raw")
			if err != nil {
				t.Fatal(err)
			}
			got := make([]byte, len(want))
			n, err := lz4DecodeBlock(comp, got)
			if err != nil {
				t.Fatalf("decode: %v", err)
			}
			if n != len(want) {
				t.Fatalf("size %d != %d", n, len(want))
			}
			if !bytes.Equal(got, want) {
				for i := range got {
					if got[i] != want[i] {
						t.Fatalf("mismatch at %d: got %02x want %02x", i, got[i], want[i])
					}
				}
			}
		})
	}
}

// 输出缓冲不足时必须报错而不是越界写
func TestLZ4DecodeShortDst(t *testing.T) {
	comp, err := os.ReadFile("testdata/rle_1.lz4")
	if err != nil {
		t.Skip("no testdata")
	}
	dst := make([]byte, 1000)
	if _, err := lz4DecodeBlock(comp, dst); err == nil {
		t.Fatal("expected overflow error for short destination")
	}
}

// 截断输入必须报错
func TestLZ4DecodeTruncated(t *testing.T) {
	comp, err := os.ReadFile("testdata/choppy.lz4")
	if err != nil {
		t.Skip("no testdata")
	}
	dst := make([]byte, 1<<20)
	if _, err := lz4DecodeBlock(comp[:len(comp)/2], dst); err == nil {
		t.Fatal("expected error for truncated input")
	}
}
