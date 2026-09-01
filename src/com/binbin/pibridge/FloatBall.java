package com.binbin.pibridge;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** MT 式悬浮球：保活可见性 + 一键回小丘。可拖拽，单击打开主界面。 */
public class FloatBall {
    private static WindowManager wm;
    private static View ball;

    public static boolean isOn() { return ball != null; }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private static java.io.File prefFile(Context c) { return new File(c.getFilesDir(), "floatball"); }
    private static void save(Context c, boolean on) {
        try { java.io.FileOutputStream fo = new java.io.FileOutputStream(prefFile(c)); fo.write((on ? "1" : "0").getBytes()); fo.close(); } catch (Exception ignore) {}
    }
    public static boolean savedOn(Context c) {
        try { java.io.FileInputStream fi = new java.io.FileInputStream(prefFile(c)); byte[] b = new byte[1]; int n = fi.read(b); fi.close(); return n == 1 && b[0] == '1'; } catch (Exception e) { return false; }
    }

    public static boolean toggle(Context c) {
        if (isOn()) { runMain(c, "hide"); return false; }
        runMain(c, "show");
        return true;
    }

    private static void runMain(Context c, final String what) {
        MAIN.post(() -> { if (what.equals("show")) show(c); else hide(c); });
    }

    public static boolean show(Context c) {
        if (ball != null) return true;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return false;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        TextView v = new TextView(c);
        v.setText("丘");
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xE63E7C59);
        v.setBackground(g);
        int size = (int) (c.getResources().getDisplayMetrics().density * 52);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 24;
        p.y = 420;
        final WindowManager.LayoutParams fp = p;
        final float[] down = new float[2];
        final boolean[] moved = {false};
        v.setOnTouchListener((vw, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX() - fp.x;
                    down[1] = ev.getRawY() - fp.y;
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float nx = ev.getRawX() - down[0], ny = ev.getRawY() - down[1];
                    if (Math.abs(ev.getRawX() - (fp.x + size / 2f)) > 12
                            || Math.abs(ev.getRawY() - (fp.y + size / 2f)) > 12) moved[0] = true;
                    fp.x = (int) nx; fp.y = (int) ny;
                    try { wm.updateViewLayout(v, fp); } catch (Exception ignore) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) {
                        Intent i = new Intent(c, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(i);
                    }
                    return true;
            }
            return false;
        });
        wm.addView(v, p);
        ball = v;
        save(c, true);
        return true;
    }

    public static void hide(Context c) {
        save(c, false);
        try { if (ball != null && wm != null) wm.removeView(ball); } catch (Exception ignore) {}
        ball = null;
    }
}
