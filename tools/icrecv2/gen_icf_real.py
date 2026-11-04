#!/usr/bin/env python3
"""用真实车机截图合成一个与线上推流完全同尺寸的 ICF1 帧。

复现 CaptureService 的取景链路：
  车机屏 2400x1200 --(VirtualDisplay 缩放到)--> 1920x960
  --> 取 y=198..762 得到 1920x564 --> RGB565 --> LZ4 block

用法: python gen_icf_real.py <车机截图.png> [输出.icf]
"""
import os
import struct
import sys

import lz4.block
from PIL import Image

CAP_W, CAP_H = 1920, 960
BAND_H, CROP_TOP = 568, 196
HERE = os.path.dirname(os.path.abspath(__file__))


def to565(im):
    w, h = im.size
    px = im.load()
    out = bytearray(w * h * 2)
    o = 0
    for y in range(h):
        for x in range(w):
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
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "real_1920x564.icf")
    im = Image.open(src).convert("RGB").resize((CAP_W, CAP_H), Image.BILINEAR)
    band = im.crop((0, CROP_TOP, CAP_W, CROP_TOP + BAND_H))
    band.save(os.path.join(HERE, "real_band.png"))
    raw = to565(band)
    payload = lz4.block.compress(raw, store_size=False)
    hdr = b"ICF1" + struct.pack("<IIIII", CAP_W, BAND_H, 2, len(payload), len(raw))
    with open(dst, "wb") as f:
        f.write(hdr + payload)
    print(f"raw={len(raw)} payload={len(payload)} ratio={len(raw)/len(payload):.2f}x")
    print(f"wrote {dst} ({os.path.getsize(dst)} bytes)")


if __name__ == "__main__":
    main()
