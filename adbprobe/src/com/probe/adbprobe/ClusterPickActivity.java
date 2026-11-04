package com.probe.adbprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * 仪表投屏区域选择页。
 *
 * 底图用打包进来的 fb0_pre.png（原生 1920x720，与仪表 fb1 坐标系一致，不用换算），
 * 红色框 = 投屏矩形，默认就是现在的满宽横带 (0,72,1920,568)。
 * 框压到上下状态栏时给一条安全提醒，但仍然允许确定。
 */
public class ClusterPickActivity extends Activity {

    private CropView view;
    private TextView hint;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 和其它页一致：全屏，别让系统栏白吃掉底部一行按钮
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
        int top = in.getIntExtra("top", 72);
        int bot = in.getIntExtra("bot", 80);

        Bitmap bmp = decodeAsset("fb0_pre.png");
        if (bmp == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D10);

        final int barH = dp(56);

        view = new CropView(this);
        view.setBitmap(bmp);
        Rect init = parse(in.getStringExtra("crop"));
        if (init != null) view.setCrop(init);
        view.setRatio(0f);                 // 仪表区域自由比例
        view.setFrameColor(0xFFFF3B30);    // 红框
        view.setBands(top, bot);
        view.setInsetBottom(barH);
        view.setOnChangeListener(r -> {
            int cy = r.top, ch = r.height(), cx = r.left, cw = r.width();
            boolean bad = cy < top || cy + ch > 720 - bot;
            hint.setText(bad
                    ? "⚠ 压到状态栏了（挡位/温度/时间可能被遮住），仍可确定"
                    : "投屏区 " + cx + "," + cy + "  " + cw + "×" + ch);
            hint.setTextColor(bad ? 0xFFFFB020 : 0xFF9AA4AF);
        });
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(170), 0);   // 右侧给语音助手悬浮球让位
        bar.setBackgroundColor(0xF20B0D10);

        hint = new TextView(this);
        hint.setText("拖出要投到仪表上的区域");
        hint.setTextColor(0xFF9AA4AF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        bar.addView(hint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button reset = mkBtn("整条");
        reset.setOnClickListener(v -> {
            view.setCrop(new Rect(0, top, 1920, 720 - bot));
        });
        bar.addView(reset);

        Button cancel = mkBtn("取消");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        bar.addView(cancel);

        Button ok = mkBtn("确定");
        ok.setTextColor(0xFFFF6A60);
        ok.setBackground(pill(0xFF3A1D20, 0xFF6B2A2E));
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setOnClickListener(v -> {
            Rect r = view.getCrop();
            Intent out = new Intent();
            out.putExtra("crect", r.left + "," + r.top + "," + r.width() + "," + r.height());
            out.putExtra("custom", !(r.left == 0 && r.top == top
                    && r.width() == 1920 && r.height() == 720 - top - bot));
            setResult(RESULT_OK, out);
            finish();
        });
        bar.addView(ok);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bar, blp);

        setContentView(root);
    }

    /** 跟主面板一致的胶囊按钮：暗底 + 细描边，主操作给强调色 */
    private Button mkBtn(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
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
        lp.setMargins(dp(5), 0, dp(5), 0);
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density * 2.4f);
    }

    /** 底图打包在 assets 里，缺省时退回到能拿到的任意 fb0 截图 */
    private Bitmap decodeAsset(String name) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream is = getAssets().open(name)) {
            return BitmapFactory.decodeStream(is, null, o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Rect parse(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            int x = Integer.parseInt(p[0].trim());
            int y = Integer.parseInt(p[1].trim());
            int w = Integer.parseInt(p[2].trim());
            int h = Integer.parseInt(p[3].trim());
            if (w <= 0 || h <= 0) return null;
            return new Rect(x, y, x + w, y + h);
        } catch (Exception e) {
            return null;
        }
    }

    /** 主题色 */
    static final int ACCENT = 0xFFFF3B30;
}
