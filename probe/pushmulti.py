#!/usr/bin/env python3
"""把车机 /sdcard/Download/<name> 分批推到仪表 <remote>。

仪表端 busybox nc 一次连接最多落 65536 字节（64KB socket 缓冲），
超过就静默丢尾：400KB 只落 397312、128KB 只落 65536、wget 拉大文件停在
61440，都是同一个上限。所以必须切块，每块 < 64KB。

每次连接要重新 listen（nc -l 接完一个连接就退出），逐块串行要几十次
30510 往返。这里改成：一批起 N 个端口同时监听 → 车机串行发 N 块 →
一次校验 → 最后 cat 拼接。RPC 次数从 80+ 降到 12。

用法: python pushmulti.py <sdcard文件名> <仪表路径> <总字节> [块大小] [批量]
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icrpc

ADB = r"D:/Android/sdk/platform-tools/adb.exe"
DEV = "192.168.31.210:5050"
IC_IP = "172.20.1.40"
CHUNK = 60000
BATCH = 10
PORT0 = 6200


def adb_shell(cmd, timeout=300):
    """adb server 会被周期性回收，不重连就是 no devices/emulators found，
    而且这个失败是静默的（capture_output 吞掉 stderr），表现为「一块都没进去」。"""
    for _ in range(4):
        r = subprocess.run([ADB, "-s", DEV, "shell", cmd],
                           capture_output=True, timeout=timeout)
        err = (r.stderr or b"").decode("utf-8", "replace")
        if "no devices" in err or "not found" in err:
            subprocess.run([ADB, "start-server"], capture_output=True)
            subprocess.run([ADB, "connect", DEV], capture_output=True)
            continue
        return r
    return r


def ic(cmd, timeout=90, hold=3):
    _, out, _ = icrpc.run(cmd, timeout=timeout, hold=hold)
    return out.decode("utf-8", "replace")


def main():
    name, remote, total = sys.argv[1], sys.argv[2], int(sys.argv[3])
    chunk = int(sys.argv[4]) if len(sys.argv) > 4 else CHUNK
    batch = int(sys.argv[5]) if len(sys.argv) > 5 else BATCH
    src = "/sdcard/Download/" + name
    nblk = (total + chunk - 1) // chunk

    # 车机侧切片（dd 按 bs 切，skip=i count=1）
    print("切块 %d 块 x %d 字节" % (nblk, chunk))
    cut = ("cd /sdcard/Download && rm -f _p_*; "
           "i=0; while [ $i -lt %d ]; do "
           "dd if=%s of=_p_$i bs=%d skip=$i count=1 2>/dev/null; "
           "i=$((i+1)); done; ls -l _p_* | tail -2; echo CUT_OK" % (nblk, src, chunk))
    r = adb_shell(cut)
    out = r.stdout.decode("utf-8", "replace")
    print(out.strip()[-200:])
    if "CUT_OK" not in out:
        print("切块失败")
        return 1

    ic("rm -rf /tmp/_icp; mkdir -p /tmp/_icp", hold=2)

    b = 0
    while b < nblk:
        lo, hi = b, min(b + batch, nblk)
        ports = [PORT0 + i for i in range(lo, hi)]
        # 一次 RPC 起 N 个监听
        start = ("killall nc 2>/dev/null; " + "; ".join(
            "(setsid nc -l -p %d >> /tmp/_icp/p%d 2>/dev/null < /dev/null &)" % (p, i)
            for i, p in zip(range(lo, hi), ports)) +
            "; sleep 1.5; echo LIVE=$(ss -ltn 2>/dev/null | grep -c ':6[2-3][0-9][0-9] ')")
        r = ic(start, hold=3)
        m = re.search(r"LIVE=(\d+)", r)
        live = int(m.group(1)) if m else -1
        print("  批 %d-%d: 监听 %d/%d" % (lo, hi - 1, live, hi - lo))
        if live < hi - lo:
            print("  监听不足，重试")
            continue

        # 车机串行发这一批
        send = []
        for i, p in zip(range(lo, hi), ports):
            send.append("cat /sdcard/Download/_p_%d | nc -q 1 -w 3 %s %d" % (i, IC_IP, p))
        r = adb_shell("; ".join(send))
        time.sleep(0.5)

        # 校验这一批
        chk = "; ".join("echo B%d=$(stat -c %%s /tmp/_icp/p%d 2>/dev/null)"
                        % (i, i) for i in range(lo, hi))
        r = ic(chk, hold=3)
        bad = []
        for i in range(lo, hi):
            m = re.search(r"B%d=(\d+)" % i, r)
            got = int(m.group(1)) if m else 0
            want = chunk if i < nblk - 1 else total - chunk * (nblk - 1)
            if got != want:
                bad.append((i, got, want))
        if bad:
            print("  坏块：", bad[:4])
            for i, got, want in bad:
                ic("rm -f /tmp/_icp/p%d" % i, hold=2)
            continue
        print("  批 %d-%d 校验通过" % (lo, hi - 1))
        b = hi

    # 拼接
    cat = ("cd /tmp/_icp && rm -f all && for i in $(seq 0 %d); do cat p$i >> all; done; "
           "rm -f %s; cp all %s && chmod 755 %s && ls -l %s && md5sum %s"
           % (nblk - 1, remote, remote, remote, remote, remote))
    # hold 必须给够：拼接 + md5 要好几秒，hold 太短会在 cp 之前把会话掐断，
    # 表现为 all 已经完整但远端文件没更新、回包是 exit code 1。
    print(ic(cat, hold=12).strip()[-260:])
    return 0


if __name__ == "__main__":
    sys.exit(main())
