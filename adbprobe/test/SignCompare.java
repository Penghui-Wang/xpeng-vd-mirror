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
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

/**
 * 对比「Adb.sign 的手工 PKCS#1 v1.5」和「JDK 标准 SHA1withRSA」的输出。
 * 两者一致 → 签名算法没问题，问题在协议层；
 * 不一致 → 算法有 bug，直接按 JDK 的写法改。
 */
public class SignCompare {

    private static final byte[] SHA1_DI = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    public static void main(String[] a) throws Exception {
        String keyPath = System.getProperty("user.home") + "/.android/adbkey";
        PrivateKey key = loadPem(new File(keyPath));
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) key;

        for (int round = 0; round < 3; round++) {
            byte[] token = new byte[20];
            new Random(round).nextBytes(token);

            byte[] mine = manual(k, token);

            Signature s = Signature.getInstance("SHA1withRSA");
            s.initSign(key);
            s.update(token);
            byte[] jdk = s.sign();

            System.out.println("round " + round
                    + "  mine=" + mine.length + "B  jdk=" + jdk.length + "B  equal=" + Arrays.equals(mine, jdk));
            if (!Arrays.equals(mine, jdk)) {
                System.out.println("  mine[0..8] " + hex(mine, 8));
                System.out.println("  jdk [0..8] " + hex(jdk, 8));
            }

            // 用公钥自验：证明手工签名本身可被标准验签器接受
            Signature v = Signature.getInstance("SHA1withRSA");
            v.initVerify(KeyFactory.getInstance("RSA").generatePublic(
                    new java.security.spec.RSAPublicKeySpec(k.getModulus(), k.getPublicExponent())));
            v.update(token);
            System.out.println("  verify(mine)=" + v.verify(mine));
        }
    }

    private static byte[] manual(RSAPrivateCrtKey k, byte[] token) throws Exception {
        BigInteger n = k.getModulus();
        BigInteger d = k.getPrivateExponent();
        int klen = (n.bitLength() + 7) / 8;

        byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(token);
        int tlen = SHA1_DI.length + digest.length;
        int padLen = klen - 3 - tlen;

        byte[] em = new byte[klen];
        em[0] = 0x00;
        em[1] = 0x01;
        for (int i = 2; i < 2 + padLen; i++) em[i] = (byte) 0xFF;
        em[2 + padLen] = 0x00;
        System.arraycopy(SHA1_DI, 0, em, 3 + padLen, SHA1_DI.length);
        System.arraycopy(digest, 0, em, 3 + padLen + SHA1_DI.length, digest.length);

        byte[] s = new BigInteger(1, em).modPow(d, n).toByteArray();
        byte[] sig = new byte[klen];
        if (s.length > klen) System.arraycopy(s, s.length - klen, sig, 0, klen);
        else System.arraycopy(s, 0, sig, klen - s.length, s.length);
        return sig;
    }

    private static PrivateKey loadPem(File f) throws Exception {
        String pem = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < b.length; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }
}
