package com.binbin.pibridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.app.PendingIntent;

/** 前台服务：保活 + 承载内嵌 MCP/HTTP 服务器（127.0.0.1:8099） */
public class BridgeService extends Service {
    public static BridgeService inst;
    private Mcp mcp;

    @Override public void onCreate() {
        inst = this;
        Tools.init(this);
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        startForeground(1, notif());
        if (mcp == null) {
            mcp = new Mcp();
            new Thread(mcp, "mcp-http").start();
        }
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
        return b.setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("pi 桥运行中")
                .setContentText("MCP 127.0.0.1:8099 · Web UI 8787")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        if (mcp != null) mcp.stop();
        inst = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
