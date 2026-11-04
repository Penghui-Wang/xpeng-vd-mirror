// 【已走完的弯路·留证】这三个/四个文件当时假设「apk 用的是 PC 的 ~/.android/adbkey」，
// 结论是错的：车机弹窗里的指纹是 app 自己的 key。真正的两条原因是
//   1) 包头 data_check 必须为 0（>= A_VERSION_SKIP_CHECKSUM）
//   2) 本 ROM adbd 认「token 当摘要」的签名，不认 AOSP 标准的先 SHA-1 再签
// 见 Adb.java 的 SIGN_MODES 注释 与 docs/APK控制器-v0.2.md 的 v0.5 章节。这里只作留证，别再照着它排查。
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * 读 authcap.txt（代理抓到的 token + 签名），用 PC 公钥验签。
 *
 * 目的：官方 adb 和我们的实现用的是不是同一种签名。
 *   官方能过而我们不过，如果两条都验签通过，说明差异不在签名值，而在协议其它字段。
 */
public class VerifyCapture {

    public static void main(String[] a) throws Exception {
        String keyPath = System.getProperty("user.home") + "/.android/adbkey";
        PrivateKey priv = loadPem(new File(keyPath));
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) priv;
        PublicKey pub = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(k.getModulus(), k.getPublicExponent()));

        List<String> lines = Files.readAllLines(new File("authcap.txt").toPath(), StandardCharsets.UTF_8);
        int i = 0;
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 3) continue;
            byte[] token = hex(f[1]);
            byte[] sig = hex(f[2]);

            Signature v = Signature.getInstance("SHA1withRSA");
            v.initVerify(pub);
            v.update(token);
            boolean ok = false;
            try {
                ok = v.verify(sig);
            } catch (Exception e) {
                System.out.println("  verify threw " + e);
            }
            // 同一条 token 用私钥重算，看是否与抓到的签名一致
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initSign(priv);
            s.update(token);
            byte[] ref = s.sign();
            boolean same = java.util.Arrays.equals(ref, sig);

            System.out.println("#" + (++i)
                    + " token=" + token.length + "B sig=" + sig.length + "B"
                    + " verify=" + ok
                    + " 与本地重算一致=" + same);
            if (!same) {
                int diff = 0;
                for (int j = 0; j < Math.min(ref.length, sig.length); j++) if (ref[j] != sig[j]) diff++;
                System.out.println("   字节差异数 " + diff + " / " + ref.length);
            }
        }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static PrivateKey loadPem(File f) throws Exception {
        String pem = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
