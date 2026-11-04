import os, re, subprocess, sys, time, random

ADB = r"D:/Android/sdk/platform-tools/adb.exe"
PROBE = r"E:/Users/73576/WorkBuddy/2026-10-30-19-53-46/probe"
LOCAL = r"E:/Users/73576/WorkBuddy/2026-10-30-19-53-46/tools/icrecv/icrecv_arm"
REMOTE = "/tmp/icrecv"
CHUNK = 100000

TOTAL = os.path.getsize(LOCAL)
print("local size:", TOTAL)


def ic(cmd, timeout=40):
    p = subprocess.run([sys.executable, "icrpc.py", cmd], cwd=PROBE,
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=timeout)
    return p.stdout


def remote_size():
    out = ic("echo SZ=$(stat -c %s " + REMOTE + " 2>/dev/null || echo 0)")
    m = re.search(r"SZ=(\d+)", out)
    return int(m.group(1)) if m else -1


def send_chunk(off, n):
    port = random.randint(5200, 5900)
    # 仪表侧起监听
    ic("(nc -l -p %d >> %s) >/dev/null 2>&1 & sleep 1; echo R%d" % (port, REMOTE, port))
    # 车机侧发送
    cmd = ("tail -c +%d /sdcard/Download/icrecv | head -c %d | nc -w 4 172.20.1.40 %d"
           % (off + 1, n, port))
    subprocess.run([ADB, "shell", cmd], capture_output=True, timeout=60)
    time.sleep(1)


ic("rm -f %s" % REMOTE)
cur = 0
stall = 0
while cur < TOTAL:
    before = remote_size()
    if before < 0:
        before = 0
    cur = before
    if cur >= TOTAL:
        break
    n = min(CHUNK, TOTAL - cur)
    send_chunk(cur, n)
    after = remote_size()
    print("off=%d sent=%d -> remote=%d (%.1f%%)" % (cur, n, after, after * 100.0 / TOTAL))
    if after <= cur:
        stall += 1
        print("  !! no progress, retry", stall)
        if stall >= 6:
            print("GIVE UP")
            break
    else:
        stall = 0

print("final size:", remote_size(), "/", TOTAL)
print(ic("md5sum %s" % REMOTE))
