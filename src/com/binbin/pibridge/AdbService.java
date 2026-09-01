package com.binbin.pibridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.graphics.Path;

/** 辅助服务：全局键 + 手势注入。开启方式（adb）：settings put secure enabled_accessibility_services */
public class AdbService extends AccessibilityService {
    public static AdbService inst;

    public static final int GLOBAL_BACK = GLOBAL_ACTION_BACK;
    public static final int GLOBAL_HOME = GLOBAL_ACTION_HOME;
    public static final int GLOBAL_RECENTS = GLOBAL_ACTION_RECENTS;

    @Override protected void onServiceConnected() { inst = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
    @Override public boolean onUnbind(Intent i) { inst = null; return false; }

    public static boolean global(int action) {
        return inst != null && inst.performGlobalAction(action);
    }

    public static boolean tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(p, 40);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(p, Math.max(50, ms));
    }

    private static boolean gesture(Path p, long ms) {
        if (inst == null || Build.VERSION.SDK_INT < 24) return false;
        GestureBuilder gb = new GestureBuilder();
        gb.addStroke(p, ms);
        return inst.dispatchGesture(gb.build(), null, null);
    }

    /** 隔离 API 24 的 GestureDescription，避免类加载问题 */
    private static class GestureBuilder {
        android.accessibilityservice.GestureDescription.Builder b = new android.accessibilityservice.GestureDescription.Builder();
        void addStroke(Path p, long ms) {
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, ms));
        }
        android.accessibilityservice.GestureDescription build() { return b.build(); }
    }
}
