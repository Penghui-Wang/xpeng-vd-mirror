#!/usr/bin/env python3
"""从仪表把文件拉回本地：走 30510 RPC 执行 base64，再从原始响应里截取。

用法: python pull.py <远程路径> <本地路径>
"""
import base64
import os
import sys

import icrpc

BEGIN = "@@B64@@"
END = "@@EOF@@"


def pull(remote, local, chunk=None):
    cmd = f"echo {BEGIN}; busybox base64 {remote}; echo {END}"
    _, out, _ = icrpc.run(cmd, timeout=120)
    i = out.find(BEGIN.encode())
    j = out.find(END.encode(), i + 1 if i >= 0 else 0)
    if i < 0 or j < 0:
        print("marker not found; resp=%d bytes" % len(out))
        return False
    blob = out[i + len(BEGIN):j]
    txt = b"".join(blob.split())  # 去掉所有空白与 RPC 帧里的换行
    # 只保留 base64 合法字符
    txt = bytes(c for c in txt if c in b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
    data = base64.b64decode(txt)
    with open(local, "wb") as f:
        f.write(data)
    print("pulled %s -> %s (%d bytes)" % (remote, local, len(data)))
    return True


if __name__ == "__main__":
    ok = pull(sys.argv[1], sys.argv[2])
    sys.exit(0 if ok else 1)
