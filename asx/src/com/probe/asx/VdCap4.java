package com.probe.asx;

import android.graphics.Rect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 排查 captureDisplay 几百次后恒回 null 的恢复手段：
 *   R1: System.gc() + 重试
 *   R2: 重新 getInternalDisplayToken + 重建 Builder
 *   R3: 两者都做
 * 哪种能恢复，就把它接到 VdSend 的自愈路径上。
 */
public final class VdCap4 {

    static Class<?> sc, bld, argsCls;
    static Method cap, setCrop, setSize, build;
    static Constructor<?> ctor;
    static Object token;

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable ignored) { }

        String mode = args.length > 0 ? args[0] : "R1";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 900;

        sc = Class.forName("android.view.SurfaceControl");
        bld = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        argsCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        cap = sc.getMethod("captureDisplay", argsCls);
        setCrop = bld.getMethod("setSourceCrop", Rect.class);
        setSize = bld.getMethod("setSize", int.class, int.class);
        build = bld.getMethod("build");
        ctor = bld.getConstructor(android.os.IBinder.class);

        reinit();

        int x = 960, y = 345, w = 1440, h = 426;
        Rect crop = new Rect(x, y, x + w, y + h);
        int ok = 0, fail = 0, recovered = 0, deadSince = -1;

        for (int i = 0; i < n; i++) {
            Object b = ctor.newInstance(token);
            setCrop.invoke(b, crop);
            setSize.invoke(b, w, h);
            Object res = null;
            try { res = cap.invoke(null, build.invoke(b)); } catch (Throwable t) { }

            if (res != null) {
                ok++;
                if (deadSince >= 0) { recovered++; deadSince = -1; }
                Object hb = res.getClass().getMethod("getHardwareBuffer").invoke(res);
                if (hb instanceof AutoCloseable) ((AutoCloseable) hb).close();
            } else {
                fail++;
                if (deadSince < 0) deadSince = i;
                if ("R1".equals(mode)) {
                    System.gc();
                    Thread.sleep(5);
                } else if ("R2".equals(mode)) {
                    reinit();
                } else {
                    System.gc();
                    reinit();
                }
            }
            if (i % 100 == 0) {
                say(mode + " i=" + i + " ok=" + ok + " fail=" + fail + " rec=" + recovered
                        + " deadSince=" + deadSince + " rssMB=" + rssMB());
                System.out.flush();
            }
            Thread.sleep(4);
        }
        say("RESULT " + mode + " ok=" + ok + " fail=" + fail + " recovered=" + recovered);
    }

    static void reinit() throws Exception {
        token = sc.getMethod("getInternalDisplayToken").invoke(null);
    }

    static long rssMB() {
        try {
            for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/self/status"))) {
                if (l.startsWith("VmRSS:")) {
                    return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
                }
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    static void say(String s) {
        System.out.println("VDC| " + s);
        System.out.flush();
    }
}
