package com.probe.adbprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 在截图上拖出一个裁切框。
 *
 * 坐标一律用「图片坐标」（车机屏幕的绝对像素，2400x1200），由 getCrop() 返回，
 * 上层直接写进 vdcrop.txt，不需要再做换算 —— VdSend 的裁切就是按这个坐标系来的。
 *
 * 手势：空白处拖 = 新建；框内拖 = 整体移动；四角拖 = 缩放。
 * 锁定比例时按 OUT_W/OUT_H 约束，因为发送端输出是恒定 1920x568，不锁就会拉伸变形。
 */
public class CropView extends View {

    public interface OnChangeListener { void onCropChanged(Rect r); }

    private Bitmap bmp;
    private final Rect crop = new Rect();
    private final RectF dst = new RectF();
    private float scale = 1f;

    /** 锁定宽高比（宽/高），<=0 表示自由 */
    private float ratio = 0f;
    private OnChangeListener listener;

    /** 底部让位：底栏盖住的像素高度，图片只在上方区域里居中，不钻到按钮底下 */
    private int insetBottom = 0;

    private static final int MODE_NONE = 0;
    private static final int MODE_NEW = 1;
    private static final int MODE_MOVE = 2;
    private static final int MODE_RESIZE = 3;

    private int mode = MODE_NONE;
    private float downX, downY;
    private final Rect downCrop = new Rect();
    private int corner = 0;   // 1=TL 2=TR 3=BR 4=BL

    private final Paint pFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMask = new Paint();
    private final Paint pHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCross = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBand = new Paint();

    /** 上下「禁投区」高度（图片坐标）：仪表常驻状态栏，盖住会挡挡位/温度/时间 */
    private int bandTop = 0;
    private int bandBot = 0;
    private int frameColor = 0xFF00E676;

    public CropView(Context c) {
        super(c);
        pFrame.setStyle(Paint.Style.STROKE);
        pFrame.setStrokeWidth(dp(2));
        pFrame.setColor(0xFF00E676);

        pMask.setColor(0xAA000000);

        pHandle.setStyle(Paint.Style.FILL);
        pHandle.setColor(0xFF00E676);

        pText.setColor(Color.WHITE);
        pText.setTextSize(dp(13));
        pText.setShadowLayer(3, 0, 1, Color.BLACK);

        pCross.setStyle(Paint.Style.STROKE);
        pCross.setStrokeWidth(dp(1));
        pCross.setColor(0x66FFFFFF);

        pBand.setColor(0x33FF3B30);
    }

    /** 框线/把手颜色（仪表区域用红色，车机裁切保持绿色） */
    public void setFrameColor(int c) {
        frameColor = c;
        pFrame.setColor(c);
        pHandle.setColor(c);
        invalidate();
    }

    /** 标记上下状态栏为禁投区（图片坐标），只做提示，不阻止选择 */
    public void setBands(int top, int bot) {
        bandTop = Math.max(0, top);
        bandBot = Math.max(0, bot);
        invalidate();
    }

    /** 当前框是否压到了上下状态栏 */
    public boolean crossesBands() {
        if (bmp == null) return false;
        if (bandTop > 0 && crop.top < bandTop) return true;
        return bandBot > 0 && crop.bottom > bmp.getHeight() - bandBot;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density * 2.4f; }

    public void setBitmap(Bitmap b) {
        this.bmp = b;
        if (b != null && crop.isEmpty()) {
            crop.set(0, 0, b.getWidth(), b.getHeight());
        }
        requestLayout();
        invalidate();
    }

    /** 初始框（图片坐标），超出范围会被裁掉 */
    public void setCrop(Rect r) {
        if (bmp == null) { crop.set(r); return; }
        crop.set(r);
        crop.intersect(0, 0, bmp.getWidth(), bmp.getHeight());
        if (crop.isEmpty()) crop.set(0, 0, bmp.getWidth(), bmp.getHeight());
        invalidate();
    }

    public void setRatio(float r) {
        this.ratio = r;
        if (r > 0 && bmp != null) {
            // 立刻把现有框修正到该比例，避免用户看到「已锁定但框是错的」
            int w = crop.width();
            int h = Math.round(w / r);
            if (h > bmp.getHeight()) {
                h = bmp.getHeight();
                w = Math.round(h * r);
            }
            crop.set(crop.left, crop.top, crop.left + w, crop.top + h);
            clamp(crop);
            notifyChanged();
        }
    }

    public Rect getCrop() { return new Rect(crop); }

    public void setOnChangeListener(OnChangeListener l) { this.listener = l; }

    private void notifyChanged() {
        invalidate();
        if (listener != null) listener.onCropChanged(new Rect(crop));
    }

