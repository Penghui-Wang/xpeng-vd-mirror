#!/usr/bin/env python3
"""从仪表拉文件（分块 base64），自动补齐 padding。

RPC 单次响应超过 ~32KB 会被截断，所以每块原始字节 8192（base64 后约 11KB）。
用法: python pullimg.py <远程路径> <本地路径>
"""
import base64
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
import icrpc

B = "@@B@@"
E = "@@E@@"
ALPHA = set(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")


def grab(remote, skip, bs=8192):
    cmd = ("echo %s; dd if=%s bs=%d skip=%d count=1 2>/dev/null | base64; echo; echo %s"
           % (B, remote, bs, skip, E))
    _, out, _ = icrpc.run(cmd, timeout=60, hold=8)
    i = out.find(B.encode())
    j = out.find(E.encode(), i + 1 if i >= 0 else 0)
    if i < 0 or j < 0:
        return b""
    blob = out[i + len(B):j]
    return bytes(c for c in blob if c in ALPHA).replace(b"=", b"")


def size_of(remote):
    _, out, _ = icrpc.run("stat -c %%s %s" % remote, timeout=40, hold=6)
    digits = ""
    for ch in out.decode("latin-1"):
        if ch.isdigit():
            digits += ch
        elif digits:
            break
    return int(digits) if digits else 0


def main():
    remote, local = sys.argv[1], sys.argv[2]
    total = size_of(remote)
    parts = []
    for k in range(4096):
        t = grab(remote, k)
        if not t:
            break
        # 每块单独补齐再解码：块被 RPC 截断时长度不是 4 的倍数，
        # 直接拼成一条 base64 流会让后面全部错位（PNG 能读到 IEND 但 IDAT 坏）
        pad = (-len(t)) % 4
        parts.append(base64.b64decode(t + b"=" * pad))
        if total and sum(len(p) for p in parts) >= total:
            break
    data = b"".join(parts)
    with open(local, "wb") as f:
        f.write(data)
    print("%s -> %s  %d bytes (remote %d)" % (remote, local, len(data), total))


if __name__ == "__main__":
    main()
