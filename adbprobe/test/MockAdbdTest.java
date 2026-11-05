import com.xiaopeng.vdcast.Adb;
import com.xiaopeng.vdcast.Icrpc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

/**
 * 本机 mock adbd：不接车机，验证 Adb 客户端的两条链路
 *   1) shell: 开门 → WRTE 回包 → CLSE（含 execRc 的退出码哨兵）
 *   2) sync:  SEND/DATA/DONE 推文件（含 300KB 大文件分片与 0755 模式）
 * 并顺带验证 Icrpc 的接线（帧先落车机、再拼 nc 命令）。
 *
 * 跑法：见 test/run.sh
 */
public final class MockAdbdTest {

    private static final int CNXN = 0x4e584e43;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int WRTE = 0x45545257;
    private static final int CLSE = 0x45534c43;

    private static int failures = 0;
    private static volatile byte[] recvFile;
    private static volatile int recvMode = -1;
    private static volatile String recvPath = "";
    private static volatile String lastShellCmd = "";

    public static void main(String[] args) throws Exception {
        File key = new File("out/testkey.der");
        key.getParentFile().mkdirs();
        key.delete();

        ServerSocket ss = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            try { serve(ss.accept()); } catch (Throwable e) { e.printStackTrace(); }
        });
        t.setDaemon(true);
        t.start();

        Adb adb = new Adb(s -> System.out.println("  [client] " + s));
        boolean[] created = new boolean[1];
        adb.setKey(Adb.loadOrCreateKey(key, created));
        check("密钥自动生成", created[0] && key.length() > 100);

        check("CNXN 握手", adb.connect("127.0.0.1", ss.getLocalPort(), 5));
        check("endpoint 记录", adb.endpoint.endsWith(":" + ss.getLocalPort()));

        String out = adb.exec("id -Z", 8000);
        check("shell 收到回包", out.contains("u:r:shell:s0"));
        Adb.Res r = adb.execRc("echo hi", 8000);
        check("execRc 解析退出码 = 0", r.rc == 0);
        check("execRc 剥掉哨兵行", !r.text.contains("__RC="));

        byte[] big = new byte[300 * 1024];
        new Random(7).nextBytes(big);
        adb.push(big, "/data/local/tmp/vd.dex", Adb.MODE_FILE, 20000);
        check("push 300KB 字节一致", Arrays.equals(big, recvFile));
        check("push 模式 = 0100644", recvMode == Adb.MODE_FILE);
        check("push 路径正确", "/data/local/tmp/vd.dex".equals(recvPath));

        adb.push(new byte[]{1, 2, 3}, "/data/local/tmp/killvds.sh", Adb.MODE_EXEC, 20000);
        check("小文件 + 0755", recvMode == Adb.MODE_EXEC && recvPath.endsWith("killvds.sh"));

        String rpc = Icrpc.run(adb, "test -x /tmp/icrecv_dbg && echo HAVE_IT || echo NO_IT", 2, 8000);
        check("Icrpc 帧已落到车机", recvPath.startsWith("/data/local/tmp/.adbprobe_f"));
        check("Icrpc 命令走 nc 30510", lastShellCmd.contains("nc " + Icrpc.IC_IP + " " + Icrpc.IC_RPC));
        check("Icrpc 帧以魔数开头", Icrpc.frame("x")[0] == 0x01);
        check("Icrpc 输出被清洗（无控制字符）", rpc.chars().allMatch(c -> c == '\n' || (c > 31 && c < 127)));

        // 供 shell 侧对比：把三条命令的帧 hex 打出来
        for (String cmd : new String[]{"id -Z", "test -x /tmp/icrecv_dbg && echo HAVE_IT || echo NO_IT", "echo 中文"}) {
            System.out.println("FRAME\t" + cmd + "\t" + hex(Icrpc.frame(cmd)));
        }

        System.out.println();
        if (failures == 0) System.out.println("RESULT OK");
        else System.out.println("RESULT FAIL (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.println((ok ? "[OK]   " : "[FAIL] ") + name);
    }

    // ------------------------------------------------------------ mock server

    private static void serve(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        Packet hello = read(in);
        if (hello.command != CNXN) throw new IOException("首包不是 CNXN");

        byte[] banner = "device::ro.product.name=mock;features=shell_v2,cmd\0".getBytes(StandardCharsets.UTF_8);
        write(out, CNXN, 0x01000001, 256 * 1024, banner);

        int nextRemote = 1;
        while (true) {
            Packet p;
            try { p = read(in); } catch (IOException eof) { return; }
            if (p.command == OPEN) {
                int remote = nextRemote++;
                String svc = new String(p.payload, StandardCharsets.UTF_8).replace("\0", "");
                if (svc.startsWith("shell:")) {
                    lastShellCmd = svc.substring(6);
                    shell(out, remote, p.arg0, lastShellCmd);
                } else if (svc.startsWith("sync:")) {
                    sync(out, in, remote, p.arg0);
                } else {
                    write(out, CLSE, remote, p.arg0, null);
                }
            }
            // 其余（客户端回给我们的 OKAY/CLSE）忽略
        }
    }

    private static void shell(OutputStream out, int remote, int local, String cmd) throws IOException {
        // 真 adbd 先 OKAY 确认开门，再回数据，最后 CLSE
        write(out, OKAY, remote, local, null);
        String text = "uid=2000(shell) gid=2000(shell) context=u:r:shell:s0\n"
                + "mock-ok\n"
                + "__RC=0\n";
        write(out, WRTE, remote, local, text.getBytes(StandardCharsets.UTF_8));
        write(out, CLSE, remote, local, null);
    }

    private static void sync(OutputStream out, InputStream in, int remote, int local) throws IOException {
        write(out, OKAY, remote, local, null);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int pos = 0;
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        boolean done = false;

        while (!done) {
            int avail = buf.size() - pos;
            boolean progressed = false;

            if (avail >= 8) {
                byte[] b = buf.toByteArray();
                String id = new String(b, pos, 4, StandardCharsets.UTF_8);
                int len = ByteBuffer.wrap(b, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

                if ("SEND".equals(id)) {
                    if (avail >= 8 + len) {
                        String name = new String(b, pos + 8, len, StandardCharsets.UTF_8);
                        int comma = name.lastIndexOf(',');
                        recvPath = name.substring(0, comma);
                        recvMode = Integer.parseInt(name.substring(comma + 1));
                        pos += 8 + len;
                        progressed = true;
                    }
                } else if ("DATA".equals(id)) {
                    if (len == 0) {
                        pos += 8;
                        progressed = true;
                    } else if (avail >= 8 + len) {
                        file.write(b, pos + 8, len);
                        pos += 8 + len;
                        progressed = true;
                    }
                } else if ("DONE".equals(id)) {
                    // DONE = id(4) + mtime(4)，共 8 字节
                    if (avail >= 8) {
                        pos += 8;
                        done = true;
                        progressed = true;
                    }
                } else if ("QUIT".equals(id)) {
                    return;
                } else {
                    throw new IOException("未知 sync id: " + id);
                }
            }

            if (!progressed) {
                Packet p = read(in);
                if (p.command == WRTE) {
                    buf.write(p.payload, 0, p.payload.length);
                    write(out, OKAY, remote, local, null);
                } else if (p.command == CLSE) {
                    return;
                }
            }
        }

        recvFile = file.toByteArray();
        write(out, WRTE, remote, local, "OKAY".getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ 报文

    private static final class Packet {
        int command, arg0, arg1;
        byte[] payload;
    }

    private static Packet read(InputStream in) throws IOException {
        byte[] h = new byte[24];
        readFully(in, h);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.command = bb.getInt(0);
        p.arg0 = bb.getInt(4);
        p.arg1 = bb.getInt(8);
        int len = bb.getInt(12);
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad len " + len);
        p.payload = new byte[len];
        if (len > 0) readFully(in, p.payload);
        return p;
    }

    private static void write(OutputStream out, int cmd, int a0, int a1, byte[] payload) throws IOException {
        if (payload == null) payload = new byte[0];
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer bb = ByteBuffer.allocate(24 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(payload.length)
                .putInt((int) crc.getValue()).putInt(~cmd);
        if (payload.length > 0) bb.put(payload);
        out.write(bb.array());
        out.flush();
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) throw new IOException("eof");
            got += n;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
