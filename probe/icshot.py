#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从仪表抓一张 fb1 截图拉回本地。

为什么不用 adb 直传：仪表 172.20.1.40 在车机内网，PC 到不了，
只能 adb -> 车机 -> nc 仪表:30510 这条管理通道。通道前面有 8~32 字节
protobuf 头，所以必须先 echo 一个标记把正文切出来，否则 base64 会整体错位。

用法: python probe/icshot.py <输出png> [div=4]
"""
import base64
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icrpc

MARK = "ZZZMARK"


def shot(out, div=4):
    remote = "/tmp/.icshot%d.png" % div
    cmd = "/tmp/icrecv_split dump %s %d fb1; echo %s; base64 %s" % (remote, div, MARK, remote)
    _, raw, _ = icrpc.run(cmd, timeout=90, hold=30)
    text = raw.decode("latin-1")
    i = text.find(MARK)
    body = text[i + len(MARK):] if i >= 0 else text
    keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    clean = "".join(c for c in body if c in keep)
    clean = clean[: len(clean) // 4 * 4]
    data = base64.b64decode(clean)
    j = data.find(b"\x89PNG")
    if j < 0:
        raise SystemExit("no png in response (%d bytes raw)" % len(raw))
    with open(out, "wb") as f:
        f.write(data[j:])
    print("%s  %d bytes" % (out, os.path.getsize(out)))


if __name__ == "__main__":
    p = sys.argv[1] if len(sys.argv) > 1 else "tmp/shot.png"
    d = int(sys.argv[2]) if len(sys.argv) > 2 else 4
    shot(p, d)
