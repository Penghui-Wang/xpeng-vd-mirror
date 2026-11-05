import com.xiaopeng.vdcast.Acts;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 遥控入口（UX-11）的离线断言。
 *
 * <p>核心约束只有一句：**同一套动作在 APK 与 PC 脚本上都存在、命名一致**
 * （审计答复 §2.3）。这件事靠人肉同步一定会漂 —— 历史上就是「脚本 7 步 / APK 8 步」。
 * 所以这里直接对账三处：
 * <ol>
 *   <li>{@link Acts#TABLE}（APK 侧动作表）；</li>
 *   <li>{@code MainActivity.handleIntent} 的 switch（动作是否真接上了）；</li>
 *   <li>{@code tools/vdcast.sh} 的 {@code ACTS} 行（PC 侧脚本）。</li>
 * </ol>
 *
 * <p>不需要车机、不需要 adb：只读源码文件。
 */
public final class ActsTest {

    private static final String MAIN = "../src/com/xiaopeng/vdcast/MainActivity.java";
    private static final String SCRIPT = "../../tools/vdcast.sh";

    private static int pass, fail;

    public static void main(String[] a) {
        table_is_sane();
        contract_of_params();
        deprecated_names_point_somewhere();
        help_lists_every_act();
        main_activity_switch_covers_table();
        main_activity_rejects_old_names();
        pc_script_covers_table();
        pc_script_documents_order();

        System.out.println();
        System.out.println("pass=" + pass + " fail=" + fail);
        System.out.println(fail == 0 ? "RESULT OK" : "RESULT FAILED");
        if (fail != 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 表本身

    private static void table_is_sane() {
        check("动作表非空", Acts.TABLE.length > 0, "" + Acts.TABLE.length);
        check("动作数 = 22", Acts.TABLE.length == 22, "" + Acts.TABLE.length);
        Set<String> seen = new LinkedHashSet<>();
        boolean dup = false, blank = false;
        for (String[] r : Acts.TABLE) {
            if (!seen.add(r[0])) dup = true;
            if (r[0].isEmpty() || r[2].isEmpty() || r[3].isEmpty()) blank = true;
        }
        check("动作名不重复", !dup, seen.toString());
        check("每行都有说明与界面出处", !blank, "");
        check("包含 help", Acts.known("help"), "");
        check("包含 start", Acts.known("start"), "");
    }

    private static void contract_of_params() {
        check("crop 消费 --es crop", Acts.takes("crop", "crop"), "");
        check("crop 必须有 --es crop", Acts.requires("crop", "crop"), "");
        check("crect 消费 --es crect", Acts.takes("crect", "crect"), "");
        check("start 必须有 --es pkg", Acts.requires("start", "pkg"), "");
        check("start 可以带 mode/crop/crect",
                Acts.takes("start", "mode") && Acts.takes("start", "crop") && Acts.takes("start", "crect"), "");
        check("status 不消费任何参数", Acts.params("status").length == 0, "");
        check("status 不会顺手改 pkg", !Acts.takes("status", "pkg"), "");
        check("help 没有参数", Acts.params("help").length == 0, "");
    }

    private static void deprecated_names_point_somewhere() {
        check("vd 给出迁移提示", "start".equals(Acts.deprecated("vd")) || str(Acts.deprecated("vd")).contains("start"),
                str(Acts.deprecated("vd")));
        check("main 给出迁移提示", str(Acts.deprecated("main")).contains("mode main"), str(Acts.deprecated("main")));
        check("cluster 改名 area", str(Acts.deprecated("cluster")).contains("area"), str(Acts.deprecated("cluster")));
        check("新动作不会被误判成旧名", Acts.deprecated("start") == null, "");
    }

    private static void help_lists_every_act() {
        StringBuilder sb = new StringBuilder();
        for (String l : Acts.helpLines()) sb.append(l).append('\n');
        String help = sb.toString();
        boolean all = true;
        for (String[] r : Acts.TABLE) if (!help.contains(r[0])) all = false;
        check("help 覆盖每一个动作", all, "");
        check("help 写明参数顺序", help.contains("参数写在前"), "");
        check("help 写明 act 最后", help.contains("--es act"), "");
        check("ORDER_TIP 提到 crop 例子", Acts.ORDER_TIP.contains("--es crop 1,2,3,4"), Acts.ORDER_TIP);
    }

    // ---------------------------------------------------------------- 代码对账

    private static void main_activity_switch_covers_table() {
        String src = read(MAIN);
        if (src == null) return;
        List<String> missing = new ArrayList<>();
        for (String[] r : Acts.TABLE) {
            if (!src.contains("case \"" + r[0] + "\":")) missing.add(r[0]);
        }
        check("MainActivity 的 switch 覆盖全部动作", missing.isEmpty(), "缺：" + missing);
        check("收到 data URI / 被吃掉的参数会点出来（覆盖 crop 坐标被吃的坑）",
                src.contains("参数写在了 --es act 之后") && src.contains("Acts.ORDER_TIP"), "");
        check("缺参数时拒绝而不是沿用旧值",
                src.contains("缺 --es"), "");
        check("只带参数不带 act 时不做任何事", src.contains("只收到参数没有 --es act"), "");
    }

    private static void main_activity_rejects_old_names() {
        String src = read(MAIN);
        if (src == null) return;
        check("没有 case \"vd\"", !src.contains("case \"vd\":"), "");
        check("没有 case \"main\"", !src.contains("case \"main\":"), "");
        check("没有 case \"cluster\"", !src.contains("case \"cluster\":"), "");
        check("旧名走 Acts.deprecated", src.contains("Acts.deprecated(act)"), "");
    }

    private static void pc_script_covers_table() {
        String sh = read(SCRIPT);
        if (sh == null) return;
        String acts = null;
        for (String line : sh.split("\n")) {
            if (line.startsWith("ACTS=")) {
                acts = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
                break;
            }
        }
        check("vdcast.sh 有 ACTS 动作表", acts != null, "");
        if (acts == null) return;
        List<String> scriptActs = new ArrayList<>();
        for (String s : acts.trim().split("\\s+")) if (!s.isEmpty()) scriptActs.add(s);
        List<String> apkActs = new ArrayList<>();
        for (String[] r : Acts.TABLE) apkActs.add(r[0]);
        check("pc 脚本与 APK 动作数一致",
                scriptActs.size() == apkActs.size(), scriptActs.size() + " vs " + apkActs.size());
        check("pc 脚本动作集合与 APK 完全一致",
                scriptActs.containsAll(apkActs) && apkActs.containsAll(scriptActs),
                "pc=" + scriptActs);
        check("pc 脚本顺序与 APK 一致（便于对照阅读）", scriptActs.equals(apkActs), "");
        check("pc 脚本拒绝旧名", sh.contains("\"act=vd 已改名") || sh.contains("act=vd 已改名"), "");
        check("pc 脚本有 selftest", sh.contains("cmd_selftest"), "");
    }

    private static void pc_script_documents_order() {
        String sh = read(SCRIPT);
        if (sh == null) return;
        check("脚本把 --es act 放在最后拼", sh.contains("out=\"$out --es act '$act'\""), "");
        check("脚本注释写明坑", sh.contains("data URI"), "");
        check("脚本支持 --dry-run", sh.contains("--dry-run"), "");
        check("脚本支持 --wait", sh.contains("--wait"), "");
    }

    // ---------------------------------------------------------------- 工具

    private static String read(String path) {
        try {
            File f = new File(path);
            check("能读到 " + path, f.isFile(), f.getAbsolutePath());
            if (!f.isFile()) return null;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            check("读 " + path, false, t.toString());
            return null;
        }
    }

    private static String str(String s) { return s == null ? "" : s; }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + what);
        } else {
            fail++;
            System.out.println("  FAIL " + what + (detail.isEmpty() ? "" : "  ← " + detail));
        }
    }
}
