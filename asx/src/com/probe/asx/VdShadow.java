package com.probe.asx;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

/**
 * 影子 display 的资源占用探针。
 *
 * 前提：外部已有一个虚拟屏（VdTok3 hold 建的），QQ音乐 task 已 move 到它的 display。
 * 本程序建一个 layerStack 指向该虚拟屏的影子 display，对它做 captureDisplay，
 * 同时采样：自身 RSS、系统可用内存、整机 CPU、surfaceflinger CPU、
 *           影子 display 输出面出帧数（说明是否真的每帧多做了一次合成）、
 *           capture 单帧耗时。
 *
 * 用法：
 *   VdShadow --stack 39 --sec 24 --size 2400x1200 --out 1920x568 [--src 2400x1200] [--child]
 *     --size  影子 display 尺寸（= 输出面尺寸 = 投影 dst）
 *     --src   投影源尺寸（layerStack 空间），默认等于 --size
 *     --out   capture 输出尺寸
 *   加 --nosurf 则不挂输出面（已知会导致 capture 恒 null，仅作对照）
 */
public final class VdShadow {

    static int DW = 2400, DH = 1200;      // 影子 display 尺寸
    static int SW = 0, SH = 0;            // 投影源尺寸（0 = 同 DW/DH）
    static int OW = 1920, OH = 568;       // capture 输出尺寸
    static int STACK = 0, SEC = 24;
    static final long[] OUT = {0};    // 影子 display 输出面已出帧数
    static int BUFS = 3;                  // ImageReader buffer 数（实测输出面不被写，可降到 1）
    static boolean noSurf = false;
    static boolean MAIN = false;          // --main：直接对主屏 capture，作为对照基线
    static Rect CROP = null;              // --crop x,y,w,h（仅 --main 用）

    public static void main(String[] args) {
        boolean child = false;
        for (String a : args) if ("--child".equals(a)) child = true;
        if (!child) {
            StringBuilder sb = new StringBuilder();
            for (String a : args) sb.append(a).append(' ');
            try {
                new ProcessBuilder("/system/bin/sh", "-c",
                        "CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin "
                                + "com.probe.asx.VdShadow " + sb + "--child > /data/local/tmp/vdshadow.log 2>&1 &")
                        .redirectErrorStream(true).start();
            } catch (Throwable t) { System.out.println("VDS| launch FAIL " + t); }
            System.out.println("VDS| launched");
            return;
        }
        parse(args);
        try {
            run();
        } catch (Throwable t) {
            say("FAIL " + t);
            t.printStackTrace();
        }
        try { Thread.sleep(200); } catch (Throwable ignored) { }
        System.exit(0);
    }

