package com.binbin.pibridge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.app.PendingIntent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 前台服务：小丘双服务守护——MCP:8181 + pi-web-ui:8182，10 秒看门狗自愈 */
public class BridgeService extends Service {
    public static BridgeService inst;
    private Mcp mcp;
    private Process puiProc;
    private final Handler h = new Handler();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private int puiFails = 0;
    private int mcpFails = 0;

    private static final String PUI_SH =
            "#!/system/bin/sh\n" +
            "export PI_WEB_PORT=8182\n" +
            "export HOME=/data/data/com.pihost/files/home\n" +
            "export PREFIX=/data/data/com.pihost/files/usr\n" +
            "export PATH=$PREFIX/bin:/system/bin\n" +
            "export LD_LIBRARY_PATH=$PREFIX/lib\n" +
            "export TMPDIR=$PREFIX/tmp\nexport PI_WEB_CWD=$HOME\ncd $HOME\n" +
            "exec node $HOME/.pi/agent/npm/node_modules/pi-web-ui/bin/pi-web-ui.mjs --no-browser >> $HOME/pui.log 2>&1\n";

    @Override public void onCreate() {
        inst = this;
        Tools.init(this);
        startMcp();
        startPui();
        // 悬浮球状态恢复（上次开过就自动出现）
        if (FloatBall.savedOn(this)) MAIN.post(() -> FloatBall.show(this));
        // 全局唤醒词：上次开着就自动恢复
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (Exception ignore) {}
            if ("true".equals(Tools.loadCfg().optString("wake_on", "false"))) WakeService.start(this);
        }, "wake-restore").start();
        startNotifyAnnouncer(); // 通知实时播报（cfg notify_announce 门控）

        // 唤醒命中动画广播（:kws 进程 → 主进程悬浮球特效）
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                FloatBall.pulse(c);
            }
        }, new android.content.IntentFilter("com.pihost.WAKE_ANIM"));
        // 环境引擎：首启自动装 pi 环境
        if (!EnvInstaller.isReady() && !EnvInstaller.isRunning()) {
            EnvInstaller.installAsync(new EnvInstaller.Cb() {
                public void onEvent(String line) { android.util.Log.i("PiBridge", "env: " + line); }
                public void onDone(boolean ok, String msg) {
                    android.util.Log.i("PiBridge", "env " + (ok ? "OK: " : "FAIL: ") + msg);
                }
            });
        }
        watchdog();
    }

    private void startMcp() {
        mcp = new Mcp();
        new Thread(mcp, "mcp-http").start();
    }

    private synchronized void startPui() {
        File bin = new File("/data/data/com.pihost/files/home/.pi/agent/npm/node_modules/pi-web-ui/bin/pi-web-ui.mjs");
        if (!bin.exists()) {
            if (EnvInstaller.isReady()) EnvInstaller.kickPuiInstall(); // 未装则自动补装
            return;
        }
        try {
            File f = new File(getFilesDir(), "pui-start.sh");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(PUI_SH.getBytes("UTF-8"));
            fo.close();
            f.setExecutable(true, true);
            ProcessBuilder pb = new ProcessBuilder("sh", f.getAbsolutePath());
            pb.redirectErrorStream(true);
            puiProc = pb.start();
            android.util.Log.i("PiBridge", "pi-web-ui 已拉起");
        } catch (Exception e) {
            android.util.Log.e("PiBridge", "pui start", e);
        }
    }

    private boolean ping(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200;
        } catch (Exception e) { return false; }
    }

    private void watchdog() {
        // 部分唤醒锁：锁屏/灭屏也保持 CPU 运行，对话不中断
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "xiaoqiu:service");
            wl.acquire();
        } catch (Exception ignore) {}
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // MCP：连续 60 秒失联才重建（避免误杀忙碌中的服务）
                    if (!ping("http://127.0.0.1:8181/ping")) {
                        mcpFails++;
                        if (mcpFails >= 6 && mcp != null) {
                            mcp.shutdown();
                            startMcp();
                            mcpFails = 0;
                            android.util.Log.i("PiBridge", "watchdog: MCP 已重建");
                        }
                    } else mcpFails = 0;
                    // pi-web-ui：只在【进程已死】时重拉；进程活着但忙（流式输出中）绝不杀
                    if (EnvInstaller.isReady()) {
                        boolean alive = puiProc != null && puiProc.isAlive();
                        if (!alive) {
                            File bin = new File("/data/data/com.pihost/files/home/.pi/agent/npm/node_modules/pi-web-ui/bin/pi-web-ui.mjs");
                            if (bin.exists()) {
                                startPui();
                                android.util.Log.i("PiBridge", "watchdog: pi-web-ui 进程已死，重拉");
                            }
                        }
                        // 进程活着就不干预（忙/慢都容忍）
                    }
                } catch (Exception ignore) {}
                h.postDelayed(this, 10000);
            }
        }, 10000);
    }
    private android.os.PowerManager.WakeLock wl;

    @Override public int onStartCommand(Intent i, int f, int id) {
        startForeground(1, notif());
        return START_STICKY;
    }

    private Notification notif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("bridge", "pi 桥", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "bridge")
                : new Notification.Builder(this);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        b.setContentTitle("小丘").setContentText("工作台运行中 · MCP:8181")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true);
        return Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
    }

    // ═══ 聚合播报状态：待播队列 / 每发送人语境记忆 / 到达时间戳 ═══
    private static final org.json.JSONArray pendMsgs = new org.json.JSONArray();
    private static final org.json.JSONObject senderCtx = new org.json.JSONObject();
    private static volatile long lastNewMs = 0, firstPendMs = 0;

    /** 通知实时聚合播报：等消息流平稳→按发送人合并→带语境拟人化→不打断上一条播报 */
    private void startNotifyAnnouncer() {
        new Thread(() -> {
            long[] lastSeen = {System.currentTimeMillis()};
            while (true) {
                try {
                    Thread.sleep(2000);
                    if (!"true".equals(Tools.loadCfg().optString("notify_announce", "false"))) continue;
                    if (Tools.micBusy) continue;
                    java.io.File f = new java.io.File(getFilesDir(), "notify-log.json");
                    if (!f.canRead()) continue;
                    JSONArray arr = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                    long newest = lastSeen[0];
                    for (int i = 0; i < arr.length(); i++) { // 正序收集全部新条目进队列
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        long tt = o.optLong("time");
                        if (tt <= lastSeen[0]) continue;
                        newest = Math.max(newest, tt);
                        if (getPackageName().equals(o.optString("pkg"))) continue; // 防反馈链
                        String allow = Tools.loadCfg().optString("notify_announce_pkgs", "com.tencent.mm");
                        if (!allow.contains(o.optString("pkg"))) continue;
                        String body = o.optString("title") + o.optString("text");
                        String[] excl = Tools.loadCfg().optString("notify_announce_exclude", "验证码,快递,取件").split(",");
                        boolean skip = false;
                        for (String k : excl) if (!k.trim().isEmpty() && body.contains(k.trim())) { skip = true; break; }
                        if (skip) continue; // 免打扰关键词
                        JSONObject m = new JSONObject();
                        m.put("pkg", o.optString("pkg")); m.put("title", o.optString("title"));
                        m.put("text", o.optString("text")); m.put("time", tt);
                        synchronized (pendMsgs) { pendMsgs.put(m); }
                        lastNewMs = System.currentTimeMillis();
                        if (firstPendMs == 0) firstPendMs = lastNewMs;
                    }
                    lastSeen[0] = newest;

                    int pn = pendMsgs.length();
                    if (pn == 0) { firstPendMs = 0; continue; }
                    long now = System.currentTimeMillis();
                    long waitMs = Tools.loadCfg().optLong("notify_announce_wait", 10000L);
                    boolean settled = (now - lastNewMs >= waitMs) || (now - firstPendMs >= 30000); // 平稳10s或总等待30s封顶
                    if (!settled) continue;

                    // 取最早发送人的一组
                    String key = null;
                    for (int i = 0; i < pendMsgs.length() && key == null; i++) {
                        JSONObject m = pendMsgs.optJSONObject(i);
                        if (m != null) key = m.optString("pkg") + "|" + m.optString("title");
                    }
                    if (key == null) continue;
                    org.json.JSONArray texts = new org.json.JSONArray();
                    String gPkg = null, gTitle = null;
                    synchronized (pendMsgs) {
                        for (int i = 0; i < pendMsgs.length(); ) {
                            JSONObject m = pendMsgs.optJSONObject(i);
                            if (m != null && key.equals(m.optString("pkg") + "|" + m.optString("title"))) {
                                gPkg = m.optString("pkg"); gTitle = m.optString("title");
                                texts.put(m.optString("text"));
                                pendMsgs.remove(i);
                            } else i++;
                        }
                    }
                    if (texts.length() == 0) { if (pendMsgs.length() == 0) firstPendMs = 0; continue; }

                    String prev = senderCtx.optString(key, "");
                    String say0;
                    if ("true".equals(Tools.loadCfg().optString("notify_announce_ai", "true"))) {
                        String h = Tools.aiHumanizeBurst(gPkg, gTitle, prev, texts);
                        if (h != null && !h.trim().isEmpty() && !h.startsWith("ERR:")) say0 = h.trim();
                        else say0 = gTitle + "连发" + texts.length() + "条消息，" + texts.optString(texts.length() - 1);
                    } else {
                        say0 = gTitle + "连发" + texts.length() + "条消息，" + texts.optString(texts.length() - 1);
                    }
                    final String say = say0;
                    // 语境记忆：每人保留最近4轮播报
                    org.json.JSONArray cl = senderCtx.optJSONArray(key);
                    if (cl == null) { cl = new org.json.JSONArray(); }
                    cl.put(say);
                    while (cl.length() > 4) cl.remove(0);
                    senderCtx.put(key, cl);

                    // 不打断：等上一条播报完（最多30s）
                    long deadline = System.currentTimeMillis() + 30000;
                    while (Tools.ttsSpeaking && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(500); } catch (Exception ignore) {}
                    }
                    if (pendMsgs.length() == 0) firstPendMs = 0;
                    final String fsay = say;
                    final String fkey = key;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        try {
                            Tools.call("tts_speak", new org.json.JSONObject()
                                    .put("text", say).put("engine",
                                            Tools.loadCfg().optString("notify_announce_engine", "xiaomi")));
                            android.util.Log.i("PiBridge", "聚合播报(" + fkey + "): " + fsay);
                        } catch (Exception ignore) {}
                    });
                } catch (Exception ignore) {}
            }
        }, "notify-announcer").start();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        inst = null;
        super.onDestroy();
    }
}
