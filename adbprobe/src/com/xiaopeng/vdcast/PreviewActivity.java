package com.xiaopeng.vdcast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 投屏应用实时预览：一屏只看 VD 里那块画面。
 *
 * 数据来自发送端（VdSend）在 127.0.0.1 上开的小服务 —— 它本来就已经采好帧了，
 * 预览只是多播一份**未压缩的 RGB565**：车机侧零额外编码，APK 侧零解码，
 * 550x384 一帧 422KB、30fps 也才 12MB/s，回环带宽根本用不完。
 *
 * 渲染走 SurfaceView + 独立绘制线程（不是 ImageView.setImageBitmap）：
 * 后者每帧都要走一遍 View 的 measure/layout/invalidate，1440x1080 的整窗重绘
 * 直接把帧率钉在 7fps；SurfaceView 只往那块 surface 上贴一张图，30fps 轻松。
 *
 * 几个刻意的设计：
 *  1. 只在页面可见时连接（onResume 起、onPause 停），离开预览页立刻断开，
 *     发送端检测到没有客户端就整条预览路径直接返回，不占带宽也不占 CPU。
 *  2. 传输是「最新帧覆盖」：APK 卡一下只丢帧，不会反过来拖慢给仪表的主链路。
 *  3. 三张 Bitmap 轮转：上屏那张和正在写那张永远不是同一张，不用加锁也不会撕裂。
 *  4. 没连上就是没投屏（发送端没跑），提示一下并给个「启动投屏」按钮，
 *     不自己乱起进程 —— 起投屏要连 adbd、推 dex，那是主面板的活。
 */
public class PreviewActivity extends Activity {

    private static final int PORT_DEFAULT = 5570;
    /** 环形缓冲张数：上屏 1 张、在写 1 张、留 1 张余量 */
    private static final int RING = 3;

    private SurfaceView sv;
    private TextView status;
    private volatile boolean running;
    private int port = PORT_DEFAULT;

    /** 裁切信息（用于顶栏文字 + 边框标注），由主面板带过来 */
    private String crop = "", crect = "";

    private final Object lock = new Object();
    private final Bitmap[] ring = new Bitmap[RING];
    private int front = -1;      // 已就绪、可上屏的那张
    private int writeIdx = 0;    // 下一张要写的
    private int frameSeq;        // 每发布一帧 +1，绘制线程据此判断「有没有新东西」
    private int frameW, frameH;
    private int barH, headH;

    private volatile int arriveFps;
    private volatile String hint;

