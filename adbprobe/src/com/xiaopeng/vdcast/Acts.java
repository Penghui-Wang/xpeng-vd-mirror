package com.xiaopeng.vdcast;

/**
 * 遥控入口的动作表 —— APK 侧唯一真相源（UX-11）。
 *
 * <p>这张表有三处消费方，任何一处掉队都会在离线测试 {@code adbprobe/test/ActsTest.java} 里报出来：
 * <ol>
 *   <li>{@link MainActivity#handleIntent} 的 switch —— 动作是不是真的接上了；</li>
 *   <li>{@code tools/vdcast.sh} 的 {@code ACTS} 列表 —— PC 侧脚本有没有同步；</li>
 *   <li>{@code docs/遥控入口-act-v1.md} —— 人看的说明。</li>
 * </ol>
 *
 * <p>为什么单独一个类：{@code MainActivity} 依赖 {@code android.*}，在 JVM 上编译不了；
 * 这里只用 {@code java.*}，离线测试才能直接拿它和另外两处对账。
 *
 * <p>语义约定（改动作名前先读这几条）：
 * <ul>
 *   <li><b>参数在前、动作最后</b>：{@code am start} 会把 {@code --es} 之后多出来的裸参数当 data URI，
 *       {@code --es act crop 1,2,3,4} 里的坐标就是这样被吃掉的；</li>
 *   <li><b>参数只属于声明消费它的动作</b>：{@code act=status} 不会顺手把 {@code --es pkg} 写进配置；</li>
 *   <li><b>必需参数缺失就拒绝</b>，绝不沿用界面上的旧值（历史上「静默用旧裁切」就是这个原因）；</li>
 *   <li><b>旧名不再接受</b>，但会打印该改成什么（见 {@link #deprecated(String)}）。</li>
 * </ul>
 */
public final class Acts {

    private Acts() { }

    /** 参数顺序这条坑的唯一文案来源：错误提示、help、文档都引用它 */
    public static final String ORDER_TIP =
            "参数写在前、动作写最后：--es crop 1,2,3,4 --es act crop";

    /**
     * {动作, 消费的参数（逗号分隔，空 = 无）, 一行说明, 等价界面}。
     *
     * <p>动作名一律用动词/名词短词，和界面按钮一一对应；{@code vd} / {@code main} 这类
     * 其实是「模式」而不是动作，已收进 {@code start --es mode}。
     */
    public static final String[][] TABLE = {
            {"connect",  "port",                "只连 adbd",                    "高级 → 连接"},
            {"status",   "",                    "打印现状（工程级）",           "高级 → 状态"},
            {"health",   "",                    "刷新三层小灯",                 "小灯行 → 刷新"},
            {"probe",    "",                    "环境四步自检",                 "高级 → 自检"},
            {"pick",     "",                    "选目标应用",                   "目标应用 → 选择"},
            {"apps",     "",                    "候选应用清单写日志（诊断）",   "仅遥控"},
            {"area",     "",                    "打开仪表区域框选页",           "仪表区域 → 框选"},
            {"snap",     "",                    "打开车机画面裁切页",           "车机画面 → 裁切"},
            {"crop",     "crop",                "应用裁切（热生效）",           "车机画面 → 应用"},
            {"crect",    "crect",               "应用仪表矩形（需重新启动）",   "仪表区域 → 应用"},
            {"start",    "pkg,mode,crop,crect", "一键启动投屏（8 步）",         "主按钮「启动投屏」"},
            {"stop",     "",                    "停止并复原（4 步）",           "主按钮「停止」"},
            {"cancel",   "",                    "中断当前任务（粒度＝步）",     "进度条 → 中断"},
            {"residue",  "",                    "重新做启动自检",               "残留横幅"},
            {"clean",    "",                    "一键清理残留",                 "残留横幅 → 清理"},
            {"preview",  "",                    "打开实时预览",                 "「预览」"},
            {"reset",    "",                    "仪表区域复位到安全区",         "仪表区域 → 复位"},
            {"coords",   "",                    "控件坐标写日志（自校准用）",   "仅遥控"},
            {"exit",     "",                    "退出（带二次确认）",           "顶栏 → 退出"},
            {"exitnow",  "",                    "退出（不确认，自动化用）",     "仅遥控"},
            {"resync",   "",                    "清工具同步标记，下次启动重推", "仅遥控"},
            {"help",     "",                    "打印本表",                     "仅遥控"},
    };

    /** 动作是否在表里 */
    public static boolean known(String act) {
        return row(act) != null;
    }

    /** 该动作消费哪些参数；无参数返回空数组 */
    public static String[] params(String act) {
        String[] r = row(act);
        if (r == null || r[1].isEmpty()) return new String[0];
        return r[1].split(",");
    }

    /** 该动作是否消费这个参数 —— 不消费的参数一律不生效 */
    public static boolean takes(String act, String param) {
        for (String p : params(act)) if (p.equals(param)) return true;
        return false;
    }

    /** 该动作是否「必须」带这个参数；缺了就拒绝执行 */
    public static boolean requires(String act, String param) {
        if ("crop".equals(act) && "crop".equals(param)) return true;
        if ("crect".equals(act) && "crect".equals(param)) return true;
        if ("start".equals(act) && "pkg".equals(param)) return true;
        return false;
    }

    /**
     * 旧动作名 → 新写法提示；不是旧名返回 null。
     *
     * <p>R3-2 明确不要求向后兼容，但「静默不认识」会让 PC 侧脚本以为是设备问题，
     * 所以保留一条能照抄的迁移提示。
     */
    public static String deprecated(String act) {
        if ("vd".equals(act)) return "--es mode vd --es act start";
        if ("main".equals(act)) return "--es mode main --es act start";
        if ("cluster".equals(act)) return "--es act area";
        return null;
    }

    /** 一行列出全部动作名，未知 act 时打在日志里 */
    public static String oneLine() {
        StringBuilder sb = new StringBuilder();
        for (String[] r : TABLE) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(r[0]);
        }
        return sb.toString();
    }

    /** help 的正文：每条一行「动作 参数 说明」，PC 侧直接 grep 就能核对 */
    public static String[] helpLines() {
        String[] out = new String[TABLE.length + 1];
        out[0] = ORDER_TIP;
        for (int i = 0; i < TABLE.length; i++) {
            String[] r = TABLE[i];
            StringBuilder sb = new StringBuilder();
            sb.append(pad(r[0], 9));
            sb.append(pad(r[1].isEmpty() ? "-" : r[1], 20));
            sb.append(r[2]);
            sb.append("  ｜ ").append(r[3]);
            out[i + 1] = sb.toString();
        }
        return out;
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        sb.append(' ');
        return sb.toString();
    }

    private static String[] row(String act) {
        if (act == null) return null;
        for (String[] r : TABLE) if (r[0].equals(act)) return r;
        return null;
    }
}