    static void parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--stack".equals(a) && i + 1 < args.length) STACK = atoi(args[++i], 0);
            else if ("--sec".equals(a) && i + 1 < args.length) SEC = atoi(args[++i], 24);
            else if ("--bufs".equals(a) && i + 1 < args.length) BUFS = atoi(args[++i], 3);
            else if ("--size".equals(a) && i + 1 < args.length) { int[] v = wh(args[++i]); DW = v[0]; DH = v[1]; }
            else if ("--src".equals(a) && i + 1 < args.length) { int[] v = wh(args[++i]); SW = v[0]; SH = v[1]; }
            else if ("--out".equals(a) && i + 1 < args.length) { int[] v = wh(args[++i]); OW = v[0]; OH = v[1]; }
            else if ("--nosurf".equals(a)) noSurf = true;
            else if ("--main".equals(a)) MAIN = true;
            else if ("--crop".equals(a) && i + 1 < args.length) {
                String[] p = args[++i].split(",");
                if (p.length == 4) CROP = new Rect(atoi(p[0], 0), atoi(p[1], 0),
                        atoi(p[0], 0) + atoi(p[2], 0), atoi(p[1], 0) + atoi(p[3], 0));
            }
        }
        if (SW <= 0) { SW = DW; SH = DH; }
    }

    static void run() throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        try { at.getMethod("getSystemContext").invoke(thread); } catch (Throwable ignored) { }

        say("CFG stack=" + STACK + " size=" + DW + "x" + DH + " src=" + SW + "x" + SH
                + " out=" + OW + "x" + OH + " sec=" + SEC + " surf=" + (!noSurf)
                + " bufs=" + BUFS + (MAIN ? " MODE=main crop=" + CROP : " MODE=shadow"));

        Class<?> sc = Class.forName("android.view.SurfaceControl");

        // ---- 对照模式：直接对主屏 capture，不建任何 display ----
        if (MAIN) {
            IBinder tokM = (IBinder) sc.getMethod("getInternalDisplayToken").invoke(null);
            if (tokM == null) { say("no main token"); return; }
            Class<?> bldCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
            Class<?> argCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
            Object b = bldCls.getConstructor(IBinder.class).newInstance(tokM);
            if (CROP != null) bldCls.getMethod("setSourceCrop", Rect.class).invoke(b, CROP);
            bldCls.getMethod("setSize", int.class, int.class).invoke(b, OW, OH);
            Object args = bldCls.getMethod("build").invoke(b);
            loop(sc, argCls, args, "main");
            return;
        }

        IBinder tok = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class)
                .invoke(null, "asxshadow", false);
        if (tok == null) { say("createDisplay null"); return; }

        // 输出面 + 排空线程（记录到底被合成了多少帧）
        ImageReader ir = null;
        final long[] outFrames = OUT;
        HandlerThread ht = new HandlerThread("drain");
        ht.start();
        if (!noSurf) {
            ir = ImageReader.newInstance(DW, DH, PixelFormat.RGBA_8888, Math.max(1, BUFS));
            final ImageReader fir = ir;
            ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try { img = r.acquireLatestImage(); if (img != null) outFrames[0]++; }
                    catch (Throwable ignored) { }
                    finally { if (img != null) try { img.close(); } catch (Exception ignored) { } }
                }
            }, new Handler(ht.getLooper()));
        }

        Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
        Object tx = txCls.getConstructor().newInstance();
        if (ir != null) {
            txCls.getMethod("setDisplaySurface", IBinder.class, android.view.Surface.class)
                    .invoke(tx, tok, ir.getSurface());
        }
        txCls.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                .invoke(tx, tok, 0, new Rect(0, 0, SW, SH), new Rect(0, 0, DW, DH));
        if (STACK > 0) {
            txCls.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(tx, tok, STACK);
        }
        txCls.getMethod("apply").invoke(tx);
        say("display activated (projection " + SW + "x" + SH + " -> " + DW + "x" + DH + ")");

        Class<?> bldCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Class<?> argCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        Object b = bldCls.getConstructor(IBinder.class).newInstance(tok);
        bldCls.getMethod("setSourceCrop", Rect.class).invoke(b, new Rect(0, 0, DW, DH));
        bldCls.getMethod("setSize", int.class, int.class).invoke(b, OW, OH);
        Object capArgs = bldCls.getMethod("build").invoke(b);

        loop(sc, argCls, capArgs, "shadow");
        try { sc.getMethod("destroyDisplay", IBinder.class).invoke(null, tok); } catch (Throwable ignored) { }
    }

    /** capture + 采样主体；tag 只用于日志区分 */
    static void loop(Class<?> sc, Class<?> argCls, Object capArgs, String tag) throws Exception {
        java.lang.reflect.Method mCap = sc.getMethod("captureDisplay", argCls);
        long sfPid = findPid("surfaceflinger");
        say("surfaceflinger pid=" + sfPid);

        // ---- 基线：激活后先空转 2 秒，不 capture，看纯合成开销 ----
        long base0 = System.currentTimeMillis();
        long sf0 = sfTicks(sfPid), cpu0 = totalCpu();
        long out0 = OUT[0];
        Thread.sleep(2000);
        long el = System.currentTimeMillis() - base0;
        say(String.format("BASELINE %.1fs idle: sfCpu=%.1f%% sysCpu=%.1f%% outFrames=%d(+%d) memAvail=%dMB rss=%dMB",
                el / 1000.0, cpuPct(sfTicks(sfPid) - sf0, el), cpuPct(totalCpu() - cpu0, el),
                OUT[0], OUT[0] - out0, memAvail(), selfRss()));

        // ---- 正式：边 capture 边采样 ----
        long t0 = System.currentTimeMillis();
        long end = t0 + SEC * 1000L;
        long ok = 0, nul = 0, sumMs = 0, nMs = 0, minMs = 9999, maxMs = 0;
        long nextAt = t0 + 2000;
        long sfPrev = sfTicks(sfPid), cpuPrev = totalCpu();
        long outPrev = OUT[0], capPrev = 0;
        long prevAt = t0;

        while (System.currentTimeMillis() < end) {
            long c0 = System.nanoTime();
            Object res = null;
            try { res = mCap.invoke(null, capArgs); }
            catch (Throwable t) { say("capture EX " + t); break; }
            long cms = (System.nanoTime() - c0) / 1000000L;
            sumMs += cms; nMs++;
            if (cms < minMs) minMs = cms;
            if (cms > maxMs) maxMs = cms;
            if (res == null) nul++; else ok++;

            // 第 40 帧校验内容，防止测出「空转很快」的假象
            if (res != null && ok == 40) {
                try {
                    android.graphics.Bitmap bmp =
                            (android.graphics.Bitmap) res.getClass().getMethod("asBitmap").invoke(res);
                    if (bmp != null) {
                        int nb = nonBlack(bmp);
                        say("CHECK frame#40 " + bmp.getWidth() + "x" + bmp.getHeight() + " nonBlack=" + nb + "%");
                        java.io.FileOutputStream fo =
                                new java.io.FileOutputStream("/sdcard/shadow_" + tag + "_" + DW + "x" + DH + ".png");
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fo);
                        fo.close();
                    }
                } catch (Throwable t) { say("CHECK FAIL " + t); }
            }

            long now = System.currentTimeMillis();
            if (now >= nextAt) {
                long d = now - prevAt;
                long sfNow = sfTicks(sfPid), cpuNow = totalCpu(), outNow = OUT[0];
                long caps = ok + nul - capPrev;
                say(String.format(
                        "t=%2ds caps=%3d(%.1f/s) null=%d capMs=%d | sfCpu=%.1f%% sysCpu=%.1f%% | outFrames=+%d | memAvail=%dMB rss=%dMB load=%s",
                        (now - t0) / 1000, caps, caps * 1000.0 / d, nul,
                        nMs > 0 ? sumMs / nMs : -1,
                        cpuPct(sfNow - sfPrev, d), cpuPct(cpuNow - cpuPrev, d),
                        outNow - outPrev, memAvail(), selfRss(), loadAvg()));
                sfPrev = sfNow; cpuPrev = cpuNow; outPrev = outNow; capPrev = ok + nul;
                prevAt = now;
                nextAt = now + 2000;
            }
            try { Thread.sleep(33); } catch (Throwable ignored) { }
        }

        long total = System.currentTimeMillis() - t0;
        say(String.format("SUMMARY [%s] size=%dx%d out=%dx%d bufs=%d frames=%d null=%d fps=%.1f capMs(avg/min/max)=%d/%d/%d rss=%dMB",
                tag, DW, DH, OW, OH, BUFS, ok, nul, ok * 1000.0 / Math.max(1, total),
                nMs > 0 ? sumMs / nMs : -1, minMs, maxMs, selfRss()));
    }

    // ---------------- 采样 ----------------

    /** 抽样统计非黑像素占比 */
    static int nonBlack(android.graphics.Bitmap bmp) {
        try {
            android.graphics.Bitmap s = bmp.copy(android.graphics.Bitmap.Config.RGB_565, false);
            byte[] buf = new byte[s.getWidth() * s.getHeight() * 2];
            s.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(buf));
            int c = 0, t = 0;
            for (int i = 0; i < buf.length; i += 2 * 97) {
                int v = (buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8);
                if (v > 1) c++;
                t++;
            }
            return t == 0 ? -1 : c * 100 / t;
        } catch (Throwable t) { return -1; }
    }

    /** jiffies of a process (utime+stime) */
    static long sfTicks(long pid) {
        if (pid <= 0) return 0;
        try {
            String s = readFile("/proc/" + pid + "/stat");
            int p = s.lastIndexOf(')');
            if (p < 0) return 0;
            String[] f = s.substring(p + 2).split(" ");
            // 从第 3 个字段（state）开始，utime 是第 14 个 -> 下标 11（相对切分后）
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (Throwable t) { return 0; }
    }

    /** 整机累计 jiffies（所有 cpu 行之和） */
    static long totalCpu() {
        try {
            String s = readFile("/proc/stat");
            long sum = 0;
            for (String line : s.split("\n")) {
                if (!line.startsWith("cpu")) continue;
                if (line.startsWith("cpu ")) continue;
                String[] f = line.trim().split("\\s+");
                for (int i = 1; i < f.length && i < 9; i++) sum += Long.parseLong(f[i]);
            }
            return sum;
        } catch (Throwable t) { return 0; }
    }

    /**
     * ticks 增量 -> 百分比（USER_HZ=100，1 tick = 10ms）。
     * 单进程：得到「占单核多少」；整机：得到「相当几核满载 ×100」。
     */
    static double cpuPct(long dTicks, long dMs) {
        if (dMs <= 0) return 0;
        return dTicks * 10.0 * 100.0 / dMs;
    }

    static long memAvail() {
        try {
            for (String line : readFile("/proc/meminfo").split("\n")) {
                if (line.startsWith("MemAvailable:")) {
                    String[] f = line.trim().split("\\s+");
                    return Long.parseLong(f[1]) / 1024;
                }
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    static long selfRss() {
        try {
            for (String line : readFile("/proc/self/status").split("\n")) {
                if (line.startsWith("VmRSS:")) {
                    String[] f = line.trim().split("\\s+");
                    return Long.parseLong(f[1]) / 1024;
                }
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    static String loadAvg() {
        try {
            String s = readFile("/proc/loadavg");
            return s.length() > 14 ? s.substring(0, 14) : s.trim();
        } catch (Throwable t) { return "?"; }
    }

    static long findPid(String name) {
        for (int i = 0; i < 4000; i++) {
            String c = readFile("/proc/" + i + "/cmdline");
            if (c != null && c.contains(name)) return i;
        }
        return -1;
    }

    static String readFile(String p) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(p);
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return "";
            return new String(buf, 0, n).replace('\0', ' ');
        } catch (Throwable t) { return null; }
    }

    static int atoi(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    static int[] wh(String s) {
        String[] p = s.toLowerCase().split("x");
        try { return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])}; }
        catch (Exception e) { return new int[]{DW, DH}; }
    }

    static void say(String s) {
        System.out.println("VDS| " + s);
        System.out.flush();
    }
}
