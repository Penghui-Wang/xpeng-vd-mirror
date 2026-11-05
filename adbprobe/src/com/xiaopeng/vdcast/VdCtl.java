package com.xiaopeng.vdcast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 投屏控制面：把 PC 上 tools/startcast.sh、stopcast.sh、status.sh 的三套流程翻译成 adb 命令序列。
 *
 * 设计原则（照抄脚本里踩过的坑）：
 *   1. 车机端必须由 adbd 派生（shell 域），所以一律走 exec/setsid，不走 MediaProjection。
 *   2. 起长驻进程必须 `setsid ... > log 2>&1 &`，让 shell 立刻返回；否则撞 adbd 的流超时。
 *   3. 停进程只能靠 killvds.sh（ps + /proc/<pid>/cmdline 以 app_process 开头），
 *      因为 `pkill -f 类名` 会把执行它的 shell 自己一起杀掉。
 *   4. 仪表端没有 pkill，统一 ps -o pid,args + awk + kill，并把匹配串拆开避免命中自身。
 *
 * 本类不依赖任何 android.* 类型（资源和 UI 部分交给调用方注入），便于在 JVM 上跑本地 mock 验证。
 */
public final class VdCtl {

    public interface Log {
        void print(String s);
        /**
         * 进度上报（UI-12）。默认空实现，老调用方不用改。
         *
         * @param step  第几步（从 0 开始，8 步流程时最大 8）
         * @param total 总共几步
         * @param what  这一步在干什么，可直接显示在进度条旁边
         */
        default void progress(int step, int total, String what) { }
    }

    /** 当前进度（默认 0/0 = 没在跑），供面板轮询 */
    private volatile int progStep;
    private volatile int progTotal;
    private volatile String progWhat = "";

    public int progStep() { return progStep; }
    public int progTotal() { return progTotal; }
    public String progWhat() { return progWhat; }

