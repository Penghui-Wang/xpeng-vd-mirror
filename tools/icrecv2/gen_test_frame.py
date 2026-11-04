#!/usr/bin/env python3
"""生成一帧 1920x720 RGB565 的真实感测试帧，并做 LZ4 block 压缩。

用途：在仪表真机上 benchmark LZ4 解压与刷屏耗时。
内容取自实拍地图截图并压成暗色导航主题，压缩率接近真实投屏内容
（而不是合成出来的大片同色，那种数据会把解压速度算得过于乐观）。
"""
import os
import struct

import lz4.block
from PIL import Image, ImageEnhance

HERE = os.path.dirname(os.path.abspath(__file__))
PROBE = os.path.abspath(os.path.join(HERE, "..", "..", "probe"))
W, H = 1920, 720


def rgb565_le(im):
    """PIL RGB -> RGB565 little-endian bytes"""
    im = im.convert("RGB")
    px = im.load()
    out = bytearray(W * H * 2)
    o = 0
    for y in range(H):
        for x in range(W):
            r, g, b = px[x, y]
            v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
            if v == 0:
                v = 1  # 避开前景层色键
            out[o] = v & 0xFF
            out[o + 1] = v >> 8
            o += 2
    return bytes(out)


def build():
    src = Image.open(os.path.join(PROBE, "amap.png")).convert("RGB")
    # 取地图区，按 16:9 中心裁切后铺满
    tw, th = W, H
    scale = max(tw / src.width, th / src.height)
    im = src.resize((round(src.width * scale), round(src.height * scale)), Image.LANCZOS)
    left = (im.width - tw) // 2
    top = (im.height - th) // 2
    im = im.crop((left, top, left + tw, top + th))
    im = ImageEnhance.Brightness(im).enhance(0.55)
    im = ImageEnhance.Color(im).enhance(0.45)
    return im


def main():
    im = build()
    im.save(os.path.join(HERE, "frame_ref.png"))

    raw = rgb565_le(im)
    with open(os.path.join(HERE, "frame1920x720.raw"), "wb") as f:
        f.write(raw)

    comp = lz4.block.compress(raw, store_size=False)
    with open(os.path.join(HERE, "frame1920x720.lz4"), "wb") as f:
        f.write(comp)

    print(f"raw={len(raw)} lz4={len(comp)} ratio={len(raw)/len(comp):.2f}x")
    print(f"100Mbps(12.5MB/s) 下的上行帧率上限: {12.5e6/len(comp):.1f}fps")


if __name__ == "__main__":
    main()
