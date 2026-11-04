// 【已走完的弯路·留证】这三个/四个文件当时假设「apk 用的是 PC 的 ~/.android/adbkey」，
// 结论是错的：车机弹窗里的指纹是 app 自己的 key。真正的两条原因是
//   1) 包头 data_check 必须为 0（>= A_VERSION_SKIP_CHECKSUM）
//   2) 本 ROM adbd 认「token 当摘要」的签名，不认 AOSP 标准的先 SHA-1 再签
// 见 Adb.java 的 SIGN_MODES 注释 与 docs/APK控制器-v0.2.md 的 v0.5 章节。这里只作留证，别再照着它排查。
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 检查 ~/.android/adbkey 这把私钥能否还原出 adbkey.pub 里那条公钥。
 *
 * 这一步是分水岭：
 *   一致  → 我们手里的钥匙就是 adb 用的那把，「签名被拒」另有原因
 *   不一致 → 我们一直拿错了钥匙，之前所有签名实验都白做
 */
public class PubCheck {

    public static void main(String[] a) throws Exception {
        File keyFile = new File(System.getProperty("user.home") + "/.android/adbkey");
        File pubFile = new File(System.getProperty("user.home") + "/.android/adbkey.pub");

        PrivateKey priv = loadPem(keyFile);
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) priv;

        String mine = blob(k.getModulus(), k.getPublicExponent());
        String file = new String(Files.readAllBytes(pubFile.toPath()), StandardCharsets.UTF_8).trim().split("\\s+")[0];

        System.out.println("私钥还原的公钥: " + mine.substring(0, 44) + "...");
        System.out.println("adbkey.pub    : " + file.substring(0, 44) + "...");
        System.out.println("长度 mine=" + mine.length() + " file=" + file.length());
        System.out.println(mine.equals(file) ? "[OK] 同一把钥匙" : "[!!] 不是同一把钥匙");
    }

    private static String blob(BigInteger n, BigInteger e) {
        int words = (n.bitLength() + 31) / 32;
        byte[] nb = leFixed(n, words * 4);
        BigInteger r = BigInteger.ONE.shiftLeft(words * 32);
        byte[] rrb = leFixed(r.multiply(r).mod(n), words * 4);

        long n0 = 0;
        for (int i = 3; i >= 0; i--) n0 = (n0 << 8) | (nb[i] & 0xFFL);
        long inv = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32)).longValue();
        long n0inv = (0x100000000L - inv) & 0xFFFFFFFFL;

        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(8 + nb.length + rrb.length + 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt((int) n0inv);
        bb.put(nb);
        bb.put(rrb);
        bb.putInt(e.intValue());
        return Base64.getEncoder().encodeToString(bb.array());
    }

    private static byte[] leFixed(BigInteger v, int bytes) {
        byte[] be = v.toByteArray();
        byte[] r = new byte[bytes];
        for (int i = 0; i < bytes && i < be.length; i++) r[i] = be[be.length - 1 - i];
        return r;
    }

    private static PrivateKey loadPem(File f) throws Exception {
        String pem = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
