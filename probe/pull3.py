#!/usr/bin/env python3
"""从仪表拉回文件：按字节 dd 分块，绕开 busybox base64 无换行的问题。

RPC 单次响应超过 ~32KB 会被截断，所以每块原始字节控制在 8192。
用法: python pull3.py <远程路径> <本地路径> [块字节]
"""
import base64
import sys

import icrpc

B = "@@B@@"
E = "@@E@@"


def grab(remote, skip, bs):
    cmd = (f"echo {B}; dd if={remote} bs={bs} skip={skip} count=1 2>/dev/null "
           f"| base64; echo; echo {E}")
    _, out, _ = icrpc.run(cmd, timeout=60, hold=8)
    i = out.find(B.encode())
    j = out.find(E.encode(), i + 1 if i >= 0 else 0)
    if i < 0 or j < 0:
        raise RuntimeError("marker not found (resp %d B)" % len(out))
    blob = out[i + len(B):j]
    return bytes(c for c in blob if c in
                 b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")


def size_of(remote):
    cmd = f"echo {B}; busybox wc -c {remote}; echo {E}"
    _, out, _ = icrpc.run(cmd, timeout=40, hold=6)
    i = out.find(B.encode())
    j = out.find(E.encode(), i + 1 if i >= 0 else 0)
    seg = out[i + len(B):j] if i >= 0 and j >= 0 else out
    s = seg.decode("latin-1").strip()
    digits = ""
    for ch in s:
        if ch.isdigit():
            digits += ch
        elif digits:
            break
    if not digits:
        raise RuntimeError("无法获取大小: %r" % s[:80])
    return int(digits)


def pull(remote, local, bs=8192):
    parts = []
    k = 0
    while k < 200:
        txt = grab(remote, k, bs)
        if not txt:
            break
        parts.append(txt)
        print(f"  block {k}  {len(txt)} chars")
        k += 1
    if not parts:
        raise RuntimeError("没有取到任何数据")
    data = base64.b64decode(b"".join(parts))
    with open(local, "wb") as f:
        f.write(data)
    print(f"decoded {len(data)} bytes -> {local}")
    return data


if __name__ == "__main__":
    r, l = sys.argv[1], sys.argv[2]
    b = int(sys.argv[3]) if len(sys.argv) > 3 else 8192
    pull(r, l, b)
