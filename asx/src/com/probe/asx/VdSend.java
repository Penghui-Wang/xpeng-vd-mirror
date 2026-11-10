package com.probe.asx;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * 把小鹏车机上的一个屏幕区域实时推到仪表。
 *
 * 采集方式演进（关键结论）：
 *   1) VirtualDisplay + ImageReader（OWN_CONTENT_ONLY）：容器里没有窗口时不出帧；
 *      窗口搬上去后可以出帧，但完全由 damage 驱动 —— 画面不动就 0 帧。
 *   2) VirtualDisplay + AUTO_MIRROR：镜像输出依赖 SF 合成事件；
 *      主屏更新走 HWC 直通时不进 SF 合成，镜像虚拟屏就收不到帧（静止几秒才动一下）。
 *   3) SurfaceControl.captureDisplay 主动截屏：强制 SF 重新合成并回读。
 *      这是当前两条路径共同的基础。
 *
 * 两种模式：
 *   --mode main （默认）抓主屏：车机屏幕上必须看得到目标窗口，切走就跟着变。
 *   --mode vd  抓虚拟屏：窗口搬到独立虚拟屏上，车机屏切走不影响投屏内容。
 *              DMS 不把虚拟屏的 display token 暴露给客户端（mToken 是
 *              IVirtualDisplayCallback，Builder 只有 setDisplayToken、没有 setDisplayId），
 *              所以自建一个 shadow display，用 Transaction 把它的 layerStack 指向虚拟屏，
 *              再对这个 shadow token 做 captureDisplay。
 *
 * 现在用 captureDisplay 直接输出 ARGB_8888/RGB_565，CPU 转 RGB565 -> LZ4 -> TCP。
 *   SF 侧完成缩放（1440x426 -> 1920x568），CPU 不再做双线性插值。
 *
 * 用法: app_process /system/bin com.probe.asx.VdSend [--mode main|vd] [w] [h] [fps]
 *       crop 参数从 /data/local/tmp/vdcrop.txt 读，格式: x,y,w,h
 */
public final class VdSend {

    static final String DEX = "/data/local/tmp/vd.dex";
    static final String LOG = "/data/local/tmp/vdsend.log";
    static final String CROP_FILE = "/data/local/tmp/vdcrop.txt";

    /**
     * 输出尺寸 = 仪表上投屏矩形的实际大小（由 APK 从 crect 推出来，命令行第 1/2 个参数）。
     *
     * 以前写死 1920x568，出现两个后果：① 仪表只开了 550x384 的框，发送端还是发满尺寸，
     * 多出来的像素全被接收端丢掉（带宽浪费约 8 倍）；② 接收端只能做一次最近邻缩小，
     * 画质不如 SF 自己的缩放。现在由 SF 一次性缩到目标尺寸，接收端 1:1 贴上去。
     */
    static int OUT_W = 1920;
    static int OUT_H = 568;
    static final int OUT_MIN = 16, OUT_MAX = 4096;
    static final int FMT_LZ4 = 2;

    static final String HOST = "172.20.1.40";
    static final int PORT = 5560;

    /** 虚拟屏尺寸必须与主屏一致：setDisplayProjection 的缩放不生效，影子 display 只做 1:1 裁切 */
    static final int VD_W = 2400, VD_H = 1200;

    // ---------- 虚拟屏模式状态 ----------
    static boolean VDMODE = false;
    static String TARGET_PKG = "com.tencent.qqmusicpad";
    static Object vdObj;                 // VirtualDisplay
    static Object vdReader;              // ImageReader（只是让 display 有输出面，实测从不被写）
    static android.os.IBinder shadowTok;
    static volatile int vdDisplayId = -1;
    static volatile int targetTaskId = -1;
    static volatile String targetComponent = null;
    static final Object RESTORED = new Object();
    static boolean restored = false;


    // 源裁剪区（车机屏幕坐标）。默认对准 QQ 音乐 freeform 窗口 [960,0]-[2400,1080]
    // 并按仪表中间带 1920x568 的比例取横条：1440 x 426。
    //
    // 框选坐标是「相对框选那一刻的 2/3 窗口」的；小鹏允许把 2/3 窗口放左或放右，
    // 窗口原点会差 960px。真正抓屏前必须用 applyCropShift() 把 SRC_* 平移到目标
    // 窗口当前所在的一侧，否则窗口在左栏时抓到的右半边是空桌面（画面异常）。
    static int SRC_X = 960, SRC_Y = 345, SRC_W = 1440, SRC_H = 426;
    /** 文件里读出的原始裁切（未平移）；applyCropShift 每次从它重算，避免重复叠加 */
    static int RAW_X = 960, RAW_Y = 345, RAW_W = 1440, RAW_H = 426;
    /** 框选时目标窗口的 bounds {x,y,w,h}；没有基准文件时用宽高推断 */
    static int[] cropBase;
    /** 目标窗口最终落点（2/3 左栏或右栏）；null=没识别出来，不做平移 */
    static volatile Rect paneRect;
    static final String CROP_BASE_FILE = "/data/local/tmp/vdcrop.base";
    static final String ORIG_FILE = "/data/local/tmp/vdorig.txt";
    static String baseText = "";
    static String paneLogged = "";
    static long lastPanePollMs = 0;

    static volatile long frames = 0, sentFrames = 0, sentBytes = 0, capFrames = 0;
    static volatile int lastFps = 0;
    static volatile long lastProcMs = 0, lastCapMs = 0, lastCopyMs = 0;
    static volatile long sumProcMs = 0, nProcMs = 0;

    static int targetFps = 30;

    public static void main(String[] args) {
        boolean child = false;
        int w = 1920, h = 568, k = 0;
        int[] nums = new int[3];
        boolean restoreOnly = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--child".equals(a)) { child = true; continue; }
            if ("--mode".equals(a) && i + 1 < args.length) {
                VDMODE = "vd".equals(args[++i]);
                continue;
            }
            if ("--pkg".equals(a) && i + 1 < args.length) { TARGET_PKG = args[++i]; continue; }
            if ("--preview".equals(a) && i + 1 < args.length) {
                try { previewPort = Integer.parseInt(args[++i].trim()); } catch (Exception ignored) { }
                continue;
            }
            if ("--restore".equals(a)) { restoreOnly = true; continue; }
            if (k < 3) {
                try { nums[k++] = Integer.parseInt(a); } catch (Exception ignored) { }
            }
        }
        if (k > 0) w = nums[0];
        if (k > 1) h = nums[1];
        if (k > 2) targetFps = nums[2];
        // 输出尺寸跟着仪表上的矩形走：裁切区 → (SF 缩放) → OUT_W×OUT_H
        if (k > 0) OUT_W = clampDim(w, OUT_W);
        if (k > 1) OUT_H = clampDim(h, OUT_H);
        if (targetFps < 1) targetFps = 30;

