#!/usr/bin/env python3
"""把一张 1920x568 的 PNG 打成仪表能吃的 ICF1 帧（RGB565 + LZ4 block）。

用于在 CarLife 没连上时单独验证仪表接收通路。
用法: python mkframe.py <in.png> [out.icf]
"""
import struct
import sys

import lz4.block
from PIL import Image

W, H = 1920, 568
FMT_LZ4 = 2


def to565(im):
    px = im.convert("RGB").load()
    out = bytearray(W * H * 2)
    o = 0
    for y in range(H):
        for x in range(W):
            r, g, b = px[x, y]
            v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
            if v == 0:
                v = 1
            out[o] = v & 0xFF
            out[o + 1] = v >> 8
            o += 2
    return bytes(out)


def main():
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else src.rsplit(".", 1)[0] + ".icf"
    im = Image.open(src)
    if im.size != (W, H):
        im = im.resize((W, H), Image.LANCZOS)
    raw = to565(im)
    comp = lz4.block.compress(raw, store_size=False)
    hdr = b"ICF1" + struct.pack("<IIIII", W, H, FMT_LZ4, len(comp), len(raw))
    with open(dst, "wb") as f:
        f.write(hdr + comp)
    print(f"{dst} {W}x{H} raw={len(raw)} comp={len(comp)} total={24+len(comp)}")


if __name__ == "__main__":
    main()
