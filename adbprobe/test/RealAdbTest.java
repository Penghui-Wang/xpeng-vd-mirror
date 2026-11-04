// 【已走完的弯路·留证】这三个/四个文件当时假设「apk 用的是 PC 的 ~/.android/adbkey」，
// 结论是错的：车机弹窗里的指纹是 app 自己的 key。真正的两条原因是
//   1) 包头 data_check 必须为 0（>= A_VERSION_SKIP_CHECKSUM）
//   2) 本 ROM adbd 认「token 当摘要」的签名，不认 AOSP 标准的先 SHA-1 再签
// 见 Adb.java 的 SIGN_MODES 注释 与 docs/APK控制器-v0.2.md 的 v0.5 章节。这里只作留证，别再照着它排查。
import com.probe.adbprobe.Adb;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 用 PC 自己的 adb 私钥（一定已经在车机白名单里）跑一遍本项目的 Adb 实现。
 *
 * 目的：把「签名算法有没有错」和「app 那把钥匙有没有问题」分开。
 *   - 用 PC 私钥能连上  → 算法正确，问题在 app 生成/保存的密钥
 *   - 用 PC 私钥也连不上 → 算法或协议本身有问题
 *
 * 用法: ./run.sh real <host> <port>
 */
public class RealAdbTest {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "192.168.31.210";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5050;

        String keyPath = System.getProperty("user.home") + "/.android/adbkey";
        File f = new File(keyPath);
        if (!f.exists()) {
            System.out.println("找不到 PC 私钥: " + keyPath);
            System.exit(2);
        }

        System.out.println("用 PC 私钥: " + keyPath);
        PrivateKey key = loadPem(f);
        System.out.println("  密钥算法 " + key.getAlgorithm());

        Adb adb = new Adb(s -> System.out.println("  [adb] " + s));
        adb.setKey(key);

        long t0 = System.currentTimeMillis();
        boolean ok = adb.connect(host, port, 20);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("connect() = " + ok + "  用时 " + ms + " ms  endpoint=" + adb.endpoint);

        if (ok) {
            String id = adb.exec("id -Z", 10000);
            System.out.println("exec(id -Z) -> " + id.trim());
            boolean shell = id.contains("u:r:shell:s0");
            System.out.println(shell ? "[OK] 拿到 shell 域" : "[WARN] 不是 shell 域");
            adb.close();
            System.exit(shell ? 0 : 1);
        }
        adb.close();
        System.exit(1);
    }

    /** adb 的 adbkey 是 PEM(PKCS#8)，去掉头尾再 base64 解码 */
    private static PrivateKey loadPem(File f) throws Exception {
        String pem = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
