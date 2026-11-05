package com.xiaopeng.vdcast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 在车机截图上拖出投屏区域。
 *
 * 传入：png（缓存文件路径）、crop（当前 x,y,w,h）、out_w / out_h（发送端输出尺寸，用来算锁定比例）
 * 传出：crop
 */
public class CropActivity extends Activity {

    private CropView view;
    private TextView hint;
    private CheckBox lock;
    private float ratio;
    /** 比例是从哪儿来的（「仪表区域 1216×360」/「发送尺寸 …」），直接写在锁比例框上，方便肉眼核对 */
    private String ratioFrom = "";
    /** UI-02：这张底图是从哪儿截的（虚拟屏 / 目标应用窗口 / 主屏），页面左上角必须标出来 */
    private String src = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 车机上 Activity 被塞在 freeform 窗口里，装饰栏会白吃掉一截高度，
        // 直接把系统栏全部隐藏，避免底部按钮被裁掉。
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
        String path = in.getStringExtra("png");
        src = in.getStringExtra("src");
        int outW = in.getIntExtra("out_w", 1920);
        int outH = in.getIntExtra("out_h", 568);
        // UI-13：锁比例**直接用界面上的仪表区域矩形算**，不依赖 cfg.outW/outH ——
        // 那两个值以前只在 start() 里被覆盖，先定矩形、没启动就来框选，锁到的是上一次的比例，
        // 框出来的画面会被拉伸或裁掉。把 crect 本身传进来，就不存在「同步没赶上」的窗口。
        Rect cr = parse(in.getStringExtra("crect"));
        if (cr != null) {
            ratio = cr.width() / (float) cr.height();
            ratioFrom = "仪表 " + cr.width() + "×" + cr.height();
        } else {
            ratio = outH > 0 ? outW / (float) outH : 0f;
            ratioFrom = "发送 " + outW + "×" + outH;
        }

        Bitmap bmp = decode(path);
        if (bmp == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        setContentView(buildUi(bmp, in.getStringExtra("crop")));
    }

    private Bitmap decode(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // 截图无透明，省一半内存
            return BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density * 2.4f);
    }

    /** 与主面板一致的胶囊按钮 */
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

    private android.view.View buildUi(Bitmap bmp, String cropStr) {
        // 用 FrameLayout + 底部浮条：底栏永远贴在窗口下沿，
        // 不会因为图片按比例撑高而把按钮挤出屏幕（之前线性布局就被裁过半截按钮）。
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFF101214);

        final int barH = dp(62);

        view = new CropView(this);
        view.setBitmap(bmp);
        Rect init = parse(cropStr);
        if (init != null) view.setCrop(init);
        view.setRatio(ratio);
        view.setInsetBottom(barH);
        view.setOnChangeListener(r -> hint.setText(
                r.left + "," + r.top + "  " + r.width() + "×" + r.height()));
        root.addView(view, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // UI-02：底图来源角标（必须在 view 之后加，否则被底图盖住）。
        // 取到主屏时要说清「你框的未必是真正投出去的那块」，否则用户以为在调投屏画面。
        if (src != null && !src.isEmpty()) {
            boolean mainScreen = src.startsWith("主屏");
            TextView badge = new TextView(this);
            badge.setText("底图来源：" + src
                    + (mainScreen ? "（目标应用不在副屏；先启动投屏/预演再框选更准）" : ""));
            badge.setTextColor(mainScreen ? 0xFFFFC46B : 0xFFBFE3FF);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
            badge.setBackground(pill(0xE61B1F24, mainScreen ? 0xFF6B4A16 : 0xFF24445E));
            badge.setPadding(dp(12), dp(6), dp(12), dp(6));
            android.widget.FrameLayout.LayoutParams blp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.TOP | Gravity.LEFT;
            blp.leftMargin = dp(12);
            blp.topMargin = dp(12);
            root.addView(badge, blp);
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // 右下角是车机自己的语音助手悬浮球，会盖住按钮且吃点击事件，
        // 右边留出一块空位把「取消/确定」挤到悬浮球左边。
        bar.setPadding(dp(10), 0, dp(170), 0);
        bar.setBackgroundColor(0xE61B1F24);

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        hint.setText("拖出要投的区域");
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(hint, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        lock = new CheckBox(this);
        lock.setText(ratio > 0
                ? String.format(java.util.Locale.US, "锁比例 %.2f · %s", ratio, ratioFrom)
                : "锁比例");
        lock.setTextColor(0xFFE0E0E0);
        lock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
        lock.setChecked(true);
        bar.addView(lock);

        Button cancel = mkBtn("取消");
        bar.addView(cancel);

        Button ok = mkBtn("确定");
        ok.setTextColor(0xFF7FD1FF);
        ok.setBackground(pill(0xFF16283A, 0xFF23425E));
        ok.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(ok);

        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH);
        blp.gravity = Gravity.BOTTOM;
        blp.bottomMargin = dp(4);
        root.addView(bar, blp);

        lock.setOnCheckedChangeListener((v, checked) -> view.setRatio(checked ? ratio : 0f));

        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        ok.setOnClickListener(v -> {
            Rect r = view.getCrop();
            Intent out = new Intent();
            out.putExtra("crop", r.left + "," + r.top + "," + r.width() + "," + r.height());
            setResult(RESULT_OK, out);
            finish();
        });

        return root;
    }

    private Rect parse(String s) {
        if (s == null) return null;
        String[] p = s.trim().split(",");
        if (p.length != 4) return null;
        try {
            int x = Integer.parseInt(p[0].trim());
            int y = Integer.parseInt(p[1].trim());
            int w = Integer.parseInt(p[2].trim());
            int h = Integer.parseInt(p[3].trim());
            if (w <= 0 || h <= 0) return null;
            return new Rect(x, y, x + w, y + h);
        } catch (Throwable t) {
            return null;
        }
    }
}
