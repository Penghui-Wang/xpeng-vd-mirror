package com.xiaopeng.vdcast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 仪表端管理通道：把命令封成 30510 的 protobuf 帧，交给车机用 nc 发过去，再取回 stdout。
 *
 * 这条链路和 PC 上的 probe/icrpc.py 完全一致（帧构造逐字节对齐）：
 *   车机侧: (cat <帧文件>; sleep <hold>) | nc <仪表IP> 30510
 * 为什么不能直连：仪表 172.20.1.40 只在车机内网，APK 在应用网段，必须由车机代发。
 *
 * 帧结构（16 字节定长头 + 两段 protobuf）：
 *   4 字节魔数 01 01 02 11 | 4 字节大端总长 | 8 字节通道头
 *   0a 06 08 <varint 时间戳>      (字段1：时间)
 *   12 05 "shell"                 (字段2：执行方式)
 *   12 <varint 长度> <命令文本>    (字段2：shell 命令)
 */
public final class Icrpc {

    public static final String IC_IP = "172.20.1.40";
    public static final int IC_RPC = 30510;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Icrpc() { }

    /** varint 编码（protobuf 标准） */
    static byte[] varint(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(8);
        while (v >= 128) {
            o.write((int) ((v & 127) | 128));
            v >>= 7;
        }
        o.write((int) v);
        return o.toByteArray();
    }

    /** 构造一帧；与 icrpc.py 的 build_frame 输出必须逐字节相同（test/FrameHex 会校验） */
    public static byte[] frame(String cmd) {
        byte[] c = cmd.getBytes(StandardCharsets.UTF_8);
        byte[] cl = varint(c.length);
        int plen = 16 + cl.length + c.length;
        int size = 8 + plen;

        ByteArrayOutputStream b = new ByteArrayOutputStream(32 + c.length);
        b.write(0x01); b.write(0x01); b.write(0x02); b.write(0x11);
        b.write((size >>> 24) & 0xFF);
        b.write((size >>> 16) & 0xFF);
        b.write((size >>> 8) & 0xFF);
        b.write(size & 0xFF);
        byte[] head = {0x7e, 0x71, 0x41, 0x01, 0x01, 0x00, 0x00, 0x00};
        b.write(head, 0, head.length);
        byte[] ts = {0x0a, 0x06, 0x08, (byte) 0x81, (byte) 0x82, (byte) 0xc5, (byte) 0xf3, 0x07};
        b.write(ts, 0, ts.length);
        byte[] sh = {0x12, 0x05, 's', 'h', 'e', 'l', 'l'};
        b.write(sh, 0, sh.length);
        b.write(0x12);
        b.write(cl, 0, cl.length);
        b.write(c, 0, c.length);
        return b.toByteArray();
    }

    /** 远端命令执行完打的哨兵：出现它就说明回包收完了，不用再干等 sleep */
    private static final String MARK = "__ADBPROBE_RPC_END__";

    /**
     * 在仪表上执行一条命令，返回可读输出（已剔除 protobuf 二进制头与哨兵行）。
     *
     * <p>回包是「边跑边回」的，所以不必傻等：命令末尾追加一个哨兵 {@code echo}，
     * 车机侧轮询回包文件里有没有哨兵，有就立刻杀掉整条管道返回。
     * 实测一次普通查询从固定 6 秒降到 1.5 秒左右。
     *
     * <p>为什么必须先 setsid 再杀进程组：只 kill nc 的话，管道里
     * {@code (cat 帧; sleep N)} 那个子 shell 还活着，adb shell 的会话要等它
     * sleep 完才收尾 —— 那就又变回干等了（实测 8 秒的 hold 一秒不少）。
     *
     * @param holdSec 远端命令<b>最长</b>存活时间；正常的短命令它会提前结束，
     *                只有长任务（比如 wget 3.4MB）才真的跑满
     */
    public static String run(Adb adb, String cmd, int holdSec, int timeoutMs) throws IOException {
        byte[] f = frame(cmd + "; echo " + MARK);
        int id = SEQ.incrementAndGet();
        String remote = "/data/local/tmp/.adbprobe_f" + id + ".bin";
        String res = "/data/local/tmp/.adbprobe_r" + id + ".out";
        adb.push(f, remote, Adb.MODE_FILE, 20000);

        int ticks = Math.max(10, holdSec * 10);       // 0.1 秒一格
        // toybox nc 在 stdin EOF 后立刻收尾会丢回包，所以要用 sleep 把 stdin 撑住；
        // 它现在是「上限」而不是「固定等待」，因为轮询到哨兵就把整组杀了。
        String sh = "F=" + remote + "; O=" + res + "; export F O; "
                + "setsid sh -c '(cat $F; sleep " + holdSec + ") | nc " + IC_IP + " " + IC_RPC
                + " > $O 2>&1' & P=$!; "
                + "i=0; while ! grep -aq " + MARK + " $O 2>/dev/null; do "
                + "i=$((i+1)); [ $i -ge " + ticks + " ] && break; sleep 0.1; done; "
                + "kill -9 -$P 2>/dev/null; sleep 0.12; cat $O 2>/dev/null; rm -f $F $O";
        String raw = adb.exec(sh, timeoutMs);
        return printable(raw);
    }

    /** 只留可打印字符与换行，去掉 protobuf 头与哨兵（与 icrun.py 的清洗规则一致） */
    static String printable(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\t' || (ch > 31 && ch < 127)) sb.append(ch);
        }
        StringBuilder out = new StringBuilder();
        for (String line : sb.toString().split("\n")) {
            // 哨兵可能和命令输出粘在同一行（命令末尾没换行时），所以按子串去掉
            String t = line.replace(MARK, "").trim();
            // 回包头里 7e 71 41 就是 "~qA"，是可打印的，会混进日志（前面还常带一两个长度字节）
            if (t.matches(".{0,4}~qA")) continue;
            if (!t.isEmpty()) out.append(t).append('\n');
        }
        return out.toString();
    }

    /** 车机在仪表内网侧的地址（ig 用 ip route get 反查，失败退回 172.20.1.45） */
    public static String carIp(Adb adb, String fallback) {
        try {
            String out = adb.exec("ip route get " + IC_IP + " 2>/dev/null | head -1", 8000);
            int at = out.indexOf("src ");
            if (at >= 0) {
                String rest = out.substring(at + 4).trim();
                int sp = rest.indexOf(' ');
                String ip = sp > 0 ? rest.substring(0, sp) : rest;
                if (ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return ip;
            }
        } catch (Throwable ignored) { }
        return fallback;
    }
}
