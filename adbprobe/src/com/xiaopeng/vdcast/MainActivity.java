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
    private static final int REQ_PICK = 1003;
    /**
     * 仪表默认投屏区 = 安全区 1216×360（43.8 万像素），不再是满宽横带。
     *
     * 2026-11-05 16:00 口径由 A 改 B：仪表是安全件，默认不该被投屏占满；
     * 而且满宽横带 109 万像素会掉到 30~40 帧。数值与理由见 {@link VdCtl#DEF_RECT}。
     */
    private static final String CRECT_DEFAULT = VdCtl.DEF_RECT;
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
    /** 退出入口（UI-08）与日志折叠开关 */
    private Button btnExit, btnLogToggle;
    /** 高级设置区标题，本身是个整宽按钮（可点区域要够大） */
    private Button advHead;
    /** 三层小灯右侧的「刷新」 */
    private Button btnHealth;
    /** 高级里的「连接」：UI-12 要求它跟其他按钮一样受 busy 管控 */
    private Button btnConnect;
    /** 日志行的「诊断」按钮 */
    private Button btnDiag;
    /** 仪表区域「复位」：一键回到安全区默认值 */
    private Button btnReset;
    /** 中间那块可滚动的正文区（诊断用：触摸失效时要看它到底给了多大视口） */
    private ScrollView bodySv;
    /** 主 CTA 下方的「投屏区多大 / 大概能跑多少帧」提示 */
    private TextView cfgHint;

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
        if (clusterEdit != null) {
            // 一次性迁移：口径改 B（16:00）之前存的都是满宽横带 0,72,1920,568，
            // 那是「全屏投放」，现在不再推荐。旧值直接换成安全区，不然改默认值等于白改。
            String crect = sp.getString("crect", CRECT_DEFAULT);
            if ("0,72,1920,568".equals(crect)) crect = CRECT_DEFAULT;
            clusterEdit.setText(crect);
        }
        // 流程条不再按 prefs 里有没有 key 打勾：配置存过不等于这次真的可用，
        // 由 refreshUi 按界面上的实际内容推导（见 refreshUiNow）
        refreshUi();

        print("仪表投屏 v2.2");
        print("流程：选应用 → 仪表区域 → 裁切画面 → 预演 → 启动投屏");
        setBusy(false);
        showGuideIfNeeded();
        handleIntent(getIntent());
        // 首屏先自检一次上次有没有留下东西（UX-04）。放在 handleIntent 之后：
        // 遥控来的 act 优先，别让 2 秒的自检挡住 PC 侧的自动化。
        if (getIntent() == null || getIntent().getStringExtra("act") == null) checkResidue();
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        handleIntent(it);
    }

    /**
     * 返回键不能直接结束 Activity。
     *
     * 面板开着的时候 VdSend / icrecv 可能还在跑，直接 finish 会留下两个孤儿进程：
     * 车机继续采屏、仪表继续占着 fb1，用户却看不到任何界面能停它。
     * 所以返回键统一走「退出应用」那条带收尾的路径。
     */
    @Override
    public void onBackPressed() {
        if (!advOpen && !logOpen) {
            confirmExit();
        } else {
            // 有展开的区域时，返回键先收起来（符合「返回上一级」的直觉）
            if (advOpen) setAdvOpen(false);
            else setLogOpen(false);
        }
    }

    /**
     * 遥控入口：PC 侧一句 am start 就能驱动，不用在车机屏上摸黑点按钮。
     *
     * <pre>
     * am start -n com.xiaopeng.vdcast/.MainActivity --es 参数 … --es act 动作
     * </pre>
     *
     * <p>语义（UX-11 统一定义；PC 侧 {@code tools/vdcast.sh}、文档 {@code docs/遥控入口-act-v1.md}
     * 与这里同源，动作表见 {@link Acts}）：
     * <ol>
     *   <li><b>参数在前、act 最后</b>：am 会把 act 之后多出来的裸参数当 data URI，
     *       {@code --es act crop 1,2,3,4} 的坐标就是这样被吃掉的（B0 基线 §3.3）；</li>
     *   <li><b>只有声明消费的参数才生效</b>：{@code act=status} 不会顺手改 pkg；</li>
     *   <li><b>必需参数缺失 = 拒绝</b>，绝不沿用界面旧值（那正是历史上静默出错的地方）；</li>
     *   <li><b>旧名 vd / main / cluster 不再接受</b>，但打印该改成什么；</li>
     *   <li>{@code act=help} 打印整张表。</li>
     * </ol>
     */
    private void handleIntent(Intent it) {
        if (it == null) return;
        String act = it.getStringExtra("act");
        if (act == null || act.trim().isEmpty()) {
            // 只带参数不带动作 = 什么都不做。以前会静默写界面字段，PC 侧根本看不出没生效。
            if (hasAnyParam(it)) print("遥控：只收到参数没有 --es act，已忽略（动作表用 act=help）");
            return;
        }
        act = act.trim().toLowerCase(java.util.Locale.US);

        // 历史坑：`--es act crop 1,2,3,4` 里的坐标会被 am 吃掉 —— 落在 data URI（带 scheme 的值）
        // 或者包名（纯坐标这种没有 scheme 的值，实测会变成 pkg=1,2,3,4）上。
        // 收到这种「多出来的东西」就当场点出来，别让它悄悄沿用旧值。
        android.net.Uri data = it.getData();
        String stray = data != null ? data.toString() : null;
        if (stray == null) {
            String ip = it.getPackage();
            if (ip != null && !ip.equals(getPackageName())) stray = ip;
        }
        if (stray != null) {
            print("!! 参数写在了 --es act 之后，被 am 当成 "
                    + (data != null ? "data URI" : "包名") + "：" + stray);
            print("   " + Acts.ORDER_TIP);
        }

        String mig = Acts.deprecated(act);
        if (mig != null) {
            print("!! act=" + act + " 已改名：" + mig);
            return;
        }
        if (!Acts.known(act)) {
            print("!! 未知 act: " + act);
            print("   可用：" + Acts.oneLine());
            print("   完整表：--es act help");
            return;
        }
        print("遥控 → " + act + argsSummary(it, act));

        switch (act) {
            case "connect":
                applyPortIfGiven(it);
                run(() -> {
                    if (ensureConn()) print("已连接 " + adb.endpoint);
                });
                break;
            case "status":  run(this::showStatus); break;
            case "health":  run(this::refreshHealth); break;
            case "probe":   run(this::probe); break;
            case "apps":    run(this::dumpApps); break;
            case "pick":    run(this::pickApp); break;
            case "area":    run(this::pickClusterArea); break;
            case "snap":    run(this::frameCrop); break;
            case "crop": {
                String crop = opt(it, "crop");
                if (crop == null) { missParam("crop", "crop", "x,y,w,h"); break; }
                cropEdit.setText(crop);
                run(() -> {
                    if (!ensureCtl()) return;
                    ctl.setCrop(crop);
                    savePrefs();
                    refreshUi();
                });
                break;
            }
            case "crect": {
                String r = opt(it, "crect");
                if (r == null) { missParam("crect", "crect", "x,y,w,h"); break; }
                clusterEdit.setText(r);
                savePrefs();
                run(() -> {
                    if (!ensureCtl()) return;
                    applyClusterCfg();
                    refreshUi();
                    print("仪表投屏区 → " + r + "（投屏中改的，需重新启动才生效）");
                });
                break;
            }
            case "start": {
                String pkg = opt(it, "pkg");
                if (pkg != null) pkgEdit.setText(pkg);
                String crop = opt(it, "crop");
                if (crop != null) cropEdit.setText(crop);
                String rect = opt(it, "crect");
                if (rect != null) clusterEdit.setText(rect);
                String mode = opt(it, "mode");
                if (mode == null) mode = "vd";
                if (!"vd".equals(mode) && !"main".equals(mode)) {
                    print("!! --es mode 只认 vd / main，收到：" + mode);
                    break;
                }
                if (pkgEdit.getText().toString().trim().isEmpty()) {
                    print("!! act=start 需要 --es pkg（或先 act=pick 选一个）");
                    break;
                }
                final String m = mode;
                run(() -> startWith(m));
                break;
            }
            case "stop":    run(this::stopCasting); break;
            // UX-04：启动自检 / 一键清理（PC 侧自动化用，等价于点顶栏那条横幅）
            case "residue": residueChecked = false; checkResidue(); break;
            case "clean":   runOnUiThread(this::cleanResidue); break;
            // UI-12 中断：粒度是「步」，正在跑的那条 adb 命令打断不了
            case "cancel":  runOnUiThread(() -> {
                if (ctl == null) return;
                ctl.cancel();
                print("已请求中断：等当前这一步跑完就停");
            });
                break;
            case "preview": runOnUiThread(this::openPreview); break;
            // 仪表区域复位到安全区（PC 侧自动化用，等价于点面板上的「复位」）
            case "reset":   runOnUiThread(this::resetClusterArea); break;
            // 把关键控件的屏幕坐标打到日志：PC 侧 input tap 老是点偏时用来自校准
            case "coords":  runOnUiThread(this::dumpCoords); break;
            case "exit":    runOnUiThread(this::confirmExit); break;
            // 无确认直接退出：给 PC 侧和自动化留的口子（人用的路径必须走 confirmExit）
            case "exitnow": runOnUiThread(this::runExit); break;
            case "resync":
                // 正常使用不需要：同步状态是进程级的，开 App 第一次启动时自动核对一次。
                // 换过 dex/二进制、又不想杀进程时用这个强制重来。
                VdCtl.resetToolCache();
                print("已清掉工具同步标记，下次「启动」会重新核对并重推");
                break;
            case "help":    printActTable(); break;
            default:        print("!! 未知 act: " + act); break;
        }
    }

    /** 该动作声明了消费、但没给 → 当场拒绝，并提示参数顺序（缺参数最常见的成因就是这个坑） */
    private void missParam(String act, String key, String shape) {
        print("!! act=" + act + " 缺 --es " + key + "（" + shape + "）");
        print("   " + Acts.ORDER_TIP);
    }

    private void printActTable() {
        print("遥控动作表（" + Acts.TABLE.length + " 条）");
        for (String line : Acts.helpLines()) print("  " + line);
    }

    /** Intent 里该动作会消费的参数；不消费但带了的，在摘要里标出来 */
    private String argsSummary(Intent it, String act) {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"pkg", "mode", "crop", "crect", "port"}) {
            String v = opt(it, k);
            if (v == null) continue;
            sb.append(Acts.takes(act, k) ? " " : " [忽略] ").append(k).append('=').append(v);
        }
        return sb.toString();
    }

    private static boolean hasAnyParam(Intent it) {
        for (String k : new String[]{"pkg", "mode", "crop", "crect", "port"}) {
            if (opt(it, k) != null) return true;
        }
        return false;
    }

    private void applyPortIfGiven(Intent it) {
        String port = opt(it, "port");
        if (port != null) portEdit.setText(port);
    }

    /** 取字符串参数并 trim；空串等同于没给 */
    private static String opt(Intent it, String key) {
        String v = it.getStringExtra(key);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
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

    // ---------- B3 新增的视图 ----------
    /** 三层小灯：adb / 发送端 / 接收端。旧面板上三者坏掉时长得一模一样，恢复手段却完全不同 */
    private TextView dotAdb, dotSender, dotRecv;
    /** 实测帧率（车机侧 / 仪表侧） */
    private TextView fpsView;
    private TextView logSummary;
    private ScrollView logScrollRef;
    private LinearLayout logCard;
    /** 高级设置区（端口、模式、连接、状态、自检全部下沉到这里） */
    private LinearLayout advBody;
    private boolean advOpen;
    /** 日志是否展开（默认折叠成一行，出错自动展开） */
    private boolean logOpen;
    /** 诊断模式：连点日志标题 5 次打开，打开后日志常驻展开 */
    private boolean diagMode;
    private int logTitleTaps;
    /** 最近一次健康探针结果，供「灯」和「面积提示」共用 */
    private volatile VdCtl.Health lastHealth;

    // ---------- UX-04 启动自检 ----------
    /** 顶栏下方的残留提示条：只在 IDLE 且确实脏的时候出现 */
    private LinearLayout residueBar;
    private TextView residueText;
    private Button btnClean;
    /** 最近一次自检结果，供「清理后复查」和遥控入口共用 */
    private volatile VdCtl.Residue lastResidue;
    /** 自检是否已经跑过一轮（避免每次刷新都重新探 2 秒） */
    private boolean residueChecked;

    // ---------- UI-12 进度条 ----------
    private LinearLayout progRow;
    private View progFill, progRest;
    private TextView progLabel;

    // ---------- 顶部流程条 ----------
    // 顺序按「先定仪表投屏区、再按它的宽高比框车机画面」排：投屏区决定投出去多大，
    // 裁切框选要按这个比例锁框，反过来做等于先裁一次再重裁（UX-02）。
    // UI-09：预演挪到启动之前 —— 静态预演已经能「没投屏先看效果」，所以流程终点是「启动投屏」。
    private static final String[] STEP_NAMES =
            {"连接", "选应用", "仪表区域", "裁切画面", "预演", "启动投屏"};
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

    /**
     * 本次配置下预演过（静态预演或实时预览都算）。
     *
     * <p>UI-09 把「预演」排到「启动投屏」前面，所以这个标记必须在启动之后**依然成立** ——
     * 和 {@link #previewOpened}（实时预览，投屏中才看得到，重开要重新连）不是一回事。
     * 配置一改就作废：预演的是旧配置，不能拿它给新配置打勾。
     */
    private boolean rehearsed;

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
        // UI-09：预演在启动之前。看过一次（静态预演或实时预览）就算过，配置改了会被 cfgWatcher 清掉
        stepDone[4] = rehearsed || previewOpened;
        stepDone[5] = fresh;
        int cur = 0;
        while (cur < stepDone.length && stepDone[cur]) cur++;
        paintSteps(cur);

        String s;
        int c;
        // 用户级 4 态（UX-08）：未连接 / 就绪 / 投屏中 / 出错了。
        // 「启动中」「收尾中」是必须让用户看到的过渡态，否则点了没反应像是卡死。
        switch (phase) {
            case CONNECTED: s = "就绪";     c = C_OK;     break;
            case STARTING:  s = "启动中…";  c = C_ACCENT; break;
            case CASTING:
                if (stale) { s = "投屏中 · 配置已改"; c = C_WARN; }
                else       { s = "投屏中";            c = C_OK; }
                break;
            case STOPPING:  s = "收尾中…";  c = C_ACCENT; break;
            case ERROR:     s = "出错了";   c = C_DANGER; break;
            default:        s = "未连接";   c = C_MUTED;  break;
        }
        stateChip.setText(s);
        stateChip.setTextColor(c);
        stateChip.setBackground(round(0x22000000 | (c & 0xFFFFFF), 0, dp(20)));

        // 主 CTA：一个按钮，文案直接说清「现在点它会干什么」（UI-04）
        if (btnStartVd != null) {
            String go;
            if (busy || phase == Phase.STARTING) go = "正在启动…";
            else if (phase == Phase.STOPPING) go = "正在收尾…";
            else if (stale) go = "应用新配置";
            else if (fresh) go = "投屏中";
            else go = "启动投屏";
            btnStartVd.setText(go);
            btnStartVd.setEnabled(hasPkg && !busy && !fresh
                    && phase != Phase.STARTING && phase != Phase.STOPPING);
            btnStartVd.setBackground(round(fresh ? 0xFF14301F : C_ACCENT,
                    fresh ? C_OK : C_ACCENT, dp(9)));
        }
        if (cfgHint != null) cfgHint.setText(areaHint());

        // 按钮：只按「有没有可能成功」禁用，不按状态机猜到的运行态禁用 ——
        // 状态机可能落后于车机（比如车机重启了），「停止」永远留一条手动兜底的路。
        // 主 CTA 的可用性已经在上面按 phase 单独算过了，这里不再覆盖。
        if (btnStartMain != null) btnStartMain.setEnabled(hasPkg && !busy);
        if (btnStop != null) {
            // 启动中这个键是「中断」，必须保持可点 —— busy 时一律置灰的话，
            // 用户就只能干等 8 步跑完（UI-12 要的正是「能中断」）
            boolean canAbort = phase == Phase.STARTING && ctl != null;
            btnStop.setText(canAbort ? "中断" : "停止");
            btnStop.setEnabled(canAbort || !busy);
        }
        if (btnStatus != null) btnStatus.setEnabled(!busy);
        if (btnConnect != null) btnConnect.setEnabled(!busy);
        if (btnCrop != null) btnCrop.setEnabled(!busy);
        if (btnProbe != null) btnProbe.setEnabled(!busy);
        if (btnPick != null) btnPick.setEnabled(!busy);
        if (btnFrame != null) btnFrame.setEnabled(!busy);
        if (btnCluster != null) btnCluster.setEnabled(!busy);
        if (btnReset != null) btnReset.setEnabled(!busy);
        if (btnPreview != null) btnPreview.setEnabled(!busy);
        if (btnExit != null) btnExit.setEnabled(!busy);
        if (btnClean != null) btnClean.setEnabled(!busy);
        // 自检横幅：phase 变了（开始投屏 / 收尾完成）要重新判一次该不该出现
        paintResidue(lastResidue);
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
        // 顶部留出系统状态栏的高度：面板是铺到屏幕右缘的窗口，状态栏（铃铛/电量/时间）
        // 会盖在右上角 —— 顶栏的「退出」按钮原来正好被它吃掉，看得见才怪。
        root.setPadding(dp(14), dp(30), dp(14), dp(6));

        // ---------- 顶栏：标题 + 状态 + 退出 ----------
        // 状态胶囊紧贴标题（右侧是系统状态栏，推过去会被压住）；退出固定在右上角。
        // 点胶囊 = 展开工程级详情（UX-08：普通人看 4 态，工程师一键看原始 status）。
        LinearLayout head = row();
        TextView title = text("仪表投屏", 34, C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        stateChip = chip("未连接", C_MUTED);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = dp(10);
        head.addView(stateChip, chipLp);
        head.addView(spacer());
        btnExit = mkDanger("退出");
        btnExit.setPadding(dp(18), dp(5), dp(18), dp(5));
        head.addView(btnExit);
        stateChip.setOnClickListener(v -> run(this::showStatus));
        root.addView(head);

        // ---------- 三层小灯 ----------
        // 「adb 掉线 / 发送端崩了 / 接收端没起」在旧面板上长得一模一样（都显示投屏中），
        // 恢复手段却完全不同 —— 拆成三个独立灯 + 实测帧率。
        LinearLayout lamps = row();
        lamps.setPadding(0, dp(2), 0, dp(2));
        dotAdb = dot(C_MUTED);
        dotSender = dot(C_MUTED);
        dotRecv = dot(C_MUTED);
        lamps.addView(dotAdb);
        lamps.addView(lampLabel("ADB"));
        lamps.addView(dotSender);
        lamps.addView(lampLabel("发送端"));
        lamps.addView(dotRecv);
        lamps.addView(lampLabel("接收端"));
        fpsView = text("车机 -- · 仪表 --", 20, C_MUTED);
        LinearLayout.LayoutParams fpsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fpsLp.leftMargin = dp(12);
        lamps.addView(fpsView, fpsLp);
        lamps.addView(spacer());
        Button btnHealthLocal = mkButton("刷新");
        btnHealth = btnHealthLocal;
        btnHealthLocal.setOnClickListener(v -> run(this::refreshHealth));
        lamps.addView(btnHealthLocal);
        root.addView(lamps);

        // ---------- 上次残留提示（UX-04） ----------
        // 只在「确实查到残留」时出现，正常情况整条不占位。之所以要一条显眼的横幅而不是
        // 一行日志：残留的典型后果是「第一次启动莫名其妙失败」，用户不会去翻日志找原因。
        residueBar = new LinearLayout(this);
        residueBar.setOrientation(LinearLayout.HORIZONTAL);
        residueBar.setGravity(Gravity.CENTER_VERTICAL);
        residueBar.setBackgroundColor(0x33D29922);
        residueBar.setPadding(dp(10), dp(6), dp(6), dp(6));
        residueText = text("", 20, C_WARN);
        residueText.setLineSpacing(dp(2), 1f);
        residueBar.addView(residueText, weight());
        btnClean = mkButton("一键清理");
        btnClean.setOnClickListener(v -> run(this::cleanResidue));
        LinearLayout.LayoutParams cleanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cleanLp.leftMargin = dp(8);
        residueBar.addView(btnClean, cleanLp);
        residueBar.setVisibility(View.GONE);
        root.addView(residueBar);

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

        // ---------- 进度条（UI-12） ----------
        // 以前 busy 期间界面零反馈（relay 最长 55s），用户以为点错了就会反复点，
        // 而每次点击都被 run() 丢掉。现在给一条「到第几步、在干什么」的实在进度。
        progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.VERTICAL);
        progRow.setPadding(0, dp(3), 0, dp(3));

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        progFill = new View(this);
        progFill.setBackground(round(C_ACCENT, 0, dp(3)));
        progRest = new View(this);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(5), 0.02f);
        progFill.setLayoutParams(fp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(5), 0.98f);
        progRest.setLayoutParams(rp);
        track.addView(progFill);
        track.addView(progRest);
        progRow.addView(track);

        progLabel = text("", 19, C_MUTED);
        progLabel.setPadding(0, dp(3), 0, 0);
        progRow.addView(progLabel);
        progRow.setVisibility(View.GONE);
        root.addView(progRow);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // ---------- 卡片 1：目标应用 ----------
        LinearLayout c1 = card();
        c1.addView(label("目标应用（要搬到虚拟屏的第三方应用）"));
        LinearLayout r1 = row();
        pkgEdit = input("com.tencent.qqmusicpad");
        r1.addView(pkgEdit, weight());
        btnPick = mkButton("更换");
        r1.addView(btnPick);
        c1.addView(r1);
        body.addView(c1);

        // ---------- 卡片 2：区域 ----------
        // 顺序有意：先定「投到仪表哪里」，再框「车机取哪块」。
        // 裁切框选按投屏区的宽高比锁比例，反过来排会先裁一次再返工（UX-02）。
        LinearLayout c2 = card();
        c2.addView(label("仪表投屏区域  1920×720 坐标，默认安全区 1216×360"));
        LinearLayout r3 = row();
        clusterEdit = input(CRECT_DEFAULT);
        r3.addView(clusterEdit, weight());
        btnCluster = mkButton("框选");
        r3.addView(btnCluster);
        btnReset = mkButton("复位");
        btnReset.setOnClickListener(v -> resetClusterArea());
        r3.addView(btnReset);
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

        // ---------- 卡片 3：预演 → 主 CTA ----------
        // 整个面板只有这一个「必须点对」的按钮：全宽、加高、文案随状态变。
        // UI-09 之后流程是「先预演、再启动」，所以预览入口放在主 CTA **上面** ——
        // 按钮的上下顺序就是流程顺序，不靠用户自己记。
        LinearLayout c3 = card();

        LinearLayout r5 = row();
        btnPreview = mkButton("预览画面（预演：先看效果，没投屏也能看）");
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvLp.bottomMargin = dp(6);
        btnPreview.setLayoutParams(pvLp);
        r5.addView(btnPreview);
        c3.addView(r5);

        LinearLayout r4 = row();
        btnStartVd = mkPrimary("启动投屏");
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        goLp.rightMargin = dp(8);
        btnStartVd.setLayoutParams(goLp);
        btnStartVd.setTextSize(TypedValue.COMPLEX_UNIT_PX, 32);
        btnStartVd.setPadding(dp(14), dp(11), dp(14), dp(11));
        btnStop = mkDanger("停止");
        btnStop.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30);
        btnStop.setPadding(dp(20), dp(11), dp(20), dp(11));
        r4.addView(btnStartVd);
        r4.addView(btnStop);
        c3.addView(r4);

        // 安全件提醒：口径 16:00 由 A 改 B —— 默认收窄到安全区，超了就直说代价
        cfgHint = text("", 20, C_MUTED);
        cfgHint.setPadding(0, dp(5), 0, 0);
        c3.addView(cfgHint);
        body.addView(c3);

        // ---------- 卡片 4：高级（默认折叠） ----------
        LinearLayout c4 = card();
        // 用整宽按钮而不是可点文字：这台车机上面板是一个独立窗口，实测 22px 高的
        // TextView 点击区域收不到 tap（同样的位置换成 Button 就正常），
        // 与其赌，不如直接给一个 60px 高的按钮。
        advHead = btn("高级设置  ▸", 0xFF1B2330, C_MUTED);
        advHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ahLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ahLp.topMargin = dp(2);
        advHead.setLayoutParams(ahLp);
        advHead.setOnClickListener(v -> setAdvOpen(!advOpen));
        c4.addView(advHead);

        advBody = new LinearLayout(this);
        advBody.setOrientation(LinearLayout.VERTICAL);
        advBody.setVisibility(View.GONE);

        advBody.addView(label("启动模式（main = 抓整块主屏，调试用）"));
        LinearLayout mrow = row();
        Button btnStartVdAdv = mkButton("按 vd 启动");
        btnStartMain = mkButton("按 main 启动");
        btnStartVdAdv.setOnClickListener(v -> run(() -> startWith("vd")));
        btnStartMain.setOnClickListener(v -> run(() -> startWith("main")));
        mrow.addView(btnStartVdAdv);
        mrow.addView(btnStartMain);
        advBody.addView(mrow);

        advBody.addView(label("连接与诊断"));
        LinearLayout r6 = row();
        r6.setPadding(0, dp(2), dp(30), 0);
        btnConnect = mkButton("连接");
        btnStatus = mkButton("状态");
        btnProbe = mkButton("自检");
        portEdit = input("5050");
        r6.addView(btnConnect);
        r6.addView(portEdit, new LinearLayout.LayoutParams(dp(56),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        r6.addView(btnStatus);
        r6.addView(btnProbe);
        advBody.addView(r6);

        c4.addView(advBody);
        body.addView(c4);

        ScrollView sv = new ScrollView(this);
        bodySv = sv;
        sv.setVerticalScrollBarEnabled(false);
        // fillViewport + 显式 MATCH_PARENT：不这么写 ScrollView 会按「无宽度约束」测量子视图，
        // 行内 weight 的输入框会把右边的按钮挤出卡片（第一版就被挤掉半个「框选」）。
        sv.setFillViewport(true);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------- 日志（默认折叠一行，出错自动展开） ----------
        // 旧面板固定 122dp 给日志，普通用户看不懂还占掉半屏；折叠后只剩一行摘要，
        // 出错时自动展开（UI-11）。连点标题 5 次进诊断模式，日志常驻展开。
        logCard = card();
        LinearLayout lh = row();
        // 右侧留出车机悬浮球的位置：它固定在屏幕右下角，会把「展开 / 清屏」盖住
        lh.setPadding(0, 0, dp(110), 0);
        // 整行可点（连点 5 次进诊断模式）：只让 22px 的「运行日志」四个字可点，
        // 在车机上基本等于点不到
        lh.setOnClickListener(v -> onLogTitleTap());
        TextView logTitle = text("运行日志", 22, C_MUTED);
        logTitle.setOnClickListener(v -> onLogTitleTap());
        lh.addView(logTitle);
        logSummary = text("", 20, 0xFF5B6673);
        logSummary.setSingleLine(true);
        logSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sumLp.leftMargin = dp(8);
        lh.addView(logSummary, sumLp);
        btnLogToggle = mkButton("展开");
        btnLogToggle.setOnClickListener(v -> setLogOpen(!logOpen));
        lh.addView(btnLogToggle);
        // 诊断模式入口做成按钮：这台车机上面板是独立窗口，实测只有 Button 能稳定
        // 收到点击（TextView / 整行的 onClickListener 收不到 tap），
        // 所以「连点标题 5 次」那个隐藏入口保留，但给一个能点到的显式按钮。
        btnDiag = mkButton("诊断");
        btnDiag.setOnClickListener(v -> enableDiag());
        lh.addView(btnDiag);
        Button clear = mkButton("清屏");
        clear.setOnClickListener(v -> {
            logBuf.setLength(0);
            if (logView != null) logView.setText("");
            if (logSummary != null) logSummary.setText("");
        });
        lh.addView(clear);
        logCard.addView(lh);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFFB7C3D0);
        logView.setBackground(round(0xFF0B0F14, 0, dp(8)));
        logView.setPadding(dp(8), dp(6), dp(8), dp(6));
        logView.setMovementMethod(new ScrollingMovementMethod());
        logScroll.addView(logView);
        logScroll.setVisibility(View.GONE);
        logCard.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));
        root.addView(logCard);

        // ---------- 事件 ----------
        // 手改参数也要立刻反映到流程条：改了应用/矩形/裁切而没重新启动，
        // 「启动投屏」应该马上变灰，状态药丸变成「配置已改」
        android.text.TextWatcher cfgWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) { }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) { }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                // 配置一改，之前的预演就不是这份配置的了，流程条上的「预演」必须作废
                rehearsed = false;
                refreshUi();
            }
        };
        pkgEdit.addTextChangedListener(cfgWatcher);
        cropEdit.addTextChangedListener(cfgWatcher);
        clusterEdit.addTextChangedListener(cfgWatcher);

        btnConnect.setOnClickListener(v -> run(() -> {
            if (ensureConn()) print("已连接 " + adb.endpoint);
        }));

        // 主 CTA 按「上次用的模式」启动（默认 vd）；main 只从高级里显式触发。
        // 拿到 CTA 就是一条正常路径，不需要用户理解 vd / main 是什么（UI-04）。
        btnStartVd.setOnClickListener(v -> run(() -> startWith(lastMode)));

        btnStop.setOnClickListener(v -> {
            // 启动过程中这个键是「中断」：中断粒度是「步」，正在跑的那条 adb 命令打断不了
            if (phase == Phase.STARTING && ctl != null) {
                ctl.cancel();
                print("已请求中断：等当前这一步跑完就停（killvds stop 最长 4.4s）");
                btnStop.setEnabled(false);
                return;
            }
            run(this::stopCasting);
        });

        btnStatus.setOnClickListener(v -> run(this::showStatus));

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
        btnExit.setOnClickListener(v -> confirmExit());

        return root;
    }

    // ------------------------------------------------------------ B3 新增的交互

    /** 停止投屏：状态机可能落后于车机，所以永远真去收一遍尾 */
    private void stopCasting() {
        if (phase != Phase.CASTING) print("当前面板认为没有在投屏，照样执行一遍收尾");
        if (!ensureCtl()) return;
        setPhase(Phase.STOPPING);
        boolean ok = ctl.stop();
        previewOpened = false;
        setPhase(ok ? Phase.CONNECTED : Phase.ERROR, ok ? "" : "仪表未确认恢复");
        refreshHealth();
    }

    /**
     * 退出应用（UX-05）。
     *
     * 不许静默退出：必须先停投屏、等两端进程真的结束、清掉临时文件，再关 socket 退出。
     * 总预算 8s；超时也要说清是哪一步没走完，并把选择权交回用户（再等 / 强制退出）。
     */
    private void confirmExit() {
        if (busy) {
            print("（上一条任务还在跑，等它结束再退出）");
            return;
        }
        boolean casting = phase == Phase.CASTING || phase == Phase.STARTING || phase == Phase.STOPPING;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("退出应用")
                .setMessage(casting
                        ? "会先停止投屏、等车机与仪表两端进程结束，再退出。\n预计 6~8 秒。"
                        : "没有在投屏，只清理临时文件后退出。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> runExit())
                .show();
        // 默认不 focus 的 positive 按钮在「没有触屏 / 用旋钮」的场景里选不中，
        // 这里主动给焦点，回车即退出
        try {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        } catch (Throwable ignored) { }
    }

    private void runExit() {
        if (busy) {
            print("（上一条任务还在跑，等它结束再退出）");
            return;
        }
        print("开始退出：先收尾，再关连接");
        setBusy(true);
        worker.execute(() -> {
            final long t0 = System.currentTimeMillis();
            final long deadline = t0 + 8000;
            boolean carOk = true;
            boolean restored = true;
            int residual = -1;
            try {
                // 1) 停投屏。只要连过 adb 就真去收一遍（状态机可能已经落后）
                if (ctl != null || adb.isConnected()) {
                    if (!ensureConn()) {
                        carOk = false;
                        print("!! 退出时连不上 adbd，无法确认两端进程状态");
                    } else if (ctl == null) {
                        print("本次没启动过投屏，跳过收尾");
                    } else {
                        setPhase(Phase.STOPPING);
                        boolean ok = ctl.stop(deadline);
                        carOk = ok;
                        restored = ctl.isRestored();
                    }
                } else {
                    print("本次没连过 adb，跳过收尾");
                }
                // 2) 清临时文件（不删已投送产物：删了下次要多等 10~30s 重推）
                if (adb.isConnected() && ctl != null) {
                    residual = ctl.cleanupTemp();
                    print("临时文件清理：" + (residual == 0 ? "干净" : "残留 " + residual));
                }
                // 3) 本地缓存里的裁切底图
                File shot = new File(getCacheDir(), "crop.png");
                if (shot.exists() && !shot.delete()) print("（本地缓存 crop.png 没删掉，无害）");
            } catch (Throwable t) {
                print("!! 退出收尾异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                carOk = false;
            } finally {
                final long cost = System.currentTimeMillis() - t0;
                final boolean fCarOk = carOk;
                final boolean fRestored = restored;
                final boolean ok = fCarOk && fRestored;
                print("退出用时 " + cost + " ms" + (ok ? "" : "（未完全收尾）"));
                try {
                    adb.close();
                } catch (Throwable ignored) { }
                final boolean needAsk = !ok;
                runOnUiThread(() -> {
                    setBusy(false);
                    if (!needAsk) {
                        finishAffinity();
                        // 彻底退出：Activity 关掉之后进程默认还留着（下次变温启动）。
                        // 「退出应用」在用户心智里就是彻底退出，所以连进程一起收掉；
                        // 留 500ms 让 SharedPreferences 与日志落盘。
                        new android.os.Handler(getMainLooper()).postDelayed(
                                () -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
                    } else {
                        // 不能把用户困在这里，但也不能假装干净：说清楚哪里没停，让他选
                        new AlertDialog.Builder(this)
                                .setTitle("收尾未完成")
                                .setMessage("仍有环节未确认：\n" + exitProblem(fCarOk, fRestored)
                                        + "\n\n继续退出可能让仪表停在投屏画面上；"
                                        + "建议点「再收一次」等它结束。")
                                .setNegativeButton("强制退出", (d, w) -> finishAffinity())
                                .setPositiveButton("再收一次", (d, w) -> runExit())
                                .show();
                    }
                });
            }
        });
    }

    /**
     * 只说真的没确认的那一项。
     *
     * carOk 是 stop() 的总返回值（含仪表恢复），所以不能拿它单独判「进程没退」——
     * 否则仪表没恢复时会出现「进程未确认退出」的误报，把用户往错的方向引。
     */
    private String exitProblem(boolean carOk, boolean restored) {
        StringBuilder sb = new StringBuilder();
        if (!restored) sb.append("· 仪表未确认恢复原版面\n");
        else if (!carOk) sb.append("· 车机发送端 / 仪表端进程未确认退出\n");
        if (sb.length() == 0) sb.append("· 未取到确认信息\n");
        return sb.toString();
    }

    /** 三层小灯探一轮（一次 exec + 一次 RPC，约 2 秒） */
    private void refreshHealth() {
        if (!ensureConn()) return;
        VdCtl.Health h = ctl != null ? ctl.health() : null;
        if (h == null) {
            // ctl 还没建（没启动过投屏），建一个只为探测；不启动任何东西
            ensureCtl();
            h = ctl != null ? ctl.health() : null;
        }
        if (h == null) return;
        lastHealth = h;
        final VdCtl.Health hh = h;
        runOnUiThread(() -> paintHealth(hh));
        print("灯：ADB " + (h.adbOk ? "通" : "断")
                + " / 发送端 " + h.sender + " / 接收端 " + h.receiver
                + " / 车机 " + h.carsFpsText() + "fps / 仪表 " + h.icFpsText() + "fps"
                + "（" + h.costMs + "ms）");
        if (!h.adbOk) {
            print("!! adb 通道断了：车机重启 / 无线调试被关 / 端口变了");
            // 状态机别再说「投屏中」：灯红了、胶囊还绿着，用户会以为画面只是卡了一下
            setPhase(Phase.ERROR, "adb 断线");
        } else if (phase == Phase.CASTING && h.sender == 0) {
            print("!! 发送端不在了但面板还认为在投屏（B1 取消的代价）：按「停止」收尾后重来");
            setPhase(Phase.ERROR, "发送端已退出");
        } else if (phase == Phase.CASTING && h.receiver == 0) {
            print("!! 仪表接收端不在了：仪表可能重启过，按「停止」收尾后重来");
            setPhase(Phase.ERROR, "仪表接收端未运行");
        }
    }

    /** 按健康度给三盏灯上色 */
    private void paintHealth(VdCtl.Health h) {
        if (dotAdb == null) return;
        setDot(dotAdb, h.adbOk ? C_OK : C_DANGER);
        // 发送端/接收端：有进程=绿；没进程时，如果是「投屏中」就是红的（死了），
        // 否则是灰的（本来就没跑，不是故障）—— 这样才分得清「坏了」和「没开」
        boolean casting = phase == Phase.CASTING || phase == Phase.STARTING;
        paintProcLamp(dotSender, h.sender, casting);
        paintProcLamp(dotRecv, h.receiver, casting);
        if (fpsView != null) {
            fpsView.setText("车机 " + h.carsFpsText() + " · 仪表 " + h.icFpsText() + " fps");
            fpsView.setTextColor(h.adbOk ? C_MUTED : C_DANGER);
        }
    }

    private void paintProcLamp(TextView d, int n, boolean casting) {
        if (n < 0) setDot(d, C_MUTED);
        else if (n > 0) setDot(d, C_OK);
        else setDot(d, casting ? C_DANGER : C_MUTED);
    }

    // ============================================================ UI-12 进度条

    /**
     * VdCtl 每进入一步就回调一次。
     *
     * 之所以走回调而不是解析日志文本：日志格式会变（加了时间戳前缀、中括号），
     * 解析一崩进度条就永远停在第一格，而且崩了也没人发现。
     */
    @Override
    public void progress(int step, int total, String what) {
        runOnUiThread(() -> paintProgress(step, total, what));
    }

    private void paintProgress(int step, int total, String what) {
        if (progRow == null) return;
        if (total <= 0) {
            progRow.setVisibility(View.GONE);
            return;
        }
        progRow.setVisibility(View.VISIBLE);
        float frac = Math.max(0.02f, Math.min(1f, step / (float) total));
        LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) progFill.getLayoutParams();
        fp.weight = frac;
        progFill.setLayoutParams(fp);
        LinearLayout.LayoutParams rp = (LinearLayout.LayoutParams) progRest.getLayoutParams();
        rp.weight = Math.max(0.0001f, 1f - frac);
        progRest.setLayoutParams(rp);
        progLabel.setText("[" + step + "/" + total + "] " + what);
    }

    // ============================================================ UX-04 启动自检

    /**
     * 开机进面板先自检一次：上次是不是正常结束的。
     *
     * 只在未投屏时才有意义 —— 正在投屏时「有发送端进程、有虚拟屏、有接收端」全是正常的。
     * 成本同 health()（约 1.5~2s），所以只跑一次，靠 {@link #residueChecked} 挡住重复触发。
     */
    private void checkResidue() {
        if (residueChecked) return;
        residueChecked = true;
        run(() -> {
            // 首屏 ctl 还是 null，必须先走一次 ensureCtl（它会连 adb 并把 ctl 建起来）。
            // 之前只在 ctl != null 时做，结果首屏永远静默跳过 —— 自检等于没做。
            if (!ensureCtl()) {
                print("启动自检：连不上车机，跳过残留检查");
                return;
            }
            VdCtl.Residue r = ctl.scanResidue();
            lastResidue = r;
            runOnUiThread(() -> paintResidue(r));
            if (r.adbOk) {
                print(r.dirty()
                        ? "启动自检：发现上次残留 —— " + r.bannerText()
                        : "启动自检：干净（无残留进程 / 无残留虚拟屏 / 仪表已恢复）");
            } else {
                print("启动自检：连不上车机，跳过残留检查");
            }
        });
    }

    /** 按自检结果决定横幅出不出现。投屏中/启动中不显示（那时的进程数都是正常的） */
    private void paintResidue(VdCtl.Residue r) {
        if (residueBar == null) return;
        boolean idle = phase == Phase.IDLE || phase == Phase.CONNECTED;
        if (r == null || !r.adbOk || !r.dirty() || !idle) {
            residueBar.setVisibility(View.GONE);
            return;
        }
        residueText.setText(r.bannerText());
        residueBar.setVisibility(View.VISIBLE);
        if (btnClean != null) btnClean.setEnabled(!busy);
    }

    /** 一键清理残留：停车机发送端 + 停仪表接收端 + 确认恢复 + 清临时文件 */
    private void cleanResidue() {
        if (busy) return;
        // 别在这里先 setBusy(true)：run() 自己会置位，先置位会让它以为「上一条任务还在跑」
        // 直接拒绝执行（踩过，表现为点了没反应）
        run(() -> {
            boolean ok = ctl != null && ctl.cleanupResidue(0);
            // 清完再扫一遍：横幅该消失就消失，没清干净就留着并说明
            final VdCtl.Residue after = ctl == null ? null : ctl.scanResidue();
            lastResidue = after;
            if (ok && after != null && !after.dirty()) {
                print("残留已清理，可以正常启动");
            } else if (!ok) {
                print("!! 清理未完全成功，横幅仍显示剩余项；可再点一次或断电重启仪表");
            }
            runOnUiThread(() -> paintResidue(after));
        });
    }

    private void setDot(TextView d, int color) {
        if (d == null) return;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        d.setBackground(g);
    }

    private TextView dot(int color) {
        TextView t = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
        t.setLayoutParams(lp);
        setDot(t, color);
        return t;
    }

    private TextView lampLabel(String s) {
        TextView t = text(s, 20, C_MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    /** 工程级详情（UX-08）：点状态胶囊或高级里的「状态」 */
    private void showStatus() {
        // 展开是 UI 动作、取状态是网络动作，这里分两头：先展开（用户能立刻看到反应），
        // 再在 worker 线程里跑 status()（它要跑十几次 adb + 一次仪表 RPC）
        runOnUiThread(() -> {
            setAdvOpen(true);
            setLogOpen(true);
        });
        if (!ensureCtl()) return;
        print(ctl.status());
    }

    private void setAdvOpen(boolean open) {
        advOpen = open;
        if (advBody != null) advBody.setVisibility(open ? View.VISIBLE : View.GONE);
        if (advHead != null) advHead.setText(open ? "高级设置  ▾" : "高级设置  ▸");
        if (open && bodySv != null) {
            // 展开后内容会超过可视区，不滚一下用户只能看到「启动模式」这一行标题
            bodySv.post(() -> bodySv.fullScroll(View.FOCUS_DOWN));
        }
    }

    /**
     * 把关键控件的屏幕坐标打到日志。
     *
     * 为什么需要：面板是 freeform 窗口（mFrame=[960,0][2400,1080]），
     * 截图是 2400×1200 但窗口只到 1080，PC 侧用 input tap 反复点偏。
     * 有这一行就能一次算准，不用再猜。
     */
    private void dumpCoords() {
        int[] xy = new int[2];
        if (bodySv != null) {
            bodySv.getLocationOnScreen(xy);
            print("  滚动区: " + xy[0] + "," + xy[1] + "  " + bodySv.getWidth() + "x"
                    + bodySv.getHeight() + "  scrollY=" + bodySv.getScrollY());
        }
        for (View v : new View[]{btnStartVd, btnStop, btnPreview, advHead, btnLogToggle, btnDiag,
                btnExit, btnHealth, stateChip, pkgEdit, clusterEdit, btnReset}) {
            if (v == null) continue;
            v.getLocationOnScreen(xy);
            print("  坐标 " + tagOf(v) + ": " + xy[0] + "," + xy[1]
                    + "  " + v.getWidth() + "x" + v.getHeight()
                    + "  clickable=" + v.isClickable() + " enabled=" + v.isEnabled()
                    + " vis=" + (v.getVisibility() == View.VISIBLE ? "V" : "H"));
        }
    }

    private static String tagOf(View v) {
        if (v instanceof Button) {
            CharSequence t = ((Button) v).getText();
            return "[" + (t == null ? "" : t.toString().replace("  ", " ")) + "]";
        }
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            String s = t == null ? "" : t.toString();
            return "[" + (s.length() > 10 ? s.substring(0, 10) + "…" : s) + "]";
        }
        return "[" + v.getClass().getSimpleName() + "]";
    }

    private void setLogOpen(boolean open) {
        logOpen = open;
        if (logScroll != null) logScroll.setVisibility(open ? View.VISIBLE : View.GONE);
        if (btnLogToggle != null) btnLogToggle.setText(open ? "收起" : "展开");
    }

    /**
     * 连点日志标题 5 次进诊断模式（UI-11）。
     * 诊断模式的意义：让现场能自己把日志留住，不用连电脑。
     */
    private void onLogTitleTap() {
        logTitleTaps++;
        if (logTitleTaps >= 5 && !diagMode) {
            enableDiag();
            return;
        }
        // 没到 5 次就按普通点击处理：切换展开/收起
        if (!diagMode) setLogOpen(!logOpen);
    }

    /** 诊断模式：日志常驻展开 + 状态胶囊点了直接出工程详情 */
    private void enableDiag() {
        if (diagMode) {
            setLogOpen(!logOpen);
            return;
        }
        diagMode = true;
        setLogOpen(true);
        if (btnDiag != null) btnDiag.setText("诊断中");
        print("诊断模式已开启：日志常驻展开；点顶部状态胶囊可看工程详情（原始 status）");
    }

    /** 投屏区一行提示：面积 + 预期帧率（60 帧口径按 A） */
    /**
     * 投屏区一行的提示语（原「投屏区 550×384（自定义）· 21 万像素…」）。
     *
     * 2026-11-05 16:00 改文案：不再只报数字，而是先把「为什么该收窄」说清 ——
     * 仪表是安全件，不建议全屏投放，且全屏投放帧率会下降。
     * 具体文案由 {@link VdCtl#areaNote} 统一产出，日志与面板共用同一套档位。
     */
    private String areaHint() {
        int[] r = rect(txt(clusterEdit));
        if (r == null) return "投屏区格式应为 x,y,w,h（默认安全区 " + CRECT_DEFAULT + "）";
        return VdCtl.areaNote(r[2], r[3]);
    }

    /** 把仪表投屏区恢复成安全区默认值（1216×360 居中） */
    private void resetClusterArea() {
        clusterEdit.setText(CRECT_DEFAULT);
        print("仪表区域已复位到安全区 " + CRECT_DEFAULT);
    }

    /** 解析 x,y,w,h；格式不对返回 null */
    private static int[] rect(String s) {
        if (s == null) return null;
        String[] p = s.trim().split(",");
        if (p.length != 4) return null;
        try {
            int[] r = new int[4];
            for (int i = 0; i < 4; i++) r[i] = Integer.parseInt(p[i].trim());
            return r[2] > 0 && r[3] > 0 ? r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 首启引导（UX-09）：三条硬前提，只说一次 */
    private void showGuideIfNeeded() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        if (sp.getBoolean("guideV1", false)) return;
        sp.edit().putBoolean("guideV1", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("使用前请确认三件事")
                .setMessage("1. 车机首次连接会弹「允许 USB 调试」，请勾选「一律允许」再确定。\n\n"
                        + "2. 仪表必须通电开机，否则只能投到车机自己的屏幕上。\n\n"
                        + "3. 请在驻车状态下操作：行车中系统可能拦截投屏。")
                .setPositiveButton("知道了", null)
                .show();
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

    /** 打开独立选择页（UI-01：5 列图标网格 + 名称 + 运行中角标 + 搜索） */
    private void pickApp() {
        if (!ensureCtl()) return;
        // 运行中角标的数据还在 adb 这边：逐个 findTask 一次。
        // 成本和旧对话框一样（PERF-09 基线 656ms），只是把渲染搬到了独立页面。
        java.util.ArrayList<String> running = new java.util.ArrayList<>();
        try {
            for (String p : ctl.candidateApps()) {
                try {
                    if (ctl.findTask(p) != null) running.add(p);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            print("!! 取运行中列表失败（不影响选择）：" + t.getMessage());
        }
        final String sel = pkgEdit.getText().toString().trim();
        runOnUiThread(() -> {
            Intent i = new Intent(this, AppGridActivity.class);
            i.putStringArrayListExtra(AppGridActivity.EXTRA_RUNNING, running);
            i.putExtra(AppGridActivity.EXTRA_SEL, sel);
            startActivityForResult(i, REQ_PICK);
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

            final String src = ctl.shotSource;   // UI-02：底图来源带进页面并标出来

            final String crop = cropEdit.getText().toString().trim();
            // UI-13：锁比例要按「仪表区域」这一行算，而不是 cfg.outW/outH。
            // 矩形解析失败才退回 cfg（applyClusterCfg 刚刚同步过，兜底是安全区）。
            int[] cr = rect(clusterEdit.getText().toString().trim());
            final int ow = cr != null ? cr[2] : ctl.cfg().outW;
            final int oh = cr != null ? cr[3] : ctl.cfg().outH;
            final String crectTxt = clusterEdit.getText().toString().trim();
            runOnUiThread(() -> {
                Intent i = new Intent(this, CropActivity.class);
                i.putExtra("png", f.getAbsolutePath());
                i.putExtra("crop", crop);
                i.putExtra("crect", crectTxt);
                i.putExtra("out_w", ow);
                i.putExtra("out_h", oh);
                i.putExtra("src", src);
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
        if (req == REQ_PICK) {
            if (res != RESULT_OK || data == null) {
                print("选应用已取消");
                return;
            }
            String pkg = data.getStringExtra("pkg");
            if (pkg == null || pkg.isEmpty()) return;
            pkgEdit.setText(pkg);
            // 不在这里改 ctl.cfg().pkg：cfg 里的 pkg 属于「本次投出去的配置」，
            // 由 startWith 在启动时快照一次。投屏中换应用后立刻改它，
            // 停止时就会去恢复新应用的窗口，真正在虚拟屏上的旧窗口没人管（UX-17）。
            savePrefs();
            print("目标应用 -> " + pkg);
            // 投屏中换应用：refreshUi 会发现「界面配置 ≠ 已投出的快照」，
            // 把「启动投屏」重新变灰，提示需要再启动一次（UI-07）
            refreshUi();
            return;
        }
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
                int[] rr = rect(r);
                print("仪表投屏区 -> " + r
                        + (rr == null ? "" : "（" + VdCtl.areaText(rr[2], rr[3]) + "）"
                        + ((long) rr[2] * rr[3] > VdCtl.AREA_SAFE ? "  ← 超出安全区" : "")));
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
     * 预览 / 预演（两段式，UX-02）。
     *
     * 投屏中 → 实时预览：全屏看 VD 里那块画面（数据来自发送端的本地预览端口 5570）。
     * 没投屏 → 静态预演：截一张当前画面、按 crop 裁好、贴到仪表底图的 crect 上。
     *          零风险（不碰发送端、不占仪表），这是「先看效果再决定投不投」的那条路。
     *
     * 两种模式共用 {@link PreviewActivity} 的摆位逻辑，切换只换数据源。
     * 以前没投屏时这里只打一句「先启动再看」，等于把「先看效果」堵死了。
     */
    private void openPreview() {
        final String cropTxt = cropEdit.getText().toString().trim();
        final String crectTxt = clusterEdit.getText().toString().trim();
        if (phase == Phase.CASTING || phase == Phase.STARTING) {
            int port = ctl != null ? ctl.cfg().previewPort : 5570;
            Intent i = new Intent(this, PreviewActivity.class);
            i.putExtra("port", port);
            // 把当前裁切参数带过去，预览页顶栏/边框就能标出「这块是从哪儿裁出来的」
            i.putExtra("crop", cropTxt);
            i.putExtra("crect", crectTxt);
            startActivity(i);
            previewOpened = true;
            rehearsed = true;
            refreshUi();
            return;
        }
        // 静态预演：取一张当前画面（有虚拟屏就截虚拟屏），页面上会写明底图来源（UI-02）
        print("静态预演：取当前画面当底图…");
        run(() -> {
            if (!ensureCtl()) return;
            try {
                byte[] png = ctl.snapshot();
                File f = new File(getCacheDir(), "preview_static.png");
                try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(png); }
                final String src = ctl.shotSource;
                runOnUiThread(() -> {
                    Intent i = new Intent(this, PreviewActivity.class);
                    i.putExtra("mode", "static");
                    i.putExtra("png", f.getAbsolutePath());
                    i.putExtra("crop", cropTxt);
                    i.putExtra("crect", crectTxt);
                    i.putExtra("src", src);
                    startActivity(i);
                    previewOpened = true;
                    rehearsed = true;
                    refreshUi();
                });
                print("静态预演：底图 " + src + " · 仪表 " + crectTxt + "（没有起投屏，零风险）");
            } catch (Throwable t) {
                print("!! 静态预演取图失败：" + t.getMessage());
                print("   可以直接点「启动投屏」后再预览（实时流来自发送端）");
            }
        });
    }

    /** 打开仪表区域选择页（底图是打包进来的 fb0_pre.png，原生 1920x720） */    private void pickClusterArea() {
        Intent i = new Intent(this, ClusterPickActivity.class);
        i.putExtra("crop", clusterEdit.getText().toString().trim());
        i.putExtra("top", 72);
        i.putExtra("bot", 80);
        startActivityForResult(i, REQ_CLUSTER);
    }

    /**
     * 把界面上的仪表矩形写进 cfg。
     *
     * 解析失败退回**安全区**（不是满宽横带）：口径改 B 之后，兜底也该落在安全的那一侧 ——
     * 脏值绝不能把整块仪表占满。
     */
    private void applyClusterCfg() {
        int x = VdCtl.DEF_CX, y = VdCtl.DEF_CY, w = VdCtl.DEF_CW, h = VdCtl.DEF_CH;
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
            x = VdCtl.DEF_CX; y = VdCtl.DEF_CY; w = VdCtl.DEF_CW; h = VdCtl.DEF_CH;
            print("仪表区域格式不对，按安全区 " + CRECT_DEFAULT + " 启动");
        }
        ctl.cfg().cCustom = !VdCtl.isFullBandRect(x, y, w, h, ctl.cfg().top, ctl.cfg().bot);
        ctl.cfg().cX = x;
        ctl.cfg().cY = y;
        ctl.cfg().cW = w;
        ctl.cfg().cH = h;
        // 立刻对齐 outW/outH：框选页要用它锁比例，启动前也要靠它算发送尺寸。
        // 以前只在 start() 里同步，改完矩形马上去框选会锁到上一次的比例（UI-13）。
        ctl.cfg().normalize();
        // 超安全区就留一条日志，事后能对上是哪次配置选了大区域
        if ((long) ctl.cfg().cW * ctl.cfg().cH > VdCtl.AREA_SAFE && ctl.cfg().cCustom) {
            print("!! 投屏区 " + VdCtl.areaText(ctl.cfg().cW, ctl.cfg().cH)
                    + " 超出安全区，仪表是安全件，不建议铺满");
        }
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
        // UX-06：上次的残留还挂着就提一句。不硬拦 —— [2/8] 本来就会先清车机侧，
        // 硬拦反而让「明明能起来」的场景变成点了没反应。
        VdCtl.Residue rr = lastResidue;
        if (rr != null && rr.dirty()) {
            print("提醒：上次残留未清（" + rr.bannerText() + "）");
            print("      本次会先清车机侧；仪表侧清不掉就点顶栏「一键清理」");
        }
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
        // 启动流程顺手采到的健康数据（零额外成本），直接点亮三层小灯
        VdCtl.Health h = ctl.startHealth();
        if (h != null) {
            lastHealth = h;
            runOnUiThread(() -> paintHealth(h));
        }
        if (ok) nudgeCarFps();
        else {
            // 启动失败时 [3/8] 可能已经把目标应用拉到前台，面板被盖住 ——
            // 错误信息和「下一步」全在面板上，看不见等于没说（UI-12/UX-06）。
            // 注意这里是后台线程，直接执行；不能再走 run()，那会因 busy 被自己拒绝。
            // 拉回前台是尽力而为，失败也不能再抛异常盖掉真正的报错。
            try {
                adb.exec("am start -n com.xiaopeng.vdcast/.MainActivity", 12000);
            } catch (Exception ignore) { }
        }
    }

    /**
     * 启动后补一次车机帧率。
     *
     * VdSend 是每 2 秒才打第一行 `fps=`，启动流程跑到 [7/8] 时经常还没输出，
     * 于是灯全绿但「车机 --」。这里单独起个线程等 3 秒再取一次 ——
     * 不走 worker（会占住后续操作），也不影响启动耗时。
     */
    private void nudgeCarFps() {
        final Adb a = adb;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);
                if (!a.isConnected()) return;
                String out = a.exec("grep 'fps=' /data/local/tmp/vdsend.log 2>/dev/null | tail -1", 12000);
                final double fps = numOf(out, "fps=(\\d+(?:\\.\\d+)?)");
                if (fps < 0) return;
                VdCtl.Health h = lastHealth;
                if (h == null) return;
                h.carFps = fps;
                runOnUiThread(() -> {
                    if (fpsView != null) {
                        fpsView.setText("车机 " + h.carsFpsText()
                                + " · 仪表 " + h.icFpsText() + " fps");
                    }
                });
            } catch (Throwable ignored) { }
        }, "fps-nudge");
        t.setDaemon(true);
        t.start();
    }

    /** 从文本里按正则取第一个数字；取不到 -1 */
    private static double numOf(String s, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Throwable ignored) { }
        return -1;
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
        // UI-12：被丢弃的请求不能只写一行日志 —— 用户点完什么都没变就会反复点。
        // 用 `!!` 让日志摘要变红并自动展开，视线范围内一定看得见。
        if (busy) {
            print("!! 上一条任务还在跑，这次的点击没执行（等它结束，或点「中断」）");
            return;
        }
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

    /**
     * 连上了：只在「还没进入链路」时把 phase 拨到 CONNECTED，别覆盖 CASTING/STARTING。
     *
     * 也不覆盖 ERROR —— 这条踩过：stop 报「仪表未确认恢复」把 phase 设成 ERROR，
     * 紧接着 refreshHealth 里的 ensureConn() 顺手调到这里，把 ERROR 又抹回「就绪」，
     * 结果日志红字、胶囊绿色。错误态是给用户看的结果，不能被一次心跳探测悄悄消掉。
     */
    private void markConnected() {
        if (phase == Phase.IDLE) setPhase(Phase.CONNECTED);
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
                // 这里是真的新建了一条连接，从任何状态都该回到「就绪」
                setPhase(Phase.CONNECTED);
                return true;
            }

            // adbd 可能只绑在网卡地址上，兜底试本机各网卡 IP
            for (String ip : localIpv4()) {
                print("连接 " + ip + ":" + port + " …");
                if (adb.connect(ip, port, 90)) {
                    setPhase(Phase.CONNECTED);
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
        final String line = "[" + ts.format(new Date()) + "] " + s;
        // 同步一份到 logcat，方便 PC 侧 adb logcat -s ADBPROBE:I 回读（车机屏不方便滚动时用）
        android.util.Log.i("ADBPROBE", line);
        logBuf.append(line).append('\n');
        if (logBuf.length() > 200_000) logBuf.delete(0, 100_000);
        final boolean err = s.contains("!!");
        final String brief = briefLine(s);
        runOnUiThread(() -> {
            if (logView == null) return;
            logView.setText(logBuf.toString());
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            if (logSummary != null) {
                logSummary.setText(brief);
                logSummary.setTextColor(err ? C_DANGER : 0xFF5B6673);
            }
            // 出错自动展开：折叠着看不见错误等于没提示（UI-11）
            if (err && !logOpen && !diagMode) setLogOpen(true);
        });
    }

    /** 日志折叠时那一行摘要：去掉 `!!` 前缀并截断 */
    private static String briefLine(String s) {
        String t = s.replace("!!", "").trim().replace("\n", " ");
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
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
