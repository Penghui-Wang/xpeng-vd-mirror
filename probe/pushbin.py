#!/usr/bin/env python3
"""把 /sdcard/Download/<name> 推到仪表 <remote>。

为什么是 100KB 分块而不是整包：车机侧是 toybox nc，stdin 一 EOF 就退出，
4.7MB 里只有前约 440KB 能落盘（-q 也救不回来）。分块后每块都在 socket
缓冲量级内，一次写得完。

为什么要 ss 确认：随机端口偶尔撞上残留监听，nc -l 静默失败 → 发送端连不上
→ 目标文件不增长，表现为「卡在某一块反复重试」。先确认端口真的在 LISTEN
再发，比事后重试快得多。

用法: python pushbin.py <sdcard文件名> <仪表目标路径> <总字节>
"""
import os
import random
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icrpc

ADB = r"D:/Android/sdk/platform-tools/adb.exe"
CHUNK = 400000
IC_IP = "172.20.1.40"


def ic(cmd, timeout=60, hold=1):
    _, out, _ = icrpc.run(cmd, timeout=timeout, hold=hold)
    return out.decode("utf-8", "replace")


def main():
    name, remote, total = sys.argv[1], sys.argv[2], int(sys.argv[3])
    src = "/sdcard/Download/" + name

    def rsize():
        m = re.search(r"SZ=(\d+)", ic("echo SZ=$(stat -c %%s %s 2>/dev/null)" % remote))
        return int(m.group(1)) if m else 0

    cur = rsize()
    if cur > total:
        ic("rm -f " + remote)
        cur = 0
    print("start at %d / %d" % (cur, total))

    stall = 0
    while cur < total:
        n = min(CHUNK, total - cur)
        ok = False
        for _ in range(6):
            port = random.randint(5200, 5900)
            # 不能用 pkill -f 'nc -l'：这条命令自身的 cmdline 里就有 "nc -l"，
            # pkill -f 会把执行它的 /bin/sh 一起杀掉，表现为「从第 0 块就卡住」。
            # 注意重定向：stdout 只能重定向一次。写成 `>> FILE >/dev/null` 时
            # 后一个会把 stdout 改成 /dev/null，文件被创建但永远是 0 字节，
            # 而 ss 显示端口在听、发送端 RC=0 —— 表现为「一块都传不进去」。
            out = ic("killall nc 2>/dev/null; "
                     "(setsid nc -l -p %d >> %s 2>/dev/null < /dev/null &); "
                     "sleep 0.7; ss -ltn 2>/dev/null | grep -q ':%d ' && echo UP"
                     % (port, remote, port))
            if "UP" not in out:
                continue
            subprocess.run([ADB, "shell",
                            "tail -c +%d %s | head -c %d | nc -q 2 -w 5 %s %d"
                            % (cur + 1, src, n, IC_IP, port)],
                           capture_output=True, timeout=60)
            time.sleep(0.2)
            after = rsize()
            if after > cur:
                cur = after
                ok = True
                break
        if not ok:
            stall += 1
            print("  !! stuck at %d (retry %d)" % (cur, stall))
            if stall >= 5:
                print("GIVE UP")
                return
            continue
        stall = 0
        if cur % 500000 < CHUNK:
            print("  %d/%d (%.0f%%)" % (cur, total, cur * 100.0 / total))

    print(ic("md5sum %s; chmod 755 %s" % (remote, remote)).strip()[:200])


if __name__ == "__main__":
    main()