        // 独立恢复入口：kill -9 之后残留的搬移状态用这条清理。
        // 必须幂等——窗口本来就在左栏或右栏时不能把用户正在用的界面拉到前台。
        if (restoreOnly) {
            // B5：这里原来要 systemMain() 初始化 ActivityThread。但整个 restore 只用
            // `am` 命令行（exec）+ System.exit，一次 binder 都不发 —— 初始化白花 0.5~1s，去掉。

            int t = findRootTask(TARGET_PKG);
            if (t <= 0) {
                say("restore: task not found");
                System.exit(0);
            }
            targetTaskId = t;
            int cur = taskDisplayOf(t);
            String b = taskBounds(t);
            // 左栏、右栏都算干净的 2/3 状态；只有全屏/错位才需要修
            if (cur == 0 && paneOf(b) != null) {
                say("restore: nothing to do (display=0 bounds=" + b + ")");
                clearOrig();
                System.exit(0);
            }
            say("restore: fixing display=" + cur + " bounds=" + b);
            if (cur != 0) {
                moveTask(t, 0);
                // B5：原来固定 sleep(1500)。moveTask 是同步 binder 调用，返回时已经搬完；
                // 这里只做一次核实，没搬走再等 200ms 复查（最多 1 秒）。
                for (int i = 0; i < 5 && taskDisplayOf(t) != 0; i++) sleep(200);
            }
            restorePane(origTarget());
            say("restore: done bounds=" + taskBounds(t));
            System.exit(0);
        }

        loadCrop();

