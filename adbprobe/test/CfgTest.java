import com.xiaopeng.vdcast.VdCtl;

/**
 * 投屏区口径的离线断言（2026-11-05 16:00 口径由 A 改 B：默认收窄到安全区）。
 *
 * 这些数值是「仪表是安全件」这条决策的直接体现，改坏了不会有崩溃，只会悄悄把
 * 整块仪表又占满、帧率掉回 30~40 —— 所以用断言钉住，而不是靠肉眼看日志。
 *
 * 不需要车机、不需要 adb：VdCtl 不依赖任何 android.* 类型。
 */
public final class CfgTest {

    private static int pass, fail;

    public static void main(String[] a) {
        Cfg_default_is_safe();
        Cfg_default_shape();
        Cfg_default_aspect_matches_crop();
        Full_band_still_available();
        Use_safe_default_restores();
        Area_note_wording();

        System.out.println();
        System.out.println("pass=" + pass + " fail=" + fail);
        System.out.println(fail == 0 ? "RESULT OK" : "RESULT FAILED");
        if (fail != 0) System.exit(1);
    }

    // ---------------------------------------------------------------- 用例

    /** 出厂默认必须落在安全区内，且发送尺寸与矩形一致（否则仪表端要拉伸） */
    private static void Cfg_default_is_safe() {
        VdCtl.Cfg c = new VdCtl.Cfg();
        c.normalize();
        check("默认面积 <= 50 万像素",
                (long) c.cW * c.cH <= VdCtl.AREA_SAFE, c.cW + "x" + c.cH + " = " + (long) c.cW * c.cH);
        check("默认面积 = 437760", (long) c.cW * c.cH == 437760L, "" + (long) c.cW * c.cH);
        check("发送尺寸 = 矩形", c.outW == c.cW && c.outH == c.cH, c.outW + "x" + c.outH);
        check("默认走 cCustom 分支", c.cCustom, "cCustom=" + c.cCustom);
        // 16:12 车主明确：不要出现「可跑满 60 帧」这类承诺，帧率受画面复杂度影响。
        // 这里钉住「面积描述里不许出现任何帧率字样」，防止以后又被加回去
        String area = VdCtl.areaText(c.cW, c.cH);
        check("面积描述不带帧率承诺", !area.contains("帧"), area);
    }

    /** 居中于 1920x720：左右、上下留白相等 */
    private static void Cfg_default_shape() {
        VdCtl.Cfg c = new VdCtl.Cfg();
        c.normalize();
        check("默认 1216x360", c.cW == 1216 && c.cH == 360, c.cW + "x" + c.cH);
        check("水平居中", c.cX * 2 + c.cW == VdCtl.CL_W, "cX=" + c.cX);
        check("垂直居中", c.cY * 2 + c.cH == VdCtl.CL_H, "cY=" + c.cY);
        check("不压顶部状态栏", c.cY >= c.top, "cY=" + c.cY + " top=" + c.top);
        check("不压底部状态栏", c.cY + c.cH <= VdCtl.CL_H - c.bot,
                (c.cY + c.cH) + " <= " + (VdCtl.CL_H - c.bot));
    }

    /** 与默认 crop 1440x426 等比，容差 0.5% —— 不等比就会被 VdSend 拉扁 */
    private static void Cfg_default_aspect_matches_crop() {
        VdCtl.Cfg c = new VdCtl.Cfg();
        c.normalize();
        String[] p = c.crop.split(",");
        double cropRatio = Double.parseDouble(p[2]) / Double.parseDouble(p[3]);
        double rectRatio = (double) c.cW / c.cH;
        double diff = Math.abs(rectRatio - cropRatio) / cropRatio;
        check("矩形与 crop 等比（容差 0.5%）", diff < 0.005,
                String.format("rect=%.4f crop=%.4f diff=%.4f%%", rectRatio, cropRatio, diff * 100));
    }

    /** 全屏（满宽横带）仍然可以做，只是不再默认 —— 车主临时要看全屏不能被堵死 */
    private static void Full_band_still_available() {
        VdCtl.Cfg c = new VdCtl.Cfg();
        c.cCustom = false;
        c.normalize();
        check("全屏 = 1920x568", c.cW == 1920 && c.cH == 568, c.cW + "x" + c.cH);
        check("全屏识别为满宽横带", c.isFullBand(), "isFullBand=" + c.isFullBand());
        check("全屏面积超安全区", (long) c.cW * c.cH > VdCtl.AREA_SAFE, "" + (long) c.cW * c.cH);
        check("isFullBandRect 认 0,72,1920,568",
                VdCtl.isFullBandRect(0, 72, 1920, 568, 72, 80), "0,72,1920,568");
        check("isFullBandRect 不认安全区",
                !VdCtl.isFullBandRect(VdCtl.DEF_CX, VdCtl.DEF_CY, VdCtl.DEF_CW, VdCtl.DEF_CH, 72, 80),
                VdCtl.DEF_RECT);
    }

    /** 被改成全屏或脏值后，一键能回到安全区 */
    private static void Use_safe_default_restores() {
        VdCtl.Cfg c = new VdCtl.Cfg();
        c.cCustom = true;
        c.cX = 0; c.cY = 0; c.cW = 1920; c.cH = 720;
        c.normalize();
        check("先造一个坏值", c.cW == 1920 && c.cH == 720, c.cW + "x" + c.cH);
        c.useSafeDefault();
        check("useSafeDefault 回到安全区",
                c.cW == VdCtl.DEF_CW && c.cH == VdCtl.DEF_CH
                        && c.cX == VdCtl.DEF_CX && c.cY == VdCtl.DEF_CY,
                c.cX + "," + c.cY + "," + c.cW + "," + c.cH);
        check("useSafeDefault 后仍在安全区", (long) c.cW * c.cH <= VdCtl.AREA_SAFE,
                "" + (long) c.cW * c.cH);
    }

    /** 面板提醒句：安全区内不许出现「⚠」，超了必须直说「不建议全屏投放」 */
    private static void Area_note_wording() {
        String safe = VdCtl.areaNote(VdCtl.DEF_CW, VdCtl.DEF_CH);
        String big = VdCtl.areaNote(1920, 568);
        check("安全区提醒含「安全件」", safe.contains("安全件"), safe);
        check("安全区提醒不带警告符", !safe.startsWith("⚠"), safe);
        check("安全区提醒不提帧率", !safe.contains("帧"), safe);
        check("安全区提醒给出默认值", safe.contains(VdCtl.DEF_RECT), safe);
        check("超区提醒以 ⚠ 开头", big.startsWith("⚠"), big);
        check("超区提醒说「不建议全屏投放」", big.contains("不建议全屏投放"), big);
        check("超区提醒提帧率只用定性说法", !big.contains("帧率会降到") && big.contains("帧率"), big);
        check("超区提醒给出默认值", big.contains(VdCtl.DEF_RECT), big);
    }

    // ---------------------------------------------------------------- 小工具

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[OK]   " + name + "  (" + detail + ")");
        } else {
            fail++;
            System.out.println("[FAIL] " + name + "  (" + detail + ")");
        }
    }
}
