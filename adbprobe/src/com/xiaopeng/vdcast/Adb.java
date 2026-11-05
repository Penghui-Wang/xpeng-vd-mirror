package com.xiaopeng.vdcast;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.zip.CRC32;

/**
 * 最小 ADB 客户端：TCP 连本机 adbd，完成 CNXN / AUTH 握手，然后开流跑命令或推文件。
 *
 * 认证：
 *   1) 发 CNXN 声明自己是 host
 *   2) adbd 回 AUTH/TOKEN，先用私钥签名（TYPE=2）——已授权过就到此为止
 *   3) 签名被拒说明这把钥匙没登记，改发公钥（TYPE=3），车机弹窗，用户点「允许 + 一律允许」
 *   4) 之后每次启动第 2 步直接通过，不再弹窗
 *
 * 能力：
 *   - shell:<cmd>  跑命令（等同 adb exec-out，无 pty，二进制安全）
 *   - sync:        推文件（SEND/DATA/DONE），等价于 adb push
 *
 * 注：包内出站流包的 arg0=本地 id、arg1=对端 id，与 adbd 的 find_local_socket(arg1, arg0) 约定一致。
 * 注：本类只用 android.util.Base64，其余全是标准 JDK，所以可以在 JVM 上跑本地 mock（见 test/）。
 */
public final class Adb {

    public interface Log { void print(String s); }

    private static final int CMD_CNXN = 0x4e584e43;
    private static final int CMD_AUTH = 0x48545541;
    private static final int CMD_OPEN = 0x4e45504f;
    private static final int CMD_OKAY = 0x59414b4f;
    private static final int CMD_WRTE = 0x45545257;
    private static final int CMD_CLSE = 0x45534c43;

    private static final int A_VERSION = 0x01000001;
    /** 从这个协议版本起，包头里的 data_check 固定为 0（adbd 会拒绝非 0 的包） */
    private static final int A_VERSION_SKIP_CHECKSUM = 0x01000001;
    private static final int MAX_PAYLOAD = 1024 * 1024;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_PUBKEY = 3;

    /** sync 协议单包上限（SYNC_DATA_MAX），留一半余量避免撞 adbd 的缓冲 */
    private static final int SYNC_CHUNK = 32 * 1024;
    private static final int WRTE_CHUNK = 64 * 1024;

    /** 文件模式：0100644 / 0100755（十进制，adbd 用 strtoul 解析） */
    public static final int MODE_FILE = 33188;
    public static final int MODE_EXEC = 33261;

