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

    /** 通知实时语音播报（"听"的实时化）：cfg notify_announce=true 生效；notify_announce_pkgs 白名单 */
    private void startNotifyAnnouncer() {
        new Thread(() -> {
            long[] lastSeen = {System.currentTimeMillis()};
            while (true) {
                try {
                    Thread.sleep(5000);
                    if (!"true".equals(Tools.loadCfg().optString("notify_announce", "false"))) continue;
                    if (Tools.micBusy) continue;
                    java.io.File f = new java.io.File(getFilesDir(), "notify-log.json");
                    if (!f.canRead()) continue;
                    JSONArray arr = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                    long newest = lastSeen[0];
                    String pending = null;
                    for (int i = arr.length() - 1; i >= 0; i--) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        long t = o.optLong("time");
                        if (t <= lastSeen[0]) break;
                        newest = Math.max(newest, t);
                        if (getPackageName().equals(o.optString("pkg"))) continue; // 不播报自己的通知（防反馈链）
                        String allow = Tools.loadCfg().optString("notify_announce_pkgs", "com.tencent.mm");
                        if (!allow.contains(o.optString("pkg"))) continue;
                        String body = o.optString("title") + o.optString("text");
                        String[] excl = Tools.loadCfg().optString("notify_announce_exclude", "验证码,快递,取件").split(",");
                        boolean skip = false;
                        for (String k : excl) if (!k.trim().isEmpty() && body.contains(k.trim())) { skip = true; break; }
                        if (skip) continue; // 免打扰关键词命中
                        if (pending == null) pending = "新消息：" + o.optString("title") + "，" + o.optString("text");
                    }
                    lastSeen[0] = newest;
                    if (pending != null) {
                        final String say = pending;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            try {
                                Tools.call("tts_speak", new org.json.JSONObject()
                                        .put("text", say).put("engine",
                                                Tools.loadCfg().optString("notify_announce_engine", "xiaomi")));
                                android.util.Log.i("PiBridge", "播报通知: " + say);
                            } catch (Exception ignore) {}
                        });
                    }
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
