package com.probe.asx;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;

import java.lang.reflect.Method;

/**
 * 探测可用的「主动截屏」API 并测速。
 * 目的：镜像虚拟屏依赖 SF 合成事件，主屏走 HWC 直通时收不到帧；
 * 主动截屏强制 SF 重新合成并回读，不受 damage 影响。
 */
public final class VdCap {

    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            say("systemMain FAIL " + t);
        }

        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            for (Method m : sc.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("screenshot") || n.contains("capture")) {
                    say("SC." + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes())
                            + " static=" + java.lang.reflect.Modifier.isStatic(m.getModifiers()));
                }
            }
        } catch (Throwable t) {
            say("SurfaceControl FAIL " + t);
        }

        try {
            Class<?> sca = Class.forName("android.view.ScreenCapture");
            for (Method m : sca.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    say("ScreenCapture." + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
                }
            }
            for (Class<?> c : sca.getDeclaredClasses()) {
                say("ScreenCapture inner " + c.getName());
            }
        } catch (Throwable t) {
            say("ScreenCapture FAIL " + t);
        }

        // 直接试各种签名
        int x = 960, y = 345, w = 1440, h = 426;
        tryShot("4arg-rect", x, y, w, h);
        tryShot("3arg-rect", x, y, w, h);

        say("DONE");
    }

    static void tryShot(String tag, int x, int y, int w, int h) {
        Class<?> sc;
        try {
            sc = Class.forName("android.view.SurfaceControl");
        } catch (Throwable t) {
            return;
        }
        Rect r = new Rect(x, y, x + w, y + h);
        Method best = null;
        for (Method m : sc.getDeclaredMethods()) {
            if (!m.getName().equals("screenshot")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4 && p[0] == Rect.class && p[1] == int.class && p[2] == int.class) {
                best = m;
                break;
            }
            if (best == null && p.length == 3 && p[0] == Rect.class) best = m;
        }
        if (best == null) {
            say(tag + ": no signature");
            return;
        }
        best.setAccessible(true);
        try {
            Object res;
            Class<?>[] p = best.getParameterTypes();
            if (p.length == 4) res = best.invoke(null, r, w, h, 0);
            else res = best.invoke(null, r, w, h);
            if (!(res instanceof Bitmap)) {
                say(tag + ": result " + (res == null ? "null" : res.getClass().getName()));
                return;
            }
            Bitmap bmp = (Bitmap) res;
            long t0 = SystemClock.elapsedRealtime();
            int n = 6;
            long sum = 0;
            for (int i = 0; i < n; i++) {
                long s = SystemClock.elapsedRealtime();
                Bitmap b = (Bitmap) (p.length == 4 ? best.invoke(null, r, w, h, 0) : best.invoke(null, r, w, h));
                sum += SystemClock.elapsedRealtime() - s;
                if (b != null) b.recycle();
            }
            long avg = sum / n;
            say(tag + ": OK " + bmp.getWidth() + "x" + bmp.getHeight()
                    + " fmt=" + bmp.getConfig() + " avg_ms=" + avg
                    + " -> " + (avg > 0 ? (1000 / avg) + "fps" : "?"));
            bmp.recycle();
        } catch (Throwable t) {
            say(tag + ": FAIL " + t);
        }
    }

    static void say(String s) {
        System.out.println("VDC| " + s);
        System.out.flush();
    }
}
