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

    public interface Log { void print(String s); }

    /** 启动参数 */
    public static final class Cfg {
        public String mode = "vd";                 // vd | main
        public int fps = 60;
        public int hold = 86400;                   // 仪表端断流超时，每来一帧重置
        public int outW = 1920;
        public int outH = 568;                     // 必须 = 720 - top - bot
        public int top = 72;
        public int bot = 80;
        public String pkg = "com.tencent.qqmusicpad";
        public String crop = "960,345,1440,426";   // x,y,w,h（车机屏幕绝对坐标）
        public String carIpFallback = "172.20.1.45";
        /** 本地预览端口：发送端在 127.0.0.1 上多播原始 565 帧，APK 的预览页连它 */
        public int previewPort = 5570;
        /**
         * 仪表端投屏矩形（fb1 坐标，1920×720）。
         * cCustom=false 时忽略，行为等于原来的满宽横带（0,top,1920,720-top-bot）。
         * cCustom=true 时 outW/outH 会自动跟 cW/cH 对齐，避免拉伸变形。
         */
        public boolean cCustom = false;
        public int cX = 0;
        public int cY = 72;
        public int cW = 1920;
        public int cH = 568;

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
                cW = Math.max(2, Math.min(1920, cW));
                cH = Math.max(2, Math.min(720, cH));
                if (cX < 0) cX = 0;
                if (cY < 0) cY = 0;
                if (cX + cW > 1920) cX = 1920 - cW;
                if (cY + cH > 720) cY = 720 - cH;
            } else {
                // 满宽横带：矩形由 top/bot 决定，忽略外部填进来的 cX/cY/cW/cH
                cX = 0;
                cY = top;
                cW = 1920;
                cH = Math.max(2, 720 - top - bot);
            }
            outW = cW;
            outH = cH;
        }

        /** 投屏区像素数，用于按面积判断帧率预期（见 docs/B0-基线数据-v1.md §7） */
        public int areaPx() { return cW * cH; }

        /** 是否按「满宽横带」投（没有自定义矩形） */
        public boolean isFullBand() { return !cCustom; }
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
     * 按 B0 实测标定给一条「这块面积大概能跑到多少帧」的预期（不是保证值）。
     *
     * 口径见 docs/B0-基线数据-v1.md §7：仪表端解码 ≈20~24 ns/px，60Hz 预算扣掉
     * read/draw/flush 后只剩 10~11ms，所以面积上限约 45~55 万像素。
     * 实测 55.0 万以下（550x384 = 21 万）跑满 61.8 帧，满宽横带 1920x568 = 109 万
     * 只有 30~39 帧。这里只负责把话说清楚，不拦用户选大区域。
     */
    public static String fpsHint(int w, int h) {
        long px = (long) w * h;
        String area = String.format(Locale.US, "%.0f 万像素", px / 10000.0);
        String band;
        if (px <= 450000) band = "可跑满 60 帧";
        else if (px <= 600000) band = "临界，约 55~60 帧";
        else if (px <= 900000) band = "约 40~50 帧";
        else band = "约 30~40 帧（接近满宽，物理上限）";
        return area + "，" + band;
    }

    // ============================================================ 启动

    public boolean start() {
        try {
            // 自定义仪表区域 / 满宽横带都先规范化：夹到 1920x720 内，并让发送尺寸
            // 跟矩形严格一致，否则仪表端要拉伸，画面会扁/拉长（UX-15）。
            cfg.normalize();
            // 投出去的就是这一刻的 pkg，之前/之后界面上再改都与本次投屏无关
            activePkg = cfg.pkg;
            log.print("[0/8] 检查车机 adb（" + adb.endpoint + "）");
            String ok = adb.exec("echo car_ok", 8000);
            if (!ok.contains("car_ok")) {
                log.print("!! 车机无响应：adbd 没开 / 端口不通 / 不在同一网络");
                return false;
            }
            log.print("  ok (mode=" + cfg.mode + ", 目标=" + cfg.pkg + ", "
                    + cfg.outW + "x" + cfg.outH + ", " + cfg.fps + "fps)");
            log.print("  投屏区 " + cfg.cW + "x" + cfg.cH + "，" + fpsHint(cfg.cW, cfg.cH));

            // 发送端只认 vdcrop.txt（它靠这个文件热重载），所以启动前必须把界面上
            // 当前填的裁切值落盘，否则用户改了输入框没点「应用」就直接启动，
            // 实际用的还是上一次的旧裁切 —— 这个坑踩过。
            if (cfg.crop != null && cfg.crop.matches("\\d+,\\d+,\\d+,\\d+")) {
                adb.exec("echo '" + cfg.crop + "' > /data/local/tmp/vdcrop.txt", 8000);
                log.print("  裁切 " + cfg.crop + " 已写入 " + "/data/local/tmp/vdcrop.txt");
            }

            log.print("[1/8] 推发送端 dex + 杀进程脚本");
            if (!pushDex()) return false;
            adb.push(readAssetKillvds(), KILLVDS, Adb.MODE_EXEC, 20000);
            adb.exec("chmod 755 " + KILLVDS + "; rm -f /data/local/tmp/vdcap", 10000);
            log.print("  " + DEX + " (" + dexSize + " B)");

            log.print("[2/8] 停旧发送端（先 TERM 让 vd 模式把窗口搬回去）");
            String out = adb.exec("sh " + KILLVDS + " stop " + cfg.pkg, 45000);
            for (String line : tail(out, 3).split("\n")) {
                if (!line.trim().isEmpty()) log.print("  " + line.trim());
            }

            // 必须放在起发送端之前：VdSend 的 vd 模式一上来就要找目标窗口，
            // 找不到会直接抛异常退出（表现就是「投出来的是主屏/控制台自己」）。
            if ("vd".equals(cfg.mode)) {
                log.print("[3/8] 确保目标应用有窗口：" + cfg.pkg);
                if (!ensureTargetApp()) return false;
            }

            log.print("[4/8] 确保仪表端 " + ICBIN);
            if (!ensureClusterBinary()) return false;

            log.print("[5/8] 起仪表接收端（投屏区 "
                    + (cfg.cCustom ? cfg.cX + "," + cfg.cY + " " + cfg.cW + "x" + cfg.cH
                                   : "满宽横带 y=" + cfg.top + ".." + (720 - cfg.bot))
                    + "）");
            int alive = startReceiver();
            log.print("  接收端进程: " + alive + "（应为 1）");

            log.print("[6/8] 起车机发送端（shell 域，mode=" + cfg.mode + "，" + cfg.fps + " fps）");
            startSender();

            log.print("[7/8] 等端到端出帧（轮询，出帧就往下走）");
            log.print("  --- 仪表：接收/合成 ---");
            // 仪表侧也轮询：日志里出现 frames= 就说明真的收到帧了，不用再干等。
            // 上限 5 秒，超了照样往下走（真没帧的话 [8/8] 会报出来）。
            // 放在车机速率之前：这一步本来就要一秒多，正好让 VdSend 把第一行 fps= 打出来。
            logIndented(Icrpc.run(adb, "i=0; while [ $i -lt 25 ]; do "
                    + "grep -q 'frames=' /tmp/icrecv.log && break; i=$((i+1)); sleep 0.2; done; "
                    + "grep -E 'frames=|LISTEN|VISIBLE|DST ' /tmp/icrecv.log | tail -3", 8, 45000));
            log.print("  --- 车机：采集/发送 ---");
            logIndented(adb.exec("grep 'fps=' " + VDSEND_LOG + " | tail -2", 10000));

            log.print("[8/8] 健康检查");
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
                return false;
            }
            log.print("");
            log.print("就绪。改裁切区：点「框选」在截图上拖，或直接填 x,y,w,h（热生效约 0.5s）");
            return true;
        } catch (Throwable t) {
            log.print("!! 启动中断: " + brief(t));
            return false;
        }
    }

    // ============================================================ 停止

    public boolean stop() {
        try {
            // 恢复窗口用「投出去的那一份」，不是界面上当前填的（可能已经被改成别的应用）
            String pkg = activePkg.isEmpty() ? cfg.pkg : activePkg;
            if (!pkg.equals(cfg.pkg)) {
                log.print("  恢复目标用本次实际投出的应用：" + pkg
                        + "（界面上现在填的是 " + cfg.pkg + "，不参与收尾）");
            }
            log.print("[1/3] 停车机发送端");
            String out = adb.exec("sh " + KILLVDS + " stop " + pkg
                    + "; rm -f /data/local/tmp/vdcap", 45000);
            for (String line : tail(out, 8).split("\n")) {
                if (!line.trim().isEmpty()) log.print("     " + line.trim());
            }
            log.print("     残留进程: " + adb.exec("sh " + KILLVDS + " check", 15000).trim());
            log.print("     窗口位置: " + oneLine(adb.exec("am stack list 2>/dev/null | grep -F "
                    + pkg + " | head -1", 15000)));
            log.print("     残留虚拟屏: " + oneLine(adb.exec(
                    "dumpsys display | grep -c 'DisplayDeviceInfo{\"asxvd'", 15000)));

            log.print("[2/3] 停仪表接收端（SIGTERM -> restore）");
            int alive = stopReceiver();
            log.print("     残留进程: " + alive);

            log.print("[3/3] 确认仪表已恢复原版面");
            logIndented(Icrpc.run(adb,
                    "grep -E 'RESTORE_OK|pixels restored|RESTORED blank' /tmp/icrecv.log | tail -2", 8, 40000));
            log.print("done");
            activePkg = "";
            // 恢复窗口会把目标应用搬回前台，面板整个被盖住 —— 用户点完「停止」看不到结果。
            // 收尾完成后把自己拉回来（此时 restore 已完成，不会再被抢）
            try {
                adb.exec("am start -n " + SELF + "/.MainActivity", 12000);
            } catch (Throwable ignored) { }
            return true;
        } catch (Throwable t) {
            log.print("!! 停止中断: " + brief(t));
            return false;
        }
    }

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
        return parseInt(lastLine(Icrpc.run(adb, cmd, 8, 45000)));
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

    private void startSender() throws IOException {
        String cmd = "CLASSPATH=" + DEX + " setsid app_process /system/bin com.probe.asx.VdSend "
                + "--child --mode " + cfg.mode + " --pkg " + cfg.pkg + " "
                + cfg.outW + " " + cfg.outH + " " + cfg.fps
                + (cfg.previewPort > 0 ? " --preview " + cfg.previewPort : "")
                + " > " + VDSEND_LOG + " 2>&1 & "
                // 以前这里 sleep 10 硬等。现在以 capture ready 为准（实测 1~1.5 秒），
                // 出了就走；第一行 fps= 是 2 秒时才打的，留给 [7/8] 再取。
                + "i=0; while [ $i -lt 75 ]; do grep -qE 'capture ready|FAIL' " + VDSEND_LOG
                + " && break; i=$((i+1)); sleep 0.2; done; "
                + "grep -E 'child start|VD created|target task|launching target|moved to display|"
                + "shadow display ready|capture ready|args crop|FAIL|fps=' " + VDSEND_LOG + " | tail -6";
        logIndented(adb.exec(cmd, 25000));
    }

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
                log.print("  拉起成功: " + t.label());
                return true;
            }
        }
        log.print("!! 起不来。可能：包名写错 / 该应用没有桌面入口 / 被系统禁止启动");
        log.print("   可先点「选择应用」，从运行中的任务里挑一个再试");
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
        String arg = vd > 0 ? "-d " + vd + " " : "";
        log.print("  截取" + (vd > 0 ? "虚拟屏 display " + vd : "主屏"));

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