    /** SHA-1 DigestInfo（PKCS#1 v1.5） */
    private static final byte[] SHA1_DI = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    /** SHA-256 DigestInfo */
    private static final byte[] SHA256_DI = {
            0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte) 0x86,
            0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05,
            0x00, 0x04, 0x20
    };

    /**
     * 签名变体。同一个 socket 上每收到一次 AUTH TOKEN 就换下一种试，
     * 免得为了试算法反复弹授权。
     *
     * 实测（小鹏 P7 车机 Android 9 / adbd 非标准实现）：
     *   把 token 当摘要直接做 PKCS#1 v1.5 → 被接受；
     *   AOSP 标准的「先 SHA-1 再签名」反而被拒。
     * 所以 raw 放第一位，标准 SHA-1 退到第二位兜底（通用设备还是要靠它）。
     */
    private static final String[] SIGN_MODES = {"raw", "SHA-1", "SHA-256"};

    private static final int REPUB_GAP_MS = 30_000;

    /**
     * 本进程内是否已经提交过公钥。
     *
     * 本车 ROM 的 adbd 授权 prompt 是**单槽位**，且有遗留 pending 时新请求被
     * `prompt currently pending, skipping` 直接丢弃；该 pending 没有任何 UI 载体，
     * 用户无处可点。也就是说「发一次新公钥」就可能把整台车机的授权槽位占死，
     * 其他应用再也无法完成新授权（见 docs/审计-四类改造-答复与需求确认-v1.md §2.1 ADB-12）。
     *
     * 所以：正常路径只走签名；公钥最多提交一次，提交后仍无 CNXN 就放弃，不再重试。
     */
    private static boolean pubkeySubmitted;

    /** 命令结果：rc 只有在调用 execRc 时才有效（其余为 Integer.MIN_VALUE） */
    public static final class Res {
        public final String text;
        public final int rc;

        Res(String text, int rc) { this.text = text; this.rc = rc; }
    }

    private final Log log;
    private PrivateKey key;
    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private int nextId = 0x70000001;
    /** adbd 在 CNXN 里声明的最大包长；老版本可能只有 4096，超过会被直接断开 */
    private int peerMaxPayload = 4096;

    public String endpoint = "";

    public Adb(Log log) { this.log = log; }

    // ---------------- 密钥 ----------------

    /** 首次运行生成 2048 位 RSA 并落盘（PKCS#8 DER），之后复用同一把钥匙 */
    public static PrivateKey loadOrCreateKey(File f, boolean[] created) throws Exception {
        if (f.exists() && f.length() > 0) {
            byte[] der = readAll(f);
            created[0] = false;
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(f, kp.getPrivate().getEncoded());
        created[0] = true;
        return kp.getPrivate();
    }

    // ---------------- 连接 ----------------

    public void setKey(PrivateKey k) { this.key = k; }

    /**
     * 建连并完成认证。approveSec 是总等待上限。
     *
     * 为什么是「多轮」而不是一轮死等：
     * adbd 在用户点「允许」之后**不会**给已经挂在那儿的 host socket 补发 CNXN，
     * 只把公钥写进白名单。所以点完允许必须重开一次 socket 重新握手，
     * 那时签名（AUTH TYPE=2）才能通过。实测单轮死等会一直挂到超时。
     */
    public boolean connect(String host, int port, int approveSec) throws Exception {
        long deadline = System.currentTimeMillis() + approveSec * 1000L;
        for (int attempt = 1; attempt <= 3; attempt++) {
            long left = deadline - System.currentTimeMillis();
            if (left < 5000) break;
            if (attempt > 1) {
                log.print("→ 重新握手（第 " + attempt + " 次）");
                Thread.sleep(1200);
            }
            int waitSec = (int) Math.max(6, Math.min(25, left / 1000));
            if (tryOnce(host, port, waitSec)) return true;
        }
        close();
        return false;
    }

    /** 一轮握手：发放 CNXN，处理 AUTH，等到 CNXN 或本轮超时 */
    private boolean tryOnce(String host, int port, int waitSec) throws Exception {
        close();
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.setSoTimeout(15000);
            s.connect(new InetSocketAddress(host, port), 5000);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
        InputStream is = s.getInputStream();
        OutputStream os = s.getOutputStream();
        endpoint = host + ":" + port;

        send(os, CMD_CNXN, A_VERSION, MAX_PAYLOAD,
                "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,fixed_push_symlink_timestamp,sendrecv_v2,devraw,app_info\0".getBytes(StandardCharsets.UTF_8));

        int sigTry = 0;
        boolean asked = false;
        long deadline = System.currentTimeMillis() + waitSec * 1000L;

        try {
            while (System.currentTimeMillis() < deadline) {
                Packet p;
                try {
                    s.setSoTimeout(asked ? 1500 : 15000);
                    p = recv(is);
                } catch (SocketTimeoutException te) {
                    if (!asked) throw te;
                    // 弹窗还开着就继续等。
                    // 千万不要在这里重发公钥：adbd 每收到一次同一个公钥就会刷新授权弹窗，
                    // 用户刚勾上的「一律允许」会被清掉（实测踩过这个坑）。
                    continue;
                }

                if (p.command == CMD_CNXN) {
                    this.sock = s;
                    this.in = is;
                    this.out = os;
                    if (p.arg1 > 0) peerMaxPayload = Math.max(4096, Math.min(p.arg1, MAX_PAYLOAD));
                    s.setSoTimeout(20000);
                    return true;
                }
                if (p.command == CMD_AUTH && p.arg0 == AUTH_TOKEN) {
                    if (sigTry < SIGN_MODES.length) {
                        String mode = SIGN_MODES[sigTry];
                        byte[] sig = sign(p.payload, mode);
                        send(os, CMD_AUTH, AUTH_SIGNATURE, 0, sig);
                        log.print("→ token " + p.payload.length + "B 用 " + mode + " 签名（第 "
                                + (sigTry + 1) + "/" + SIGN_MODES.length + " 种）");
                        sigTry++;
                        continue;
                    }
                    if (pubkeySubmitted) {
                        log.print("!! 本进程已提交过公钥且未获授权，放弃重试（继续发会占死车机授权槽位）");
                        return false;
                    }
                    send(os, CMD_AUTH, AUTH_PUBKEY, 0, pubkeyPayload());
                    pubkeySubmitted = true;
                    if (!asked) {
                        asked = true;
                        log.print("→ 三种签名都不被接受，改提交公钥：");
                        log.print("   本车机不会弹出授权窗；若 8 秒内未通过，说明这把 key 未登记");
                    }
                }
            }
        } finally {
            if (this.sock != s) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
        return false;
    }

    public boolean isConnected() {
        return sock != null && sock.isConnected() && !sock.isClosed();
    }

    public void close() {
        try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
        sock = null;
        in = null;
        out = null;
    }

    // ---------------- 执行 ----------------

    /** 开一条 shell 流跑命令，返回 stdout+stderr 文本 */
    public String exec(String cmd, int timeoutMs) throws IOException {
        return execRc(cmd, timeoutMs).text;
    }

    /**
     * 跑命令并取回退出码。
     * legacy shell 服务不回报退出码，所以在命令尾追加哨兵行 `__RC=$?` 再剥离。
     */
    public Res execRc(String cmd, int timeoutMs) throws IOException {
        if (sock == null || out == null) throw new IOException("未连接");
        String withRc = cmd + "\n__RC=$?; echo \"__RC=$__RC\"\n";
        Stream s = openStream("shell:" + withRc + "\0", Math.min(timeoutMs, 20000));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        readAll(s, bos, timeoutMs);

        String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        int rc = Integer.MIN_VALUE;
        int at = text.lastIndexOf("__RC=");
        if (at >= 0) {
            String tail = text.substring(at + 5).trim();
            int nl = tail.indexOf('\n');
            if (nl > 0) tail = tail.substring(0, nl).trim();
            try { rc = Integer.parseInt(tail); } catch (Throwable ignored) { }
            text = text.substring(0, at);
        }
        return new Res(text, rc);
    }

    // ---------------- 推文件（adb push 等价） ----------------

    public void push(byte[] data, String remote, int mode, int timeoutMs) throws IOException {
        if (sock == null || out == null) throw new IOException("未连接");
        Stream s = openStream("sync:\0", 15000);

        byte[] name = (remote + "," + mode).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream req = new ByteArrayOutputStream(data.length + 1024);
        putId(req, "SEND");
        putInt(req, name.length);
        req.write(name, 0, name.length);
        int chunk = Math.max(512, Math.min(SYNC_CHUNK, peerMaxPayload - 8));
        for (int off = 0; off < data.length; off += chunk) {
            int n = Math.min(chunk, data.length - off);
            putId(req, "DATA");
            putInt(req, n);
            req.write(data, off, n);
        }
        putId(req, "DATA");
        putInt(req, 0);                       // 零长 DATA = 数据结束
        putId(req, "DONE");
        putInt(req, (int) (System.currentTimeMillis() / 1000L));

        writeStream(s, req.toByteArray());

        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (resp.size() < 4 && System.currentTimeMillis() < deadline) {
                Packet p = recvWithDeadline(deadline);
                if (p.command == CMD_WRTE && p.arg1 == s.local) {
                    resp.write(p.payload, 0, p.payload.length);
                    send(out, CMD_OKAY, s.local, p.arg0, null);
                } else if (p.command == CMD_CLSE && p.arg1 == s.local) {
                    break;
                }
            }
        } catch (SocketTimeoutException te) {
            close();
            throw te;
        }
        closeStream(s);

        byte[] r = resp.toByteArray();
        String tag = new String(r, 0, Math.min(4, r.length), StandardCharsets.UTF_8);
        if (!"OKAY".equals(tag)) {
            String msg = tag;
            if ("FAIL".equals(tag) && r.length >= 8) {
                int len = ByteBuffer.wrap(r, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int from = 8, to = Math.min(r.length, 8 + Math.max(0, len));
                msg = new String(r, from, to - from, StandardCharsets.UTF_8);
            }
            throw new IOException("push 失败 " + remote + ": " + msg);
        }
    }

    // ---------------- 取文件（adb pull 等价） ----------------

    /**
     * 用 sync: 的 STAT + RECV 拉回一个文件。
     *
     * 为什么不用 `shell:cat`：adbd 的 shell 服务会做 CR/LF 与特殊字节转义（伪终端），
     * 二进制走那条路必坏。sync 是裸字节流，这也是 adb pull 本身的做法。
     */
    public byte[] pull(String remote, int timeoutMs) throws IOException {
        if (sock == null || out == null) throw new IOException("未连接");
        Stream s = openStream("sync:\0", 15000);
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] path = remote.getBytes(StandardCharsets.UTF_8);

        try {
            StreamIn sin = new StreamIn(s, deadline);

            ByteArrayOutputStream req = new ByteArrayOutputStream(path.length + 16);
            putId(req, "STAT");
            putInt(req, path.length);
            req.write(path, 0, path.length);
            writeStream(s, req.toByteArray());

            byte[] hdr = new byte[16];
            readFully(sin, hdr);
            String id = new String(hdr, 0, 4, StandardCharsets.UTF_8);
            int mode = leInt(hdr, 4);
            int size = leInt(hdr, 8);
            if (!"STAT".equals(id)) throw new IOException("sync STAT 异常: " + id);
            if (mode == 0) throw new IOException("远端文件不存在: " + remote);

            req.reset();
            putId(req, "RECV");
            putInt(req, path.length);
            req.write(path, 0, path.length);
            writeStream(s, req.toByteArray());

            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(size, 4096));
            byte[] dh = new byte[8];
            while (true) {
                readFully(sin, dh);
                String did = new String(dh, 0, 4, StandardCharsets.UTF_8);
                int n = leInt(dh, 4);
                if ("DONE".equals(did)) break;
                if (!"DATA".equals(did)) throw new IOException("sync 未知帧: " + did);
                if (n < 0 || n > 64 * 1024 * 1024) throw new IOException("sync 帧长度异常 " + n);
                byte[] chunk = new byte[n];
                readFully(sin, chunk);
                bos.write(chunk, 0, n);
            }
            closeStream(s);
            return bos.toByteArray();
        } catch (SocketTimeoutException te) {
            close();
            throw te;
        }
    }

    /** 把一条 adb 流包成 InputStream，调用方可以按协议自行解析（sync 帧是嵌在流里的） */
    private final class StreamIn extends InputStream {
        private final Stream s;
        private final long deadline;
        private byte[] cur = new byte[0];
        private int pos;
        private boolean closed;

        StreamIn(Stream s, long deadline) {
            this.s = s;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= cur.length) {
                if (!fill()) return -1;
            }
            int n = Math.min(len, cur.length - pos);
            System.arraycopy(cur, pos, b, off, n);
            pos += n;
            return n;
        }

        private boolean fill() throws IOException {
            if (closed) return false;
            while (true) {
                Packet p = recvWithDeadline(deadline);
                if (p.command == CMD_WRTE && p.arg1 == s.local) {
                    send(out, CMD_OKAY, s.local, p.arg0, null);
                    if (p.payload.length == 0) continue;
                    cur = p.payload;
                    pos = 0;
                    return true;
                }
                if (p.command == CMD_CLSE && p.arg1 == s.local) {
                    send(out, CMD_CLSE, s.local, p.arg0, null);
                    closed = true;
                    return false;
                }
            }
        }
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    // ---------------- 流 ----------------

    private static final class Stream {
        int local, remote;
    }

    private Stream openStream(String service, int timeoutMs) throws IOException {
        int local = nextId++;
        send(out, CMD_OPEN, local, 0, service.getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Packet p = recvWithDeadline(deadline);
            if (p.command == CMD_OKAY && p.arg1 == local) {
                Stream s = new Stream();
                s.local = local;
                s.remote = p.arg0;
                return s;
            }
            if (p.command == CMD_CLSE && p.arg1 == local) {
                throw new IOException("adbd 拒绝该流（对端已关闭）");
            }
            // 其它流的包，忽略
        }
    }

    /** 收流数据直到 CLSE 或超时耗尽 */
    private void readAll(Stream s, ByteArrayOutputStream sink, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Packet p;
            try {
                p = recvWithDeadline(deadline);
            } catch (SocketTimeoutException te) {
                // 超时后流状态已不可信，整条连接作废，下次调用自动重连重认证
                closeStream(s);
                close();
                throw te;
            }
            if (p.command == CMD_WRTE && p.arg1 == s.local) {
                sink.write(p.payload, 0, p.payload.length);
                send(out, CMD_OKAY, s.local, p.arg0, null);
                continue;
            }
            if (p.command == CMD_CLSE && p.arg1 == s.local) {
                send(out, CMD_CLSE, s.local, p.arg0, null);
                return;
            }
            // OKAY / 其它流，忽略
        }
    }

    private void writeStream(Stream s, byte[] data) throws IOException {
        int chunk = Math.max(1024, Math.min(WRTE_CHUNK, peerMaxPayload));
        for (int off = 0; off < data.length; off += chunk) {
            int n = Math.min(chunk, data.length - off);
            byte[] part = new byte[n];
            System.arraycopy(data, off, part, 0, n);
            send(out, CMD_WRTE, s.local, s.remote, part);
        }
    }

    private void closeStream(Stream s) {
        try { send(out, CMD_CLSE, s.local, s.remote, null); } catch (Throwable ignored) { }
    }

    private Packet recvWithDeadline(long deadline) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) throw new SocketTimeoutException("命令超时");
        sock.setSoTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(100, left)));
        return recv(in);
    }

    // ---------------- 签名 / 公钥 blob ----------------

    private byte[] sign(byte[] token, String mode) throws Exception {
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) key;
        BigInteger n = k.getModulus();
        BigInteger d = k.getPrivateExponent();
        int klen = (n.bitLength() + 7) / 8;

        byte[] di;
        byte[] digest;
        if ("raw".equals(mode)) {
            di = SHA1_DI;
            digest = token;
        } else if ("SHA-256".equals(mode)) {
            di = SHA256_DI;
            digest = MessageDigest.getInstance("SHA-256").digest(token);
        } else {
            di = SHA1_DI;
            digest = MessageDigest.getInstance("SHA-1").digest(token);
        }

        int tlen = di.length + digest.length;
        int padLen = klen - 3 - tlen;
        if (padLen < 8) throw new IllegalStateException("RSA 密钥过小");

        byte[] em = new byte[klen];
        em[0] = 0x00;
        em[1] = 0x01;
        for (int i = 2; i < 2 + padLen; i++) em[i] = (byte) 0xFF;
        em[2 + padLen] = 0x00;
        System.arraycopy(di, 0, em, 3 + padLen, di.length);
        System.arraycopy(digest, 0, em, 3 + padLen + di.length, digest.length);

        byte[] s = new BigInteger(1, em).modPow(d, n).toByteArray();
        byte[] sig = new byte[klen];
        if (s.length > klen) System.arraycopy(s, s.length - klen, sig, 0, klen);
        else System.arraycopy(s, 0, sig, klen - s.length, s.length);
        return sig;
    }

    /** RSAPublicKey 结构：words | n0inv | n | rr | e，全小端 uint32 数组 */
    private byte[] pubkeyPayload() throws Exception {
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) key;
        BigInteger n = k.getModulus();
        BigInteger e = k.getPublicExponent();
        int words = (n.bitLength() + 31) / 32;

        byte[] nb = leFixed(n, words * 4);
        BigInteger r = BigInteger.ONE.shiftLeft(words * 32);
        byte[] rrb = leFixed(r.multiply(r).mod(n), words * 4);

        long n0 = 0;
        for (int i = 3; i >= 0; i--) n0 = (n0 << 8) | (nb[i] & 0xFFL);
        long inv = BigInteger.valueOf(n0)
                .modInverse(BigInteger.ONE.shiftLeft(32)).longValue();
        long n0inv = (0x100000000L - inv) & 0xFFFFFFFFL;

        ByteBuffer bb = ByteBuffer.allocate(8 + nb.length + rrb.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt((int) n0inv);
        bb.put(nb);
        bb.put(rrb);
        bb.putInt(e.intValue());

        String b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        return (b64 + " adbprobe@wb\0").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] leFixed(BigInteger v, int bytes) {
        byte[] be = v.toByteArray();
        byte[] r = new byte[bytes];
        for (int i = 0; i < bytes && i < be.length; i++) r[i] = be[be.length - 1 - i];
        return r;
    }

    // ---------------- 报文 ----------------

    private static final class Packet {
        int command, arg0, arg1;
        byte[] payload;
    }

    private static Packet recv(InputStream in) throws IOException {
        byte[] h = new byte[24];
        readFully(in, h);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.command = bb.getInt(0);
        p.arg0 = bb.getInt(4);
        p.arg1 = bb.getInt(8);
        int len = bb.getInt(12);
        if (len < 0 || len > MAX_PAYLOAD * 8) throw new IOException("非法长度 " + len);
        p.payload = new byte[len];
        if (len > 0) readFully(in, p.payload);
        return p;
    }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] payload)
            throws IOException {
        if (payload == null) payload = new byte[0];
        // 协议规定：版本 >= A_VERSION_SKIP_CHECKSUM 时，data_check 必须是 0。
        // 这里算真实 CRC 的话，adbd 会判定数据校验非法、静默丢包，
        // 表现在外面就是「签名发出去了，但对方一直重发 AUTH TOKEN，永远授权不上」。
        // 抓包对比官方 adb 才看出来：它的包 CRC 全是 0。
        int check = A_VERSION >= A_VERSION_SKIP_CHECKSUM ? 0 : (int) crc32(payload);
        ByteBuffer bb = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(payload.length)
                .putInt(check).putInt(~cmd);
        if (payload.length > 0) bb.put(payload);
        out.write(bb.array());
        out.flush();
    }

    private static long crc32(byte[] payload) {
        CRC32 c = new CRC32();
        c.update(payload);
        return c.getValue();
    }

    private static void putId(ByteArrayOutputStream o, String id) {
        byte[] b = id.getBytes(StandardCharsets.UTF_8);
        o.write(b, 0, 4);
    }

    private static void putInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) throw new IOException("连接被关闭 (" + got + "/" + buf.length + ")");
            got += n;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream fi = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int got = 0;
            while (got < b.length) {
                int n = fi.read(b, got, b.length - got);
                if (n < 0) break;
                got += n;
            }
            return b;
        } finally {
            try { fi.close(); } catch (IOException ignored) { }
        }
    }

    private static void writeAll(File f, byte[] b) throws IOException {
        FileOutputStream fo = new FileOutputStream(f);
        try {
            fo.write(b);
            fo.getFD().sync();
        } finally {
            try { fo.close(); } catch (IOException ignored) { }
        }
    }
}