    // ------------------------------------------------------------ 布局 / 绘制

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeDst(w, h);
    }

    /** 底栏高度，避免图片/框被底栏压住 */
    public void setInsetBottom(int px) {
        this.insetBottom = px;
        computeDst(getWidth(), getHeight());
        invalidate();
    }

    private void computeDst(int vw, int vh) {
        if (bmp == null || vw == 0 || vh == 0) return;
        int usableH = Math.max(1, vh - insetBottom);
        float s = Math.min(vw / (float) bmp.getWidth(), usableH / (float) bmp.getHeight());
        scale = s;
        float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
        dst.set((vw - dw) / 2f, (usableH - dh) / 2f, (vw + dw) / 2f, (usableH + dh) / 2f);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null) return;
        if (dst.isEmpty()) computeDst(getWidth(), getHeight());

        c.drawBitmap(bmp, null, dst, null);

        // 上下状态栏提示带（只提示，不阻止选择）
        if (bandTop > 0) {
            c.drawRect(toView(new Rect(0, 0, bmp.getWidth(), bandTop)), pBand);
        }
        if (bandBot > 0) {
            c.drawRect(toView(new Rect(0, bmp.getHeight() - bandBot,
                    bmp.getWidth(), bmp.getHeight())), pBand);
        }

        if (crop.isEmpty()) return;

        RectF f = toView(crop);
        // 框外压暗（四块），比逐个像素画遮罩便宜得多
        c.drawRect(0, 0, getWidth(), f.top, pMask);
        c.drawRect(0, f.bottom, getWidth(), getHeight(), pMask);
        c.drawRect(0, f.top, f.left, f.bottom, pMask);
        c.drawRect(f.right, f.top, getWidth(), f.bottom, pMask);

        c.drawRect(f, pFrame);

        // 三分线，方便对齐
        for (int i = 1; i <= 2; i++) {
            float x = f.left + f.width() * i / 3f;
            float y = f.top + f.height() * i / 3f;
            c.drawLine(x, f.top, x, f.bottom, pCross);
            c.drawLine(f.left, y, f.right, y, pCross);
        }

        float hs = dp(8);
        float[][] cs = {
                {f.left, f.top}, {f.right, f.top}, {f.right, f.bottom}, {f.left, f.bottom}
        };
        for (float[] pt : cs) c.drawCircle(pt[0], pt[1], hs, pHandle);

        String label = crop.width() + " x " + crop.height() + "  @" + crop.left + "," + crop.top;
        float tw = pText.measureText(label);
        float tx = Math.max(dp(4), Math.min(f.left, getWidth() - tw - dp(4)));
        float ty = f.top - dp(8);
        if (ty < dp(16)) ty = f.top + dp(20);
        c.drawText(label, tx, ty, pText);
    }

    private RectF toView(Rect r) {
        return new RectF(
                dst.left + r.left * scale,
                dst.top + r.top * scale,
                dst.left + r.right * scale,
                dst.top + r.bottom * scale);
    }

    // ------------------------------------------------------------ 手势

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                downCrop.set(crop);
                corner = hitCorner(x, y);
                if (corner > 0) {
                    mode = MODE_RESIZE;
                } else if (toView(crop).contains(x, y)) {
                    mode = MODE_MOVE;
                } else {
                    mode = MODE_NEW;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int bx = toBitmapX(x), by = toBitmapY(y);
                int dx0 = toBitmapX(downX), dy0 = toBitmapY(downY);
                if (mode == MODE_NEW) {
                    buildNew(dx0, dy0, bx, by);
                } else if (mode == MODE_MOVE) {
                    int dx = bx - dx0, dy = by - dy0;
                    crop.set(downCrop);
                    crop.offset(dx, dy);
                    clamp(crop);
                } else if (mode == MODE_RESIZE) {
                    resize(corner, bx, by);
                }
                notifyChanged();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode == MODE_NEW && (crop.width() < 8 || crop.height() < 8)) {
                    crop.set(downCrop);   // 误触，恢复原框
                    notifyChanged();
                }
                mode = MODE_NONE;
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private int toBitmapX(float vx) {
        return Math.round((vx - dst.left) / scale);
    }

    private int toBitmapY(float vy) {
        return Math.round((vy - dst.top) / scale);
    }

    private int hitCorner(float vx, float vy) {
        RectF f = toView(crop);
        float r = dp(28);
        if (near(vx, vy, f.left, f.top, r)) return 1;
        if (near(vx, vy, f.right, f.top, r)) return 2;
        if (near(vx, vy, f.right, f.bottom, r)) return 3;
        if (near(vx, vy, f.left, f.bottom, r)) return 4;
        return 0;
    }

    private static boolean near(float x, float y, float cx, float cy, float r) {
        return Math.abs(x - cx) <= r && Math.abs(y - cy) <= r;
    }

    private void buildNew(int ax, int ay, int bx, int by) {
        int w = Math.abs(bx - ax);
        int h = Math.abs(by - ay);
        if (ratio > 0) {
            h = Math.max(1, Math.round(w / ratio));
            // 高度优先时反推宽度，避免用户竖着拖出个 1px 高的框
            if (h > bmp.getHeight()) {
                h = bmp.getHeight();
                w = Math.round(h * ratio);
            }
        }
        int left = bx >= ax ? ax : ax - w;
        int top = by >= ay ? ay : ay - h;
        crop.set(left, top, left + Math.max(1, w), top + Math.max(1, h));
        clamp(crop);
    }

    private void resize(int which, int bx, int by) {
        Rect r = new Rect(downCrop);
        switch (which) {
            case 1: r.left = bx; r.top = by; break;
            case 2: r.right = bx; r.top = by; break;
            case 3: r.right = bx; r.bottom = by; break;
            case 4: r.left = bx; r.bottom = by; break;
        }
        r.sort();
        if (ratio > 0) {
            // 以对角为锚点，按宽度重算高度
            int w = Math.max(2, r.width());
            int h = Math.max(2, Math.round(w / ratio));
            boolean topAnchor = (which == 1 || which == 2);
            if (topAnchor) r.top = r.bottom - h; else r.bottom = r.top + h;
        }
        if (r.width() < 8 || r.height() < 8) return;
        crop.set(r);
        clamp(crop);
    }

    private void clamp(Rect r) {
        int W = bmp.getWidth(), H = bmp.getHeight();
        if (r.left < 0) r.offset(-r.left, 0);
        if (r.top < 0) r.offset(0, -r.top);
        if (r.right > W) r.offset(W - r.right, 0);
        if (r.bottom > H) r.offset(0, H - r.bottom);
        if (r.left < 0) r.left = 0;
        if (r.top < 0) r.top = 0;
        if (r.right > W) r.right = W;
        if (r.bottom > H) r.bottom = H;
    }
}
