#!/usr/bin/env python3
"""生成一个 ICF1 帧文件，用于绕过车机 App 直接验证仪表接收端。

ICF1 头（小端，24 字节）：
  magic "ICF1" | w u32 | h u32 | fmt u32 | complen u32 | rawlen u32 | payload

输出中间带 1920x564 的画面（含卡片），与 compose 的 mid 区严格对齐：
仪表把它放在 y=72..639，上下 72/80 行取自 fb0。
"""
import os
import struct
import sys

import lz4.block
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
W, MID_H = 1920, 564
TOP, BOT = 72, 80


def to565(im, keep_zero=False):
    im = im.convert("RGB")
    px = im.load()
    out = bytearray(W * MID_H * 2)
    o = 0
    for y in range(MID_H):
        for x in range(W):
            r, g, b = px[x, y]
            v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
            if v == 0 and not keep_zero:
                v = 1  # 避开前景层色键 key=0
            out[o] = v & 0xFF
            out[o + 1] = v >> 8
            o += 2
    return bytes(out)


def main():
    # 默认取版式预览的中间带（地图 + 三张卡片已经合成在里面）
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "tools", "cards", "layout_preview.png")
    mode = sys.argv[2] if len(sys.argv) > 2 else "lz4"

    im = Image.open(src).convert("RGB")
    if im.size != (W, 720):
        im = im.resize((W, 720), Image.LANCZOS)
    im = im.crop((0, TOP, W, TOP + MID_H))
    im.save(os.path.join(HERE, "mid_band_ref.png"))

    raw = to565(im)
    if mode == "raw":
        fmt, payload = 1, raw
    else:
        fmt, payload = 2, lz4.block.compress(raw, store_size=False)

    header = b"ICF1" + struct.pack("<IIIII", W, MID_H, fmt, len(payload), len(raw))
    name = os.path.join(HERE, f"frame_1920x{MID_H}_{mode}.icf")
    with open(name, "wb") as f:
        f.write(header + payload)

    print(f"fmt={fmt} raw={len(raw)} payload={len(payload)} ratio={len(raw)/len(payload):.2f}x")
    print(f"wrote {name} ({os.path.getsize(name)} bytes)")


if __name__ == "__main__":
    main()
