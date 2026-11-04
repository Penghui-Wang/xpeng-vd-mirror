#!/usr/bin/env python3
"""用 Python lz4 库生成 LZ4 block 测试向量，供 Go 解码器做交叉验证。

刻意覆盖解码器的各个分支：
  - 纯 literal（不可压缩数据，无 match）
  - offset=1 的重叠 match（RLE 型，matchLen 远大于 offset）
  - 短 match / 长 match
  - literal 长度跨过 15、255、510 的扩展编码
  - match 长度跨过 15+4、255、510 的扩展编码
  - 真实帧尺寸（2764800 字节）的类地图数据
"""
import os
import random

import lz4.block

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "testdata")


def cases():
    rnd = random.Random(20261030)

    # 1 纯 literal：随机字节几乎无法匹配
    yield "random", bytes(rnd.getrandbits(8) for _ in range(70000))

    # 2 单字节重复 → offset=1 重叠 match
    yield "rle_1", b"\xa5" * 90000

    # 3 三字节周期 → 短 offset 重叠
    yield "period_3", (b"xyz" * 30000)

    # 4 小字面 + 超长 match：literal 长度触发 255 链
    yield "long_literal", (b"HEAD" + bytes(rnd.getrandbits(8) for _ in range(700)) + b"TAIL" * 40000)

    # 5 大量短片段交替，制造大量小 match 与小 literal
    parts = []
    for i in range(4000):
        parts.append(bytes([i & 0xFF]) * (i % 7 + 1))
        parts.append(b"sep%d" % (i % 13))
    yield "choppy", b"".join(parts)

    # 6 真实帧尺寸的类地图数据：大片同色 + 线条
    w, h = 1280, 376
    buf = bytearray(w * h * 2)
    for y in range(h):
        base = (y * 3) & 0xFF
        row = y * w * 2
        for x in range(0, w, 2):
            v = base if (x + y) % 97 else 0xFF
            buf[row + x] = v
            buf[row + x + 1] = v >> 1
    yield "frame_1280x376", bytes(buf)

    # 7 空与极短
    yield "tiny", b"A"
    yield "emptyish", b"AB"


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, data in cases():
        comp = lz4.block.compress(data, store_size=False)
        with open(os.path.join(OUT, name + ".raw"), "wb") as f:
            f.write(data)
        with open(os.path.join(OUT, name + ".lz4"), "wb") as f:
            f.write(comp)
        ratio = len(data) / len(comp) if comp else 0
        print(f"{name:16s} raw={len(data):8d} lz4={len(comp):8d} ratio={ratio:5.2f}x")


if __name__ == "__main__":
    main()
