import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

/** 把 Adb.java 里 sign() / pubkeyPayload() 的算法原样搬到 JVM 上验证 */
public class AdbCryptoTest {

    static final byte[] SHA1_DI = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        RSAPrivateCrtKey k = (RSAPrivateCrtKey) key;
        BigInteger n = k.getModulus();
        BigInteger d = k.getPrivateExponent();
        int klen = (n.bitLength() + 7) / 8;

        byte[] digest = MessageDigest.getInstance("SHA-1").digest(token);
        int tlen = SHA1_DI.length + digest.length;
        int padLen = klen - 3 - tlen;
        if (padLen < 8) throw new IllegalStateException("key too small");

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

    static byte[] pubkeyBlob(RSAPrivateCrtKey k) throws Exception {
        BigInteger n = k.getModulus();
        BigInteger e = k.getPublicExponent();
        int words = (n.bitLength() + 31) / 32;

        byte[] nb = leFixed(n, words * 4);
        BigInteger r = BigInteger.ONE.shiftLeft(words * 32);
        byte[] rrb = leFixed(r.multiply(r).mod(n), words * 4);

        long n0 = 0;
        for (int i = 3; i >= 0; i--) n0 = (n0 << 8) | (nb[i] & 0xFFL);
        long inv = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32)).longValue();
        long n0inv = (0x100000000L - inv) & 0xFFFFFFFFL;

        ByteBuffer bb = ByteBuffer.allocate(8 + nb.length + rrb.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt((int) n0inv);
        bb.put(nb);
        bb.put(rrb);
        bb.putInt(e.intValue());
        return bb.array();
    }

    static byte[] leFixed(BigInteger v, int bytes) {
        byte[] be = v.toByteArray();
        byte[] r = new byte[bytes];
        for (int i = 0; i < bytes && i < be.length; i++) r[i] = be[be.length - 1 - i];
        return r;
    }

    static byte[] reverse(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return r;
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kp.getPrivate();

        byte[] token = new byte[20];
        new SecureRandom().nextBytes(token);

        byte[] sig = sign(priv, token);
        Signature v = Signature.getInstance("SHA1withRSA");
        v.initVerify(kp.getPublic());
        v.update(token);
        boolean sigOk = v.verify(sig);
        System.out.println("[1] PKCS#1 v1.5 SHA1 签名可被标准验签器接受 = " + sigOk);
        System.out.println("    sig len = " + sig.length + " (期望 256)");

        byte[] blob = pubkeyBlob(priv);
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int words = bb.getInt();
        int n0inv = bb.getInt();
        byte[] nb = new byte[words * 4];
        bb.get(nb);
        byte[] rrb = new byte[words * 4];
        bb.get(rrb);
        int e = bb.getInt();

        BigInteger n2 = new BigInteger(1, reverse(nb));
        BigInteger r2 = new BigInteger(1, reverse(rrb));
        BigInteger R = BigInteger.ONE.shiftLeft(words * 32);

        System.out.println("[2] blob 长度 = " + blob.length + "，期望 " + (8 + words * 8 + 4));
        System.out.println("[3] modulus 还原一致 = " + n2.equals(priv.getModulus()));
        System.out.println("[4] exponent 一致 = " + (e == priv.getPublicExponent().intValue()));
        System.out.println("[5] rr = R^2 mod n 一致 = " + r2.equals(R.multiply(R).mod(priv.getModulus())));

        long n0 = 0;
        for (int i = 3; i >= 0; i--) n0 = (n0 << 8) | (nb[i] & 0xFFL);
        long inv = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32)).longValue();
        long expect = (0x100000000L - inv) & 0xFFFFFFFFL;
        System.out.println("[6] n0inv 一致 = " + ((n0inv & 0xFFFFFFFFL) == expect));

        String payload = Base64.getEncoder().encodeToString(blob) + " adbprobe@wb\0";
        System.out.println("[7] AUTH TYPE=3 载荷前缀 = " + payload.substring(0, 32) + "…");

        boolean all = sigOk && n2.equals(priv.getModulus())
                && e == priv.getPublicExponent().intValue()
                && r2.equals(R.multiply(R).mod(priv.getModulus()))
                && (n0inv & 0xFFFFFFFFL) == expect
                && blob.length == 8 + words * 8 + 4;
        System.out.println(all ? "RESULT OK" : "RESULT FAIL");
    }
}
