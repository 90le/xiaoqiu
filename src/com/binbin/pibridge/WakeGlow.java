package com.binbin.pibridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 唤醒全屏边缘流光特效：四边"水波纹荧光跑马灯"。
 * 悬浮窗实现（不抢焦点、不挡触摸），主进程持有；:kws 进程经广播 WAKE_GLOW_ON/OFF 控制。
 */
public class WakeGlow {
    private static WindowManager wm;
    private static GlowView view;
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void show(final Context c) {
        main.post(new Runnable() { public void run() {
            try {
                if (view != null) return;
                wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                view = new GlowView(c);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.FILL;
                wm.addView(view, lp);
            } catch (Exception e) { android.util.Log.w("PiBridge", "glow show: " + e); }
        }});
    }

    public static void hide() {
        main.post(new Runnable() { public void run() {
            try {
                if (view != null && wm != null) { wm.removeView(view); }
            } catch (Exception ignore) {}
            view = null; wm = null;
        }});
    }

    /** 边缘流光视图：3 束光点沿四边跑 + 底色微光 */
    static class GlowView extends View {
        private float phase = 0f; // 0..1 跑马灯相位
        private final Choreographer choreo = Choreographer.getInstance();
        private final long t0 = android.os.SystemClock.elapsedRealtime();
        private final Runnable frame = new Runnable() { public void run() {
            phase = (phase + 0.004f) % 1f;
            invalidate();
            if (getVisibility() == VISIBLE) postFrame();
        }};
        private void postFrame() { choreo.postFrameCallback(new Choreographer.FrameCallback() {
            public void doFrame(long time) { frame.run(); }
        }); }

        GlowView(Context c) { super(c); postFrame(); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float inset = 4f, r = 26f; // 光带距边 + 圆角
            float peri = 2 * (w + h) - 8 * r; // 圆角矩形周长（近似展开）
            long el = android.os.SystemClock.elapsedRealtime() - t0;
            float in = Math.min(1f, el / 450f); // 淡入

            // 底层：整圈微光描边
            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(6f);
            rim.setColor(Color.argb((int)(90 * in), 64, 207, 114));
            android.graphics.RectF rr = new android.graphics.RectF(inset, inset, w - inset, h - inset);
            cv.drawRoundRect(rr, r, r, rim);

            // 3 束跑马灯光点（等距相位）+ 尾迹
            for (int k = 0; k < 3; k++) {
                float head = (phase + k / 3f) % 1f;
                for (int tail = 0; tail < 9; tail++) { // 尾迹 9 点渐隐
                    float p = ((head - tail * 0.011f) % 1f + 1f) % 1f;
                    float[] xy = periPoint(p, peri, w, h, inset, r);
                    float alpha = (1f - tail / 9f) * 235 * in;
                    int cr = tail < 3 ? 0x3e : 0x8b; // 头绿尾紫
                    int cg = tail < 3 ? 0xcf : 0x5c;
                    int cb = tail < 3 ? 0x72 : 0xf6;
                    Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
                    pt.setColor(Color.argb((int) alpha, cr, cg, cb));
                    float radius = 20f - tail * 1.5f;
                    cv.drawCircle(xy[0], xy[1], radius, pt);
                    // 中心亮核
                    if (tail == 0) {
                        Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
                        core.setColor(Color.argb((int)(255 * in), 220, 255, 230));
                        cv.drawCircle(xy[0], xy[1], 6f, core);
                    }
                }
            }
        }

        /** 周长参数 0..1 → 边框坐标（顺时针：左上角右侧起点→顶边→右→底→左） */
        private float[] periPoint(float p, float peri, float w, float h, float inset, float r) {
            float segX = w - 2 * inset - 2 * r; // 横边长
            float segY = h - 2 * inset - 2 * r; // 竖边长
            float corner = (float) (Math.PI * r / 2);
            float d = p * peri;
            float L = inset + r, Rt = w - inset - r, T = inset + r, B = h - inset - r;
            double a;
            if (d < segX) return new float[]{L + d, inset};                    // 顶边 →
            if ((d -= segX) < corner) { a = d / corner * Math.PI / 2;          // 右上角
                return new float[]{Rt + (float) Math.sin(a) * r, T - (float) Math.cos(a) * r}; }
            if ((d -= corner) < segY) return new float[]{w - inset, T + d};    // 右边 ↓
            if ((d -= segY) < corner) { a = d / corner * Math.PI / 2;          // 右下角
                return new float[]{Rt + (float) Math.cos(a) * r, B + (float) Math.sin(a) * r}; }
            if ((d -= corner) < segX) return new float[]{Rt - d, h - inset};   // 底边 ←
            if ((d -= segX) < corner) { a = d / corner * Math.PI / 2;          // 左下角
                return new float[]{L - (1 - (float) Math.cos(a)) * r, B - (1 - (float) Math.sin(a)) * r}; }
            if ((d -= corner) < segY) return new float[]{inset, B - d};        // 左边 ↑
            if ((d -= segY) < corner) { a = d / corner * Math.PI / 2;          // 左上角
                return new float[]{L - (1 - (float) Math.sin(a)) * r, T - (1 - (float) Math.cos(a)) * r}; }
            d -= corner; return new float[]{L + d, inset};                     // 顶边收尾
        }
    }
}