        if (!child) {
            launchChild(w, h, targetFps);
            System.out.println("VDS| launched child, parent exiting");
            return;
        }
        runChild(w, h);
    }

    static void loadCrop() {
        try {
            File f = new File(CROP_FILE);
            if (!f.exists()) return;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
            String[] p = s.split("[,\\s]+");
            if (p.length >= 4) {
                RAW_X = Integer.parseInt(p[0].trim());
                RAW_Y = Integer.parseInt(p[1].trim());
                RAW_W = Integer.parseInt(p[2].trim());
                RAW_H = Integer.parseInt(p[3].trim());
                loadCropBase(s);
                applyCropShift();
            }
        } catch (Throwable t) {
            say("loadCrop FAIL " + t);
        }
    }

    /**
     * vdcrop.base：首行是框选时的 crop 文本，次行是那会儿目标窗口的 bounds。
     *
     * 面板写裁切时顺手记下窗口在哪一栏；发送端据此知道「裁切框是相对哪一栏选的」。
     * 首行和当前裁切对不上说明文件过期，宁可退回按宽高推断，也不能拿旧基准乱平移。
     */
    static void loadCropBase(String cropText) {
        cropBase = null;
        try {
            File f = new File(CROP_BASE_FILE);
            if (!f.exists()) return;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
            String[] ln = s.split("\\r?\\n");
            if (ln.length < 2) return;
            String want = RAW_X + "," + RAW_Y + "," + RAW_W + "," + RAW_H;
            if (!ln[0].trim().equals(want)) {
                say("crop base stale (" + ln[0].trim() + " != " + want + ") -> infer");
                return;
            }
            cropBase = parseInts(ln[1], 4);
            if (cropBase != null) {
                say("crop base window=" + cropBase[0] + "," + cropBase[1]
                        + "," + cropBase[2] + "," + cropBase[3]);
            }
        } catch (Throwable t) {
            say("loadCropBase FAIL " + t);
        }
    }

    /**
     * 把裁切框从「框选时那一栏」平移到目标窗口现在所在的那一栏。
     *
     * 基准优先级：
     *  1) vdcrop.base 记录的窗口 bounds（最准，能处理窗口不在 0 起点）；
     *  2) 裁切宽度≈1440：说明选的是整条 2/3 栏，x 就是窗口 x，y 取 0；
     *  3) 其余：x≥480 猜框选时在右栏，否则左栏，y 取 0。
     * 全屏框选（≈2400×1200）不平移 —— 用户要的就是整个屏幕。
     */
    static void applyCropShift() {
        SRC_X = RAW_X;
        SRC_Y = RAW_Y;
        SRC_W = RAW_W;
        SRC_H = RAW_H;
        Rect pane = paneRect;
        if (pane == null) return;
        if (RAW_W >= VD_W - 200 && RAW_H >= VD_H - 200) return;
        if (RAW_W <= 0 || RAW_H <= 0 || RAW_W > VD_W || RAW_H > VD_H) return;
        // 没有基准记录时只对「和 2/3 栏同宽」的裁切做推断平移；1920 宽这类
        // 自定义绝对裁切保持原样，免得平移后反而跑偏。
        if (cropBase == null && (RAW_W < 1200 || RAW_W > 1700)) return;

        int baseX, baseY;
        if (cropBase != null) {
            baseX = cropBase[0];
            baseY = cropBase[1];
        } else if (Math.abs(RAW_W - 1440) <= 120) {
            baseX = RAW_X;
            baseY = 0;
        } else {
            baseX = RAW_X >= 480 ? 960 : 0;
            baseY = 0;
        }
        int dx = pane.left - baseX;
        int dy = pane.top - baseY;
        if (dx == 0 && dy == 0) return;
        int nx = clamp(RAW_X + dx, 0, VD_W - RAW_W);
        int ny = clamp(RAW_Y + dy, 0, VD_H - RAW_H);
        SRC_X = nx;
        SRC_Y = ny;
        String sig = pane.left + "," + pane.top + " base=" + baseX + "," + baseY
                + " crop=" + RAW_X + "," + RAW_Y + " -> " + nx + "," + ny;
        if (!sig.equals(paneLogged)) {
            paneLogged = sig;
            say("crop follow pane " + sig);
        }
    }

    static int clamp(int v, int lo, int hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 解析 "[960,0][2400,1080]" / "960,0,2400,1080" 这类四段文本 */
    static int[] parseInts(String s, int n) {
        try {
            String[] p = s.replace("[", " ").replace("]", " ").replace(",", " ").trim()
                    .split("\\s+");
            if (p.length < n) return null;
            int[] v = new int[n];
            for (int i = 0; i < n; i++) v[i] = Integer.parseInt(p[i]);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    static Rect parseRect(String s) {
        int[] v = parseInts(s, 4);
        if (v == null) return null;
        if (v[2] <= v[0] || v[3] <= v[1]) return null;
        return new Rect(v[0], v[1], v[2], v[3]);
    }

    static String readText(File f) {
        try {
            if (!f.exists()) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // 裁切热重载：改 vdcrop.txt 后无需重启。不能用 mtime 判断——adb push 后时间戳可能不变。
    static String cropText = null;

    static void maybeReloadCrop() {
        try {
            File f = new File(CROP_FILE);
            if (!f.exists()) return;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim();
            // base 也要一起看：窗口换边后重选同一条裁切，crop 文本不变，只有 base 变
            String b = readText(new File(CROP_BASE_FILE));
            if (s.equals(cropText) && b.equals(baseText)) return;
            cropText = s;
            baseText = b;
            loadCrop();
            say("crop reload " + s + (b.isEmpty() ? "" : " base=" + b.replace("\n", " | ")));
            rebuildArgs();
        } catch (Throwable ignored) { }
    }

    static void launchChild(int w, int h, int fps) {
        try {
            String cmd = "CLASSPATH=" + DEX
                    + " setsid app_process /system/bin com.probe.asx.VdSend --child "
                    + (VDMODE ? "--mode vd --pkg " + TARGET_PKG + " " : "")
                    + w + " " + h + " " + fps
                    + " > " + LOG + " 2>&1 &";
            new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
        } catch (Throwable t) {
            System.out.println("VDS| launchChild FAIL " + t);
        }
    }

    // ---------- 发送侧状态 ----------
    static Socket sock;
    static OutputStream out;
    static byte[] compBuf;
    static byte[] frame565;
    static ByteBuffer pxBuf;
    static byte[] px;

    // 静止画面不发帧会让仪表端 i/o timeout 掉线，缓存最后一帧压缩结果按需保活重发。
    static final Object TX = new Object();
    static int lastCompLen = 0, lastRawLen = 0;
    static long lastSendAt = 0;
    static volatile boolean haveFrame = false;

    // 采集 -> 发送 的单槽队列（覆盖式，永远只发最新帧）
    static final Object TXQ = new Object();
    static byte[] txBuf565;
    static int txLen = 0, txSeq = 0;

    // ---------- 本地预览：APK 连 127.0.0.1 看实时画面 ----------
    //
    // 只是把已经采集好的帧多播一份，不额外截屏、不额外缩放，所以成本接近 0。
    // 走回环发原始 RGB565（不压缩）：省钱在车机 CPU，APK 侧也不用解压，
    // 550x384 一帧 422KB，10fps 才 4MB/s，回环完全吃得下。
    // 没有客户端连接时整条路径直接返回（不出帧、不拷贝）。
    static int previewPort = 0;
    static final java.util.List<PreviewClient> pvClients =
            new java.util.concurrent.CopyOnWriteArrayList<PreviewClient>();
    static volatile int pvSent = 0;

    static final class PreviewClient {
        Socket sock;
        OutputStream out;

        PreviewClient(Socket s, OutputStream o) { sock = s; out = o; }

        void close() {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
            try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
        }
    }

    // 预览队列同样是覆盖式的单槽：APK 卡一下只丢帧，绝不拖慢给仪表的主链路
    static final Object PVQ = new Object();
    static byte[] pvBuf;
    static int pvLen = 0, pvSeq = 0;

    static void startPreviewServer() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.ServerSocket ss = new java.net.ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress("127.0.0.1", previewPort), 2);
                    say("preview listen 127.0.0.1:" + previewPort);
                    while (true) {
                        try {
                            Socket s = ss.accept();
                            s.setTcpNoDelay(true);
                            s.setSendBufferSize(1 << 18);
                            pvClients.add(new PreviewClient(s, s.getOutputStream()));
                            say("preview client in n=" + pvClients.size());
                        } catch (Throwable e) {
                            say("preview accept FAIL " + e);
                            sleep(300);
                        }
                    }
                } catch (Throwable e) {
                    say("preview listen FAIL " + e);
                }
            }
        }, "vds-preview-srv");
        t.setDaemon(true);
        t.start();

        Thread w = new Thread(new Runnable() {
            public void run() { previewLoop(); }
        }, "vds-preview-tx");
        w.setDaemon(true);
        w.start();
    }

    /**
     * 采集侧调用：把当前帧塞进预览单槽（有客户端才拷）。
     *
     * 不按时间节流：主链路本来就是 30fps，再拿 33ms 边界去卡它，抖一下就被砍掉，
     * 实测只能出 25fps。真正需要限流时由 previewLoop 的「最新帧覆盖」自然丢帧。
     */
    static void offerPreview(byte[] buf, int len) {
        if (pvClients.isEmpty()) return;
        synchronized (PVQ) {
            if (pvBuf == null || pvBuf.length < len) pvBuf = new byte[len];
            System.arraycopy(buf, 0, pvBuf, 0, len);
            pvLen = len;
            pvSeq++;
        }
    }

    static void previewLoop() {
        int lastSeq = -1;
        byte[] snap = null;
        byte[] hdr = new byte[24];
        hdr[0] = 'I'; hdr[1] = 'C'; hdr[2] = 'F'; hdr[3] = '1';
        putInt(hdr, 12, 0);              // fmt=0 原始 RGB565
        while (true) {
            try {
                int len = 0, seq;
                synchronized (PVQ) {
                    if (pvSeq == lastSeq || pvBuf == null) {
                        seq = lastSeq;
                    } else {
                        if (snap == null || snap.length < pvBuf.length) snap = new byte[pvBuf.length];
                        System.arraycopy(pvBuf, 0, snap, 0, pvLen);
                        len = pvLen;
                        seq = pvSeq;
                    }
                }
                if (seq == lastSeq || snap == null || len == 0) {
                    Thread.sleep(4);
                    continue;
                }
                lastSeq = seq;
                putInt(hdr, 4, OUT_W);
                putInt(hdr, 8, OUT_H);
                putInt(hdr, 16, len);
                putInt(hdr, 20, len);
                for (PreviewClient c : pvClients) {
                    try {
                        c.out.write(hdr);
                        c.out.write(snap, 0, len);
                        c.out.flush();
                        pvSent++;
                    } catch (Throwable e) {
                        pvClients.remove(c);
                        c.close();
                        say("preview client out n=" + pvClients.size());
                    }
                }
            } catch (Throwable t) {
                try { Thread.sleep(50); } catch (Throwable ignored) { }
            }
        }
    }

    // 调裁切参数用：touch /data/local/tmp/vdcap 触发存一张 PNG
    static volatile boolean dumpWanted = false;
    static final String CAP_TRIG = "/data/local/tmp/vdcap";
    static final String CAP_PNG = "/sdcard/vdcap.png";

    // ---------- 虚拟屏模式：建屏 / 搬窗口 / 建影子 display / 恢复 ----------

    static Object shadowReader;

    /** app_process 的 uid 是 2000，必须用 com.android.shell 的包上下文建虚拟屏，否则 DMS 拒绝 */
    static android.content.Context shellContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        android.content.Context sys =
                (android.content.Context) at.getMethod("getSystemContext").invoke(thread);
        return sys.createPackageContext("com.android.shell",
                android.content.Context.CONTEXT_IGNORE_SECURITY);
    }

    static android.media.ImageReader newReader(int w, int h, android.os.HandlerThread ht) {
        final android.media.ImageReader ir =
                android.media.ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1);
        ir.setOnImageAvailableListener(new android.media.ImageReader.OnImageAvailableListener() {
            public void onImageAvailable(android.media.ImageReader r) {
                android.media.Image img = null;
                try { img = r.acquireLatestImage(); }
                catch (Throwable ignored) { }
                finally { if (img != null) try { img.close(); } catch (Exception ignored) { } }
            }
        }, new android.os.Handler(ht.getLooper()));
        return ir;
    }

    /**
     * 建虚拟屏 -> 把目标窗口搬上去 -> 自建 shadow display（layerStack 指向虚拟屏）-> 返回 token。
     * 返回的 token 可以直接喂给 DisplayCaptureArgs.Builder。
     */
    static android.os.IBinder setupVirtualScreen(Class<?> sc) throws Exception {
        android.content.Context pkg = shellContext();
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager)
                        pkg.getSystemService(android.content.Context.DISPLAY_SERVICE);

        android.os.HandlerThread ht = new android.os.HandlerThread("vddrain");
        ht.start();

        // 虚拟屏自身的输出面：只为让 display 处于可合成状态，实测从不产生帧
        android.media.ImageReader ir = newReader(VD_W, VD_H, ht);
        android.hardware.display.VirtualDisplay vd = dm.createVirtualDisplay(
                "asxvd", VD_W, VD_H, 160, ir.getSurface(), 0x9 /* PUBLIC|OWN_CONTENT_ONLY */);
        if (vd == null) throw new RuntimeException("createVirtualDisplay null");
        vdObj = vd;
        vdReader = ir;
        vdDisplayId = vd.getDisplay().getDisplayId();
        say("VD created id=" + vdDisplayId + " state=" + vd.getDisplay().getState()
                + " " + VD_W + "x" + VD_H);

        int task = findRootTask(TARGET_PKG);
        if (task <= 0) {
            // 双保险：APK 侧按理已经拉起过了，但应用可能被系统回收 / 用户从后台清掉。
            // 这里自己再拉一次，避免整个 vd 流程因为「装了但没运行」直接抛异常退出。
            say("task not found, launching " + TARGET_PKG);
            exec("monkey -p " + TARGET_PKG + " -c android.intent.category.LAUNCHER 1");
            for (int i = 0; i < 8 && task <= 0; i++) {
                sleep(1000);
                task = findRootTask(TARGET_PKG);
            }
        }
        if (task <= 0) throw new RuntimeException("task not found: " + TARGET_PKG);
        targetTaskId = task;
        int cur = taskDisplayOf(task);
        say("target task=" + task + " comp=" + targetComponent + " curDisplay=" + cur
                + " bounds=" + taskBounds(task));
        // 上次异常退出可能把它留在已销毁的虚拟屏上，先搬回主屏并恢复栏位。
        if (cur > 0) {
            say("task on display " + cur + " -> move back to 0 and restore pane");
            moveTask(task, 0);
            // B5：同 restoreOnly 路径，把固定 sleep(1200) 换成一次核实
            for (int i = 0; i < 5 && taskDisplayOf(task) != 0; i++) sleep(200);
            restorePane(null);
        }
        // 记住搬走前窗口在哪一栏：停止时按它还原；搬移不改布局，这个 bounds 就是
        // 目标窗口在虚拟屏上的落点。左栏/右栏都认，只有全屏/错位才用右栏兜底。
        String b0 = taskBounds(task);
        Rect paneTarget = paneOf(b0);
        if (paneTarget == null) paneTarget = new Rect(960, 0, 2400, 1080);
        writeOrig(paneTarget);
        if (!moveTask(task, vdDisplayId)) {
            throw new RuntimeException("move-stack failed " + task + " -> " + vdDisplayId);
        }
        String bb = taskBounds(task);
        say("task " + task + " moved to display " + vdDisplayId + " bounds=" + bb
                + " want=" + paneTarget);
        // 搬移本身不改布局，但若落点是全屏（例如窗口原本不是 2/3 栏）就 resize 回目标栏位
        if (paneOf(bb) == null) {
            say("bounds not a 2/3 pane -> resize to " + paneTarget);
            resizeTask(task, paneTarget.left, paneTarget.top, paneTarget.right, paneTarget.bottom);
            for (int i = 0; i < 6; i++) {
                sleep(200);
                bb = taskBounds(task);
                if (paneOf(bb) != null) break;
            }
            say("bounds after resize=" + bb);
        }
        // 最终落点决定裁切框往左还是往右平移；识别不出来就保持默认右栏行为
        paneRect = paneOf(bb);
        if (paneRect == null) {
            say("pane unknown (bounds=" + bb + "), crop not shifted");
        } else {
            say("pane follow " + paneRect.left + "," + paneRect.top
                    + (paneRect.left < 480 ? " (left)" : " (right)"));
        }
        applyCropShift();

        // DMS 不暴露虚拟屏的 token，只能自建一个 shadow display 共享它的 layerStack
        android.os.IBinder tok = (android.os.IBinder) sc.getMethod("createDisplay",
                String.class, boolean.class).invoke(null, "asxvdshadow", false);
        if (tok == null) throw new RuntimeException("createDisplay null");
        shadowTok = tok;

        android.media.ImageReader sir = newReader(VD_W, VD_H, ht);
        shadowReader = sir;

        // 必须用显式 Transaction：静态 setDisplaySurface 依赖全局 transaction，在 app_process 里是 null（NPE）
        Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
        Object tx = txCls.getConstructor().newInstance();
        txCls.getMethod("setDisplaySurface", android.os.IBinder.class, android.view.Surface.class)
                .invoke(tx, tok, sir.getSurface());
        // 尺寸必须与虚拟屏一致：缩放投影不生效，影子只做 1:1 裁切
        txCls.getMethod("setDisplayProjection", android.os.IBinder.class, int.class,
                        Rect.class, Rect.class)
                .invoke(tx, tok, 0, new Rect(0, 0, VD_W, VD_H), new Rect(0, 0, VD_W, VD_H));
        txCls.getMethod("setDisplayLayerStack", android.os.IBinder.class, int.class)
                .invoke(tx, tok, vdDisplayId);
        txCls.getMethod("apply").invoke(tx);
        say("shadow display ready layerStack=" + vdDisplayId);
        return tok;
    }

    /**
     * 恢复成 2/3 栏。
     *
     * want 非空：停止投屏时用，尽量回到投屏前所在的那一栏（左/右都认）；
     * want 为空：启动前清理残留时用，只要已经在左栏或右栏就不动。
     * 两种情况都幂等，不会把用户正在用的窗口顶到前台。
     */
    static void restorePane(Rect want) {
        if (targetComponent == null) return;
        dropStackCache();
        String b = taskBounds(targetTaskId);
        Rect cur = paneOf(b);
        if (cur != null && (want == null || sameSide(cur, want))) {
            say("pane already ok bounds=" + b);
            if (want != null) clearOrig();
            return;
        }
        exec("am start -n " + targetComponent + " --windowingMode 5");
        dropStackCache();
        for (int i = 0; i < 6; i++) {
            sleep(200);
            b = taskBounds(targetTaskId);
            cur = paneOf(b);
            if (cur != null && (want == null || sameSide(cur, want))) break;
        }
        say("pane after freeform bounds=" + b);
        if (cur == null || (want != null && !sameSide(cur, want))) {
            // 投屏前记下的栏位优先；没有记录就按右栏兜底（兼容旧版本/首次升级）
            Rect t = want != null ? want : origTarget();
            resizeTask(targetTaskId, t.left, t.top, t.right, t.bottom);
            for (int i = 0; i < 6; i++) {
                sleep(200);
                b = taskBounds(targetTaskId);
                cur = paneOf(b);
                if (cur != null && (want == null || sameSide(cur, want))) break;
            }
            say("pane after resize bounds=" + b);
        }
        if (cur != null && (want == null || sameSide(cur, want))) clearOrig();
    }

    /** 左栏/右栏判定：x 原点小于 480 视为左栏 */
    static boolean sameSide(Rect a, Rect b) {
        return (a.left < 480) == (b.left < 480);
    }

    /** 投屏前目标窗口的栏位，停止时按它还原 */
    static void writeOrig(Rect r) {
        exec("echo '" + r.left + "," + r.top + "," + r.right + "," + r.bottom
                + "' > " + ORIG_FILE);
    }

    /** 读投屏前栏位；没有记录时按右栏兜底 */
    static Rect origTarget() {
        Rect r = parseRect(readText(new File(ORIG_FILE)));
        return r != null ? r : new Rect(960, 0, 2400, 1080);
    }

    static void clearOrig() {
        try { new File(ORIG_FILE).delete(); } catch (Throwable ignored) { }
    }

    /** 读 RootTask 的 bounds 字符串，如 [960,0][2400,1080] */
    static String taskBounds(int task) {
        String out = stackList();
        String key = "RootTask id=" + task + " ";
        for (String ln : out.split("\n")) {
            String s = ln.trim();
            if (s.startsWith(key)) {
                int i = s.indexOf("bounds=");
                if (i >= 0) return s.substring(i + 7).split(" ")[0];
            }
        }
        return "?";
    }

    /**
     * 解析 2/3 栏的 Rect。小鹏的 2/3 窗口可以左右切换，所以两种 x 原点都认；
     * 全屏 [0,0][2400,1200]、1/3 左栏、以及正在拖动中的中间态都返回 null。
     */
    static Rect paneOf(String b) {
        int[] v = parseInts(b, 4);
        if (v == null) return null;
        int x0 = v[0], y0 = v[1], x1 = v[2], y1 = v[3];
        int w = x1 - x0, h = y1 - y0;
        if (h < 1000 || h > 1200) return null;
        if (w < 1200 || w > 1700) return null;
        if (!((x0 >= -20 && x0 <= 220) || (x0 >= 780 && x0 <= 1120))) return null;
        if (y0 < -20 || y0 > 220) return null;
        return new Rect(x0, y0, x1, y1);
    }

    /**
     * `am stack list` 的短缓存。
     *
     * B5：findRootTask / taskDisplayOf / taskBounds 以前各起一个 `am` 进程（每次约 0.4s），
     * 一次恢复要 5~6 次。同一个瞬间的 dump 内容是一样的，缓存 800ms 就够，
     * 搬移/改尺寸之后必须 dropStackCache()。
     */
    static String stackCache;
    static long stackAtMs;

    static String stackList() {
        long now = System.currentTimeMillis();
        if (stackCache != null && now - stackAtMs < 800) return stackCache;
        stackCache = exec("am stack list");
        stackAtMs = now;
        return stackCache;
    }

    static void dropStackCache() {
        stackCache = null;
    }

    static void resizeTask(int task, int l, int t, int r, int b) {
        say("resize Task -> " + exec("am task resize " + task + " " + l + " " + t + " " + r + " " + b)
                .trim());
        dropStackCache();
    }

    /** 从 am stack list 里找包名所在的 RootTask，顺便记下组件名 */
    static int findRootTask(String pkg) {
        String out = stackList();
        int curRoot = -1;
        for (String ln : out.split("\n")) {
            String s = ln.trim();
            if (s.startsWith("RootTask id=")) {
                curRoot = numAt(s, "RootTask id=".length());
            } else if (curRoot > 0 && s.contains(pkg)) {
                int p = s.indexOf(pkg);
                String rest = s.substring(p);
                int sp = rest.indexOf(' ');
                targetComponent = sp > 0 ? rest.substring(0, sp) : rest;
                return curRoot;
            }
        }
        return -1;
    }

    static int taskDisplayOf(int task) {
        String out = stackList();
        String key = "RootTask id=" + task + " ";
        for (String ln : out.split("\n")) {
            String s = ln.trim();
            if (s.startsWith(key)) {
                int i = s.indexOf("displayId=");
                if (i >= 0) return numAt(s, i + "displayId=".length());
            }
        }
        return -1;
    }

    static boolean moveTask(int task, int display) {
        for (int i = 0; i < 4; i++) {
            String r = exec("am display move-stack " + task + " " + display);
            dropStackCache();
            sleep(1500);
            int cur = taskDisplayOf(task);
            if (cur == display) return true;
            say("move-stack try " + (i + 1) + " cur=" + cur + " r=" + r.trim());
        }
        return false;
    }

    /** 幂等恢复：搬回主屏、还原 2/3 栏位、销毁 shadow display、释放虚拟屏 */
    static void restoreAll() {
        synchronized (RESTORED) {
            if (restored) return;
            restored = true;
        }
        say("RESTORE start");
        if (targetTaskId > 0) {
            try {
                exec("am display move-stack " + targetTaskId + " 0");
                sleep(2000);
                // 搬回主屏可能变成全屏，restorePane 会按投屏前的栏位还原（左/右都认）
                restorePane(origTarget());
                say("RESTORE task " + targetTaskId + " -> display 0");
            } catch (Throwable t) { say("RESTORE task FAIL " + t); }
        }
        try {
            if (shadowTok != null) {
                Class.forName("android.view.SurfaceControl")
                        .getMethod("destroyDisplay", android.os.IBinder.class)
                        .invoke(null, shadowTok);
                say("RESTORE shadow destroyed");
            }
        } catch (Throwable t) { say("RESTORE shadow FAIL " + t); }
        try {
            if (vdObj != null) {
                ((android.hardware.display.VirtualDisplay) vdObj).release();
                say("RESTORE vd released");
            }
        } catch (Throwable t) { say("RESTORE vd FAIL " + t); }
        say("RESTORE done");
    }

    static String exec(String cmd) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        java.io.InputStream is = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            is = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n));
        } catch (Throwable t) {
            return "";
        } finally {
            if (is != null) try { is.close(); } catch (Throwable ignored) { }
            if (p != null) {
                try { p.getOutputStream().close(); } catch (Throwable ignored) { }
                try { p.getErrorStream().close(); } catch (Throwable ignored) { }
                try { p.waitFor(); } catch (Throwable ignored) { }
            }
        }
        return sb.toString();
    }

    static int numAt(String s, int from) {
        int i = from;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
        try { return Integer.parseInt(s.substring(i, j)); } catch (Exception e) { return -1; }
    }

    static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ---------- captureDisplay 反射句柄 ----------
    static Object capBuilder, capArgs;
    static java.lang.reflect.Method mCap, mBuild, mSetCrop, mSetSize, mSetFmt;

    static void initCapture() throws Exception {
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Object token;
        if (VDMODE) {
            token = setupVirtualScreen(sc);
        } else {
            token = sc.getMethod("getInternalDisplayToken").invoke(null);
            setupMainPane();
        }
        if (token == null) throw new RuntimeException("no display token");
        Class<?> bld = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
        capBuilder = bld.getConstructor(android.os.IBinder.class).newInstance(token);
        mSetCrop = bld.getMethod("setSourceCrop", Rect.class);
        mSetSize = bld.getMethod("setSize", int.class, int.class);
        // 让 SF 直接出 RGB565：省掉 4 字节/px 的读回和 Java 侧 32->16 转换（约 8~12ms/帧）
        try {
            mSetFmt = bld.getMethod("setPixelFormat", int.class);
            say("setPixelFormat available");
        } catch (Throwable t) {
            mSetFmt = null;
            say("no setPixelFormat -> ARGB8888 path only");
        }
        mBuild = bld.getMethod("build");
        Class<?> argsCls = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
        mCap = sc.getMethod("captureDisplay", argsCls);
        say("capture ready (" + (VDMODE ? "vd display " + vdDisplayId : "main display") + ")");
        rebuildArgs();
    }

    /**
     * 主屏模式：目标窗口在主屏上也可能被切到左/右 2/3 栏，同样要平移裁切。
     * 找不到窗口就维持默认行为（不平移）。
     */
    static void setupMainPane() {
        int t = findRootTask(TARGET_PKG);
        if (t <= 0) {
            say("main mode: target task not found, crop not shifted");
            return;
        }
        targetTaskId = t;
        Rect p = paneOf(taskBounds(t));
        if (p != null) {
            paneRect = p;
            say("main pane follow " + p.left + "," + p.top);
            applyCropShift();
        } else {
            say("main mode: target not in a 2/3 pane, crop not shifted");
        }
    }

    /** 运行中窗口被换到另一侧时，裁切跟着平移（5 秒一次，够跟上手动切换） */
    static void pollPane() {
        if (targetTaskId <= 0) return;
        Rect p = paneOf(taskBounds(targetTaskId));
        if (p == null) return;
        if (p.equals(paneRect)) return;
        paneRect = p;
        applyCropShift();
        rebuildArgs();
    }

    // 只在裁切区变化时重建，不要每帧重建——每帧重建还会顺带写一行日志，
    // 30fps 下光日志 IO 就能吃掉几毫秒（旧版本 vdsend.log 里 19000 条 args crop 就是它）。
    static void rebuildArgs() {
        try {
            Rect r = cropForOut();
            mSetCrop.invoke(capBuilder, r);
            mSetSize.invoke(capBuilder, OUT_W, OUT_H);
            if (mSetFmt != null) {
                try { mSetFmt.invoke(capBuilder, PixelFormat.RGB_565); } catch (Throwable ignored) { }
            }
            capArgs = mBuild.invoke(capBuilder);
            say("args crop=" + r + " size=" + OUT_W + "x" + OUT_H + " fmt=" + (mSetFmt != null ? "565?" : "argb"));
        } catch (Throwable t) {
            say("rebuildArgs FAIL " + t);
        }
    }

    /**
     * 真正喂给 captureDisplay 的源区域。
     *
     * SRC 是用户框出来的「要投的内容」，OUT_W/OUT_H 是「仪表上那块矩形有多大」，
     * 两者比例不一致时 setSize 会把画面压扁。所以这里以裁切框中心为锚点等比调整：
     * 能扩就扩（多看到一点内容，不丢东西），扩到屏幕边界再反向收缩。
     * APK 的框选页有「锁比例」，正常情况下这一步什么都不做。
     */
    static Rect cropForOut() {
        int cx0 = SRC_X, cy0 = SRC_Y, w = SRC_W, h = SRC_H;
        int nx = cx0, ny = cy0, nw = w, nh = h;
        if (OUT_W > 0 && OUT_H > 0 && w > 0 && h > 0) {
            double want = (double) OUT_W / OUT_H;
            double cur = (double) w / h;
            if (Math.abs(want - cur) / cur > 0.01) {
                int cx = cx0 + w / 2, cy = cy0 + h / 2;
                nw = w;
                nh = (int) Math.round(w / want);
                if (nh > VD_H) { nh = VD_H; nw = (int) Math.round(nh * want); }
                if (nw > VD_W) { nw = VD_W; nh = (int) Math.round(nw / want); }
                if (nw < 16) nw = 16;
                if (nh < 16) nh = 16;
                nx = cx - nw / 2;
                ny = cy - nh / 2;
                if (nx < 0) nx = 0;
                if (ny < 0) ny = 0;
                if (nx + nw > VD_W) nx = VD_W - nw;
                if (ny + nh > VD_H) ny = VD_H - nh;
                if (nx < 0) nx = 0;
                if (ny < 0) ny = 0;
                String sig = nx + "," + ny + "," + nw + "x" + nh;
                if (!sig.equals(fitLogged)) {
                    fitLogged = sig;
                    say("crop fit " + cx0 + "," + cy0 + " " + w + "x" + h
                            + " -> " + nx + "," + ny + " " + nw + "x" + nh
                            + " (out " + OUT_W + "x" + OUT_H + ")");
                }
            }
        }
        return new Rect(nx, ny, nx + nw, ny + nh);
    }

    static String fitLogged = "";

    static int clampDim(int v, int def) {
        if (v < OUT_MIN || v > OUT_MAX) return def;
        return v;
    }

    // 每帧 ARGB_8888 像素缓冲
    static ByteBuffer dst565;   // frame565 的写视图：copyPixelsToBuffer 直接落进去，省一次整帧中转

    static void ensureBuffers() {
        if (frame565 != null) return;
        int n = OUT_W * OUT_H;
        frame565 = new byte[n * 2];
        compBuf = new byte[LZ4.maxCompressedSize(n * 2)];
        txBuf565 = new byte[n * 2];
        px = new byte[n * 4];
        pxBuf = ByteBuffer.wrap(px);
        dst565 = ByteBuffer.wrap(frame565);
        say("buffers ready out=" + OUT_W + "x" + OUT_H + " rgb565=" + (n * 2) + "B");
    }

    static void runChild(int w, int h) {
        say("child start mode=" + (VDMODE ? "vd pkg=" + TARGET_PKG : "main")
                + " out=" + OUT_W + "x" + OUT_H + " pid=" + android.os.Process.myPid()
                + " crop=" + SRC_X + "," + SRC_Y + " " + SRC_W + "x" + SRC_H
                + " -> " + HOST + ":" + PORT + " targetFps=" + targetFps
                + (previewPort > 0 ? " preview=127.0.0.1:" + previewPort : ""));
        try {
            android.os.Looper.prepareMainLooper();
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("systemMain").invoke(null);
                at.getMethod("getSystemContext").invoke(thread);
            } catch (Throwable t) {
                say("systemMain FAIL " + t);
            }

            if (VDMODE) {
                // SIGTERM 时把窗口搬回去、销毁虚拟屏；kill -9 走不到这里，靠 --restore 兜底
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    public void run() { restoreAll(); }
                }, "vds-restore"));
                say("shutdown hook registered");
            }

            ensureBuffers();
            if (previewPort > 0) startPreviewServer();
            try {
                initCapture();
            } catch (Throwable t) {
                if (VDMODE) {
                    say("VD init FAIL " + t);
                    t.printStackTrace();
                    restoreAll();
                    return;
                }
                useSF = false;
                say("SF init FAIL " + t + " -> screencap only");
            }

            Thread tx = new Thread(new Runnable() {
                public void run() { txLoop(); }
            }, "vdsend-tx");
            tx.setDaemon(true);
            tx.start();
            say("tx thread started");

            long last = SystemClock.elapsedRealtime();
            long lastF = 0, lastB = 0;
            int tick = 0;
            final long gap = Math.max(1, 1000 / Math.max(1, targetFps));
            while (true) {
                long now0 = SystemClock.elapsedRealtime();
                // 裁切热重载每 15 帧（约 0.5s）查一次，不必每帧都读文件
                if ((tick++ % 15) == 0) {
                    maybeReloadCrop();
                    // 运行中窗口被系统换边：5 秒查一次，跟上手动切换
                    long nowMs = SystemClock.elapsedRealtime();
                    if (VDMODE && nowMs - lastPanePollMs > 5000) {
                        lastPanePollMs = nowMs;
                        pollPane();
                    }
                    if (new File(CAP_TRIG).exists()) {
                        try { new File(CAP_TRIG).delete(); } catch (Throwable ignored) { }
                        dumpWanted = true;
                        say("CAP armed");
                    }
                }
                captureOnce();
                long now1 = SystemClock.elapsedRealtime();
                if (now1 - last >= 2000) {
                    long f = frames, b = sentBytes;
                    lastFps = (int) ((f - lastF) * 1000 / Math.max(1, now1 - last));
                    long avgProc = nProcMs > 0 ? sumProcMs / nProcMs : 0;
                    sumProcMs = 0; nProcMs = 0;
                    say("fps=" + lastFps + " cap=" + capFrames + " proc=" + f
                            + " sent=" + sentFrames
                            + " bytes/s=" + ((b - lastB) * 1000 / Math.max(1, now1 - last))
                            + " procMs=" + avgProc + " lastComp=" + lastCompLen);
                    last = now1; lastF = f; lastB = b;
                }
                long used = SystemClock.elapsedRealtime() - now0;
                if (used < gap) {
                    try { Thread.sleep(gap - used); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            say("FAIL " + t);
            t.printStackTrace();
        }
    }

    static volatile long nullRes = 0, nullBmp = 0;
    static boolean fmtLogged = false;

    // screencap 兜底路径的缓冲
    static byte[] fullBuf;
    static int fullW, fullH;

    static boolean useSF = true;   // true=走 captureDisplay（30fps），false=退回 screencap
    static int sfFail = 0;

    static int leInt(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    static void readFully(java.io.InputStream is, byte[] dst, int off, int len) throws java.io.IOException {
        int got = 0;
        while (got < len) {
            int n = is.read(dst, off + got, len - got);
            if (n < 0) throw new java.io.EOFException("short read " + got + "/" + len);
            got += n;
        }
    }

    static void commitFrame(int bytes, long capMs, long convMs) {
        capFrames++;
        frames++;
        lastCapMs = capMs;
        lastCopyMs = convMs;
        lastProcMs = capMs + convMs;
        sumProcMs += lastProcMs;
        nProcMs++;
        synchronized (TXQ) {
            System.arraycopy(frame565, 0, txBuf565, 0, bytes);
            txLen = bytes;
            txSeq++;
        }
    }

    /**
     * 主路径：SurfaceControl.captureDisplay。
     * 裁剪 1440x426 + setSize(1920,568) 时 capture 仅 2~5ms，读回 6~7ms。
     *
     * 注意：在售后域(u:r:xpaftersalesd)里同一个进程调用约 330 次后 SF 就恒回 null；
     * 用 shell 域启动则 1500 次全成功。所以进程必须由 `adb shell` 拉起。
     */
    static boolean captureViaSF() {
        long t0 = SystemClock.elapsedRealtime();
        Bitmap hw = null, soft = null;
        Object hb = null;
        try {
            // capArgs 在 initCapture / 裁切区变化时才重建，这里直接用（每帧重建纯属浪费）
            Object res = mCap.invoke(null, capArgs);
            if (res == null) {
                if (++nullRes % 50 == 1) say("capture null x" + nullRes);
                return false;
            }
            nullRes = 0;
            hb = res.getClass().getMethod("getHardwareBuffer").invoke(res);
            hw = (Bitmap) res.getClass().getMethod("asBitmap").invoke(res);
            if (hw == null) return false;
            soft = hw.copy(mSetFmt != null ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888, false);
            if (soft == null) soft = hw.copy(Bitmap.Config.ARGB_8888, false);
            if (soft == null) return false;
            pxBuf.clear();
            soft.copyPixelsToBuffer(pxBuf);
            // touch /data/local/tmp/vdcap 存一张 PNG，用来核对裁切区。
            // 触发文件在采集循环里已先删除，所以只会存一张，不会像旧版本那样每 100ms 卡死采集线程。
            if (dumpWanted) {
                dumpWanted = false;
                dumpSoftPng(soft);
            }
            long t1 = SystemClock.elapsedRealtime();

            final int n = OUT_W * OUT_H;
            final int rb = soft.getRowBytes();
            byte[] src = px, dst = frame565;
            boolean is565 = soft.getConfig() == Bitmap.Config.RGB_565;
            if (!fmtLogged) {
                fmtLogged = true;
                say("capture fmt=" + soft.getConfig() + " rowBytes=" + rb + " (565=" + is565 + ")");
            }
            if (is565 && rb == OUT_W * 2) {
                // 行跨距正好等于 OUT_W*2：copyPixelsToBuffer 直接落进 frame565。
                // 旧路径要「先读到 px → 21 万次逐字节循环搬进 frame565」，
                // 60fps 下这一趟读写就是 3~4ms。现在只剩一次原地扫描。
                dst565.clear();
                soft.copyPixelsToBuffer(dst565);
                // 色键修正（0→1）必须留在发送端：仪表接收端走「满宽直写」时不查像素，
                // 漏过来的 0 会直接变成黑洞。
                for (int sp = 0; sp < n * 2; sp += 2) {
                    if (dst[sp] == 0 && dst[sp + 1] == 0) dst[sp] = 1;
                }
            } else if (is565) {
                // 行跨距带对齐填充：只能逐行读，仍然避免整帧中转
                pxBuf.clear();
                soft.copyPixelsToBuffer(pxBuf);
                int dp = 0;
                for (int y = 0; y < OUT_H; y++) {
                    int sp = y * rb;
                    for (int x = 0; x < OUT_W; x++, sp += 2, dp += 2) {
                        int v = (src[sp] & 0xFF) | ((src[sp + 1] & 0xFF) << 8);
                        if (v == 0) v = 1;
                        dst[dp] = (byte) v;
                        dst[dp + 1] = (byte) (v >> 8);
                    }
                }
            } else {
                // ARGB_8888：每像素 4 字节转 565
                pxBuf.clear();
                soft.copyPixelsToBuffer(pxBuf);
                int dp = 0, sp = 0;
                for (int i = 0; i < n; i++) {
                    int r = src[sp] & 0xFF;
                    int g = src[sp + 1] & 0xFF;
                    int b = src[sp + 2] & 0xFF;
                    int v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
                    if (v == 0) v = 1;   // 避开仪表前景层的色键 key=0
                    dst[dp] = (byte) v;
                    dst[dp + 1] = (byte) (v >> 8);
                    sp += 4; dp += 2;
                }
            }
            long t2 = SystemClock.elapsedRealtime();
            commitFrame(n * 2, t1 - t0, t2 - t1);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (soft != null) try { soft.recycle(); } catch (Throwable ignored) { }
            if (hw != null) try { hw.recycle(); } catch (Throwable ignored) { }
            if (hb instanceof AutoCloseable) {
                try { ((AutoCloseable) hb).close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 兜底路径：fork 一个 screencap 读整屏 raw RGBA，实测 ~85ms/帧。 */
    static void captureViaScreencap() {
        long t0 = SystemClock.elapsedRealtime();
        Process p = null;
        java.io.InputStream is = null;
        try {
            p = new ProcessBuilder("/system/bin/screencap", "-d", "0")
                    .redirectErrorStream(false)
                    .start();
            is = p.getInputStream();
            byte[] hdr = new byte[16];
            readFully(is, hdr, 0, 16);
            int w = leInt(hdr, 0), h = leInt(hdr, 4);
            int fmt = leInt(hdr, 8);
            if (w <= 0 || h <= 0 || w > 8192 || h > 8192) {
                say("screencap bad header " + w + "x" + h + " fmt=" + fmt);
                return;
            }
            int need = w * h * 4;
            if (fullBuf == null || fullBuf.length != need) {
                fullBuf = new byte[need];
                fullW = w; fullH = h;
                say("screencap " + w + "x" + h + " fmt=0x" + Integer.toHexString(fmt));
            }
            readFully(is, fullBuf, 0, need);
            long t1 = SystemClock.elapsedRealtime();

            final int srcRowBytes = w * 4;
            int n = OUT_W * OUT_H;
            int dp = 0;
            byte[] src = fullBuf, dst = frame565;
            for (int y = 0; y < OUT_H; y++) {
                int sy = SRC_Y + (y * SRC_H) / OUT_H;
                int rowBase = sy * srcRowBytes + SRC_X * 4;
                for (int x = 0; x < OUT_W; x++) {
                    int o = rowBase + (x * SRC_W / OUT_W) * 4;
                    int r = src[o] & 0xFF;
                    int g = src[o + 1] & 0xFF;
                    int b = src[o + 2] & 0xFF;
                    int v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
                    if (v == 0) v = 1;
                    dst[dp++] = (byte) v;
                    dst[dp++] = (byte) (v >> 8);
                }
            }
            long t2 = SystemClock.elapsedRealtime();
            commitFrame(n * 2, t1 - t0, t2 - t1);
        } catch (Throwable t) {
            say("capture FAIL " + t);
        } finally {
            // 子进程三个管道各占一个 fd，必须逐个关闭；destroy 后还要 waitFor，
            // 否则 fd / 僵尸累积，最后 ProcessBuilder 直接报 error=13。
            if (is != null) try { is.close(); } catch (Throwable ignored) { }
            if (p != null) {
                try { p.getOutputStream().close(); } catch (Throwable ignored) { }
                try { p.getErrorStream().close(); } catch (Throwable ignored) { }
                try { p.destroy(); } catch (Throwable ignored) { }
                try { p.waitFor(); } catch (Throwable ignored) { }
            }
        }
    }

    static void captureOnce() {
        if (useSF && mCap != null) {
            if (captureViaSF()) { sfFail = 0; return; }
            if (++sfFail >= 5) {
                useSF = false;
                say("SF capture degraded -> screencap fallback");
            }
            // 主屏模式本轮仍用兜底补一帧，避免花屏/断流。
            // 虚拟屏模式不能用 screencap（它只认物理屏），只能等下一帧。
            if (!VDMODE) captureViaScreencap();
            return;
        }
        if (VDMODE) {
            // 虚拟屏模式下 capture 不可用 = 屏幕没了，除了报错没别的办法
            if (++sfFail % 200 == 1) say("vd capture unavailable x" + sfFail);
            sleep(200);
            return;
        }
        captureViaScreencap();
    }

    // 发送线程：只取最新一帧压缩发送，发送慢时自动丢中间帧；无新帧则 250ms 保活重发。
    static void txLoop() {        int lastSeq = -1;
        byte[] snap = null;
        while (true) {
            try {
                byte[] work = null;
                int len = 0;
                synchronized (TXQ) {
                    if (txSeq != lastSeq && txBuf565 != null) {
                        if (snap == null || snap.length != txBuf565.length) {
                            snap = new byte[txBuf565.length];
                        }
                        System.arraycopy(txBuf565, 0, snap, 0, txBuf565.length);
                        len = txLen;
                        lastSeq = txSeq;
                        work = snap;
                    }
                }
                if (work != null) {
                    offerPreview(work, len);
                    synchronized (TX) {
                        if (compBuf == null) {
                            compBuf = new byte[LZ4.maxCompressedSize(len)];
                        }
                        int compLen = LZ4.compress(work, len, compBuf);
                        lastCompLen = compLen;
                        lastRawLen = len;
                        haveFrame = true;
                        sendLocked(compLen, len);
                    }
                } else {
                    long now = SystemClock.elapsedRealtime();
                    if (haveFrame && now - lastSendAt > 250) {
                        synchronized (TX) {
                            if (lastCompLen > 0) sendLocked(lastCompLen, lastRawLen);
                        }
                    }
                    Thread.sleep(10);
                }
            } catch (Throwable t) {
                say("tx FAIL " + t);
                try { Thread.sleep(200); } catch (Throwable ignored) { }
            }
        }
    }

    static void sendLocked(int compLen, int rawLen) {
        try {
            ensureSocket();
            byte[] h = new byte[24];
            h[0] = 'I'; h[1] = 'C'; h[2] = 'F'; h[3] = '1';
            putInt(h, 4, OUT_W);
            putInt(h, 8, OUT_H);
            putInt(h, 12, FMT_LZ4);
            putInt(h, 16, compLen);
            putInt(h, 20, rawLen);
            out.write(h);
            out.write(compBuf, 0, compLen);
            out.flush();
            sentFrames++;
            sentBytes += 24 + compLen;
            lastSendAt = SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            say("send FAIL " + t);
            closeSocket();
        }
    }

    static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    static void ensureSocket() throws Exception {
        if (sock != null && sock.isConnected() && !sock.isClosed()) return;
        closeSocket();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(HOST, PORT), 2000);
        s.setTcpNoDelay(true);
        s.setSendBufferSize(1 << 20);
        sock = s;
        out = s.getOutputStream();
        say("socket connected " + HOST + ":" + PORT);
    }

    static void closeSocket() {
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        out = null;
        sock = null;
    }

    /** 把当前帧存成 PNG，用于核对裁切区是否对准目标窗口 */
    static void dumpSoftPng(Bitmap bmp) {
        try {
            FileOutputStream fo = new FileOutputStream(CAP_PNG);
            bmp.compress(Bitmap.CompressFormat.PNG, 90, fo);
            fo.close();
            say("DUMPED " + CAP_PNG + " crop=" + SRC_X + "," + SRC_Y + "," + SRC_W + "," + SRC_H);
        } catch (Throwable t) {
            say("dumpPng FAIL " + t);
        }
    }

    static void say(String s) {
        System.out.println("VDS| " + s);
        System.out.flush();
    }
}
