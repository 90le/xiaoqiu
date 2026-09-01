package com.binbin.pibridge;

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
        h.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // MCP 自愈：连续 3 次失联 → 重建服务线程（Mcp 内部永生循环兜底）
                    if (!ping("http://127.0.0.1:8181/ping")) {
                        mcpFails++;
                        if (mcpFails >= 3 && mcp != null) {
                            mcp.shutdown();
                            startMcp();
                            mcpFails = 0;
                            android.util.Log.i("PiBridge", "watchdog: MCP 已重建");
                        }
                    } else mcpFails = 0;
                    // pi-web-ui 自愈：环境就绪后连续 2 次失联 → 重拉
                    if (EnvInstaller.isReady() && !ping("http://127.0.0.1:8182/")) {
                        puiFails++;
                        if (puiFails >= 2) {
                            if (puiProc != null) puiProc.destroy();
                            startPui();
                            puiFails = 0;
                            android.util.Log.i("PiBridge", "watchdog: pi-web-ui 已重拉");
                        }
                    } else puiFails = 0;
                } catch (Exception ignore) {}
                h.postDelayed(this, 10000);
            }
        }, 10000);
    }

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

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        inst = null;
        super.onDestroy();
    }
}
