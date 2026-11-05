package com.xiaopeng.vdcast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.PrivateKey;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 车机端控制面板：一个 APK 里完成「连 adbd → 授权 → 推 dex → 起两端 → 调裁切 → 停止」。
 *
 * 所有特权动作都不在 app 进程里做，而是把 shell 命令交给 adbd 执行（域为 u:r:shell:s0），
 * 由此绕开「app 域拿不到 captureDisplay / 平台签名权限」的死结。
 */
public class MainActivity extends Activity implements Adb.Log, VdCtl.Log {

    private static final int PORT_DEFAULT = 5050;
    private static final int REQ_CROP = 1001;
    private static final int REQ_CLUSTER = 1002;
    /** 仪表默认投屏区 = 满宽横带，与 icrecv_dbg 的默认值一致 */
    private static final String CRECT_DEFAULT = "0,72,1920,568";
    private static final String PKG_DEFAULT = "com.tencent.qqmusicpad";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView logView;
    private ScrollView logScroll;
    private EditText cropEdit;
    private EditText portEdit;
    private EditText pkgEdit;
    private EditText clusterEdit;
    private Button btnStartVd, btnStartMain, btnStop, btnStatus, btnCrop, btnProbe, btnPick, btnFrame, btnCluster, btnPreview;

    private Adb adb;
    private VdCtl ctl;
    private final StringBuilder logBuf = new StringBuilder();
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        adb = new Adb(this);

        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        cropEdit.setText(sp.getString("crop", "960,345,1440,426"));
        portEdit.setText(sp.getString("port", String.valueOf(PORT_DEFAULT)));
        pkgEdit.setText(sp.getString("pkg", PKG_DEFAULT));
        if (clusterEdit != null) clusterEdit.setText(sp.getString("crect", CRECT_DEFAULT));
        // 流程条不再按 prefs 里有没有 key 打勾：配置存过不等于这次真的可用，
        // 由 refreshUi 按界面上的实际内容推导（见 refreshUiNow）
        refreshUi();

