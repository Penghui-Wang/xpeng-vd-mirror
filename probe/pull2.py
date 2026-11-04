#!/usr/bin/env python3
"""从仪表把文件拉回本地：30510 RPC 上分块取 busybox base64 输出。

RPC 单次响应有限（约 1.6KB 起、超过 ~32KB 会被截断），所以按 base64 行分块取。
busybox base64 每行 76 字符 = 57 原始字节。

用法: python pull2.py <远程路径> <本地路径> [每块行数]
"""
import base64
import sys

import icrpc

B = "@@B@@"
E = "@@E@@"


def grab(remote, start, count):
    """取 base64 输出的第 start 行起 count 行（1-based）。"""
    cmd = (f"echo {B}; busybox base64 {remote} | "
           f"busybox tail -n +{start} | busybox head -n {count}; echo {E}")
    _, out, _ = icrpc.run(cmd, timeout=60)
    i = out.find(B.encode())
    j = out.find(E.encode(), i + 1 if i >= 0 else 0)
    if i < 0 or j < 0:
        raise RuntimeError("marker not found (resp %d B)" % len(out))
    blob = out[i + len(B):j]
    txt = bytes(c for c in blob if c in
                b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
    return txt, j


def total_lines(remote):
    cmd = f"echo {B}; busybox base64 {remote} | busybox wc -l; echo {E}"
    _, out, _ = icrpc.run(cmd, timeout=60)
    i = out.find(B.encode())
    j = out.find(E.encode(), i + 1 if i >= 0 else 0)
    seg = out[i + len(B):j] if i >= 0 and j >= 0 else out
    digits = "".join(chr(c) for c in seg if 48 <= c <= 57)
    if not digits:
        raise RuntimeError("无法获取行数: %r" % seg[:80])
    return int(digits)


def pull(remote, local, chunk=96):
    n = total_lines(remote)
    print(f"{remote}: base64 {n} 行, 每块 {chunk} 行 -> {-(-n // chunk)} 次")
    parts = []
    for s in range(1, n + 1, chunk):
        txt, _ = grab(remote, s, chunk)
        parts.append(txt)
        print(f"  lines {s}-{min(s + chunk - 1, n)}  {len(txt)} chars")
    blob = b"".join(parts)
    pad = blob.count(b"=")
    data = base64.b64decode(blob)
    print(f"decoded {len(data)} bytes (pad={pad})")
    with open(local, "wb") as f:
        f.write(data)
    return data


if __name__ == "__main__":
    r, l = sys.argv[1], sys.argv[2]
    ch = int(sys.argv[3]) if len(sys.argv) > 3 else 96
    pull(r, l, ch)
    head = open(l, "rb").read(8)
    print("head:", head[:8].hex(" "), head[:4])
