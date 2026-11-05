package android.util;

/**
 * 离线测试用的最小替身：真实实现来自 Android 框架，JVM 上没有。
 * 只需要 Base64.encodeToString(byte[], NO_WRAP)，与 android.util.Base64 输出一致。
 * 仅被 adbprobe/test/run.sh 编译，不进 APK。
 */
public final class Base64 {

    public static final int NO_WRAP = 2;

    private Base64() { }

    public static String encodeToString(byte[] input, int flags) {
        String s = java.util.Base64.getEncoder().encodeToString(input);
        return (flags & NO_WRAP) != 0 ? s : s + "\n";
    }
}
