package com.probe.asx;

/**
 * LZ4 block 格式压缩器（无帧头、无校验和）。
 *
 * 为什么不直接用 Deflater：接收端是 i.MX6，zlib 解 2.76MB 要 90-140ms，
 * 会把整条链路锁死在 5-8fps。LZ4 解压只要 23ms，但压缩端必须在车机上跑，
 * 所以这里实现一个 LZ4 "fast" 模式的贪心压缩器（只查最近一次出现的位置，
 * 不做链式回溯），对 2.1MB 帧在 8295 上大约 8-15ms，且常驻内存只有一张哈希表。
 *
 * block 结构：token(高4位literal长度|低4位match长度-4) + [长度扩展] + literal +
 *            offset(2B LE) + [match长度扩展]
 * 最后一个序列只有 literal，且长度 >= 5（LAST_LITERALS），否则解码端会误读。
 */
final class LZ4 {

    private static final int MIN_MATCH = 4;
    private static final int LAST_LITERALS = 5;
    private static final int MF_LIMIT = 12;
    private static final int MAX_DISTANCE = 65535;
    private static final int HASH_LOG = 15;
    private static final int HASH_SIZE = 1 << HASH_LOG;

    /**
     * 匹配后「补登记」的字节数上限。
     *
     * 原来是把整个匹配区间逐位置重新 hash 登记（一直补到 match 末尾）。压缩率
     * 好看一点，但代价是：画面上大片渐变/纯色区的匹配长度动辄几百字节，
     * 等于整帧每个字节都被 hash 了两次。实测这是车机侧 txLoop 跟不上采集的
     * 主要原因（采集 62fps，实际只发得出去 47fps）。
     *
     * 只补前 4 个位置就够修正「匹配起点后紧跟重复」这种最常见的模式，
     * 长匹配剩下的部分交给贪心跳过 —— 标准 lz4 fast 是完全不补的。
     */
    private static final int BACKFILL = 4;

    // 哈希表复用：422KB 的帧每帧 new 一个 128KB 的表 + fill，纯属给 GC 找事
    private static final int[] TABLE = new int[HASH_SIZE];

    private LZ4() {
    }

    static int maxCompressedSize(int srcLen) {
        return srcLen + srcLen / 255 + 32;
    }

    private static int hash(byte[] src, int p) {
        int v = (src[p] & 0xFF)
                | ((src[p + 1] & 0xFF) << 8)
                | ((src[p + 2] & 0xFF) << 16)
                | ((src[p + 3] & 0xFF) << 24);
        // 必须先掩回 32 位再右移：直接对 long 乘积 >>> 17 会得到 47 位的值，
        // 强转 int 会溢出成巨大的下标（曾实测 index=1327238132 把哈希表打崩）。
        long h = ((v & 0xFFFFFFFFL) * 2654435761L) & 0xFFFFFFFFL;
        return (int) (h >>> (32 - HASH_LOG));
    }

    /**
     * 压缩 src[0..srcLen) 到 dst，返回写入字节数。
     * dst 容量必须 >= maxCompressedSize(srcLen)。
     */
    static int compress(byte[] src, int srcLen, byte[] dst) {
        if (srcLen < MF_LIMIT + 1) {
            return emitLastLiterals(dst, 0, src, 0, srcLen);
        }
        // 每次调用清零：表里存的是上一帧的偏移，不清会拿旧位置做匹配。
        // 表本身复用（compress 由 txLoop 单线程串行调用）。
        final int[] table = TABLE;
        java.util.Arrays.fill(table, -1);

        int dp = 0;
        int anchor = 0;
        int ip = 0;
        final int matchLimit = srcLen - LAST_LITERALS;   // 匹配不得吃掉末尾 5 字节
        final int mfLimit = srcLen - MF_LIMIT;

        while (ip < mfLimit) {
            final int h = hash(src, ip) & (HASH_SIZE - 1);
            final int ref = table[h];
            table[h] = ip;

            if (ref >= 0 && ip - ref <= MAX_DISTANCE
                    && src[ref] == src[ip]
                    && src[ref + 1] == src[ip + 1]
                    && src[ref + 2] == src[ip + 2]
                    && src[ref + 3] == src[ip + 3]) {

                int maxLen = matchLimit - ip;
                int len = MIN_MATCH;
                while (len < maxLen && src[ref + len] == src[ip + len]) {
                    len++;
                }

                dp = emitSequence(dst, dp, src, anchor, ip - anchor, ip - ref, len);

                int end = ip + len;
                // 只补前 BACKFILL 个位置（原来补满整个匹配区间，长匹配时等于
                // 整帧每个字节 hash 两遍，实测把 txLoop 拖到 47fps）
                final int backfillEnd = Math.min(end, ip + BACKFILL);
                for (int p = ip + 1; p < backfillEnd && p < mfLimit; p++) {
                    table[hash(src, p) & (HASH_SIZE - 1)] = p;
                }
                ip = end;
                anchor = ip;
            } else {
                ip++;
            }
        }

        return emitLastLiterals(dst, dp, src, anchor, srcLen - anchor);
    }

    private static int emitSequence(byte[] dst, int dp, byte[] src,
                                    int litStart, int litLen, int offset, int matchLen) {
        final int tokenPos = dp++;
        final int litCode = litLen < 15 ? litLen : 15;
        final int mLen = matchLen - MIN_MATCH;
        final int mCode = mLen < 15 ? mLen : 15;
        dst[tokenPos] = (byte) ((litCode << 4) | mCode);

        if (litLen >= 15) {
            int l = litLen - 15;
            while (l >= 255) {
                dst[dp++] = (byte) 255;
                l -= 255;
            }
            dst[dp++] = (byte) l;
        }
        System.arraycopy(src, litStart, dst, dp, litLen);
        dp += litLen;

        dst[dp++] = (byte) (offset & 0xFF);
        dst[dp++] = (byte) ((offset >> 8) & 0xFF);

        if (mLen >= 15) {
            int m = mLen - 15;
            while (m >= 255) {
                dst[dp++] = (byte) 255;
                m -= 255;
            }
            dst[dp++] = (byte) m;
        }
        return dp;
    }

    private static int emitLastLiterals(byte[] dst, int dp, byte[] src, int start, int len) {
        final int tokenPos = dp++;
        if (len < 15) {
            dst[tokenPos] = (byte) (len << 4);
        } else {
            dst[tokenPos] = (byte) 0xF0;
            int l = len - 15;
            while (l >= 255) {
                dst[dp++] = (byte) 255;
                l -= 255;
            }
            dst[dp++] = (byte) l;
        }
        System.arraycopy(src, start, dst, dp, len);
        return dp + len;
    }
}
