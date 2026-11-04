package com.probe.asx;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.lang.reflect.Method;

/**
 * 定位 captureDisplay 跑到几百帧后恒返回 null 的原因。
 * A 组：只 capture + 释放 HardwareBuffer，不做 Bitmap 转换。
 * B 组：capture + asBitmap + copy + recycle（VdSend 现路径）。
 * 哪组先失败，问题就在那一步。
 */
public final class VdCap3 {

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable ignored) { }

        String mode = args.length > 0 ? args[0] : "A";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 1500;

        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Object token = sc.getMethod("getInternalDisplayToken").invoke(null);
        Class<?> bld = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        Class<?> argsCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        Method cap = sc.getMethod("captureDisplay", argsCls);
        Method setCrop = bld.getMethod("setSourceCrop", Rect.class);
        Method setSize = bld.getMethod("setSize", int.class, int.class);
        Method build = bld.getMethod("build");
        java.lang.reflect.Constructor<?> ctor = bld.getConstructor(android.os.IBinder.class);

        int x = 960, y = 345, w = 1440, h = 426;
        Rect crop = new Rect(x, y, x + w, y + h);
        int ok = 0, fail = 0, failFirst = -1;
        byte[] sink = null;

        for (int i = 0; i < n; i++) {
            Object builder = ctor.newInstance(token);
            setCrop.invoke(builder, crop);
            setSize.invoke(builder, w, h);
            Object a = build.invoke(builder);

            Object res = null;
            try { res = cap.invoke(null, a); } catch (Throwable t) { }
            if (res == null) {
                fail++;
                if (failFirst < 0) failFirst = i;
            } else {
                ok++;
                Object hb = res.getClass().getMethod("getHardwareBuffer").invoke(res);
                if ("B".equals(mode)) {
                    Bitmap hw = (Bitmap) res.getClass().getMethod("asBitmap").invoke(res);
                    Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                    if (sink == null) sink = new byte[w * h * 4];
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(sink);
                    soft.copyPixelsToBuffer(bb);
                    soft.recycle();
                    hw.recycle();
                }
                if (hb instanceof AutoCloseable) ((AutoCloseable) hb).close();
            }
            if (i % 100 == 0) {
                say(mode + " i=" + i + " ok=" + ok + " fail=" + fail + " firstFail=" + failFirst);
                System.out.flush();
            }
            Thread.sleep(4);
        }
        say("RESULT " + mode + " ok=" + ok + " fail=" + fail + " firstFail=" + failFirst);
    }

    static void say(String s) {
        System.out.println("VDC| " + s);
        System.out.flush();
    }
}
