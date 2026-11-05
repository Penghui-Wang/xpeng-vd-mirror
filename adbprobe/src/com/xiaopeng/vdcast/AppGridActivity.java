package com.xiaopeng.vdcast;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 选应用页（UI-01）。
 *
 * 视觉基准是车机自带的应用列表（`materials/M1_applist.png`）：5 列图标网格 + 应用名 +
 * 运行中角标 + 搜索框。老的 {@code AlertDialog} 单列纯文字有两个真问题：
 * 包名难认、列表里混着没有桌面入口的应用。
 *
 * 两条性能约束（R-05）：图标**必须**在后台线程解码 + 内存缓存，主线程只贴图；
 * 页面打开时先出「名字 + 占位图」，图标陆续补上，不能因为取图标把首帧拖住。
 */
public class AppGridActivity extends Activity {

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_SEL = "sel";

    private GridView grid;
    private EditText search;
    private TextView empty;

    /** 全量条目（已按「运行中优先 + 名称」排序），搜索只做过滤 */
    private final List<AppItem> all = new ArrayList<>();
    /** 当前显示的条目 */
    private final List<AppItem> shown = new ArrayList<>();
    private final Set<String> running = new HashSet<>();

    private final ExecutorService loader = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 图标内存缓存：约 8 MB，够放几百个 96px 图标 */
    private final LruCache<String, Drawable> iconCache = new LruCache<String, Drawable>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Drawable d) {
            return Math.max(1, d.getIntrinsicWidth() * d.getIntrinsicHeight() * 4);
        }
    };

    private GridAdapter adapter;

    private static final class AppItem {
        String pkg;
        String label;
        Drawable icon;   // null = 还没解码
        boolean tried;   // 取过一次就别再反复试（取不到图标的包会一直占着线程）
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Intent in = getIntent();
        ArrayList<String> r = in == null ? null : in.getStringArrayListExtra(EXTRA_RUNNING);
        if (r != null) running.addAll(r);
        String sel = in == null ? null : in.getStringExtra(EXTRA_SEL);

        setContentView(buildUi());
        loadAppsAsync(sel);
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density * 2.4f);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1319);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("选择要投到仪表的应用");
        title.setTextColor(0xFFE8EEF5);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索应用名 / 包名");
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        search.setTextColor(0xFFE8EEF5);
        search.setHintTextColor(0xFF5B6673);
        search.setBackground(pill(0xFF141B24, 0xFF243040));
        search.setPadding(dp(12), dp(5), dp(12), dp(5));
        head.addView(search, new LinearLayout.LayoutParams(dp(360), dp(40)));

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        cancel.setTextColor(0xFFE8EEF5);
        cancel.setBackground(pill(0xFF1D2530, 0xFF243040));
        cancel.setMinWidth(0);
        cancel.setMinHeight(0);
        cancel.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        clp.setMargins(dp(10), 0, 0, 0);
        head.addView(cancel, clp);
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        empty = new TextView(this);
        empty.setText("正在读取应用列表…");
        empty.setTextColor(0xFF8A97A6);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26);
        empty.setPadding(dp(6), dp(10), 0, dp(6));
        root.addView(empty);

        grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setBackgroundColor(0xFF0E1319);
        grid.setPadding(0, dp(4), 0, dp(4));
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return;
            Intent out = new Intent();
            out.putExtra("pkg", shown.get(pos).pkg);
            setResult(RESULT_OK, out);
            finish();
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(s.toString()); }
        });
        return root;
    }

    private android.graphics.drawable.Drawable pill(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(8));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    // ---------------------------------------------------------------- 数据

    /**
     * 取「有桌面入口」的应用。
     *
     * 只认 MAIN/LAUNCHER：老对话框把没有入口的包也列出来，用户点了也拉不起来（B4 需求）。
     * 排序：运行中的在前，其次按名称 —— 用户最可能选的就在眼前。
     */
    private void loadAppsAsync(final String sel) {
        loader.execute(() -> {
            final List<AppItem> items = new ArrayList<>();
            try {
                PackageManager pm = getPackageManager();
                Intent probe = new Intent(Intent.ACTION_MAIN);
                probe.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(probe, 0);
                Set<String> seen = new HashSet<>();
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    String p = ri.activityInfo.packageName;
                    if (p.equals(getPackageName()) || !seen.add(p)) continue;
                    AppItem it = new AppItem();
                    it.pkg = p;
                    // 优先用应用级名称：activity 别名（…HomeActivity / …Alias）的 loadLabel
                    // 在部分系统应用上会返回 null，于是列表里全是包名，根本认不出来。
                    CharSequence lb = null;
                    try {
                        lb = pm.getApplicationLabel(pm.getApplicationInfo(p, 0));
                    } catch (Throwable ignored) { }
                    if (lb == null || lb.length() == 0) lb = ri.loadLabel(pm);
                    String label = (lb == null || lb.length() == 0) ? p : lb.toString();
                    // 取不到名字、label 就是包名/包名样式的，基本是系统内部 activity
                    //（它们虽然声明了桌面入口，但不是给人点的），留在列表里只会挤掉真正能用的应用
                    if (label.equals(p) || looksLikePackage(label)) continue;
                    it.label = label;
                    items.add(it);
                }
            } catch (Throwable ignored) { }
            Collections.sort(items, (x, y) -> {
                boolean rx = running.contains(x.pkg), ry = running.contains(y.pkg);
                if (rx != ry) return rx ? -1 : 1;
                return x.label.compareToIgnoreCase(y.label);
            });
            ui.post(() -> {
                all.clear();
                all.addAll(items);
                empty.setText(items.isEmpty() ? "没有可投的应用（都需要桌面入口）" : "");
                empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                applyFilter(search.getText().toString());
                if (sel != null && !sel.isEmpty()) preloadIcon(sel);
            });
        });
    }

    /** 「com.xiaopeng.xxx.App」这种包名样式的 label：没有空格、只含字母数字下划线点、带点 */
    private static boolean looksLikePackage(String label) {
        if (label == null) return true;
        String s = label.trim();
        if (s.isEmpty() || !s.contains(".")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    private void applyFilter(String q) {
        String s = q == null ? "" : q.trim().toLowerCase(Locale.US);
        shown.clear();
        for (AppItem it : all) {
            if (s.isEmpty() || it.label.toLowerCase(Locale.US).contains(s)
                    || it.pkg.toLowerCase(Locale.US).contains(s)) {
                shown.add(it);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void preloadIcon(String pkg) {
        for (AppItem it : all) if (it.pkg.equals(pkg)) loadIcon(it);
    }

    /** 后台解码一个图标，回主线程刷新那一格 */
    private void loadIcon(final AppItem it) {
        if (it.icon != null || it.tried) return;
        Drawable cached = iconCache.get(it.pkg);
        if (cached != null) {
            it.icon = cached;
            return;
        }
        it.tried = true;
        loader.execute(() -> {
            final Drawable d;
            try {
                d = getPackageManager().getApplicationIcon(it.pkg);
            } catch (Throwable t) {
                return;
            }
            if (d == null) return;
            iconCache.put(it.pkg, d);
            ui.post(() -> {
                it.icon = d;
                adapter.notifyDataSetChanged();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
    }

    // ---------------------------------------------------------------- 适配器

    private final class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View reuse, ViewGroup parent) {
            Cell cell;
            if (reuse instanceof Cell) {
                cell = (Cell) reuse;
            } else {
                cell = new Cell();
            }

            AppItem it = shown.get(pos);
            cell.name.setText(it.label);
            cell.badge.setVisibility(running.contains(it.pkg) ? View.VISIBLE : View.INVISIBLE);
            if (it.icon != null) {
                cell.icon.setImageDrawable(it.icon);
            } else {
                cell.icon.setImageDrawable(null);
                loadIcon(it);   // 后台解码，回来自己 notify
            }
            return cell;
        }
    }

    /** 一格：图标 + 应用名 + 运行中角标（自带布局，直接当 GridView 的行视图复用） */
    private final class Cell extends LinearLayout {
        final ImageView icon;
        final TextView name;
        final TextView badge;

        Cell() {
            super(AppGridActivity.this);
            setOrientation(LinearLayout.VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);

            icon = new ImageView(AppGridActivity.this);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
            addView(icon);

            name = new TextView(AppGridActivity.this);
            name.setTextColor(0xFFDCE5EF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(3);
            addView(name, nlp);

            badge = new TextView(AppGridActivity.this);
            badge.setText("运行中");
            badge.setTextColor(0xFF0E1319);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(pill(0xFF5ED08A, 0));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(56), dp(20));
            blp.topMargin = dp(2);
            addView(badge, blp);
        }
    }
}
