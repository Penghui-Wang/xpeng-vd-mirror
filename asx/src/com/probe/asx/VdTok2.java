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
 * 诊断二：能不能绕过「拿不到虚拟屏 display token」这件事。
 *
 * 三件事：
 *   1. DisplayCaptureArgs / Builder 里有没有 setDisplayId —— 能按 displayId 抓就不用 token；
 *   2. SurfaceControl.createDisplay 自建的 display token 能不能 capture（验证 SF 对虚拟 display 的支持）；
 *   3. binder 层（ISurfaceComposer / IDisplayManager）有没有暴露 token。
 */
public final class VdTok2 {

    static final int VW = 2400, VH = 1200;
    static final int CX = 960, CY = 345, CW = 1440, CH = 426, OW = 1920, OH = 568;

    public static void main(String[] args) {
        boolean child = false;
        for (String a : args) if ("--child".equals(a)) child = true;
        if (!child) {
            try {
                new ProcessBuilder("/system/bin/sh", "-c",
                        "CLASSPATH=/data/local/tmp/vd.dex setsid app_process /system/bin "
                                + "com.probe.asx.VdTok2 --child > /data/local/tmp/vdtok2.log 2>&1 &")
                        .redirectErrorStream(true).start();
                System.out.println("VDT2| launched");
            } catch (Throwable t) { System.out.println("VDT2| launch FAIL " + t); }
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

        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Class<?> bldCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Class<?> argCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");

        say("=== DisplayCaptureArgs$Builder 方法 ===");
        for (Method m : bldCls.getDeclaredMethods()) {
            say("  " + Modifier.toString(m.getModifiers()) + " " + m.getReturnType().getSimpleName()
                    + " " + m.getName() + java.util.Arrays.toString(m.getParameterTypes()));
        }
        say("=== DisplayCaptureArgs 字段/方法 ===");
        for (Field f : argCls.getDeclaredFields()) {
            say("  F " + f.getType().getSimpleName() + " " + f.getName());
        }
        for (Method m : argCls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            say("  M " + m.getReturnType().getSimpleName() + " " + m.getName()
                    + java.util.Arrays.toString(m.getParameterTypes()));
        }

        // 建一个 DMS 虚拟屏（和 VdCap5 一样用 com.android.shell 的 package context）
        android.media.ImageReader ir = android.media.ImageReader.newInstance(
                VW, VH, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay vd = dm.createVirtualDisplay("asxcap2", VW, VH, 160, ir.getSurface(), 0x9);
        if (vd == null) { say("CREATE_NULL"); return; }
        int vdId = vd.getDisplay().getDisplayId();
        say("VD_ID=" + vdId + " state=" + vd.getDisplay().getState());

        // 1) 试试 setDisplayId
        say("=== 尝试按 displayId 抓 ===");
        try {
            Method setId = bldCls.getMethod("setDisplayId", long.class);
            Object b = bldCls.getConstructor(IBinder.class).newInstance((Object) null);
            setId.invoke(b, (long) vdId);
            say("  setDisplayId(" + vdId + ") 调用成功");
            bldCls.getMethod("setSourceCrop", Rect.class).invoke(b, new Rect(CX, CY, CX + CW, CY + CH));
            bldCls.getMethod("setSize", int.class, int.class).invoke(b, OW, OH);
            Object argsObj = bldCls.getMethod("build").invoke(b);
            captureAndReport(sc, argCls, argsObj, "byDisplayId");
        } catch (NoSuchMethodException e) {
            say("  没有 setDisplayId —— 此路不通");
        } catch (Throwable t) {
            say("  setDisplayId 尝试失败: " + t);
        }

        // 2) 自建 display token 再 capture
        say("=== 尝试自建 display token ===");
        IBinder rawTok = null;
        try {
            rawTok = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class)
                    .invoke(null, "asxraw", false);
            say("  createDisplay -> " + (rawTok == null ? "null" : rawTok.getClass().getName() + " " + rawTok));
            if (rawTok != null) {
                Object b2 = bldCls.getConstructor(IBinder.class).newInstance(rawTok);
                bldCls.getMethod("setSourceCrop", Rect.class).invoke(b2, new Rect(0, 0, CW, CH));
                bldCls.getMethod("setSize", int.class, int.class).invoke(b2, OW, OH);
                Object args2 = bldCls.getMethod("build").invoke(b2);
                captureAndReport(sc, argCls, args2, "rawToken");
            }
        } catch (Throwable t) {
            say("  createDisplay 失败: " + t);
        } finally {
            if (rawTok != null) {
                try { sc.getMethod("destroyDisplay", IBinder.class).invoke(null, rawTok); say("  destroyDisplay ok"); }
                catch (Throwable t) { say("  destroyDisplay FAIL " + t); }
            }
        }

        // 3) binder 层找 token 接口
        say("=== ISurfaceComposer 里带 token/display 的方法 ===");
        dumpBinder("android.view.ISurfaceComposer");
        say("=== IDisplayManager 里带 token/display 的方法 ===");
        dumpBinder("android.hardware.display.IDisplayManager");
    }

    static void dumpBinder(String cls) {
        try {
            Class<?> c = Class.forName(cls);
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (!n.contains("token") && !n.contains("display")) continue;
                say("  " + m.getReturnType().getSimpleName() + " " + m.getName()
                        + java.util.Arrays.toString(m.getParameterTypes()));
            }
        } catch (Throwable t) {
            say("  " + cls + " 不可用: " + t);
        }
    }

    static void captureAndReport(Class<?> sc, Class<?> argCls, Object argsObj, String tag) {
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            Object res = sc.getMethod("captureDisplay", argCls).invoke(null, argsObj);
            long ms = android.os.SystemClock.elapsedRealtime() - t0;
            if (res == null) { say("  " + tag + " -> null (" + ms + "ms)"); return; }
            Bitmap bmp = (Bitmap) res.getClass().getMethod("asBitmap").invoke(res);
            int nb = -1;
            if (bmp != null) {
                Bitmap s = bmp.copy(Bitmap.Config.RGB_565, false);
                byte[] buf = new byte[s.getWidth() * s.getHeight() * 2];
                s.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(buf));
                int cnt = 0, tot = 0;
                for (int i = 0; i < buf.length; i += 2 * 97) {
                    int v = (buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8);
                    if (v > 1) cnt++;
                    tot++;
                }
                nb = tot == 0 ? -1 : cnt * 100 / tot;
            }
            say("  " + tag + " -> OK " + ms + "ms bmp="
                    + (bmp == null ? "null" : bmp.getWidth() + "x" + bmp.getHeight()) + " nonBlack=" + nb + "%");
        } catch (Throwable t) {
            say("  " + tag + " -> EX " + t);
        }
    }

    static void say(String s) {
        System.out.println("VDT2| " + s);
        System.out.flush();
    }
}