        print("仪表投屏 v2.0");
        print("流程：选应用 → 仪表区域 → 裁切画面 → 启动投屏 → 预览");
        setBusy(false);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handleIntent(it);
    }

    /**
     * 遥控入口：PC 侧一句 am start 就能驱动，不用在车机屏上摸黑点按钮。
     *   am start -n com.xiaopeng.vdcast/.MainActivity --es act connect
     *   --es act vd|main|stop|status|probe|apps|snap   --es pkg <pkg>   --es crop x,y,w,h
     */
    private void handleIntent(Intent it) {
        if (it == null) return;
        String pkg = it.getStringExtra("pkg");
        if (pkg != null && !pkg.isEmpty()) pkgEdit.setText(pkg);
        String crop = it.getStringExtra("crop");
        if (crop != null && !crop.isEmpty()) cropEdit.setText(crop);
        String port = it.getStringExtra("port");
        if (port != null && !port.isEmpty()) portEdit.setText(port);
        String crect = it.getStringExtra("crect");
        if (crect != null && !crect.isEmpty()) clusterEdit.setText(crect);

        String act = it.getStringExtra("act");
        if (act == null || act.isEmpty()) return;
        print("遥控 → " + act);
        switch (act) {
            case "connect": run(() -> {
                if (ensureConn()) print("已连接 " + adb.endpoint);
            });
                break;
            case "apps":    run(this::dumpApps); break;
            case "pick":    run(this::pickApp); break;
            case "vd":      run(() -> startWith("vd")); break;
            case "main":    run(() -> startWith("main")); break;
            case "stop":    run(() -> {
                if (!ensureCtl()) return;
                setPhase(Phase.STOPPING);
                boolean ok = ctl.stop();
                setPhase(ok ? Phase.CONNECTED : Phase.ERROR, ok ? "" : "停止未完成");
                previewOpened = false;
            });
                break;
            case "crop":    run(() -> {
                if (!ensureCtl()) return;
                ctl.setCrop(cropEdit.getText().toString());
                savePrefs();
                refreshUi();
            });
                break;
            case "status":  run(() -> { if (ensureCtl()) print(ctl.status()); }); break;
            case "probe":   run(this::probe); break;
            case "snap":    run(this::frameCrop); break;
            case "cluster": run(this::pickClusterArea); break;
            case "preview": runOnUiThread(this::openPreview); break;
            case "resync":
                // 正常使用不需要：同步状态是进程级的，开 App 第一次启动时自动核对一次。
                // 换过 dex/二进制、又不想杀进程时用这个强制重来。
                VdCtl.resetToolCache();
                print("已清掉工具同步标记，下次「启动」会重新核对并重推");
                break;
            default:        print("!! 未知 act: " + act);
        }
    }

    /** 「QQ音乐」这种口语化名字，比 com.tencent.qqmusicpad 好认；取不到就退回包名 */
    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence lb = pm.getApplicationLabel(ai);
            if (lb != null && lb.length() > 0) return lb + "  (" + pkg + ")";
        } catch (Throwable ignored) { }
        return pkg;
    }

    /** 把候选应用连同窗口信息打到日志里，PC 侧直接读，省得在车机上翻列表 */
    private void dumpApps() {
        if (!ensureCtl()) return;
        try {
            java.util.List<String> apps = ctl.candidateApps();
            print("候选应用 " + apps.size() + " 个：");
            for (String p : apps) {
                VdCtl.TaskInfo t = null;
                try { t = ctl.findTask(p); } catch (Throwable ignored) { }
                print("  " + (t != null ? "● " : "○ ") + p + (t != null ? "   " + t.label() : ""));
            }
        } catch (Throwable t) {
            print("!! 取应用列表失败: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ UI

    // 配色：暗底 + 卡片 + 蓝色强调。车机屏幕夜里很亮，白底面板刺眼；
    // 深色底也让日志和状态文字更耐看。
    private static final int C_BG = 0xFF0E1116;
    private static final int C_CARD = 0xFF161C24;
    private static final int C_STROKE = 0xFF243040;
    private static final int C_TEXT = 0xFFE8EEF5;
    private static final int C_MUTED = 0xFF8A97A6;
    private static final int C_ACCENT = 0xFF2F81F7;
    private static final int C_DANGER = 0xFFE5484D;
    private static final int C_OK = 0xFF3DD68C;
    /** 投屏中但配置已改（改了参数还没重新启动） */
    private static final int C_WARN = 0xFFD29922;

    private LinearLayout root;
    private TextView stateChip;

    // ---------- 顶部流程条 ----------
    // 顺序按「先定仪表投屏区、再按它的宽高比框车机画面」排：投屏区决定投出去多大，
    // 裁切框选要按这个比例锁框，反过来做等于先裁一次再重裁（UX-02）。
    private static final String[] STEP_NAMES =
            {"连接", "选应用", "仪表区域", "裁切画面", "启动投屏", "预览"};
    private final TextView[] stepViews = new TextView[STEP_NAMES.length];
    private final boolean[] stepDone = new boolean[STEP_NAMES.length];

    /**
     * 运行态：整个面板的单一真相源（B2）。
     *
     * 以前流程条是各回调里手工 step(i, true) 打出来的，于是出现三类「界面说谎」：
     *   - onCreate 只按 prefs 里有没有这个 key 就打勾（存过 ≠ 这次能用）
     *   - openPreview() 无条件把「预览」标成完成
     *   - 换了应用 / 改了矩形之后，「启动投屏」还是绿的
     * 现在只维护一个 phase + 界面上的真实配置，其余全部推导（refreshUi）。
     */
    private enum Phase { IDLE, CONNECTED, STARTING, CASTING, STOPPING, ERROR }

    private volatile Phase phase = Phase.IDLE;
    private String lastError = "";
    /** 最近一次成功启动真正投出去的配置快照；与界面不一致就说明「需要重新启动」 */
    private String launchedKey = "";
    private String lastMode = "vd";
    /** 本次「投屏中」期间打开过预览页 */
    private boolean previewOpened;

    private void setPhase(Phase p) { setPhase(p, ""); }

    private void setPhase(Phase p, String err) {
        phase = p;
        lastError = err == null ? "" : err;
        refreshUi();
    }

    private static String keyOf(String pkg, String crop, String crect, String mode) {
        return pkg + "|" + crop + "|" + crect + "|" + mode;
    }

    private void refreshUi() {
        runOnUiThread(this::refreshUiNow);
    }

    /**
     * 一次把「状态药丸 + 流程条 + 按钮可用性」刷到位。
     * 幂等：任何地方状态变了只调它，不做局部改文字。
     */
    private void refreshUiNow() {
        if (stateChip == null) return;
        String pkg = txt(pkgEdit), crop = txt(cropEdit), crect = txt(clusterEdit);
        boolean hasPkg = !pkg.isEmpty();
        boolean hasCrop = crop.matches("\\d+,\\d+,\\d+,\\d+");
        boolean hasCrect = crect.matches("\\d+,\\d+,\\d+,\\d+");
        boolean fresh = phase == Phase.CASTING
                && keyOf(pkg, crop, crect, lastMode).equals(launchedKey);
        // 投屏中，但界面配置已经和投出去的那份不一致 —— 必须重新启动才生效
        boolean stale = phase == Phase.CASTING && !fresh;

        stepDone[0] = phase != Phase.IDLE;
        stepDone[1] = hasPkg;
        stepDone[2] = hasCrect;
        stepDone[3] = hasCrop;
        stepDone[4] = fresh;
        stepDone[5] = fresh && previewOpened;
        int cur = 0;
        while (cur < stepDone.length && stepDone[cur]) cur++;
        paintSteps(cur);

        String s;
        int c;
        switch (phase) {
            case CONNECTED: s = "已连接";   c = C_OK;     break;
            case STARTING:  s = "启动中…";  c = C_ACCENT; break;
            case CASTING:
                if (stale) { s = "投屏中 · 配置已改"; c = C_WARN; }
                else       { s = "投屏中";            c = C_OK; }
                break;
            case STOPPING:  s = "停止中…";  c = C_ACCENT; break;
            case ERROR:     s = "失败";     c = C_DANGER; break;
            default:        s = "待机";     c = C_MUTED;  break;
        }
        stateChip.setText(s);
        stateChip.setTextColor(c);
        stateChip.setBackground(round(0x22000000 | (c & 0xFFFFFF), 0, dp(20)));

        // 按钮：只按「有没有可能成功」禁用，不按状态机猜到的运行态禁用 ——
        // 状态机可能落后于车机（比如车机重启了），「停止」永远留一条手动兜底的路。
        btnStartVd.setEnabled(hasPkg && !busy);
        btnStartMain.setEnabled(hasPkg && !busy);
        btnStop.setEnabled(!busy);
        btnStatus.setEnabled(!busy);
        btnCrop.setEnabled(!busy);
        btnProbe.setEnabled(!busy);
        btnPick.setEnabled(!busy);
        btnFrame.setEnabled(!busy);
        btnCluster.setEnabled(!busy);
        btnPreview.setEnabled(!busy);
    }

    private void paintSteps(int cur) {
        for (int i = 0; i < stepViews.length; i++) {
            TextView t = stepViews[i];
            if (t == null) continue;
            if (stepDone[i]) {
                t.setText("✓ " + STEP_NAMES[i]);
                t.setTextColor(C_OK);
                t.setBackground(round(0xFF10231A, 0xFF1E4A34, dp(18)));
            } else if (i == cur) {
                t.setText((i + 1) + " " + STEP_NAMES[i]);
                t.setTextColor(0xFF9CCBFF);
                t.setBackground(round(0xFF12283E, C_ACCENT, dp(18)));
            } else {
                t.setText((i + 1) + " " + STEP_NAMES[i]);
                t.setTextColor(0xFF66717F);
                t.setBackground(round(0xFF151A21, 0xFF202834, dp(18)));
            }
        }
    }

    private static String txt(EditText e) {
        return e == null ? "" : e.getText().toString().trim();
    }

    private View buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(14), dp(6), dp(14), dp(6));

        // ---------- 顶栏：标题 + 状态 ----------
        // 状态胶囊紧贴标题，不再用 spacer 推到最右：面板窗口铺到屏幕右缘，
        // 最右侧是系统状态栏（铃铛/电量/时间），推过去就被压住看不见了。
        LinearLayout head = row();
        TextView title = text("仪表投屏", 34, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        stateChip = chip("待机", C_MUTED);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = dp(10);
        head.addView(stateChip, chipLp);
        head.addView(spacer());
        root.addView(head);

        // ---------- 流程条（常驻，不随日志滚走） ----------
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout steps = row();
        steps.setPadding(0, dp(4), 0, dp(2));
        for (int i = 0; i < STEP_NAMES.length; i++) {
            TextView st = text("", 20, C_MUTED);
            st.setTypeface(Typeface.DEFAULT_BOLD);
            st.setPadding(dp(9), dp(3), dp(9), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(4);
            st.setLayoutParams(lp);
            steps.addView(st);
            stepViews[i] = st;
        }
        hs.addView(steps);
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 先画一遍空态（refreshUi 会在 onCreate 里补齐真实状态）
        paintSteps(0);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // ---------- 目标应用 ----------
        LinearLayout c1 = card();
        c1.addView(label("目标应用（搬到虚拟屏的第三方应用）"));
        LinearLayout r1 = row();
        pkgEdit = input("com.tencent.qqmusicpad");
        r1.addView(pkgEdit, weight());
        btnPick = mkButton("选择");
        r1.addView(btnPick);
        c1.addView(r1);
        body.addView(c1);

        // ---------- 区域 ----------
        // 顺序有意：先定「投到仪表哪里」，再框「车机取哪块」。
        // 裁切框选按投屏区的宽高比锁比例，反过来排会先裁一次再返工（UX-02）。
        LinearLayout c2 = card();
        c2.addView(label("仪表投屏区域  1920×720 坐标，默认满宽横带"));
        LinearLayout r3 = row();
        clusterEdit = input("0,72,1920,568");
        r3.addView(clusterEdit, weight());
        btnCluster = mkButton("框选");
        r3.addView(btnCluster);
        c2.addView(r3);

        c2.addView(label("车机画面裁切  x, y, w, h（按投屏区比例锁框）"));
        LinearLayout r2 = row();
        cropEdit = input("960,345,1440,426");
        r2.addView(cropEdit, weight());
        btnFrame = mkButton("框选");
        btnCrop = mkButton("应用");
        r2.addView(btnFrame);
        r2.addView(btnCrop);
        c2.addView(r2);
        body.addView(c2);

        // ---------- 操作 ----------
        LinearLayout c3 = card();
        c3.addView(label("投屏控制"));
        LinearLayout r4 = row();
        btnStartVd = mkPrimary("启动 vd");
        btnStartMain = mkButton("启动 main");
        btnStop = mkDanger("停止");
        r4.addView(btnStartVd);
        r4.addView(btnStartMain);
        r4.addView(btnStop);
        c3.addView(r4);

        LinearLayout r5 = row();
        r5.setPadding(0, dp(2), dp(40), 0);
        final Button btnConnect = mkButton("连接");
        btnPreview = mkButton("预览");
        btnStatus = mkButton("状态");
        btnProbe = mkButton("自检");
        r5.addView(btnPreview);
        r5.addView(btnStatus);
        r5.addView(btnProbe);
        r5.addView(btnConnect);
        portEdit = input("5050");
        r5.addView(portEdit, new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        c3.addView(r5);
        body.addView(c3);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        // fillViewport + 显式 MATCH_PARENT：不这么写 ScrollView 会按「无宽度约束」测量子视图，
        // 行内 weight 的输入框会把右边的按钮挤出卡片（第一版就被挤掉半个「框选」）。
        sv.setFillViewport(true);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------- 日志 ----------
        LinearLayout c4 = card();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(122));
        clp.topMargin = dp(8);
        c4.setLayoutParams(clp);
        LinearLayout lh = row();
        lh.addView(label("运行日志"));
        lh.addView(spacer());
        Button clear = mkButton("清屏");
        clear.setOnClickListener(v -> {
            logBuf.setLength(0);
            if (logView != null) logView.setText("");
        });
        lh.addView(clear);
        c4.addView(lh);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFFB7C3D0);
        logView.setBackground(round(0xFF0B0F14, 0, dp(8)));
        logView.setPadding(dp(8), dp(6), dp(8), dp(6));
        logView.setMovementMethod(new ScrollingMovementMethod());
        logScroll.addView(logView);
        c4.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(c4);

        // ---------- 事件 ----------
        // 手改参数也要立刻反映到流程条：改了应用/矩形/裁切而没重新启动，
        // 「启动投屏」应该马上变灰，状态药丸变成「配置已改」
        android.text.TextWatcher cfgWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) { }

            @Override
            public void afterTextChanged(android.text.Editable s) { refreshUi(); }
        };
        pkgEdit.addTextChangedListener(cfgWatcher);
        cropEdit.addTextChangedListener(cfgWatcher);
        clusterEdit.addTextChangedListener(cfgWatcher);

        btnConnect.setOnClickListener(v -> run(() -> {
            if (ensureConn()) print("已连接 " + adb.endpoint);
        }));

        btnStartVd.setOnClickListener(v -> run(() -> startWith("vd")));
        btnStartMain.setOnClickListener(v -> run(() -> startWith("main")));

        btnStop.setOnClickListener(v -> run(() -> {
            // 状态机可能落后于车机（车机重启 / 掉线后语义就错了），所以「停止」
            // 永远可点：没在投也让它真去收一遍尾，而不是靠界面判断直接跳过。
            if (phase != Phase.CASTING) print("当前面板认为没有在投屏，照样执行一遍收尾");
            if (!ensureCtl()) return;
            setPhase(Phase.STOPPING);
            boolean ok = ctl.stop();
            previewOpened = false;
            setPhase(ok ? Phase.CONNECTED : Phase.ERROR, ok ? "" : "停止未完成");
        }));

        btnStatus.setOnClickListener(v -> run(() -> {
            if (!ensureCtl()) return;
            print(ctl.status());
        }));

        btnProbe.setOnClickListener(v -> run(this::probe));

        btnCrop.setOnClickListener(v -> run(() -> {
            if (!ensureCtl()) return;
            ctl.setCrop(cropEdit.getText().toString());
            savePrefs();
            refreshUi();
        }));

        btnPick.setOnClickListener(v -> run(this::pickApp));
        btnFrame.setOnClickListener(v -> run(this::frameCrop));
        btnCluster.setOnClickListener(v -> run(this::pickClusterArea));
        btnPreview.setOnClickListener(v -> openPreview());

        return root;
    }

    // ------------------------------------------------------------ UI 小工具

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(C_CARD, C_STROKE, dp(12)));
        c.setPadding(dp(12), dp(6), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    private android.graphics.drawable.GradientDrawable round(int fill, int stroke, int r) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(r);
        if (stroke != 0) g.setStroke(dp(1) > 0 ? dp(1) : 1, stroke);
        return g;
    }

    private TextView text(String s, int px, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 22, C_MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 0;
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView chip(String s, int color) {
        TextView t = text(s, 22, color);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setBackground(round(0x22000000 | (color & 0xFFFFFF), 0, dp(20)));
        return t;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Button btn(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        b.setTextColor(fg);
        b.setBackground(round(bg, C_STROKE, dp(9)));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(5), dp(14), dp(5));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        lp.topMargin = dp(3);
        b.setLayoutParams(lp);
        return b;
    }

    private Button mkButton(String s) { return btn(s, 0xFF1D2530, C_TEXT); }

    private Button mkPrimary(String s) { return btn(s, C_ACCENT, 0xFFFFFFFF); }

    private Button mkDanger(String s) { return btn(s, 0xFF3A1D20, 0xFFFF8A8F); }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28);
        e.setTextColor(C_TEXT);
        e.setHintTextColor(0xFF5B6673);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setBackground(round(0xFF0B0F14, C_STROKE, dp(8)));
        e.setPadding(dp(10), dp(6), dp(10), dp(6));
        e.setMinHeight(dp(36));
        e.setMinimumHeight(dp(36));
        return e;
    }

    // ------------------------------------------------------------ 选应用 / 框选

    /** 列出「正在跑的 + 已装的第三方应用」，点一个就设成目标 */
    private void pickApp() {
        if (!ensureCtl()) return;
        java.util.List<String> apps;
        try {
            apps = ctl.candidateApps();
        } catch (Throwable t) {
            print("!! 取应用列表失败: " + t.getMessage());
            return;
        }
        if (apps.isEmpty()) {
            print("!! 没取到应用列表（pm/am 不可用？）");
            return;
        }
        print("可投应用 " + apps.size() + " 个，选一个");

        final String[] items = new String[apps.size()];
        for (int i = 0; i < items.length; i++) {
            String p = apps.get(i);
            boolean running = false;
            try {
                running = ctl.findTask(p) != null;
            } catch (Throwable ignored) { }
            items[i] = (running ? "● " : "○ ") + appLabel(p);
        }

        // 默认列表项字号在 density≈0.9 的车机上小得看不清也点不准，自己控制字号和行高
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, items) {
            @Override
            public android.view.View getView(int pos, android.view.View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                setPx(tv, 30);
                tv.setTextColor(Color.rgb(20, 20, 20));
                tv.setPadding(dp(6), dp(10), dp(6), dp(10));
                return tv;
            }
        };

        runOnUiThread(() -> {
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle("选择要投的应用（● 正在运行）")
                    .setAdapter(ad, (d, which) -> {
                        String pkg = apps.get(which);
                        pkgEdit.setText(pkg);
                        // 不在这里改 ctl.cfg().pkg：cfg 里的 pkg 属于「本次投出去的配置」，
                        // 由 startWith 在启动时快照一次。投屏中换应用后立刻改它，
                        // 停止时就会去恢复新应用的窗口，真正在虚拟屏上的旧窗口没人管。
                        savePrefs();
                        print("目标应用 -> " + pkg);
                        // 投屏中换应用：refreshUi 会发现「界面配置 ≠ 已投出的快照」，
                        // 把「启动投屏」重新变灰，提示需要再启动一次（UI-07）
                        refreshUi();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            // 默认对话框在横屏上只有一块窄条，包名+应用名要折两行；撑宽点一眼能看完
            if (dlg.getWindow() != null) {
                dlg.getWindow().setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
    }

    /**
     * 截一张图，进拖框界面。
     *
     * 投屏在跑的时候截的是虚拟屏（screencap -d），所以看到的就是实际投出去的画面，
     * 运行中也能调框；没在跑则截主屏，方便先框好再启动。
     */
    private void frameCrop() {
        if (!ensureCtl()) return;
        try {
            // 先把界面上的仪表矩形灌进 cfg 再取尺寸：框选页要用 outW/outH 锁比例，
            // 拿到上一次的旧比例会把画面裁歪（UI-13）。
            applyClusterCfg();
            print("取当前画面作底图…");
            byte[] png = ctl.snapshot();
            File f = new File(getCacheDir(), "crop.png");
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(png);
            }
            print("  已取到 " + png.length / 1024 + " KB，进拖框界面");

            final String crop = cropEdit.getText().toString().trim();
            runOnUiThread(() -> {
                Intent i = new Intent(this, CropActivity.class);
                i.putExtra("png", f.getAbsolutePath());
                i.putExtra("crop", crop);
                i.putExtra("out_w", ctl.cfg().outW);
                i.putExtra("out_h", ctl.cfg().outH);
                startActivityForResult(i, REQ_CROP);
            });
        } catch (Throwable t) {
            print("!! 截图失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            print("   可直接手填 x,y,w,h");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CLUSTER) {
            if (res != RESULT_OK || data == null) {
                print("仪表区域选择已取消");
                return;
            }
            String r = data.getStringExtra("crect");
            if (r != null) {
                clusterEdit.setText(r);
                savePrefs();
                // ctl 还没建（没连过 adb）时不用急：startWith / frameCrop 都会再同步一次
                if (ctl != null) applyClusterCfg();
                print("仪表投屏区 -> " + r
                        + (data.getBooleanExtra("custom", false) ? "（自定义）" : "（满宽横带）"));
                refreshUi();
            }
            return;
        }
        if (req != REQ_CROP) return;
        if (res != RESULT_OK || data == null) {
            print("框选已取消");
            return;
        }
        final String crop = data.getStringExtra("crop");
        if (crop == null) return;
        cropEdit.setText(crop);
        savePrefs();
        print("框选结果 " + crop);
        refreshUi();
        run(() -> {
            if (!ensureCtl()) return;
            ctl.setCrop(crop);
        });
    }

    /**
     * 实时预览：全屏看 VD 里那块画面（数据来自发送端的本地预览端口）。
     *
     * 只能在投屏中开：画面是发送端在 5570 上现推的，没启动就没有流。
     * 以前这里无条件把「预览」流程项标成完成，等于骗用户（UI-10）。
     * 「启动前先看合成效果」要走静态预演，那是 B4 的事。
     */
    private void openPreview() {
        if (phase != Phase.CASTING && phase != Phase.STARTING) {
            print("还没启动投屏：预览画面来自发送端的实时流（端口 "
                    + (ctl != null ? ctl.cfg().previewPort : 5570) + "），先启动再看");
            return;
        }
        int port = ctl != null ? ctl.cfg().previewPort : 5570;
        Intent i = new Intent(this, PreviewActivity.class);
        i.putExtra("port", port);
        // 把当前裁切参数带过去，预览页顶栏/边框就能标出「这块是从哪儿裁出来的」
        i.putExtra("crop", cropEdit.getText().toString().trim());
        i.putExtra("crect", clusterEdit.getText().toString().trim());
        startActivity(i);
        previewOpened = true;
        refreshUi();
    }

    /** 打开仪表区域选择页（底图是打包进来的 fb0_pre.png，原生 1920x720） */    private void pickClusterArea() {
        Intent i = new Intent(this, ClusterPickActivity.class);
        i.putExtra("crop", clusterEdit.getText().toString().trim());
        i.putExtra("top", 72);
        i.putExtra("bot", 80);
        startActivityForResult(i, REQ_CLUSTER);
    }

    /** 把界面上的仪表矩形写进 cfg；解析失败就退回满宽横带，绝不让脏值传到仪表端 */
    private void applyClusterCfg() {
        final int defY = ctl.cfg().top;
        final int defH = 720 - ctl.cfg().top - ctl.cfg().bot;
        int x = 0, y = defY, w = 1920, h = defH;
        boolean ok = false;
        String s = clusterEdit.getText().toString().trim();
        String[] p = s.split(",");
        if (p.length == 4) {
            try {
                x = Integer.parseInt(p[0].trim());
                y = Integer.parseInt(p[1].trim());
                w = Integer.parseInt(p[2].trim());
                h = Integer.parseInt(p[3].trim());
                ok = w > 0 && h > 0;
            } catch (Exception ignored) { }
        }
        if (!ok) {
            x = 0; y = defY; w = 1920; h = defH;
            print("仪表区域格式不对，按默认满宽横带启动");
        }
        ctl.cfg().cCustom = !(x == 0 && y == defY && w == 1920 && h == defH);
        ctl.cfg().cX = x;
        ctl.cfg().cY = y;
        ctl.cfg().cW = w;
        ctl.cfg().cH = h;
        // 立刻对齐 outW/outH：框选页要用它锁比例，启动前也要靠它算发送尺寸。
        // 以前只在 start() 里同步，改完矩形马上去框选会锁到上一次的比例（UI-13）。
        ctl.cfg().normalize();
    }

    private void startWith(String mode) {
        if (!ensureCtl()) return;
        String pkg = pkgEdit.getText().toString().trim();
        if (pkg.isEmpty()) {
            print("!! 先选一个目标应用");
            return;
        }
        ctl.setAssets(readAsset("vd.dex"), readAsset("killvds.sh"));
        ctl.cfg().mode = mode;
        ctl.cfg().pkg = pkg;
        ctl.cfg().crop = cropEdit.getText().toString().trim();
        applyClusterCfg();
        savePrefs();
        lastMode = mode;
        previewOpened = false;
        setPhase(Phase.STARTING);
        boolean ok = ctl.start();
        if (ok && !ctl.binMd5().isEmpty()) {
            // 记下「仪表上现在跑的是这一份」，下次开 App 直接命中快路径
            getPreferences(MODE_PRIVATE).edit().putString("binMd5", ctl.binMd5()).apply();
        }
        if (ok) {
            // 记下「真正投出去的那一份配置」：之后界面任何一项改了，
            // refreshUi 都会把「启动投屏」重新变灰（UI-07）
            launchedKey = keyOf(pkg, txt(cropEdit), txt(clusterEdit), mode);
            setPhase(Phase.CASTING);
        } else {
            launchedKey = "";
            setPhase(Phase.ERROR, "启动失败");
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density * 2.4f);
    }

    /** 直接按像素写字号：车机 density 偏低，sp 折算后小得点不到 */
    private void setPx(android.widget.TextView v, int px) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    // ------------------------------------------------------------ 任务

    private void run(Runnable r) {
        if (busy) { print("（上一条任务还在跑）"); return; }
        setBusy(true);
        worker.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                print("!! " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    private void setBusy(boolean b) {
        busy = b;
        // 按钮可用性统一由 refreshUi 决定，这里只翻标志再刷一遍
        refreshUi();
    }

    /** 连上了：只在「还没进入链路」时把 phase 拨到 CONNECTED，别覆盖 CASTING/STARTING */
    private void markConnected() {
        if (phase == Phase.IDLE || phase == Phase.ERROR) setPhase(Phase.CONNECTED);
    }

    private boolean ensureConn() {
        if (adb.isConnected()) {
            markConnected();
            return true;
        }
        int port;
        try {
            port = Integer.parseInt(portEdit.getText().toString().trim());
        } catch (Throwable t) {
            port = PORT_DEFAULT;
        }
        try {
            // 先看有没有可导入的现成密钥：本 ROM 的 adbd 授权 prompt 是单槽位、且没有 UI 可消，
            // 一次「新公钥」请求就可能把整台车机的授权槽位占死（审计答复 §2.1 / ADB-12）。
            // 所以只要能在约定位置找到已授权的私钥，就绝不生成新的。
            File keyFile = new File(getFilesDir(), "adbkey.der");
            if (!keyFile.exists()) importKeyIfPresent(keyFile);
            boolean[] created = new boolean[1];
            PrivateKey key = Adb.loadOrCreateKey(keyFile, created);
            if (created[0]) {
                print("!! 已生成新的 RSA 密钥：本车机不会弹出授权窗，新 key 无法完成授权");
                print("   请把已授权的 adbkey.der 放到 /data/local/tmp/vdcast_adbkey.der 后重开应用");
            }
            adb.setKey(key);

            print("连接 127.0.0.1:" + port + " …");
            if (adb.connect("127.0.0.1", port, 90)) {
                markConnected();
                return true;
            }

            // adbd 可能只绑在网卡地址上，兜底试本机各网卡 IP
            for (String ip : localIpv4()) {
                print("连接 " + ip + ":" + port + " …");
                if (adb.connect(ip, port, 90)) {
                    markConnected();
                    return true;
                }
            }
            print("!! 连不上 adbd。检查开发者选项里的 USB 调试是否开着、端口是否 " + port);
            setPhase(Phase.ERROR, "连不上 adbd");
            return false;
        } catch (Throwable t) {
            print("!! 连接失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            setPhase(Phase.ERROR, "连接失败");
            return false;
        }
    }

    /**
     * 首次运行时从约定位置导入一把「已经在本车 adb_keys 里」的私钥（PKCS#8 DER）。
     *
     * 为什么需要：包名一换，应用私有目录就是新的，adbkey.der 不存在 → 会生成新 key；
     * 而本 ROM 的 adbd 只会把新 key 的授权请求丢进「单槽位且无 UI 可消」的 pending 里，
     * 结果是这台车机再也无法完成任何新授权。宁可导入，也不要生成。
     *
     * 文件来源由维护者用 adb 推：
     *   adb push adbkey.der /data/local/tmp/vdcast_adbkey.der
     */
    private void importKeyIfPresent(File dst) {
        final String[] paths = {
                "/data/local/tmp/vdcast_adbkey.der",
                "/sdcard/Download/vdcast_adbkey.der",
        };
        for (String p : paths) {
            try {
                File src = new File(p);
                if (!src.isFile() || src.length() < 100 || src.length() > 8192) continue;
                byte[] der = new byte[(int) src.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(src)) {
                    int off = 0, n;
                    while (off < der.length && (n = in.read(der, off, der.length - off)) > 0) off += n;
                    if (off != der.length) continue;
                }
                // 只接受能解析的 PKCS#8，脏文件直接跳过（避免写坏之后每次启动都失败）
                java.security.KeyFactory.getInstance("RSA")
                        .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    out.write(der);
                }
                print("已导入已有密钥：" + p + "（不生成新 key）");
                return;
            } catch (Throwable ignored) { }
        }
    }

    private boolean ensureCtl() {
        if (!ensureConn()) return false;
        if (ctl == null) {
            VdCtl.Cfg cfg = new VdCtl.Cfg();
            // 60 而不是 30：车机侧 captureDisplay 单帧 11~12ms，1000/60=16.7ms 吃得下；
            // 矩形投屏后帧只有 422KB，接收端解码+窄行落屏也进 10ms 内。
            // 真跑不满时车机会自动落到实际能到的帧率，不会更差。
            cfg.fps = 60;
            cfg.hold = 86400;
            cfg.crop = cropEdit.getText().toString().trim();
            cfg.pkg = pkgEdit.getText().toString().trim();
            if (cfg.pkg.isEmpty()) cfg.pkg = PKG_DEFAULT;
            ctl = new VdCtl(adb, this, cfg);

            // 仪表端接收程序（ARMv7 静态 ELF）随 APK 一起分发，缺了才退回车机 /sdcard 旁路
            byte[] ic = readAsset("icrecv_dbg");
            ctl.setClusterBinary(ic.length > 0 ? ic : null);
            if (ic.length > 0) print("仪表端程序已内置（" + ic.length + " B）");
            // 上次成功投送到仪表的那份 md5：命中就不用再查仪表、更不用重推
            ctl.setKnownBinMd5(getPreferences(MODE_PRIVATE).getString("binMd5", ""));
            // 界面上的仪表矩形（来自 prefs 或遥控参数）一建好 cfg 就灌进去，
            // 别等到点「启动」才同步：框选页、状态显示都要用正确的 outW/outH
            applyClusterCfg();
        }
        return true;
    }

    /** 四步自检：域 / 环境 / am 可读 / 长驻进程可脱离 */
    private void probe() {
        if (!ensureConn()) return;
        try {
            String idz = adb.exec("id -Z", 10000);
            print("STEP-1 身份与域");
            print("  " + idz.trim().replace("\n", " "));
            boolean ok = idz.contains("u:r:shell:s0");
            print(ok ? "  [OK] shell 域（captureDisplay 可用）" : "  [FAIL] 不是 shell 域");

            print("STEP-1b 认证与环境");
            String env = adb.exec("getprop ro.adb.secure; getenforce; getprop ro.build.version.release", 10000);
            for (String line : env.trim().split("\n")) print("  " + line.trim());

            print("STEP-2 am 可读");
            print("  am stack list 行数 = "
                    + adb.exec("am stack list 2>/dev/null | wc -l", 15000).trim());

            print("STEP-3 长驻进程");
            String alive = adb.exec("setsid sh -c 'sleep 30' >/dev/null 2>&1 & sleep 1; "
                    + "ps -A -o PID,ARGS | grep -F 'sleep 30' | grep -v grep | wc -l", 15000);
            print("  存活 = " + alive.trim() + (alive.trim().matches("[1-9].*") ? "  [OK]" : "  [FAIL]"));
        } catch (Throwable t) {
            print("!! 自检中断: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void savePrefs() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("crop", cropEdit.getText().toString().trim())
                .putString("crect", clusterEdit == null ? CRECT_DEFAULT
                        : clusterEdit.getText().toString().trim())
                .putString("port", portEdit.getText().toString().trim())
                .putString("pkg", pkgEdit.getText().toString().trim())
                .apply();
    }

    // ------------------------------------------------------------ 日志

    @Override
    public void print(String s) {
        String line = "[" + ts.format(new Date()) + "] " + s;
        // 同步一份到 logcat，方便 PC 侧 adb logcat -s ADBPROBE:I 回读（车机屏不方便滚动时用）
        android.util.Log.i("ADBPROBE", line);
        logBuf.append(line).append('\n');
        if (logBuf.length() > 200_000) logBuf.delete(0, 100_000);
        runOnUiThread(() -> {
            logView.setText(logBuf.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ------------------------------------------------------------ 杂项

    private byte[] readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            print("!! 读取 assets/" + name + " 失败: " + t.getMessage());
            return new byte[0];
        }
    }

    private java.util.List<String> localIpv4() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> e =
                 java.net.NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                java.net.NetworkInterface ni = e.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    java.net.InetAddress ia = a.nextElement();
                    if (ia instanceof java.net.Inet4Address && !ia.isLoopbackAddress()) {
                        out.add(ia.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }
}
