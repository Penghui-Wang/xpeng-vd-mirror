#!/usr/bin/env python3
"""在仪表上跑一条 shell 命令，只打印可读输出（丢掉 protobuf 头部二进制）。

管理通道 30510 的回包是一个 protobuf 帧，前 8~16 字节是二进制头，
之后才是远端命令的 stdout，而且二进制头常常和第一行文本粘在一起。
这里把所有不可打印字符（保留换行）直接删掉再打印——既不丢行，也不会刷乱码。

用法: python icrun.py "<命令>" [hold秒] [timeout秒]
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icrpc

cmd = sys.argv[1]
hold = int(sys.argv[2]) if len(sys.argv) > 2 else 8
timeout = int(sys.argv[3]) if len(sys.argv) > 3 else 40

_, out, err = icrpc.run(cmd, timeout=timeout, hold=hold)

if out is None:
    out = b""
text = out.decode("utf-8", "replace")
clean = "".join(c if (c == "\n" or 31 < ord(c) < 127 or c == "\t") else "" for c in text)

# 去掉空行，保持输出紧凑
for line in clean.splitlines():
    line = line.rstrip()
    if line:
        print(line)

if err:
    e = (err or b"").decode("utf-8", "replace").strip()
    if e:
        print("[stderr] " + e[:300])
