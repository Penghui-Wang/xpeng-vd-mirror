package com.probe.asx;

import java.lang.reflect.Method;

/** 直接调 ActivityTaskManager.moveTaskToDisplay，把任务搬到指定显示器。 */
public final class VdMove {
    public static void main(String[] a) throws Exception {
        android.os.Looper.prepareMainLooper();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            say("systemMain FAIL " + t);
        }

        Class<?> atmc = Class.forName("android.app.ActivityTaskManager");
        Object atm = atmc.getMethod("getService").invoke(null);
        say("atm=" + atm);

        for (Method m : atm.getClass().getMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("movetask") || n.contains("resize") || n.contains("movedisplay")) {
                say("M " + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
            }
        }

        if (a.length < 2) { say("usage: VdMove <taskId> <displayId>"); return; }
        int taskId = Integer.parseInt(a[0]);
        int displayId = Integer.parseInt(a[1]);
        try {
            Method mv = atm.getClass().getMethod("moveTaskToDisplay", int.class, int.class, boolean.class);
            mv.invoke(atm, taskId, displayId, true);
            say("moveTaskToDisplay OK task=" + taskId + " -> display=" + displayId);
        } catch (Throwable t) {
            say("moveTaskToDisplay FAIL " + t.getCause());
        }
    }

    static void say(String s) {
        System.out.println("VDM| " + s);
        System.out.flush();
    }
}