    /**
     * 中断标志（UI-12）：设了之后，**下一步开始前**就退出。
     *
     * 正在跑的那条 adb 命令没法打断（killvds stop 最长 4.4s），所以中断粒度是「步」而不是「毫秒」。
     * 这一点如实写在面板上：点「中断」后会说「等当前这一步结束」。
     */
    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }

    public void clearCancel() { cancelled = false; }

    /** 打一步的标题并上报进度；返回 true 表示已被要求中断 */
    private boolean step(int n, int total, String what) {
        if (cancelled) {
            log.print("已中断（停在第 " + progStep + " 步，后续步骤跳过）");
            // 中断点如果在 [5/8] 之后，两端进程其实已经起来了 —— 不提示的话
            // 用户会以为「没启动成功」，然后直接退出，把两个进程留在车上
            if (progStep >= 5) {
                log.print("   已经起来的发送端/接收端不会自动回收：点「停止」收尾");
            }
            log.progress(progStep, total, "已中断");
            return true;
        }
        progStep = n;
        progTotal = total;
        progWhat = what;
        log.progress(n, total, what);
        log.print("[" + n + "/" + total + "] " + what);
        return false;
    }

    private void progressDone() {
        progStep = 0;
        progTotal = 0;
        progWhat = "";
        log.progress(0, 0, "");
    }

    // ============================================================ 投屏区口径

    /** 仪表 fb1 尺寸，也是仪表投屏矩形的坐标系 */
    public static final int CL_W = 1920;
    public static final int CL_H = 720;

    /**
     * 推荐投屏区面积上限（像素）。**只用来定默认方案，不当作帧率承诺。**
     *
     * 60Hz 预算 16.7ms，扣掉 read/draw/flush 只剩 10~11ms，按仪表端解码 ≈20~24 ns/px
     * 反推面积上限约 45~55 万像素（见 docs/B0-基线数据-v1.md §7）。
     *
     * 但**实际帧率还受画面复杂度影响**（静态图与高动态画面的解码/绘制成本不同），
     * 所以面板上不承诺帧率，只说面积 —— 真帧率看三层小灯的实测值。
     *
     * 口径在 2026-11-05 16:00 由「A：承认物理上限、按面积分档」改为
     * 「B：默认矩形直接收窄到安全区」——理由是**仪表是安全件**，不该默认被投屏占满。
     * 全屏仍能做，只是不推荐。
     */
    public static final int AREA_SAFE = 500000;

    /**
     * 推荐默认矩形：1216×360 = **43.8 万像素**，居中于 1920×720（x=352, y=180）。
     *
     * 为什么是这几个数：
     * 1. 默认 crop 1440×426 是 3.38:1，只有等比才不会变形；1216×360 差 0.07%，肉眼看不出。
     * 2. 面积压在 45 万一线之下（按解码预算推算的上限），但**不承诺任何帧率** ——
     *    实际帧率随画面复杂度浮动，面板只报面积。
     * 3. 四个数都是偶数，且 x/y 正好整除：352×2+1216=1920、180×2+360=720，
     *    严格居中，不用为奇偶对齐做偏移。
     *
     * 缩放 0.845×（原满宽横带是 1.33× 放大），画质不但没降，反而更锐。
     */
    public static final int DEF_CX = 352;
    public static final int DEF_CY = 180;
    public static final int DEF_CW = 1216;
    public static final int DEF_CH = 360;
    public static final String DEF_RECT = DEF_CX + "," + DEF_CY + "," + DEF_CW + "," + DEF_CH;

    /**
     * 是否「满宽横带」，也就是全屏投放。
     *
     * 仍然保留这条路径：车主想临时看全屏时可以手动填 0,top,1920,720-top-bot，
     * 但默认不再走它 —— 默认是 {@link #DEF_RECT}。
     */
    public static boolean isFullBandRect(int x, int y, int w, int h, int top, int bot) {
        return x == 0 && y == top && w == CL_W && h == CL_H - top - bot;
    }

    /** 启动参数 */
    public static final class Cfg {
        public String mode = "vd";                 // vd | main
        public int fps = 60;
        public int hold = 86400;                   // 仪表端断流超时，每来一帧重置
        public int outW = DEF_CW;
        public int outH = DEF_CH;
        public int top = 72;
        public int bot = 80;
        public String pkg = "com.tencent.qqmusicpad";
        public String crop = "960,345,1440,426";   // x,y,w,h（车机屏幕绝对坐标）
        public String carIpFallback = "172.20.1.45";
        /** 本地预览端口：发送端在 127.0.0.1 上多播原始 565 帧，APK 的预览页连它 */
        public int previewPort = 5570;
        /**
         * 仪表端投屏矩形（fb1 坐标，1920×720）。
         *
         * cCustom=true（默认）：用下面的 cX/cY/cW/cH，出厂值即安全区 1216×360（43.8 万像素，跑满 60 帧）。
         * cCustom=false：满宽横带 0,top,1920,720-top-bot = 全屏投放。仍支持，但不推荐 ——
         *   仪表是安全件，全屏会把它整个盖住，且面积 109 万像素时帧率只有 30~40。
         */
        public boolean cCustom = true;
        public int cX = DEF_CX;
        public int cY = DEF_CY;
        public int cW = DEF_CW;
        public int cH = DEF_CH;

        /**
         * 把「仪表投屏矩形」和「发送尺寸」对齐。幂等，可以在任何时机反复调。
         *
         * 这段逻辑原来只写在 start() 的 cCustom 分支里，导致一个真实回退（UX-15）：
         * 先投自定义矩形 550x384、再切回满宽横带，outW/outH 会一直停在 550x384，
         * 于是拿着一块 550x384 的图往 1920x568 的带子里画 ——
         * 实测 draw 2ms→21.5ms、total 36.8ms、27fps、画面拉伸。
         *
         * 现在两种情况都收敛到这里：满宽横带也必须显式把 outW/outH 打回 1920 x
         * (720-top-bot)，绝不复用上一次的自定义值。
         */
        public void normalize() {
            if (cCustom) {
                cW = Math.max(2, Math.min(CL_W, cW));
                cH = Math.max(2, Math.min(CL_H, cH));
                if (cX < 0) cX = 0;
                if (cY < 0) cY = 0;
                if (cX + cW > CL_W) cX = CL_W - cW;
                if (cY + cH > CL_H) cY = CL_H - cH;
            } else {
                // 满宽横带 = 全屏投放：矩形由 top/bot 决定，忽略外部填进来的 cX/cY/cW/cH。
                // 这条分支现在只是「车主手动要看全屏」的可选路径，默认不走它。
                cX = 0;
                cY = top;
                cW = CL_W;
                cH = Math.max(2, CL_H - top - bot);
            }
            outW = cW;
            outH = cH;
        }

        /** 投屏区像素数，用于按面积判断帧率预期（见 docs/B0-基线数据-v1.md §7） */
        public int areaPx() { return cW * cH; }

        /** 面积是否在安全区内（≤ {@link #AREA_SAFE}） */
        public boolean inSafeArea() { return (long) cW * cH <= AREA_SAFE; }

        /** 是否按「满宽横带」投（= 全屏投放，不推荐） */
        public boolean isFullBand() { return !cCustom; }

        /** 把矩形恢复成推荐默认值（安全区），幂等 */
        public void useSafeDefault() {
            cCustom = true;
            cX = DEF_CX;
            cY = DEF_CY;
            cW = DEF_CW;
            cH = DEF_CH;
            normalize();
        }
    }

    private static final String DEX = "/data/local/tmp/vd.dex";
    private static final String KILLVDS = "/data/local/tmp/killvds.sh";
    private static final String VDSEND_LOG = "/data/local/tmp/vdsend.log";
    private static final String ICBIN = "/tmp/icrecv_dbg";
    private static final int PORT = 5560;

    /**
     * 工具同步状态（进程级，只在本 App 这次运行里有效）。
     *
     * 正常使用中车机上的 dex 和仪表上的二进制都是稳定的，只有换 APK 版本才需要重投，
     * 而换版本进程必然重启 —— 所以「本次开 App 后第一次启动投屏核对一次」就够，
     * 之后每次点「启动」秒起，不再花 10~30s 推 3.4MB。
     *
     * 车机/仪表重启会清掉 /data/local/tmp 与 /tmp，所以「已同步」之后仍会花几毫秒
     * 探一下文件在不在（test -s / test -x），不在就自动重投；这个探针比 md5 便宜得多。
     */
    private static boolean dexSynced;
    /** 本次运行里仪表端二进制已经核对/投送过 */
    private static boolean binSynced;

    /**
     * 上次成功投送到仪表的那份二进制的 md5（跨进程记忆，由 MainActivity 从
     * SharedPreferences 灌进来 / 成功后写回去）。
     *
     * 这样「同一个 APK 版本，第二次打开」连 md5 都不用查：本地记录一拍即合，
     * 只花 3 秒探一下 /tmp/icrecv_dbg 还在不在。以前每次启动都要查一次仪表
     * md5，而 Icrpc 是「sleep N 撑住 nc」的实现，一次查询就固定等 6 秒。
     */
    private String knownBinMd5 = "";
    private String binMd5 = "";

    public void setKnownBinMd5(String md5) { knownBinMd5 = md5 == null ? "" : md5.trim(); }

    /** 本次用的二进制 md5；投送成功后由界面存进 prefs */
    public String binMd5() { return binMd5; }

    /** act=resync 后的下一次启动：连 prefs 里记的 md5 也不认，强制重新核对/重推 */
    private static boolean forceResync;

    /** 调试用：让下一次启动重新核对 / 重推（遥控 act=resync） */
    public static void resetToolCache() {
        dexSynced = false;
        binSynced = false;
        forceResync = true;
    }

    private final Adb adb;
    private final Log log;
    private final Cfg cfg;

    /**
     * 本次真正投出去的目标应用（启动时快照）。
     *
     * stop / status 必须用它，而不是 cfg.pkg：投屏中用户可以在面板上换成别的应用，
     * cfg.pkg 一改，停止时就会去「恢复新应用的窗口」，而真正在虚拟屏上的是旧应用 ——
     * 旧窗口留在已销毁的虚拟屏上，正是 PERF-05 记录的那类隐患。
     */
    private String activePkg = "";

    /**
     * 目标应用是不是「本次由我们拉起」的（UX-13）。
     *
     * 只用于**记录与提示**，绝不用于关进程：用户可能正开着音乐 App 一边听一边投，
     * 退出时把它杀掉是最典型的误伤（R2-1 的默认值就是「不代关」）。
     * 这里留一个标记，stop 时如实说明「是我们拉起的，仍留着」或「本来就在跑，没动过」。
     */
    private boolean launchedByUs;

    /**
     * 本次进程里仪表接收端「真的起来过」。restore 幂等的依据：
     *
     * 没起过接收端，仪表 fb1 根本没被碰过，这时候去等 RESTORE 标记是白等（一次 RPC 1.5~2s），
     * 而且永远等不到 → 会被误报成「恢复失败」。所以只有起过才要求看到恢复标记。
     */
    private boolean receiverEverStarted;
    /** 仪表已确认恢复原版面（看到 RESTORE_OK / pixels restored / RESTORED blank） */
    private boolean receiverRestored = true;

    /** 仪表端二进制：null 表示 APK 里没带，只能指望车机上已有或用 /sdcard 旁路 */
    private byte[] icrecv;

    public VdCtl(Adb adb, Log log, Cfg cfg) {
        this.adb = adb;
        this.log = log;
        this.cfg = cfg;
    }

    public void setClusterBinary(byte[] bin) { this.icrecv = bin; }

    /** 让调用方在启动前改参数（模式、fps、裁切等） */
    public Cfg cfg() { return cfg; }

    /**
     * 投屏区的面积描述。**只说面积，不说帧率。**
     *
     * 之前这里会给「可跑满 60 帧 / 约 55~60 帧 / 约 30~40 帧」这类档位，
     * 2026-11-05 16:12 车主明确要求去掉：帧率受画面复杂度影响，静态画面和高动态
     * 画面不是一回事，写死档位等于给自己挖坑。真帧率一律以三层小灯的实测值为准。
     */
    public static String areaText(int w, int h) {
        return w + "×" + h + " · "
                + String.format(Locale.US, "%.1f 万像素", (long) w * h / 10000.0);
    }

    /**
     * 面板上的投屏区提醒：把它当成**方案选择**来说，不承诺帧率。
     *
     * 原来这一行是「投屏区 550×384（自定义）· 21 万像素，可跑满 60 帧」——
     * 只报数字，不解释为什么该收窄。现在改成把理由说在前面：
     * **仪表是安全件，不建议全屏投放；面积越大，帧率越容易掉。**
     *
     * @return 一行提示，超安全区时以「⚠」开头
     */
    public static String areaNote(int w, int h) {
        long px = (long) w * h;
        String size = areaText(w, h);
        if (px > AREA_SAFE) {
            // 显式换行：整句太长会顶到卡片右边，读起来像被裁掉
            return "⚠ 仪表为安全件，不建议全屏投放：当前 " + size + "\n"
                    + "面积越大帧率越容易掉，建议改回安全区 " + DEF_RECT;
        }
        return "仪表为安全件，建议只用安全区（默认 " + DEF_RECT + "）：当前 " + size;
    }

    // ============================================================ 启动

    /**
     * 启动失败的统一出口（UX-06）：停在具体第几步、为什么、下一步点什么。
     *
     * 以前各步失败只打一行 `!!`，用户看完还是不知道该动哪里 —— 尤其「目标应用没有窗口」
     * 和「仪表端没响应」这两类，修复动作完全不同却都表现为一句报错。
     *
     * @param step 第几步（对应的 [n/8] 编号）
     * @param what 这一步出了什么事
     * @param next 下一步该做什么
     */
    private boolean fail(int step, String what, String next) {
        log.print("!! 停在 [" + step + "/8]：" + what);
        log.print("   下一步：" + next);
        return false;
    }

    public boolean start() {
        try {
            // 自定义仪表区域 / 满宽横带都先规范化：夹到 1920x720 内，并让发送尺寸
            // 跟矩形严格一致，否则仪表端要拉伸，画面会扁/拉长（UX-15）。
            cfg.normalize();
            // 上一次按过「中断」不能影响这一次；进度也要归零重算
            cancelled = false;
            progressDone();
            // 投出去的就是这一刻的 pkg，之前/之后界面上再改都与本次投屏无关
            activePkg = cfg.pkg;
            // 顺手把这一轮已经采到的数据填成健康快照：面板上的三层小灯直接用它，
            // 不用再额外跑一次 health()（那要 2 秒，会把「启动到出画面」拖长）
            lastStartHealth = new Health();
            lastStartHealth.adbOk = true;
            if (step(0, 8, "检查车机 adb（" + adb.endpoint + "）")) return false;
            String ok = adb.exec("echo car_ok", 8000);
            if (!ok.contains("car_ok")) {
                return fail(0, "车机 adb 不通（" + adb.endpoint + "）",
                        "确认车机在开机、adb 端口没变、手机/PC 与车机同网段；改「高级 → 端口」后可重试");
            }
            log.print("  ok (mode=" + cfg.mode + ", 目标=" + cfg.pkg + ", "
                    + cfg.outW + "x" + cfg.outH + ", " + cfg.fps + "fps)");
            log.print("  投屏区 " + areaText(cfg.cW, cfg.cH)
                    + (cfg.areaPx() > AREA_SAFE ? "  (超出安全区)" : "  (安全区)"));

            // 发送端只认 vdcrop.txt（它靠这个文件热重载），所以启动前必须把界面上
            // 当前填的裁切值落盘，否则用户改了输入框没点「应用」就直接启动，
            // 实际用的还是上一次的旧裁切 —— 这个坑踩过。
            if (cfg.crop != null && cfg.crop.matches("\\d+,\\d+,\\d+,\\d+")) {
                adb.exec("echo '" + cfg.crop + "' > /data/local/tmp/vdcrop.txt", 8000);
                log.print("  裁切 " + cfg.crop + " 已写入 " + "/data/local/tmp/vdcrop.txt");
            }

            if (step(1, 8, "推发送端 dex + 杀进程脚本")) return false;
            if (!pushDex()) {
                return fail(1, "发送端 dex 推送失败",
                        "看上面的大小比对；多半是 /data/local/tmp 写不进，重连 adb 后重试");
            }
            adb.push(readAssetKillvds(), KILLVDS, Adb.MODE_EXEC, 20000);
            adb.exec("chmod 755 " + KILLVDS + "; rm -f /data/local/tmp/vdcap", 10000);
            log.print("  " + DEX + " (" + dexSize + " B)");

            if (step(2, 8, "停旧发送端（先 TERM 让 vd 模式把窗口搬回去）")) return false;
            String out = killSender(cfg.pkg);
            for (String line : tail(out, 3).split("\n")) {
                if (!line.trim().isEmpty()) log.print("  " + line.trim());
            }

            // 必须放在起发送端之前：VdSend 的 vd 模式一上来就要找目标窗口，
            // 找不到会直接抛异常退出（表现就是「投出来的是主屏/控制台自己」）。
            if ("vd".equals(cfg.mode)) {
                if (step(3, 8, "确保目标应用有窗口：" + cfg.pkg)) return false;
                if (!ensureTargetApp()) {
                    return fail(3, "目标应用没有可用窗口：" + cfg.pkg,
                            "确认它已安装、并且手动打开过一次（有的车机应用不允许后台拉起）；"
                                    + "或点「目标应用 → 更换」换一个可投的");
                }
            }

            if (step(4, 8, "确保仪表端 " + ICBIN)) return false;
            if (!ensureClusterBinary()) {
                return fail(4, "仪表端程序投送失败",
                        "确认仪表已开机、30510 端口在听；仪表重启后 /tmp 会被清空，需要重投一次。"
                                + "可在「高级 → 自检」看仪表侧是否可达");
            }

            if (step(5, 8, "起两端（发送端后台 + 接收端，并行）"
                    + "（投屏区 "
                    + (cfg.cCustom ? cfg.cX + "," + cfg.cY + " " + cfg.cW + "x" + cfg.cH
                                   : "满宽横带 y=" + cfg.top + ".." + (720 - cfg.bot))
                    + "）")) return false;
            // B5-C：发送端从「拉起」到 capture ready 约 1.9s，接收端起来只要约 0.5s。
            // 先拉起发送端但不等它就绪，紧接着起接收端 —— 两段重叠，省掉那 0.5s。
            // 顺序不能反：VdSend 真正开始发帧是在 capture ready 之后，那时接收端早已 listen，
            // 所以不会出现「连不上被拒」的 send FAIL。
            launchSender();
            int alive = startReceiver();
            lastStartHealth.receiver = alive;
            log.print("  接收端进程: " + alive + "（应为 1）");
            if (alive <= 0) {
                // 多半是端口被上一次没退干净的接收端占着（/tmp/icrecv.log 里会有 listen: 错误）
                String tailLog = Icrpc.run(adb, "tail -3 /tmp/icrecv.log 2>/dev/null", 4, 30000);
                logIndented(tailLog);
                return fail(5, "仪表接收端没起来（进程 0）",
                        "看上面 icrecv.log 末尾：出现 listen/Address already in use 就是端口被占，"
                                + "点顶栏「一键清理」后再启动；否则确认仪表已开机");
            }

            if (step(6, 8, "等车机发送端就绪（capture ready，mode=" + cfg.mode + "，" + cfg.fps + " fps）")) return false;
            awaitSenderReady();

            if (step(7, 8, "等端到端出帧（轮询，出帧就往下走）")) return false;
            log.print("  --- 仪表：接收/合成 ---");
            // 仪表侧也轮询：日志里出现 frames= 就说明真的收到帧了，不用再干等。
            // 上限 5 秒，超了照样往下走（真没帧的话 [8/8] 会报出来）。
            // 放在车机速率之前：这一步本来就要一秒多，正好让 VdSend 把第一行 fps= 打出来。
            String icOut = Icrpc.run(adb, "i=0; while [ $i -lt 25 ]; do "
                    + "grep -q 'frames=' /tmp/icrecv.log && break; i=$((i+1)); sleep 0.2; done; "
                    + "grep -E 'frames=|LISTEN|VISIBLE|DST ' /tmp/icrecv.log | tail -3", 8, 45000);
            logIndented(icOut);
            lastStartHealth.icFps = maxOf(lastStartHealth.icFps, num(icOut, "(\\d+(?:\\.\\d+)?)fps"));
            // 收端没出帧 / 也没打印监听与几何，说明画面根本没到仪表
            boolean gotFrames = icOut.contains("frames=") || icOut.contains("VISIBLE");
            log.print("  --- 车机：采集/发送 ---");
            String carOut = adb.exec("grep 'fps=' " + VDSEND_LOG + " | tail -2", 10000);
            logIndented(carOut);
            lastStartHealth.carFps = maxOf(lastStartHealth.carFps, num(carOut, "fps=(\\d+(?:\\.\\d+)?)"));

            if (step(8, 8, "健康检查")) return false;
            // 两件小事合成一次往返：异常计数 + 发送端存活数
            // （grep -c 在 0 匹配时退出码是 1，所以不能写 `|| echo 0`，那会多打一行）
            String[] hc = adb.exec("echo BAD=$(grep -cE 'capture null|SF capture degraded|send FAIL' "
                    + VDSEND_LOG + " 2>/dev/null); "
                    + "echo PROC=$(sh " + KILLVDS + " check 2>/dev/null)", 20000).split("\n");
            String bad = "", proc = "";
            for (String line : hc) {
                String t = line.trim();
                if (t.startsWith("BAD=")) bad = t.substring(4).trim();
                else if (t.startsWith("PROC=")) proc = t.substring(5).trim();
            }
            log.print("  车机异常计数: " + (bad.isEmpty() ? "0" : bad));
            lastStartHealth.sender = parseInt(proc);
            // 发送端没起来时，日志里的 fps 是上一轮的，别拿来点灯
            if (lastStartHealth.sender <= 0) lastStartHealth.carFps = -1;
            if ("vd".equals(cfg.mode)) {
                // 用 runningTasks 解析，别再用 grep 拼 am stack list 的文本：
                // 窗口搬进虚拟屏之后 RootTask 段落格式会变，grep 常常一无所获，误报「未找到」。
                VdCtl.TaskInfo ti = findTask(cfg.pkg);
                log.print("  目标窗口: " + (ti == null
                        ? "(没找到 task，目标 app 可能已退出)"
                        : ti.label() + (ti.displayId > 0 ? "  → 已在虚拟屏" : "  → 仍在主屏")));
            }
            // 发送端是否真的活着比什么都重要：崩了的话上面全是空输出
            if (proc.startsWith("0") || proc.isEmpty()) {
                log.print("!! 发送端已退出，最后几行日志：");
                logIndented(adb.exec("tail -6 " + VDSEND_LOG, 10000));
                return fail(8, "车机发送端起来后立刻退出了",
                        "看上面 tail 的报错：目标应用无窗口 / 虚拟屏创建失败 / capture 被拒。"
                                + "最常见是目标应用在启动瞬间退出了，换一个应用重试");
            }
            if (!gotFrames) {
                // 进程都在但仪表没收到帧：链路断在中间，这时候说「就绪」就是在骗人
                log.print("!! 两端进程都在，但仪表 5 秒内没收到任何一帧");
                log.print("   下一步：点「停止」收尾后重来；反复出现就查仪表 5560 端口是否可达");
                return false;
            }
            log.print("");
            log.print("就绪。改裁切区：点「框选」在截图上拖，或直接填 x,y,w,h（热生效约 0.5s）");
            return true;
        } catch (Throwable t) {
            log.print("!! 启动中断: " + brief(t));
            log.print("   下一步：点「停止」把两端收干净再重试");
            return false;
        } finally {
            progressDone();
        }
    }

    // ============================================================ 启动自检（UX-04）

    /**
     * 上一轮是否正常结束的取证结果。
     *
     * 关车/断电后重进，界面上看不出上一次留了什么 —— 而残留物恰恰是「第一次启动
     * 就莫名其妙失败」的主因：旧发送端占着虚拟屏、旧接收端占着端口、以及最危险的
     * 一种：**仪表进程没了但 fb1 没恢复，仪表就停在最后一帧投屏画面上**。
     */
    public static final class Residue {
        /** adb 通道是否活 */
        public boolean adbOk;
        /** 车机发送端进程数；-1 = 没测出来 */
        public int sender = -1;
        /** 车机上 asxvd 虚拟屏个数；-1 = 没测出来 */
        public int vdDisplay = -1;
        /** 仪表接收端进程数；-1 = 没测出来 */
        public int receiver = -1;
        /** /tmp/icrecv.log 最后一条有效记录（出帧行或 RESTORE 行） */
        public String lastLine = "";
        /** 仪表可能停在投屏画面上（进程没了，但最后一条记录是出帧） */
        public boolean stuck;
        public long costMs;

        /** 有进程级残留 */
        public boolean hasProc() { return sender > 0 || receiver > 0 || vdDisplay > 0; }

        /** 需要用户处理 */
        public boolean dirty() { return hasProc() || stuck; }

        /** 顶部横幅文案 */
        public String bannerText() {
            StringBuilder sb = new StringBuilder("检测到上次未正常结束：");
            boolean first = true;
            if (sender > 0) {
                sb.append("车机发送端 ").append(sender).append(" 个");
                first = false;
            }
            if (vdDisplay > 0) {
                if (!first) sb.append("、");
                sb.append("虚拟屏 ").append(vdDisplay).append(" 个");
                first = false;
            }
            if (receiver > 0) {
                if (!first) sb.append("、");
                sb.append("仪表接收端 ").append(receiver).append(" 个");
                first = false;
            }
            if (stuck) {
                if (!first) sb.append("、");
                sb.append("仪表可能停在投屏画面上");
                first = false;
            }
            if (first) return "";
            return sb.append("。清理后可正常启动。").toString();
        }
    }

    /**
     * 启动自检（UX-04）：一次 exec 拿车机侧，一次 RPC 拿仪表侧，成本同 {@link #health()}。
     *
     * 四项判据：`killvds check` 的发送端数、`dumpsys display` 的 asxvd 计数、
     * 仪表 `ps icrecv_dbg`、`/tmp/icrecv.log` 的最后一条记录是不是 RESTORE。
     *
     * 第四项是关键：「进程没了」不等于「画面回来了」。进程被 SIGKILL 时不会写回
     * fb1，仪表会一直停在最后一帧；这时只有看日志最后一条是出帧还是 RESTORE 才判得出来。
     */
    public Residue scanResidue() {
        Residue r = new Residue();
        long t0 = System.currentTimeMillis();
        try {
            String car = adb.exec("echo SB; echo PROC=$(sh " + KILLVDS + " check 2>/dev/null); "
                    + "echo VD=$(dumpsys display 2>/dev/null | grep -c 'DisplayDeviceInfo{\"asxvd');",
                    20000);
            r.adbOk = car.contains("SB");
            for (String line : car.split("\n")) {
                String t = line.replace("\r", "").trim();
                int q = t.indexOf("PROC=");
                if (q >= 0) { r.sender = parseInt(numText(t, q + 5)); continue; }
                q = t.indexOf("VD=");
                if (q >= 0) r.vdDisplay = parseInt(numText(t, q + 3));
            }
            if (!r.adbOk) {
                r.costMs = System.currentTimeMillis() - t0;
                return r;
            }

            // 进程数仍用「拆串防自命中」：执行这条命令的 shell 自己命令行里就有
            // icrecv_dbg，不拆开永远多数出 1 个。
            String pattern = splitPattern("icrecv_", "dbg");
            String ic = Icrpc.run(adb, pattern
                    + "echo RECV=$(ps -o pid,args | grep -F \"$PAT\" | grep -v grep | wc -l); "
                    + "grep -E 'frames=|RESTORE' /tmp/icrecv.log 2>/dev/null | tail -1", 5, 40000);
            for (String line : ic.split("\n")) {
                String t = line.replace("\r", "").trim();
                if (t.isEmpty()) continue;
                int q = t.indexOf("RECV=");
                if (q >= 0) { r.receiver = parseInt(numText(t, q + 5)); continue; }
                if (t.contains("frames=") || t.contains("RESTORE")) r.lastLine = t;
            }
            if (r.receiver < 0) r.receiver = 0;
            // 仪表没进程、而日志停在一条出帧记录上 → 上一轮是被硬杀的，fb1 没写回
            r.stuck = r.receiver <= 0 && !r.lastLine.isEmpty() && r.lastLine.contains("frames=");
        } catch (Throwable t) {
            log.print("!! 启动自检中断: " + brief(t));
        }
        r.costMs = System.currentTimeMillis() - t0;
        return r;
    }

    /**
     * 一键清理上一轮残留（UX-04）。
     *
     * 只清「进程 + 虚拟屏 + 一次性临时文件」，**保留** vd.dex 与 /tmp/icrecv_dbg ——
     * 那两样是已投送产物，删了下次启动要多等 10~30s 重推（C-1 定案）。
     *
     * @param deadlineAt 截止时间戳；0 = 不限
     */
    public boolean cleanupResidue(long deadlineAt) {
        long t0 = System.currentTimeMillis();
        // 同 stop()：清理也不许被中断标志跳过
        cancelled = false;
        progressDone();
        boolean carOk = true;
        boolean restoreOk = true;
        String pkg = activePkg.isEmpty() ? cfg.pkg : activePkg;
        try {
            if (step(1, 3, "停车机发送端 + 销毁虚拟屏")) return false;
            String out = adb.exec("sh " + KILLVDS + " stop " + pkg
                    + "; rm -f /data/local/tmp/vdcap", 45000);
            for (String line : tail(out, 4).split("\n")) {
                if (!line.trim().isEmpty()) log.print("     " + line.trim());
            }
            int left = parseInt(lastLine(adb.exec("sh " + KILLVDS + " check", 15000).trim()));
            log.print("     残留进程: " + left + (left == 0 ? "  [OK]" : "  [未清空]"));
            carOk = left == 0;

            if (step(2, 3, "停仪表接收端（SIGTERM -> restore）")) return false;
            int alive = stopReceiver();
            log.print("     残留进程: " + alive);
            if (overBudget(deadlineAt)) {
                log.print("     !! 预算用尽，跳过恢复确认");
                restoreOk = false;
            } else {
                // 仪表本来就没进程也要看日志：被 SIGKILL 的那种情况进程早没了，
                // 但画面还留在仪表上，只有最后一条记录能区分「恢复过」和「停在画面」
                String last = Icrpc.run(adb,
                        "grep -E 'frames=|RESTORE' /tmp/icrecv.log 2>/dev/null | tail -1", 6, 40000).trim();
                if (alive > 0 || last.contains("frames=")) {
                    restoreOk = confirmRestore();
                    if (!restoreOk && !overBudget(deadlineAt)) {
                        log.print("     未看到恢复标记，补一次收尾并复查");
                        stopReceiver();
                        restoreOk = confirmRestore();
                    }
                } else {
                    log.print("     仪表侧本来就已恢复，无需处理");
                }
                if (!restoreOk) {
                    log.print("  !! 仪表未确认恢复：可能停在投屏画面上");
                    log.print("     处理：再点一次「清理」，或断电重启仪表；日志在仪表 /tmp/icrecv.log");
                }
            }
            receiverRestored = restoreOk;

            if (step(3, 3, "清临时文件（保留 vd.dex 与 /tmp/icrecv_dbg）")) return false;
            int tempLeft = cleanupTemp();
            log.print("     剩余 /data/local/tmp/.adbprobe_*: " + tempLeft);

            boolean ok = carOk && restoreOk;
            log.print(ok ? "清理完成，用时 " + (System.currentTimeMillis() - t0) + " ms"
                         : "清理未完成，用时 " + (System.currentTimeMillis() - t0) + " ms");
            return ok;
        } catch (Throwable t) {
            log.print("!! 清理中断: " + brief(t));
            return false;
        } finally {
            progressDone();
        }
    }

    // ============================================================ 停止

    /** 不限预算的停止（PC 遥控、手动点「停止」用） */
    public boolean stop() { return stop(0L); }

    /**
     * 停止投屏，并把「车机发送端退出」与「仪表 fb1 恢复」都确认到位（UX-12）。
     *
     * 返回值语义收紧了：以前只要命令没抛异常就 return true，于是「仪表没恢复原版面」
     * 也算成功 —— 这是安全相关部件，必须只认恢复标记。
     *
     * @param deadlineAt System.currentTimeMillis() 的截止点；0 = 不限。
     *                   退出应用给 8 秒预算（UX-05），超了就明确报「哪一步没走完」，
     *                   不静默退出、也不假装成功。
     */
    public boolean stop(long deadlineAt) {
        long t0 = System.currentTimeMillis();
        // 收尾绝不允许被「中断」跳过：启动时按过中断，cancelled 会一直是 true，
        // 那样 step(1,4) 会直接返回 —— 车机进程和仪表画面就留着了。这里强制清掉。
        cancelled = false;
        progressDone();
        boolean carOk = false;
        // 没起过接收端时仪表本来就没被改动，天然算恢复好了；只有起过才要求看到恢复标记
        boolean restoreOk = true;
        int residualCar = -1;
        try {
            // 恢复窗口用「投出去的那一份」，不是界面上当前填的（可能已经被改成别的应用）
            String pkg = activePkg.isEmpty() ? cfg.pkg : activePkg;
            if (!pkg.equals(cfg.pkg)) {
                log.print("  恢复目标用本次实际投出的应用：" + pkg
                        + "（界面上现在填的是 " + cfg.pkg + "，不参与收尾）");
            }
            if (step(1, 4, "停车机发送端")) return false;
            // B5-B：这一步历史上会偶发卡到 adb 的 45s 读超时（前台跑的 app_process --restore 等不到退出）。
            // 15s 上限 + kill -9 兜底，宁可留红灯也不让用户盯着转圈 45 秒。
            String out = killSender(pkg);
            for (String line : tail(out, 8).split("\n")) {
                if (!line.trim().isEmpty()) log.print("     " + line.trim());
            }
            String check = adb.exec("sh " + KILLVDS + " check", 15000).trim();
            residualCar = parseInt(lastLine(check));
            carOk = residualCar == 0;
            log.print("     残留进程: " + residualCar + (carOk ? "  [OK]" : "  [未清空]"));
            log.print("     窗口位置: " + oneLine(adb.exec("am stack list 2>/dev/null | grep -F "
                    + pkg + " | head -1", 15000)));
            log.print("     残留虚拟屏: " + oneLine(adb.exec(
                    "dumpsys display | grep -c 'DisplayDeviceInfo{\"asxvd'", 15000)));

            if (step(2, 4, "停仪表接收端（SIGTERM -> restore）")) return false;
            int alive = stopReceiver();
            log.print("     残留进程: " + alive);

            if (step(3, 4, "确认仪表已恢复原版面")) return false;
            if (!receiverEverStarted) {
                log.print("     本次没起过仪表接收端，fb1 未被改动，无需恢复");
            } else if (overBudget(deadlineAt)) {
                restoreOk = false;
                log.print("     !! 预算用尽，跳过恢复确认");
            } else {
                restoreOk = confirmRestore();
                if (!restoreOk && !overBudget(deadlineAt)) {
                    // 多数是时序：TERM 之后日志落盘晚一点。补一次收尾再复查；
                    // 真挂住了也靠这一轮补刀（SIGTERM 幂等，进程没了就什么都不做）。
                    log.print("     未看到恢复标记，补一次收尾并复查");
                    int alive2 = stopReceiver();
                    log.print("     残留进程: " + alive2);
                    restoreOk = confirmRestore();
                }
            }
            // 只有确认恢复了才清标记。失败时保留 —— 否则第二次点「停止」会直接跳过
            // 验证并报「收尾完成」，把第一次的红灯悄悄抹掉，等于从没发现过问题。
            if (restoreOk) receiverEverStarted = false;
            receiverRestored = restoreOk;

            if (step(4, 4, "结论")) return false;
            boolean ok = carOk && restoreOk;
            if (ok) {
                log.print("  收尾完成，用时 " + (System.currentTimeMillis() - t0) + " ms");
            } else {                if (!carOk) {
                    log.print("  !! 车机发送端未确认退出（残留 " + residualCar + " 个）");
                }
                if (!restoreOk) {
                    log.print("  !! 仪表未确认恢复原版面：仪表可能停在投屏画面上");
                    log.print("     处理：重开一次「停止」，或断电重启仪表；日志在仪表 /tmp/icrecv.log");
                }
            }
            // UX-13：退出只停自己的进程（VdSend / icrecv），**不代关目标应用** ——
            // 用户可能正开着音乐 App 一边听一边投，退出时把它杀掉是最典型的误伤（R2-1）。
            // 这里只如实说明是不是我们拉起的，方便事后核对「没误杀」。
            String who = activePkg.isEmpty() ? cfg.pkg : activePkg;
            log.print("  不代关目标应用：" + who
                    + (launchedByUs ? "（本次由我们拉起，仍留着）" : "（本来就在运行，没动过）"));
            activePkg = "";
            // 恢复窗口会把目标应用搬回前台，面板整个被盖住 —— 用户点完「停止」看不到结果。
            // 收尾完成后把自己拉回来（此时 restore 已完成，不会再被抢）
            try {
                adb.exec("am start -n " + SELF + "/.MainActivity", 12000);
            } catch (Throwable ignored) { }
            log.print("done");
            return ok;
        } catch (Throwable t) {
            log.print("!! 停止中断: " + brief(t));
            receiverRestored = false;
            return false;
        } finally {
            progressDone();
        }
    }

    /** 预算到了没（deadlineAt=0 表示不限） */
    private static boolean overBudget(long deadlineAt) {
        return deadlineAt > 0 && System.currentTimeMillis() >= deadlineAt;
    }

    /**
     * 清理本次投屏留在车机上的临时文件，返回剩余 .adbprobe_* 个数。
     *
     * 刻意不删 vd.dex 和 /tmp/icrecv_dbg：那两样是「已投送产物」，删掉的话下次启动
     * 要多等 10~30s 重推（UX-05 定案）。这里只清一次性的 RPC 帧、截图、relay 脚本。
     */
    public int cleanupTemp() {
        try {
            String r = adb.exec("rm -f /data/local/tmp/vdcap /data/local/tmp/_vdcrop.png "
                    + "/data/local/tmp/.adbprobe_nc.pid /data/local/tmp/.adbprobe_relay.sh "
                    + "/data/local/tmp/.adbprobe_f*.bin /data/local/tmp/.adbprobe_r*.out; "
                    + "ls /data/local/tmp/.adbprobe_* 2>/dev/null | wc -l", 15000).trim();
            return parseInt(lastLine(r));
        } catch (Throwable t) {
            log.print("!! 清理临时文件失败: " + brief(t));
            return -1;
        }
    }

    /**
     * 仪表恢复判定，以恢复标记为唯一成功判据（UX-12）。
     *
     * 接收端收到 SIGTERM 后会先把 fb1 的原内容写回去再退出；只有这些标记出现，
     * 才说明画面真的回来了。「进程没了」不等于「画面回来了」—— 进程被 SIGKILL 时
     * 不会 restore，仪表会一直停在最后一帧投屏画面上。
     */
    private boolean confirmRestore() throws IOException {
        String out = Icrpc.run(adb,
                "grep -E 'RESTORE_OK|pixels restored|RESTORED blank' /tmp/icrecv.log | tail -3", 8, 40000);
        logIndented(out);
        return out.contains("RESTORE_OK") || out.contains("pixels restored")
                || out.contains("RESTORED blank");
    }

    // ============================================================ 三层小灯

    /**
     * 面板上三个小灯的状态（UI-10）。
     *
     * 为什么要单独探一轮：状态机只知道「上次启动成没成功」，不知道现在还行不行。
     * adb 掉线、发送端崩掉、接收端被别人杀了 —— 这三种在旧面板上看起来完全一样
     * （都是「投屏中」），而恢复手段完全不同，所以必须分开测。
     */
    public static final class Health {
        /** adb 通道活着（车机回了心跳） */
        public boolean adbOk;
        /** 车机发送端进程数；-1 = 没测出来 */
        public int sender = -1;
        /** 仪表接收端进程数；-1 = 没测出来 */
        public int receiver = -1;
        /** 车机侧实测帧率；<0 = 没有 */
        public double carFps = -1;
        /** 仪表侧实测帧率；<0 = 没有 */
        public double icFps = -1;
        /** 车机上 asxvd 虚拟屏个数（字符串，可能为空） */
        public String vd = "";
        public long costMs;

        public String carsFpsText() {
            return carFps < 0 ? "--" : String.format(Locale.US, "%.0f", carFps);
        }

        public String icFpsText() {
            return icFps < 0 ? "--" : String.format(Locale.US, "%.1f", icFps);
        }
    }

    /**
     * 探一轮真实健康度：一次 adb 往返拿车机侧全部信息，再一次 RPC 拿仪表侧。
     *
     * 成本 ≈ 一次 exec（几十毫秒）+ 一次 Icrpc（1.5~2s）。所以只在「手动刷新」
     * 和「启动/停止刚结束」时调，别挂进 refreshUi —— 那会把 UI 拖成幻灯片。
     */
    public Health health() {
        Health h = new Health();
        long t0 = System.currentTimeMillis();
        try {
            String car = adb.exec("echo HB; echo PROC=$(sh " + KILLVDS + " check 2>/dev/null); "
                    + "echo VD=$(dumpsys display 2>/dev/null | grep -c 'DisplayDeviceInfo{\"asxvd'); "
                    + "grep 'fps=' " + VDSEND_LOG + " 2>/dev/null | tail -1", 20000);
            h.adbOk = car.contains("HB");
            for (String line : car.split("\n")) {
                String t = line.replace("\r", "").trim();
                int q = t.indexOf("PROC=");
                if (q >= 0) h.sender = parseInt(numText(t, q + 5));
                else if ((q = t.indexOf("VD=")) >= 0) h.vd = numText(t, q + 3);
                else h.carFps = maxOf(h.carFps, num(t, "fps=(\\d+(?:\\.\\d+)?)"));
            }
            if (!h.adbOk) {
                h.costMs = System.currentTimeMillis() - t0;
                return h;
            }

            // 仪表侧：进程数用「拆串防自命中」的写法，否则执行这条命令的 shell
            // 自己的命令行里就有 icrecv_dbg，永远会多数出 1 个。
            // 只取带 fps 的 frames 行：接收端退出时会补一行 `stream idle/end: EOF (frames=N)`，
            // 那行没有帧率，拿它当最新状态会误报「没在出帧」。
            String pattern = splitPattern("icrecv_", "dbg");
            String ic = Icrpc.run(adb, pattern
                    + "echo RECV=$(ps -o pid,args | grep -F \"$PAT\" | grep -v grep | wc -l); "
                    + "grep -E 'frames=.*fps' /tmp/icrecv.log 2>/dev/null | tail -1", 5, 40000);
            for (String line : ic.split("\n")) {
                String t = line.replace("\r", "").trim();
                // 回包前面常粘着 protobuf 头的可打印残渣（形如 `+.)RECV=0`），
                // 所以不能 startsWith，必须按子串找
                int q = t.indexOf("RECV=");
                if (q >= 0) h.receiver = parseInt(numText(t, q + 5));
                else h.icFps = maxOf(h.icFps, num(t, "(\\d+(?:\\.\\d+)?)fps"));
            }
            // 没有进程时，日志里的 fps 是「上一轮投屏」留下的，不能当现状显示
            if (h.sender <= 0) h.carFps = -1;
            if (h.receiver <= 0) h.icFps = -1;
        } catch (Throwable t) {
            log.print("!! 健康检查中断: " + brief(t));
        }
        h.costMs = System.currentTimeMillis() - t0;
        return h;
    }

    /** 从一行文本里按正则取第一个数字；取不到返回 -1 */
    private static double num(String s, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Throwable ignored) { }
        return -1;
    }

    /** 取两者中更大的那个（多行日志里会有历史帧率，留最新一次即可） */
    private static double maxOf(double a, double b) {
        if (b < 0) return a;
        return b;   // 日志是顺序的，后出现的覆盖前面的
    }

    /** 从 from 之后取第一段连续数字，返回文本（容忍后面还跟着别的字） */
    private static String numText(String s, int from) {
        int i = Math.max(0, from);
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        int st = i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return st == i ? "" : s.substring(st, i);
    }

    /** 仪表侧是否已确认恢复（退出时判断要不要拦一下） */
    public boolean isRestored() { return receiverRestored; }

    /**
     * 本次启动过程中顺手采到的健康快照（零额外成本）。
     *
     * 面板上的三层小灯优先用它：启动流程本来就已经拿到了「接收端进程数」「车机 fps」
     * 「仪表 fps」「发送端进程数」，再单跑一次 health() 纯属白花 2 秒。
     */
    private Health lastStartHealth;

    public Health startHealth() { return lastStartHealth; }

    // ============================================================ 状态

    public String status() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("=== 车机端（采集/发送） ===\n");
            sb.append(adb.exec("grep -E 'child start' " + VDSEND_LOG + " | tail -1; echo '  --'; "
                    + "grep -E 'VD created|moved to display|shadow display ready|capture null|send FAIL' "
                    + VDSEND_LOG + " | tail -3; grep 'fps=' " + VDSEND_LOG + " | tail -2", 15000));
            sb.append("\n=== 虚拟屏 / 目标窗口 ===\n");
            String vd = adb.exec("dumpsys display 2>/dev/null | grep -c 'DisplayDeviceInfo{\"asxvd'", 15000).trim();
            String win = oneLine(adb.exec("am stack list 2>/dev/null | grep -F "
                    + (activePkg.isEmpty() ? cfg.pkg : activePkg) + " | head -1", 15000));
            sb.append("  虚拟屏数: ").append(vd).append('\n');
            sb.append("  窗口: ").append(win).append('\n');
            sb.append("\n=== 仪表端（接收/合成） ===\n");
            sb.append(Icrpc.run(adb, "ps w | grep -F icrecv_dbg | grep -v grep | head -2; echo '  --'; "
                    + "grep -E 'LISTEN|VISIBLE|frames=|IDLE|RESTORE' /tmp/icrecv.log | tail -3", 10, 40000));
            sb.append("\n=== 车机网口 ===\n");
            sb.append(adb.exec("cat /sys/class/net/eth0/speed 2>/dev/null; "
                    + "ip -4 addr show eth0 2>/dev/null | grep -F inet", 10000));
        } catch (Throwable t) {
            sb.append("\n!! 读取状态失败: ").append(brief(t)).append('\n');
        }
        return sb.toString();
    }

    // ============================================================ 裁切 / 目标

    public boolean setCrop(String crop) {
        try {
            String c = crop.trim();
            if (!c.matches("\\d+,\\d+,\\d+,\\d+")) {
                log.print("!! 裁切格式应为 x,y,w,h，例如 960,345,1440,426");
                return false;
            }
            cfg.crop = c;
            adb.exec("echo '" + c + "' > /data/local/tmp/vdcrop.txt", 10000);
            String back = adb.exec("cat /data/local/tmp/vdcrop.txt", 10000).trim();
            log.print("裁切已设为 " + back + "（0.5s 内热生效）");
            return true;
        } catch (Throwable t) {
            log.print("!! 设置裁切失败: " + brief(t));
            return false;
        }
    }

    public String currentCrop() {
        try {
            return adb.exec("cat /data/local/tmp/vdcrop.txt 2>/dev/null", 8000).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // ============================================================ 内部步骤

    private int dexSize = -1;

    /**
     * 发送端 dex + 杀进程脚本的同步。
     *
     * 本次运行里推过一次就不再推，只花几毫秒探一下文件还在不在
     * （车机重启会清 /data/local/tmp，那时候必须重投，否则起不来）。
     *
     * 曾经用「车机上文件大小一致就跳过」来判断，结果同一份源码改了几行、dex 大小没变，
     * 车机跑的还是旧逻辑 —— 所以「不清不楚的省」不如「一次推干净 + 明确跳过」。
     */
    private boolean pushDex() throws IOException {
        if (dexSynced) {
            String probe = adb.exec("test -s " + DEX + " && test -s " + KILLVDS
                    + " && echo HAVE || echo NO", 8000).trim();
            if (probe.contains("HAVE")) {
                log.print("  本次运行已推过，跳过（车机上文件在）");
                return true;
            }
            log.print("  车机上文件不见了（重启过？），重新推");
        }
        byte[] dex = readAssetDex();
        dexSize = dex.length;
        adb.push(dex, DEX, Adb.MODE_FILE, 60000);
        String after = adb.exec("stat -c %s " + DEX + " 2>/dev/null", 10000).trim();
        if (!String.valueOf(dex.length).equals(after)) {
            log.print("!! dex 大小不符：本地 " + dex.length + " / 车机 " + after);
            return false;
        }
        dexSynced = true;
        return true;
    }

    /**
     * 仪表端二进制同步。
     *
     * 本次运行里核对过一次就不再核对；只有文件不在了（仪表重启清了 /tmp）才重来。
     * 换 APK 版本时进程重启，dexSynced/binSynced 归零，且 prefs 里的 md5 也对不上，
     * 于是会重新走一次 md5 比对 ——
     * 这一步必须用 md5 不能用大小：改过显示的几行代码、二进制长度常常一样，
     * 于是仪表上一直跑旧版本，日志里看不出任何异常（踩过两次）。
     */
    private boolean ensureClusterBinary() throws Exception {
        byte[] bin = icrecv;
        String want = bin == null ? "" : md5Hex(bin);
        binMd5 = want;

        // 快路径：本地记录说「上次投的就是这一份」→ 只探文件在不在（3 秒），
        // 不查 md5、更不会白等 relay。仪表重启会清 /tmp，所以这一步不能省。
        boolean known = !forceResync && !want.isEmpty() && want.equalsIgnoreCase(knownBinMd5);
        if (known || binSynced) {
            if (probeClusterBin()) {
                log.print(known ? "  与上次投送的版本一致（本地记录命中），跳过核对"
                                : "  本次运行已核对过，跳过");
                binSynced = true;
                return true;
            }
            log.print("  仪表上没有 " + ICBIN + "（重启过？），重新投送");
        }

        // 慢路径：真去仪表上要一次 md5。Icrpc 是「sleep N 撑住 nc」的实现，
        // 所以这里的 hold 秒数就是固定等待成本，别开大。
        String have = "";
        String raw = Icrpc.run(adb, "md5sum " + ICBIN + " 2>/dev/null", 4, 30000);
        for (String line : raw.split("\n")) {
            String t = line.trim();
            // 回包前面可能沾着别的东西，只在行里找 32 位十六进制
            if (t.matches("[0-9a-fA-F]{32}.*")) {
                have = t.substring(0, 32).toLowerCase();
                break;
            }
        }
        if (!want.isEmpty() && !have.isEmpty() && want.equalsIgnoreCase(have)) {
            log.print("  仪表上已是最新（md5 " + want.substring(0, 8) + "）");
            binSynced = true;
            forceResync = false;
            return true;
        }
        String carPath = "/data/local/tmp/icrecv_dbg";
        // 来源优先级：APK 内置 → 车机 /sdcard 旁路
        if (bin == null) {
            String size = adb.exec("stat -c %s /sdcard/icrecv_dbg 2>/dev/null", 8000).trim();
            if (size.matches("\\d+")) {
                log.print("  使用车机 /sdcard/icrecv_dbg（" + size + " B）");
                adb.exec("cp /sdcard/icrecv_dbg " + carPath, 30000);
            } else {
                log.print("!! 仪表上没有 " + ICBIN + "，APK 也没内置。");
                log.print("   把 icrecv_dbg 放到车机 /sdcard/ 或 APK 的 assets/ 下再试");
                return false;
            }
        } else {
            String carMd5 = adb.exec("md5sum " + carPath + " 2>/dev/null", 20000)
                    .trim().replaceAll("\\s.*$", "");
            if (want.equalsIgnoreCase(carMd5)) {
                log.print("  车机上已有同一份，跳过推送");
            } else {
                log.print("  推仪表端 " + bin.length + " B（"
                        + (want.isEmpty() ? "md5 取不到" : "md5 " + want.substring(0, 8)) + "）");
                adb.push(bin, carPath, Adb.MODE_EXEC, 120000);
            }
        }
        log.print("  经车机 HTTP 中转推送（约 10~30s）");
        boolean ok = relayToCluster(carPath, ICBIN, 8105);
        if (ok) { binSynced = true; forceResync = false; }
        return ok;
    }

    /** 只问一句「文件还在不在」，Icrpc 的 hold 给 3 秒就够 */
    private boolean probeClusterBin() throws Exception {
        String probe = Icrpc.run(adb, "test -x " + ICBIN + " && echo HAVE_IT || echo NO_IT",
                3, 30000);
        return probe.contains("HAVE_IT");
    }

    private static String md5Hex(byte[] b) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                    .append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 车机 HTTP 服务 + 仪表 wget，同一进程内完成投送。
     *
     * 为什么不用 `(...) &` 直接起：adbd 的 shell 一返回，后台子进程可能吃 SIGHUP，
     * nc 会在 wget 到达前就死掉。所以脚本落盘 + setsid 脱离会话，并把自己 pid 写出来，
     * 投送完再 kill（仪表没有 pkill，车机侧也不能按 -f 杀，会误伤执行命令的 shell 自己）。
     */
    private boolean relayToCluster(String carPath, String dst, int port) throws Exception {
        String size = adb.exec("stat -c %s " + carPath + " 2>/dev/null", 10000).trim();
        if (!size.matches("\\d+")) {
            log.print("!! 车机上没有 " + carPath);
            return false;
        }
        String carIp = Icrpc.carIp(adb, cfg.carIpFallback);

        String script = "#!/system/bin/sh\n"
                + "# 由 APK 投送的临时 HTTP 服务：给仪表 wget 拉一次文件\n"
                + "echo $$ > /data/local/tmp/.adbprobe_nc.pid\n"
                + "i=0\n"
                + "while [ $i -lt 8 ]; do\n"
                + "  { printf 'HTTP/1.0 200 OK\\r\\nContent-Length: %s\\r\\nConnection: close\\r\\n\\r\\n' \"$2\";"
                + " cat \"$1\"; } | nc -l -p \"$3\" -q 2 >/dev/null 2>&1\n"
                + "  i=$((i+1))\n"
                + "done\n";
        String relScript = "/data/local/tmp/.adbprobe_relay.sh";
        adb.push(script.getBytes(StandardCharsets.UTF_8), relScript, Adb.MODE_EXEC, 20000);
        adb.exec("rm -f /data/local/tmp/.adbprobe_nc.pid; setsid sh " + relScript + " "
                + carPath + " " + size + " " + port + " >/dev/null 2>&1 & sleep 1; "
                + "cat /data/local/tmp/.adbprobe_nc.pid 2>/dev/null", 15000);

        String cmd = "rm -f " + dst + "; S=$(date +%s); wget -q -O " + dst
                + " http://" + carIp + ":" + port + "/x; E=$(date +%s); echo took_$((E-S))s; "
                + "ls -l " + dst + " 2>&1 | tail -1; chmod +x " + dst + " 2>/dev/null; md5sum " + dst;
        // hold 就是 nc 撑住 stdin 的时长，也就是这条命令的固定等待成本：
        // wget 3.4MB 实测 10~30s，原来给 90 秒，每次重推都白等一分钟。
        String out = Icrpc.run(adb, cmd, 55, 120000);
        adb.exec("kill $(cat /data/local/tmp/.adbprobe_nc.pid 2>/dev/null) 2>/dev/null; "
                + "rm -f /data/local/tmp/.adbprobe_nc.pid", 8000);

        String carMd5 = adb.exec("md5sum " + carPath, 20000).trim().replaceAll("\\s.*$", "");
        String icMd5 = "";
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.matches("[0-9a-f]{32}.*")) icMd5 = t.substring(0, 32);
        }
        for (String line : tail(out, 4).split("\n")) {
            if (!line.trim().isEmpty()) log.print("    " + line.trim());
        }
        if (!carMd5.isEmpty() && !icMd5.isEmpty()) {
            boolean same = carMd5.equals(icMd5);
            log.print("  md5 车机 " + carMd5.substring(0, 8) + " / 仪表 " + icMd5.substring(0, 8)
                    + (same ? "  [OK]" : "  [不等]"));
            return same;
        }
        // 拿不到 md5 时退化为只看文件是否落地
        String check = Icrpc.run(adb, "test -x " + dst + " && echo OK || echo MISSING", 6, 35000);
        return check.contains("OK");
    }

    private int startReceiver() throws IOException {
        String pattern = splitPattern("icrecv_", "dbg");
        String rectArg = cfg.cCustom ? (" " + cfg.cX + " " + cfg.cY + " " + cfg.cW + " " + cfg.cH) : "";
        // 杀旧 + 起新 + 数活口 合成一条命令：拆成两次 RPC 的话，
        // 光固定等待就要 20 秒（每条的 hold 都得等满）。
        //
        // 起进程那里写成 ./icrecv_"dbg" 是有意的：命令文本会出现在执行它的
        // shell 的 cmdline 里，如果直接写 ./icrecv_dbg，下面那句
        // `grep -F "$PAT"` 会把 shell 自己也数进去，永远报「残留 1 个」。
        String cmd = pattern
                + "for p in $(ps -o pid,args | grep -F \"$PAT\" | awk '{print $1}'); do "
                + "[ \"$p\" = \"$$\" ] && continue; kill \"$p\" 2>/dev/null; done; "
                // 等旧进程真的退掉，而不是固定 sleep 2：正常情况下 0.2 秒就没了。
                // 这一步不能省 —— 端口没释放，新进程 bind 会失败。
                + "i=0; while [ $i -lt 20 ]; do "
                + "n=$(ps -o pid,args | grep -F \"$PAT\" | grep -v grep | wc -l); "
                + "[ \"$n\" -eq 0 ] && break; i=$((i+1)); sleep 0.15; done; "
                + "cd /tmp && ICBANDS=1200 setsid ./icrecv_\"dbg\" compose " + PORT + " " + cfg.hold
                + " " + cfg.top + " " + cfg.bot + " 0" + rectArg + " > /tmp/icrecv.log 2>&1 & "
                // 以它打出 LISTEN 为准（实测 0.3~0.8s），比固定 sleep 2 又快又稳
                + "i=0; while [ $i -lt 40 ]; do grep -q 'LISTEN' /tmp/icrecv.log 2>/dev/null && break; "
                + "i=$((i+1)); sleep 0.15; done; "
                + "ps -o pid,args | grep -F \"$PAT\" | grep -v grep | wc -l";
        int alive = parseInt(lastLine(Icrpc.run(adb, cmd, 8, 45000)));
        if (alive > 0) {
            // 只有真的起来了才要求收尾时看到恢复标记（UX-12 的幂等前提）
            receiverEverStarted = true;
            receiverRestored = false;
        }
        return alive;
    }

    private int stopReceiver() throws IOException {
        String pattern = splitPattern("icrecv_", "dbg");
        // TERM 之后它会先把 fb1 还原再退出，所以「等进程消失」就等于「还原完了」，
        // 比固定 sleep 3 既快又准（实测约 1 秒）。
        String kill = pattern + "for p in $(ps -o pid,args | grep -F \"$PAT\" | awk '{print $1}'); do "
                + "[ \"$p\" = \"$$\" ] && continue; kill -TERM \"$p\" 2>/dev/null; done; "
                + "i=0; while [ $i -lt 25 ]; do "
                + "n=$(ps -o pid,args | grep -F \"$PAT\" | grep -v grep | wc -l); "
                + "[ \"$n\" -eq 0 ] && break; i=$((i+1)); sleep 0.2; done; "
                + "ps -o pid,args | grep -F \"$PAT\" | grep -v grep | wc -l";
        return parseInt(lastLine(Icrpc.run(adb, kill, 7, 45000)));
    }

    /** 把匹配串拆成两段再拼，避免 `ps` 把执行本命令的 shell 自己也列进来 */
    private static String splitPattern(String a, String b) {
        return "PAT=" + a + "; PAT=${PAT}" + b + "; ";
    }

    /**
     * 只把 VdSend 拉起来，**不等 capture ready**（B5-C：和起接收端并行）。
     *
     * 用 `& echo LAUNCHED` 立刻返回；进程靠 setsid 脱离 shell，shell 退出不影响它。
     */
    private void launchSender() throws IOException {
        String cmd = "CLASSPATH=" + DEX + " setsid app_process /system/bin com.probe.asx.VdSend "
                + "--child --mode " + cfg.mode + " --pkg " + cfg.pkg + " "
                + cfg.outW + " " + cfg.outH + " " + cfg.fps
                + (cfg.previewPort > 0 ? " --preview " + cfg.previewPort : "")
                + " > " + VDSEND_LOG + " 2>&1 & echo LAUNCHED; sleep 0.5";
        // 末尾那个 sleep 不是凑数：Adb 客户端读到 __RC 哨兵就关 socket，adbd 随之收掉这个会话的
        // 进程组；而 toybox 的 setsid 要 fork 一次才真正 detach —— 会话先关、setsid 还没跑完，
        // VdSend 就会被一起带走（实测 18:04 那次 vdsend.log 是空的）。
        // 留 0.5s 给 setsid 完成 detach；这 0.5s 里发送端已经在初始化，不浪费。
        String out = adb.exec(cmd, 15000).trim();
        if (!out.contains("LAUNCHED")) log.print("  !! 发送端没拉起来：" + oneLine(out));
    }

    /** 等 VdSend 打出 capture ready（0.2s 一轮，上限 15s），再把日志尾巴打出来 */
    private void awaitSenderReady() throws IOException {
        String cmd = "i=0; while [ $i -lt 75 ]; do grep -qE 'capture ready|FAIL' " + VDSEND_LOG
                + " && break; i=$((i+1)); sleep 0.2; done; "
                + "grep -E 'child start|VD created|target task|launching target|moved to display|"
                + "shadow display ready|capture ready|args crop|FAIL|fps=' " + VDSEND_LOG + " | tail -6";
        logIndented(adb.exec(cmd, 25000));
    }

    /**
     * 停车机发送端（B5-B）：给「可能卡死」的那一步一个确定的预算。
     *
     * <p>killvds.sh 的最后一步是在前台跑 `app_process --restore` 等它退出；
     * 2026-11-05 17:53 / 17:54 各卡死一次，直到 adb 的 45s 读超时才返回 ——
     * 用户看到的是「点了停止没反应」。这里把预算压到 15s，超时就 kill -9 残留再补一发（8s）。
     *
     * <p>两次都超时返回空串，让调用方按「残留未清」报红灯 —— 绝不假装收尾成功。
     */
    private String killSender(String pkg) {
        String cmd = "sh " + KILLVDS + " stop " + pkg + "; rm -f /data/local/tmp/vdcap";
        try {
            return adb.exec(cmd, STOP_SENDER_MS);
        } catch (Throwable t) {
            log.print("     !! 停发送端超过 " + STOP_SENDER_MS + "ms：" + brief(t));
            // 读超时会把这条 adb 连接弄死：不重连的话，后面的 kill -9、二次 stop、残留检查
            // 全都会报「未连接」，整个收尾直接中断（2026-11-05 18:13 注入测试实测就是这条路径）。
            if (!reconnectQuietly()) {
                log.print("     !! 重连失败：本次收尾不能确认，请再点一次「停止」");
                return "";
            }
        }
        try {
            String k = adb.exec(FORCE_KILL, 10000).trim();
            log.print("     补一刀 kill -9：" + (k.isEmpty() ? "(无残留)" : oneLine(k)));
        } catch (Throwable t) {
            log.print("     !! kill -9 也失败：" + brief(t));
        }
        try {
            String out = adb.exec(cmd, STOP_SENDER_RETRY_MS);
            log.print("     再补一次 stop（上限 " + STOP_SENDER_RETRY_MS + "ms）");
            return out;
        } catch (Throwable t) {
            log.print("     !! 二次 stop 仍超时：继续往下走，靠「确认恢复」给结论");
            return "";
        }
    }

    /** B5-B：停发送端的单次预算（原 45s 一次卡死就吃掉整个退出预算） */
    private static final int STOP_SENDER_MS = 15000;    /** B5-B：超时后补一次的上限 */
    private static final int STOP_SENDER_RETRY_MS = 8000;

    /**
     * 读超时后重连。
     *
     * <p>Adb 的读超时会把 socket 置成断开状态，之后任何 exec 都直接抛「未连接」。
     * 收尾链路里超时是**可以被预期的**（restore 会偶发卡死），所以必须能自己接回来：
     * 从 {@code adb.endpoint}（形如 127.0.0.1:5050）里取地址重连，认证走已有 key，一般 1 秒内完成。
     */
    private boolean reconnectQuietly() {
        try {
            adb.close();
            String ep = adb.endpoint;
            String host = "127.0.0.1";
            int port = 5050;
            if (ep != null && ep.lastIndexOf(':') > 0) {
                host = ep.substring(0, ep.lastIndexOf(':'));
                int p = parseInt(ep.substring(ep.lastIndexOf(':') + 1));
                if (p > 0) port = p;
            }
            boolean ok = adb.connect(host, port, 8);
            log.print("     重连 " + host + ":" + port + (ok ? " 成功" : " 失败"));
            return ok;
        } catch (Throwable t) {
            log.print("     重连异常：" + brief(t));
            return false;
        }
    }

    /**
     * 兜底强杀：只杀 cmdline 以 app_process 开头的 VdSend 实例。
     *
     * 这里的匹配串和 killvds.sh 一样要防「把自己也杀了」—— 本命令的 shell 是 `sh -c ...`，
     * cmdline 不以 app_process 开头，所以 case 判断天然排除它。
     */
    private static final String FORCE_KILL =
            "PAT=com.probe.asx.VdSend; "
                    + "for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -F \"$PAT\" | awk '{print $1}'); do "
                    + "c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\\0' ' '); "
                    + "case \"$c\" in app_process*) kill -9 $p 2>/dev/null && echo \"killed $p\";; esac; "
                    + "done; true";

    // ============================================================ 目标应用

    /** 一条正在运行的任务（用于「选择应用」列表与位置展示） */
    public static final class TaskInfo {
        public String pkg;
        public int taskId;
        public int displayId;
        public String bounds;

        public String label() {
            return pkg + "  #" + taskId + "  " + bounds + (displayId > 0 ? "  D" + displayId : "");
        }
    }

    /** 从 am stack list 里解析出「正在运行、有窗口」的包名与 taskId */
    public java.util.List<TaskInfo> runningTasks() throws IOException {
        String out = adb.exec("am stack list", 20000);
        java.util.List<TaskInfo> list = new java.util.ArrayList<>();
        TaskInfo cur = null;
        for (String raw : out.split("\n")) {
            String s = raw.trim();
            if (s.startsWith("RootTask id=")) {
                cur = new TaskInfo();
                cur.taskId = numAfter(s, "RootTask id=".length());
                int d = s.indexOf("displayId=");
                if (d >= 0) cur.displayId = numAfter(s, d + "displayId=".length());
                int b = s.indexOf("bounds=");
                if (b >= 0) cur.bounds = s.substring(b + 7).split(" ")[0];
                continue;
            }
            if (cur != null && s.startsWith("taskId=") && s.contains("/")) {
                int c = s.indexOf(':');
                int sl = s.indexOf('/', Math.max(c, 0));
                if (c > 0 && sl > c) {
                    cur.pkg = s.substring(c + 1, sl).trim();
                    list.add(cur);
                    cur = null;
                }
            }
        }
        return list;
    }

    /** 候选应用：运行中的在前，第三方已装应用在后（去重，排除自己） */
    public java.util.List<String> candidateApps() throws IOException {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        try {
            for (TaskInfo t : runningTasks()) {
                if (t.pkg != null && t.pkg.contains(".") && !t.pkg.equals(SELF)) set.add(t.pkg);
            }
        } catch (Throwable ignored) { }
        try {
            String third = adb.exec("pm list packages -3 2>/dev/null", 20000);
            for (String line : third.split("\n")) {
                String s = line.trim();
                if (s.startsWith("package:")) {
                    String p = s.substring(8).trim();
                    if (p.contains(".") && !p.equals(SELF)) set.add(p);
                }
            }
        } catch (Throwable ignored) { }
        return new java.util.ArrayList<>(set);
    }

    private static final String SELF = "com.xiaopeng.vdcast";

    /** 目标包是否已经有窗口（有就不再重复拉起，避免把用户的界面顶掉） */
    public TaskInfo findTask(String pkg) throws IOException {
        for (TaskInfo t : runningTasks()) {
            if (pkg.equals(t.pkg)) return t;
        }
        return null;
    }

    /**
     * 确保目标应用有一个可搬的窗口。
     *
     * VdSend 的 vd 模式第一步就是 findRootTask(包名)，找不到直接抛异常退出——
     * 之前默认包名写死成 QQ 音乐、而它只是「装了没运行」，于是整个流程静默退化成主屏采集。
     */
    private boolean ensureTargetApp() throws Exception {
        TaskInfo t = findTask(cfg.pkg);
        if (t != null) {
            log.print("  已在运行: " + t.label());
            launchedByUs = false;
            return true;
        }
        log.print("  没有窗口，先拉起 " + cfg.pkg);
        String r = adb.exec("monkey -p " + cfg.pkg
                + " -c android.intent.category.LAUNCHER 1 2>&1 | tail -2", 25000).trim();
        if (!r.isEmpty()) log.print("  " + oneLine(r));
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            t = findTask(cfg.pkg);
            if (t != null) {
                launchedByUs = true;   // 只有这条路径算「我们拉起的」；停的时候不关它
                log.print("  拉起成功: " + t.label());
                return true;
            }
        }
        // 不在这里打 `!!`：调用方 start() 会经 fail() 统一打「停在哪 + 下一步」，
        // 两处都打红字会连着两条，看着像两个独立故障
        log.print("  起不来。可能：包名写错 / 该应用没有桌面入口 / 被系统禁止启动");
        return false;
    }

    // ============================================================ 截图（裁切框底图）

    private static final String SHOT = "/data/local/tmp/_vdcrop.png";

    /** 虚拟屏的 displayId；没有则 -1（用 dumpsys 里的 DisplayInfo 行解析） */
    public int vdDisplayId() {
        try {
            String out = adb.exec("dumpsys display 2>/dev/null | grep -o "
                    + "'DisplayInfo{\"asxvd\", displayId [0-9]*' | head -1", 15000).trim();
            int i = out.lastIndexOf(' ');
            if (i > 0) return parseInt(out.substring(i + 1));
        } catch (Throwable ignored) { }
        return -1;
    }

    /**
     * 截一张图当裁切底图（shell 域才有 READ_FRAME_BUFFER，所以必须走 adb）。
     *
     * 虚拟屏在跑就截虚拟屏（screencap -d <id>），那样框出来的就是实际投出去的画面，
     * 而且投屏运行中也能调框；没有虚拟屏才退到主屏。
     */
    public byte[] snapshot() throws IOException {
        int vd = vdDisplayId();
        // UI-02：底图来源必须能说清楚，页面上要标出来。
        // 优先级 = 虚拟屏（正在投的那块）→ 目标应用窗口（被搬到副屏、但虚拟屏还没起）
        //          → 主屏（兜底，此时框出来的未必是真正会投出去的画面）。
        int target = targetDisplayId();
        int disp = 0;
        if (vd > 0) {
            disp = vd;
            shotSource = "虚拟屏 display " + vd;
        } else if (target > 0) {
            disp = target;
            shotSource = "目标应用窗口 display " + target;
        } else {
            shotSource = "主屏";
        }
        shotDisplay = disp;
        String arg = disp > 0 ? "-d " + disp + " " : "";
        log.print("  截取" + shotSource);

        String r = adb.exec("screencap -p " + arg + SHOT
                + "; stat -c %s " + SHOT + " 2>/dev/null", 30000).trim();
        String size = "";
        for (String line : r.split("\n")) {
            String s = line.trim();
            if (s.matches("\\d+")) size = s;
        }
        if (size.isEmpty()) throw new IOException("screencap 失败（无输出）: " + oneLine(r));
        byte[] png = adb.pull(SHOT, 60000);
        adb.exec("rm -f " + SHOT, 8000);
        if (png.length < 1000) throw new IOException("截图过小 " + png.length + " B，可能屏幕全黑");
        return png;
    }

    /** 上一次 snapshot() 的底图来源（给 UI-02 的页面角标用） */
    public String shotSource = "";
    /** 上一次 snapshot() 用的 displayId（0 = 主屏） */
    public int shotDisplay;

    /** 目标应用所在的副屏；不在副屏 / 没窗口返回 -1 */
    private int targetDisplayId() {
        try {
            TaskInfo t = findTask(cfg.pkg);
            if (t != null && t.displayId > 0) return t.displayId;
        } catch (Throwable ignored) { }
        return -1;
    }

    // ============================================================ 资源

    /** 由 MainActivity 注入 assets/vd.dex，避免本类依赖 Context */
    private byte[] dex;
    private byte[] killvds;

    public void setAssets(byte[] dex, byte[] killvds) {
        this.dex = dex;
        this.killvds = killvds;
        this.dexSize = dex == null ? -1 : dex.length;
    }

    private byte[] readAssetDex() {
        if (dex == null) throw new IllegalStateException("未注入 vd.dex");
        return dex;
    }

    private byte[] readAssetKillvds() {
        if (killvds == null) throw new IllegalStateException("未注入 killvds.sh");
        return killvds;
    }

    // ============================================================ 工具

    private void logIndented(String text) {
        for (String line : text.split("\n")) {
            String t = line.replace("\r", "").trim();
            if (!t.isEmpty()) log.print("  " + t);
        }
    }

    private static String tail(String s, int n) {
        String[] lines = s.replace("\r", "").split("\n");
        int from = Math.max(0, lines.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    private static String lastLine(String s) {
        String[] lines = s.replace("\r", "").split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (!t.isEmpty() && t.matches("\\d+")) return t;
        }
        return "";
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    /** 从 from 之后找第一段连续数字（解析 am stack list 的 id / displayId 用） */
    private static int numAfter(String s, int from) {
        int i = Math.max(0, from);
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        int st = i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return parseInt(s.substring(st, i));
    }

    private static String oneLine(String s) {
        for (String line : s.split("\n")) {
            String t = line.replace("\r", "").trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    static String fmt(String f, Object... args) { return String.format(Locale.US, f, args); }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
