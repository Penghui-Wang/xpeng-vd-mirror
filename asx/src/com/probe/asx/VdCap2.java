package com.probe.asx;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;

import java.lang.reflect.Method;

/** 探测 SurfaceControl.captureDisplay 同步 API，并测「裁剪区域截图」的真实耗时。 */
public final class VdCap2 {

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            say("systemMain FAIL " + t);
        }

        Class<?> sc = Class.forName("android.view.SurfaceControl");
        for (Method m : sc.getDeclaredMethods()) {
            String n = m.getName();
            if (n.contains("Token") || n.contains("token")) {
                say("SC." + n + " " + java.util.Arrays.toString(m.getParameterTypes()));
            }
        }

        Object token = null;
        for (String name : new String[]{"getInternalDisplayToken", "getDefaultDisplayToken"}) {
            try {
                token = sc.getMethod(name).invoke(null);
                say(name + " -> " + (token == null ? "null" : token.getClass().getName()));
                if (token != null) break;
            } catch (Throwable t) {
                say(name + " FAIL " + t);
            }
        }
        if (token == null) { say("NO TOKEN"); return; }

        Class<?> argsCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        for (Method m : argsCls.getDeclaredMethods()) {
            say("DCA." + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
        }
        Class<?> builderCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        for (Method m : builderCls.getDeclaredMethods()) {
            say("Builder." + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
        }

        int x = 960, y = 345, w = 1440, h = 426;
        Rect crop = new Rect(x, y, x + w, y + h);
        Object builder = builderCls.getConstructor(android.os.IBinder.class).newInstance(token);
        builderCls.getMethod("setSourceCrop", Rect.class).invoke(builder, crop);
        builderCls.getMethod("setSize", int.class, int.class).invoke(builder, w, h);
        Object capArgs = builderCls.getMethod("build").invoke(builder);
        say("args built");

        Method cap = null;
        for (Method m : sc.getDeclaredMethods()) {
            if (m.getName().equals("captureDisplay") && m.getParameterTypes().length == 1) {
                cap = m;
                break;
            }
        }
        if (cap == null) { say("NO SYNC captureDisplay"); return; }
        cap.setAccessible(true);

        Object res = cap.invoke(null, capArgs);
        say("result " + (res == null ? "null" : res.getClass().getName()));
        if (res == null) return;

        for (Method m : res.getClass().getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                say("RES." + m.getName() + " -> " + m.getReturnType().getSimpleName());
            }
        }

        Method getHb = res.getClass().getMethod("getHardwareBuffer");
        Method getCs = res.getClass().getMethod("getColorSpace");
        Object hb = getHb.invoke(res);
        Object cs = getCs.invoke(res);
        say("hb=" + (hb == null ? "null" : hb.getClass().getName()) + " cs=" + cs);

        if (hb == null) return;

        // 6 次：截屏 + 转软位图 + 拷进 ByteBuffer，模拟真实每帧路径
        int n = 6;
        long sumCap = 0, sumCopy = 0;
        byte[] out = new byte[w * h * 4];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(out);
        for (int i = 0; i < n; i++) {
            long s0 = SystemClock.elapsedRealtime();
            Object r = cap.invoke(null, capArgs);
            long s1 = SystemClock.elapsedRealtime();
            Object h2 = getHb.invoke(r);
            Object c2 = getCs.invoke(r);
            Bitmap hw = Bitmap.wrapHardwareBuffer((android.hardware.HardwareBuffer) h2, (android.graphics.ColorSpace) c2);
            Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
            bb.clear();
            soft.copyPixelsToBuffer(bb);
            long s2 = SystemClock.elapsedRealtime();
            sumCap += s1 - s0;
            sumCopy += s2 - s1;
            soft.recycle();
            hw.recycle();
            say("iter" + i + " cap=" + (s1 - s0) + "ms copy=" + (s2 - s1) + "ms");
        }
        say("AVG cap=" + (sumCap / n) + "ms copy=" + (sumCopy / n) + "ms total=" + ((sumCap + sumCopy) / n)
                + "ms -> " + (1000 / Math.max(1, (sumCap + sumCopy) / n)) + "fps");
        say("DONE");
    }

    static void say(String s) {
        System.out.println("VDC| " + s);
        System.out.flush();
    }
}
