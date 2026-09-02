package com.binbin.pibridge;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/** 辅助服务：全局键 + 手势注入 + 截图 + 读屏 + 文本直设。开启方式（adb）：settings put secure enabled_accessibility_services */
public class AdbService extends AccessibilityService {
    public static AdbService inst;

    public static final int GLOBAL_BACK = GLOBAL_ACTION_BACK;
    public static final int GLOBAL_HOME = GLOBAL_ACTION_HOME;
    public static final int GLOBAL_RECENTS = GLOBAL_ACTION_RECENTS;

    @Override protected void onServiceConnected() {
        inst = this;
        try { // 副屏读树 + 全量节点（含"不重要"视图）
            AccessibilityServiceInfo info = getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        } catch (Exception ignore) {}
    }
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

    // ========== 截图（API 30+，无障碍通道，无需投影授权） ==========

    public interface ShotCb { void onShot(boolean ok, String msg); }

    public static void screenshot(final ShotCb cb) {
        if (inst == null) { cb.onShot(false, "辅助服务未开启"); return; }
        if (Build.VERSION.SDK_INT < 30) { cb.onShot(false, "需要 Android 11+"); return; }
        inst.takeScreenshot(Display.DEFAULT_DISPLAY, Runnable::run,
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult r) {
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                            Bitmap soft = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                            r.getHardwareBuffer().close();
                            if (soft == null) { cb.onShot(false, "位图转换失败"); return; }
                            File dir = new File(Environment_getExternal(), "pibridge/shots");
                            if (!dir.isDirectory()) dir.mkdirs();
                            File f = new File(dir, "shot_" + System.currentTimeMillis() + ".png");
                            FileOutputStream fo = new FileOutputStream(f);
                            soft.compress(Bitmap.CompressFormat.PNG, 100, fo);
                            fo.close();
                            cb.onShot(true, f.getAbsolutePath() + " " + f.length() + "B");
                        } catch (Exception e) {
                            cb.onShot(false, e.toString());
                        }
                    }
                    @Override public void onFailure(int code) { cb.onShot(false, "截图失败 code=" + code); }
                });
    }

    private static File Environment_getExternal() {
        return android.os.Environment.getExternalStorageDirectory();
    }

    // ========== 读屏（控件树结构化输出，AI 直接理解界面） ==========

    public static String diag = ""; // 副屏读树诊断
    public static JSONArray readTree() { return readTree(0); }

    /** 读结构树；displayId>0 时读指定副屏（隐形虚拟屏同样可读，API33+） */
    public static JSONArray readTree(int displayId) {
        seq = 0;
        if (inst == null) return null;
        JSONArray out = new JSONArray();
        try {
            if (displayId > 0 && Build.VERSION.SDK_INT >= 33) {
                diag = "";
                // 反射调用 API33+ getWindowsOnAllDisplays()（返回 SparseArray<displayId, 窗口列表>）
                Object arr = null;
                try {
                    arr = AccessibilityService.class.getMethod("getWindowsOnAllDisplays").invoke(inst);
                } catch (Throwable e) { diag = "反射异常: " + e; }
                java.util.List<AccessibilityWindowInfo> wins = null;
                if (arr instanceof android.util.SparseArray) {
                    Object l = ((android.util.SparseArray<?>) arr).get(displayId);
                    if (l instanceof java.util.List) {
                        wins = (java.util.List<AccessibilityWindowInfo>) l;
                    } else diag = "该屏无窗口表";
                }
                if (wins != null) {
                    diag += " 窗口数:" + wins.size();
                    // 副屏窗口就绪有延迟：重试等待（最多4次×900ms）
                    for (int tryN = 0; tryN < 4 && out.length() == 0; tryN++) {
                        if (tryN > 0) { try { Thread.sleep(900); } catch (Exception ignore) {} }
                        for (AccessibilityWindowInfo w : wins) {
                            AccessibilityNodeInfo r = null;
                            try { r = w.getRoot(); } catch (Throwable e) { diag += " root异常:" + e; }
                            if (r != null) walk(r, out, 0);
                        }
                    }
                    diag += " 节点数:" + out.length();
                    return out.length() > 0 ? out : null;
                }
                return null;
            }
            AccessibilityNodeInfo root = inst.getRootInActiveWindow();
            if (root == null) return null;
            walk(root, out, 0);
            return out;
        } catch (Exception e) { return null; }
    }

    private static int seq = 0; // 全局节点编号（截图/点击引用的稳定锚）

    private static void walk(AccessibilityNodeInfo n, JSONArray out, int depth) {
        if (out.length() >= 250 || depth > 15 || n == null) return;
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            JSONObject o = new JSONObject();
            o.put("i", seq++);
            CharSequence t = n.getText();
            if (t != null && t.length() > 0) o.put("text", t.length() > 80 ? t.toString().substring(0, 80) + "…" : t.toString());
            if (n.isClickable() && (t == null || t.length() == 0) && n.getContentDescription() == null) {
                String ht = harvest(n, 0); // 行容器：从子节点借文本
                if (ht != null) o.put("text", ht.length() > 80 ? ht.substring(0, 80) + "…" : ht);
            }
            CharSequence d = n.getContentDescription();
            if (d != null && d.length() > 0) o.put("desc", d.length() > 60 ? d.toString().substring(0, 60) + "…" : d.toString());
            String id = n.getViewIdResourceName();
            if (id != null) { int i = id.indexOf('/'); if (i >= 0) id = id.substring(i + 1); o.put("id", id); }
            o.put("xy", r.left + "," + r.top + " " + r.width() + "x" + r.height());
            CharSequence cls = n.getClassName();
            if (cls != null) {
                String c = cls.toString();
                o.put("cls", c.substring(c.lastIndexOf('.') + 1));
            }
            if (n.isClickable()) o.put("click", true);
            if (n.isEditable()) o.put("edit", true);
            if (n.isPassword()) o.put("pwd", true);
            if (n.isSelected()) o.put("sel", true);
            if (n.isScrollable()) o.put("scroll", true);
            if (o.length() > 1) out.put(o);
            for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1);
        } catch (Exception ignore) {}
    }

    /** 深度收割后代的首个有效文本/描述（给可点击行容器用） */
    private static String harvest(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 4) return null;
        try {
            CharSequence t = n.getText();
            if (t != null && t.toString().trim().length() > 0) return t.toString().trim();
            CharSequence ds = n.getContentDescription();
            if (ds != null && ds.toString().trim().length() > 0) return ds.toString().trim();
            for (int i = 0; i < n.getChildCount(); i++) {
                String r = harvest(n.getChild(i), d + 1);
                if (r != null) return r;
            }
        } catch (Exception ignore) {}
        return null;
    }

    // ========== 文本直设（对可编辑节点 ACTION_SET_TEXT，绕过粘贴菜单） ==========

    public static String setText(String text) {
        if (inst == null) return "辅助服务未开启";
        AccessibilityNodeInfo root = inst.getRootInActiveWindow();
        if (root == null) return "无活动窗口";
        AccessibilityNodeInfo node = inst.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null || !node.isEditable()) node = findEditable(root, 0);
        if (node == null) return "未找到可编辑输入框";
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "已设置" : "设置被拒绝";
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 15) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }
}