    // 绘制线程和网络线程都会碰，创建一次复用
    // 不做双线性：这台车机的 lockHardwareCanvas 会撞 libhwui 断言直接 SIGABRT
    // （native 层 abort，try/catch 拦不住），只能走软件 canvas；软件双线性放大
    // 550x384 → 1100x760 一帧要 20ms 上下，关掉滤波后只做最近邻，快 5 倍以上。
    // 2 倍整数放大 + 视频画面，最近邻和双线性肉眼几乎分不出来。
    private final Paint bmpPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Thread netThread, drawThread;
    private byte[] padBuf;
    private volatile boolean needClear = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        Intent in = getIntent();
        port = in.getIntExtra("port", PORT_DEFAULT);
        crop = nz(in.getStringExtra("crop"));
        crect = nz(in.getStringExtra("crect"));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0xFF3D8BFD);

        setContentView(buildUi());
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density * 2.4f);
    }

    private android.view.View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070A);

        // SurfaceView 铺满整个窗口，画面几何自己在 canvas 里算（顶部信息条 / 底部按钮条要让开）
        sv = new SurfaceView(this);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { needClear = true; }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { needClear = true; }
            public void surfaceDestroyed(SurfaceHolder h) { }
        });
        root.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        headH = dp(30);
        barH = dp(46);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        status.setTextColor(0xFFC6D2DE);
        status.setBackgroundColor(0xFF0C1117);
        status.setPadding(dp(10), dp(4), dp(10), dp(4));
        status.setText(glyphLine());
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headH);
        slp.gravity = Gravity.TOP;
        root.addView(status, slp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // 右下角是车机语音助手悬浮球，会吃点击；按钮全放左边，右边留空
        bar.setPadding(dp(12), 0, dp(170), 0);
        bar.setBackgroundColor(0xFF0C1117);

        Button reconnect = mkBtn("重连");
        Button startCast = mkBtn("启动投屏");
        Button close = mkBtn("关闭");
        bar.addView(reconnect);
        bar.addView(startCast);
        bar.addView(close);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bar, blp);

        reconnect.setOnClickListener(v -> {
            stopThreads();
            startThreads();
        });
        // 主面板是 singleTask，把「启动 vd」这条遥控指令交给它自己走完整流程
        startCast.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("act", "vd");
            startActivity(i);
            hint = "已请求启动 vd，等画面…";
        });
        close.setOnClickListener(v -> finish());
        return root;
    }

    /** 顶部一行：源裁切 → 仪表矩形，加实时帧率 */
    private String glyphLine() {
        StringBuilder sb = new StringBuilder();
        if (!crop.isEmpty()) sb.append("车机裁切 ").append(crop);
        if (!crect.isEmpty()) {
            if (sb.length() > 0) sb.append("   →   ");
            sb.append("仪表 ").append(crect);
        }
        if (sb.length() > 0) sb.append("     ");
        sb.append(front < 0 ? "等待画面…" : (frameW + "×" + frameH
                + "  " + arriveFps + " fps"));
        return sb.toString();
    }

    private Button mkBtn(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
        b.setTextColor(0xFFE8EEF5);
        b.setBackground(pill(0xFF1D2530, 0xFF243040));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private android.graphics.drawable.GradientDrawable pill(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(9));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startThreads();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开预览就不拉流：发送端发现没客户端后连帧都不拷
        stopThreads();
    }

    private void startThreads() {
        if (running) return;
        running = true;
        needClear = true;
        netThread = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "vds-preview-net");
        netThread.setDaemon(true);
        netThread.start();

        drawThread = new Thread(new Runnable() {
            public void run() { drawLoop(); }
        }, "vds-preview-draw");
        drawThread.setDaemon(true);
        drawThread.start();
    }

    private void stopThreads() {
        running = false;
        Thread a = netThread, b = drawThread;
        netThread = null;
        drawThread = null;
        if (a != null) a.interrupt();
        if (b != null) b.interrupt();
        synchronized (lock) {
            front = -1;
        }
    }

    // ------------------------------------------------------------ 接收

    private void loop() {
        byte[] hdr = new byte[24];
        byte[] data = new byte[0];
        while (running) {
            Socket s = null;
            try {
                hint = "连接 127.0.0.1:" + port + " …";
                s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", port), 1200);
                s.setReceiveBufferSize(1 << 18);
                InputStream in = s.getInputStream();

                int winFrames = 0;
                long winAt = SystemClock.uptimeMillis();
                while (running) {
                    readFully(in, hdr, 24);
                    if (hdr[0] != 'I' || hdr[1] != 'C' || hdr[2] != 'F' || hdr[3] != '1') {
                        throw new java.io.IOException("bad magic");
                    }
                    int w = le(hdr, 4), h = le(hdr, 8), fmt = le(hdr, 12);
                    int payLen = le(hdr, 16);
                    if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || payLen <= 0
                            || payLen > 64 << 20) {
                        throw new java.io.IOException("bad frame " + w + "x" + h + " len=" + payLen);
                    }
                    if (data.length < payLen) data = new byte[payLen];
                    readFully(in, data, payLen);
                    if (fmt != 0) throw new java.io.IOException("fmt " + fmt);

                    Bitmap dst;
                    synchronized (lock) {
                        if (frameW != w || frameH != h) {
                            // 不复用旧尺寸的图，也不 recycle：绘制线程可能还捏着一帧，
                            // 让 GC 收更省心（切尺寸是低频事件）
                            for (int i = 0; i < RING; i++) ring[i] = null;
                            front = -1;
                            writeIdx = 0;
                            frameW = w;
                            frameH = h;
                        }
                        if (ring[writeIdx] == null) {
                            ring[writeIdx] = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                        }
                        dst = ring[writeIdx];
                    }
                    // 拷像素放在锁外：这张是「在写」的，绘制线程永远拿不到它
                    int rb = dst.getRowBytes();
                    if (rb == w * 2) {
                        dst.copyPixelsFromBuffer(ByteBuffer.wrap(data, 0, w * h * 2)
                                .order(ByteOrder.LITTLE_ENDIAN));
                    } else {
                        // 行跨距被对齐过（奇数宽），按行拷一遍
                        int need = rb * h;
                        if (padBuf == null || padBuf.length < need) padBuf = new byte[need];
                        for (int y = 0; y < h; y++) {
                            System.arraycopy(data, y * w * 2, padBuf, y * rb, w * 2);
                        }
                        dst.copyPixelsFromBuffer(ByteBuffer.wrap(padBuf, 0, need));
                    }
                    synchronized (lock) {
                        front = writeIdx;
                        writeIdx = (writeIdx + 1) % RING;
                        frameSeq++;
                    }

                    hint = null;
                    winFrames++;
                    long now = SystemClock.uptimeMillis();
                    if (now - winAt >= 500) {
                        arriveFps = (int) (winFrames * 1000 / Math.max(1, now - winAt));
                        winFrames = 0;
                        winAt = now;
                    }
                }
            } catch (Throwable t) {
                if (!running) break;
                hint = "未连上发送端（投屏没在跑？）  1 秒后重试";
                synchronized (lock) { front = -1; }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
            } finally {
                if (s != null) try { s.close(); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------ 上屏

    private void drawLoop() {
        SurfaceHolder holder = sv.getHolder();
        Rect dst = new Rect();
        Rect dirty = new Rect();
        String lastText = "";
        int lastSeq = -1;

        while (running) {
            if (!holder.getSurface().isValid()) {
                sleep(10);
                continue;
            }
            Bitmap bmp;
            int seq;
            synchronized (lock) {
                bmp = front >= 0 ? ring[front] : null;
                seq = frameSeq;
            }
            if (bmp == null) {
                if (needClear) clearAll(holder);
                sleep(10);
                continue;
            }

            if (seq == lastSeq && !needClear) { sleep(2); continue; }

            int cw = sv.getWidth(), ch = sv.getHeight();
            if (cw <= 0 || ch <= 0) { sleep(10); continue; }

            int availTop = headH + dp(6);
            int availH = ch - availTop - barH - dp(6);
            int availW = cw - dp(16);
            if (availH <= 0 || availW <= 0) { sleep(10); continue; }

            float sc = Math.min(availW / (float) bmp.getWidth(),
                    availH / (float) bmp.getHeight());
            int dw = Math.round(bmp.getWidth() * sc);
            int dh = Math.round(bmp.getHeight() * sc);
            int left = (cw - dw) / 2;
            int top = availTop + (availH - dh) / 2;
            dst.set(left, top, left + dw, top + dh);

            if (needClear) clearAll(holder);

            Canvas c = null;
            try {
                // 只锁画面那一块：整屏 lockCanvas + drawColor 每帧要抹 6MB，
                // 30fps 就是 180MB/s，白白吃掉一两个毫秒
                dirty.set(dst);
                dirty.inset(-dp(3), -dp(3));
                dirty.intersect(0, 0, cw, ch);
                c = holder.lockCanvas(dirty);
                if (c == null) continue;
                c.drawColor(0xFF05070A);
                c.drawBitmap(bmp, null, dst, bmpPaint);
                drawEdge(c, dst);
            } catch (Throwable ignored) {
                needClear = true;
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Throwable ignored) { }
                }
            }

            // 没有新帧就不重绘：空转重绘除了烧 CPU 没有任何意义
            lastSeq = seq;
            // 顶栏文字：状态 / 帧率变了才 setText，别每帧都触发一次布局
            String txt = hint != null ? hint : glyphLine();
            if (!txt.equals(lastText)) {
                lastText = txt;
                final String t2 = txt;
                runOnUiThread(() -> { if (status != null) status.setText(t2); });
            }
            sleep(2);
        }
    }

    /** 画面边缘：一圈细线 + 四个角加粗，一眼看出裁到哪儿 */
    private void drawEdge(Canvas c, Rect r) {
        int w = Math.max(1, dp(1));
        borderPaint.setStrokeWidth(w);
        borderPaint.setColor(0xFF3D8BFD);
        c.drawRect(r.left, r.top, r.right, r.bottom, borderPaint);

        int len = Math.min(r.width(), r.height()) / 8;
        borderPaint.setStrokeWidth(Math.max(2, dp(2)));
        borderPaint.setColor(0xFF7FD1FF);
        // 左上
        c.drawLine(r.left, r.top, r.left + len, r.top, borderPaint);
        c.drawLine(r.left, r.top, r.left, r.top + len, borderPaint);
        // 右上
        c.drawLine(r.right - len, r.top, r.right, r.top, borderPaint);
        c.drawLine(r.right, r.top, r.right, r.top + len, borderPaint);
        // 左下
        c.drawLine(r.left, r.bottom - len, r.left, r.bottom, borderPaint);
        c.drawLine(r.left, r.bottom, r.left + len, r.bottom, borderPaint);
        // 右下
        c.drawLine(r.right, r.bottom - len, r.right, r.bottom, borderPaint);
        c.drawLine(r.right - len, r.bottom, r.right, r.bottom, borderPaint);
    }

    private void clearAll(SurfaceHolder holder) {
        Canvas c = null;
        try {
            c = holder.lockCanvas(null);
            if (c != null) c.drawColor(0xFF05070A);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try { holder.unlockCanvasAndPost(c); } catch (Throwable ignored) { }
            }
        }
        needClear = false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static int le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static void readFully(InputStream in, byte[] dst, int len)
            throws java.io.IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(dst, got, len - got);
            if (n < 0) throw new java.io.EOFException("short read " + got + "/" + len);
            got += n;
        }
    }
}
