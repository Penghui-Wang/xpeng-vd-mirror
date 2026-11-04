package com.probe.asx;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.IBinder;
import android.view.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 诊断：虚拟屏的 display token 到底藏在哪个字段里。
 *
 * 上一轮 VdCap5 反射 VirtualDisplay.mToken 拿到的是 VirtualDisplayCallback（回调），
 * 不是 display token，导致 captureDisplay 恒返回 null。这里把所有字段/方法都打出来，
 * 并按名字猜测逐个试 capture，谁能让 captureDisplay 返回非 null 就是它。
 *
 * 用法（shell 域）：
 *   CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin \
 *     com.probe.asx.VdTok > /data/local/tmp/vdtok.log 2>&1 &
 */
public final class VdTok {

    static final int VW = 2400, VH = 1200;
    static final int CX = 960, CY = 345, CW = 1440, CH = 426, OW = 1920, OH = 568;

    public static void main(String[] args) {
        boolean child = false;
        for (String a : args) if ("--child".equals(a)) child = true;
        if (!child) {
            try {
                new ProcessBuilder("/system/bin/sh", "-c",
                        "CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin "
                                + "com.probe.asx.VdTok --child > /data/local/tmp/vdtok.log 2>&1 &")
                        .redirectErrorStream(true).start();
                System.out.println("VDT| launched child");
            } catch (Throwable t) {
                System.out.println("VDT| launch FAIL " + t);
            }
            return;
        }
        try { run(); } catch (Throwable t) { say("FAIL " + t); t.printStackTrace(); }
        try { Thread.sleep(300); } catch (Throwable ignored) { }
        System.exit(0);
    }

    static void run() throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context pkg = sys.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        DisplayManager dm = (DisplayManager) pkg.getSystemService(Context.DISPLAY_SERVICE);

        // 0) 先用主屏 token 验证 capture 通路本身没问题
        IBinder mainTok = (IBinder) Class.forName("android.view.SurfaceControl")
                .getMethod("getInternalDisplayToken").invoke(null);
        say("MAIN_CAPTURE ok=" + probeCapture(mainTok, "主屏"));

        android.media.ImageReader ir = android.media.ImageReader.newInstance(
                VW, VH, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay vd = dm.createVirtualDisplay("asxtok", VW, VH, 160, ir.getSurface(), 0x9);
        if (vd == null) { say("CREATE_NULL"); return; }
        Display d = vd.getDisplay();
        say("VD_ID=" + d.getDisplayId() + " name=" + d.getName() + " state=" + d.getState());

        say("--- VirtualDisplay 字段 ---");
        for (Field f : VirtualDisplay.class.getDeclaredFields()) {
            f.setAccessible(true);
            Object v = null;
            String err = "";
            try { v = f.get(vd); } catch (Throwable t) { err = " <" + t + ">"; }
            String vs = String.valueOf(v);
            if (vs.length() > 120) vs = vs.substring(0, 120) + "...";
            say("  " + Modifier.toString(f.getModifiers()) + " " + f.getType().getSimpleName()
                    + " " + f.getName() + " = " + vs + err);
        }

        say("--- Display 字段 ---");
        for (Field f : Display.class.getDeclaredFields()) {
            String n = f.getName();
            if (!n.toLowerCase().contains("token") && !n.toLowerCase().contains("id")) continue;
            f.setAccessible(true);
            Object v = null;
            try { v = f.get(d); } catch (Throwable t) { v = "<" + t + ">"; }
            say("  " + f.getType().getSimpleName() + " " + n + " = " + v);
        }

        say("--- SurfaceControl 里跟 token/display 有关的静态方法 ---");
        for (Method m : Class.forName("android.view.SurfaceControl").getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            if (!n.contains("token") && !n.contains("display")) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            say("  " + m.getReturnType().getSimpleName() + " " + m.getName()
                    + java.util.Arrays.toString(m.getParameterTypes()));
        }

        say("--- 逐个字段试 capture ---");
        for (Field f : VirtualDisplay.class.getDeclaredFields()) {
            if (!IBinder.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object v;
            try { v = f.get(vd); } catch (Throwable t) { continue; }
            if (!(v instanceof IBinder)) continue;
            IBinder b = (IBinder) v;
            say("  try field " + f.getName() + " (" + b.getClass().getSimpleName() + ")");
            say("    -> ok=" + probeCapture(b, f.getName()));
        }
    }

    /** 用给定 token 试一次 captureDisplay，返回是否拿到结果 */
    static boolean probeCapture(IBinder token, String tag) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            Class<?> bld = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
            Object b = bld.getConstructor(IBinder.class).newInstance(token);
            bld.getMethod("setSourceCrop", Rect.class).invoke(b, new Rect(CX, CY, CX + CW, CY + CH));
            bld.getMethod("setSize", int.class, int.class).invoke(b, OW, OH);
            Object args = bld.getMethod("build").invoke(b);
            Class<?> ac = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
            long t0 = android.os.SystemClock.elapsedRealtime();
            Object res = sc.getMethod("captureDisplay", ac).invoke(null, args);
            long ms = android.os.SystemClock.elapsedRealtime() - t0;
            if (res == null) { say("    " + tag + " capture=null " + ms + "ms"); return false; }
            Bitmap bmp = (Bitmap) res.getClass().getMethod("asBitmap").invoke(res);
            say("    " + tag + " capture=OK " + ms + "ms bmp="
                    + (bmp == null ? "null" : bmp.getWidth() + "x" + bmp.getHeight() + "/" + bmp.getConfig()));
            return bmp != null;
        } catch (Throwable t) {
            say("    " + tag + " capture=EX " + t);
            return false;
        }
    }

    static void say(String s) {
        System.out.println("VDT| " + s);
        System.out.flush();
    }
}
