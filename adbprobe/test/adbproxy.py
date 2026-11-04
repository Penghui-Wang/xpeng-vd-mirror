#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
adb 报文代理：本地监听，转发到车机 adbd，把双向的包全打印出来，并校验 CRC。

用法:
    python adbproxy.py 5051 192.168.31.210 5050
    adb connect 127.0.0.1:5051
    adb -s 127.0.0.1:5051 shell id
"""
import socket
import struct
import sys
import threading
import zlib

NAMES = {
    0x4e584e43: "CNXN",
    0x48545541: "AUTH",
    0x4e45504f: "OPEN",
    0x59414b4f: "OKAY",
    0x45545257: "WRTE",
    0x45534c43: "CLSE",
    0x4b414e53: "SYNC",
}

lock = threading.Lock()
CAP = open("authcap.txt", "w", encoding="utf-8")
CID = [0]
TOKENS = {}


def log(msg):
    with lock:
        print(msg, flush=True)


def pump(src, dst, tag, cid):
    buf = b""
    token = {"v": b""}
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
            buf += data
            while len(buf) >= 24:
                cmd, a0, a1, ln, crc, magic = struct.unpack("<6I", buf[:24])
                if ln > 4 * 1024 * 1024:
                    buf = b""
                    break
                if len(buf) < 24 + ln:
                    break
                payload = buf[24:24 + ln]
                buf = buf[24 + ln:]
                name = NAMES.get(cmd, hex(cmd))

                calc = zlib.crc32(payload) & 0xFFFFFFFF
                bad = ""
                if calc != crc:
                    bad = "  !!CRC 收到 %08x 计算 %08x" % (crc, calc)
                if magic != (~cmd & 0xFFFFFFFF):
                    bad += "  !!MAGIC 不匹配"

                extra = ""
                if name == "CNXN":
                    extra = " version=%#x data=%d banner=%r" % (
                        a0, a1, payload.split(b"\0")[0].decode("utf-8", "replace")[:44])
                elif name == "AUTH":
                    kind = {1: "TOKEN", 2: "SIGNATURE", 3: "PUBKEY"}.get(a0, str(a0))
                    extra = " %s a0=%d a1=%d len=%d" % (kind, a0, a1, ln)
                    if kind == "TOKEN":
                        TOKENS[cid] = payload
                    elif kind == "SIGNATURE":
                        CAP.write("cid%d %s %s\n" % (cid, TOKENS.get(cid, b"").hex(), payload.hex()))
                        CAP.flush()
                elif name == "WRTE":
                    extra = " a0=%d a1=%d len=%d" % (a0, a1, ln)
                log("%-4s #%d %-5s%s%s" % (tag, cid, name, extra, bad))
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def main():
    lport = int(sys.argv[1]) if len(sys.argv) > 1 else 5051
    rhost = sys.argv[2] if len(sys.argv) > 2 else "192.168.31.210"
    rport = int(sys.argv[3]) if len(sys.argv) > 3 else 5050

    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", lport))
    srv.listen(8)
    log("proxy %d -> %s:%d" % (lport, rhost, rport))

    while True:
        c, addr = srv.accept()
        cid = CID[0] = CID[0] + 1
        log("--- #%d 新连接 %s ---" % (cid, addr[1]))
        r = socket.create_connection((rhost, rport), timeout=10)
        threading.Thread(target=pump, args=(c, r, "C->S", cid), daemon=True).start()
        threading.Thread(target=pump, args=(r, c, "S->C", cid), daemon=True).start()


if __name__ == "__main__":
    main()
