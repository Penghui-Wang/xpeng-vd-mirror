#!/usr/bin/env python3
"""30510 管理通道只读探针：构造 protobuf 请求帧，仅发送只读命令。"""
import struct, sys, os, subprocess

ADB = r"D:/Android/sdk/platform-tools/adb.exe"
IC_IP = "172.20.1.40"
IC_RPC = 30510


def varint(v):
    out = bytearray()
    while v >= 128:
        out.append((v & 127) | 128)
        v >>= 7
    out.append(v)
    return bytes(out)


def build_frame(cmd):
    c = cmd.encode()
    clen = len(c)
    plen = 16 + len(varint(clen)) + clen
    size = 8 + plen
    b = bytearray()
    b += b"\x01\x01\x02\x11"
    b += struct.pack(">I", size)
    b += b"\x7e\x71\x41\x01\x01\x00\x00\x00"
    b += b"\x0a\x06\x08\x81\x82\xc5\xf3\x07"
    b += b"\x12\x05shell"
    b += b"\x12"
    b += varint(clen)
    b += c
    return bytes(b)


_ready = False


def _online():
    try:
        out = subprocess.run([ADB, "devices"], capture_output=True, timeout=30).stdout
        return ("192.168.31.210:5050\tdevice").encode() in out.replace(b"\r", b"")
    except Exception:
        return False


def ensure(force=False):
    """确保 adb 守护进程在线且已连上车机。

    注意：调用方环境会在两次调用之间回收 adb 守护进程，所以这里每次都要真的
    验证一遍（缓存 _ready 只在同一个进程内的连续调用里省事），并且 connect
    有概率超时（车机 adbd 上留着一条已死的会话），必须多重试几次。
    """
    global _ready
    if _ready and not force and _online():
        return True
    dev = "192.168.31.210:5050"
    for _ in range(3):
        try:
            subprocess.run([ADB, "start-server"], capture_output=True, timeout=30)
            if _online():
                _ready = True
                return True
            subprocess.run([ADB, "connect", dev], capture_output=True, timeout=30)
            import time
            for _ in range(10):
                if _online():
                    _ready = True
                    return True
                time.sleep(0.4)
            subprocess.run([ADB, "kill-server"], capture_output=True, timeout=20)
            time.sleep(1)
        except Exception:
            pass
    _ready = False
    return False


def run(cmd, timeout=15, hold=4):
    frame = build_frame(cmd)
    local = os.path.join(os.path.dirname(__file__), f".frame_{os.getpid()}.bin")
    with open(local, "wb") as f:
        f.write(frame)
    remote = "/data/local/tmp/.probe_frame.bin"
    last = (frame, b"", b"")
    for attempt in range(3):
        ensure(force=attempt > 0)
        subprocess.run([ADB, "push", local, remote], capture_output=True, timeout=30)
        # toybox nc 在 stdin EOF 后立刻收尾会丢回包，需保持 stdin 打开
        # hold 决定远端命令最长存活时间（长任务要调大，否则 stdout 管道被关掉）
        sh = f"(cat {remote}; sleep {hold}) | nc {IC_IP} {IC_RPC}"
        p = subprocess.run([ADB, "exec-out", sh], capture_output=True, timeout=timeout + 10)
        last = (frame, p.stdout, p.stderr)
        err = (p.stderr or b"").lower()
        if b"no devices" in err or b"not found" in err or b"cannot connect" in err:
            continue
        break
    return last


def show(cmd):
    frame, out, err = run(cmd)
    print(f"### CMD: {cmd}")
    print(f"### 请求帧 {len(frame)} 字节, 响应 {len(out)} 字节")
    if out:
        txt = out.decode("utf-8", "replace")
        printable = "".join(ch if (31 < ord(ch) < 127 or ch in "\r\n\t") else "." for ch in txt)
        print("--- 可读 ---")
        print(printable[:3000])
        print("--- hex(前160) ---")
        print(out[:160].hex(" "))
    else:
        print("!! 无响应")
    print()


if __name__ == "__main__":
    for cmd in sys.argv[1:]:
        show(cmd)
