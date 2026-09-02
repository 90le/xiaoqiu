package com.binbin.pibridge;

import android.util.Log;
import android.app.Notification;
import android.util.Log;
import android.app.NotificationChannel;
import android.util.Log;
import android.app.NotificationManager;
import android.util.Log;
import android.app.PendingIntent;
import android.util.Log;
import android.content.ClipData;
import android.util.Log;
import android.content.ClipboardManager;
import android.util.Log;
import android.content.Context;
import android.util.Log;
import android.content.Intent;
import android.util.Log;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.content.pm.PackageManager;
import android.util.Log;
import android.database.Cursor;
import android.util.Log;
import android.hardware.camera2.CameraAccessException;
import android.util.Log;
import android.hardware.camera2.CameraCharacteristics;
import android.util.Log;
import android.hardware.camera2.CameraManager;
import android.util.Log;
import android.media.AudioManager;
import android.util.Log;
import android.media.MediaScannerConnection;
import android.util.Log;
import android.net.ConnectivityManager;
import android.util.Log;
import android.net.NetworkCapabilities;
import android.util.Log;
import android.net.Uri;
import android.util.Log;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.os.BatteryManager;
import android.util.Log;
import android.os.Build;
import android.util.Log;
import android.os.Bundle;
import android.util.Log;
import android.os.Environment;
import android.util.Log;
import android.os.Handler;
import android.util.Log;
import android.os.Looper;
import android.util.Log;
import android.os.PowerManager;
import android.util.Log;
import android.os.StatFs;
import android.util.Log;
import android.os.VibrationEffect;
import android.util.Log;
import android.os.Vibrator;
import android.util.Log;
import android.provider.CallLog;
import android.util.Log;
import android.provider.ContactsContract;
import android.util.Log;
import android.provider.Settings;
import android.util.Log;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 工具注册表：每个工具 = 名称 + 描述 + schema + 处理器。结果信封照 MT 管理器风格 {ok,data,error} */
public class Tools {
    public interface H { JSONObject run(JSONObject a) throws Exception; }

    public static class Tool {
        public String desc; public JSONObject schema; public H h;
        Tool(String d, JSONObject s, H hh) { desc = d; schema = s; h = hh; }
    }

    public static final Map<String, Tool> REG = new LinkedHashMap<>();
    public static Context ctx;
    private static TextToSpeech tts;
    private static volatile boolean ttsReady = false;
    private static int notifId = 100;

    static JSONObject ok(Object data) {
        try { return new JSONObject().put("ok", true).put("data", data == null ? JSONObject.NULL : data).put("error", JSONObject.NULL); }
        catch (Exception e) { return err("INTERNAL", e.toString()); }
    }
    static JSONObject err(String code, String msg) {
        try { return new JSONObject().put("ok", false).put("data", JSONObject.NULL)
                .put("error", new JSONObject().put("code", code).put("message", msg)); }
        catch (Exception e) { return new JSONObject(); }
    }
    static JSONObject props(Object... kv) throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }
    static JSONObject prop(String type, String desc) throws Exception {
        return new JSONObject().put("type", type).put("description", desc);
    }
    static JSONObject schema(JSONObject props, String... req) throws Exception {
        JSONArray r = new JSONArray();
        for (String s : req) r.put(s);
        return new JSONObject().put("type", "object").put("properties", props).put("required", r);
    }
    static void def(String name, String desc, JSONObject schema, H h) { REG.put(name, new Tool(desc, schema, h)); }

    public static void init(Context c) {
        // TTS 预热：后台线程初始化（ttsInit 的 await 绝不能发生在主线程，否则 onInit 回调死锁）
        new Thread(() -> { boolean r = ttsInit(); Log.i("PiBridge", "TTS 预热: " + (r ? "就绪" : "失败")); }, "tts-prewarm").start();
        ctx = c;
        if (REG.isEmpty()) reg();
    }

    public static JSONObject call(String name, JSONObject args) {
        Tool t = REG.get(name);
        if (t == null) return err("NOT_FOUND", "未知工具: " + name + "（/api/tools_list 可查全部）");
        try {
            return t.h.run(args == null ? new JSONObject() : args);
        } catch (Throwable e) {
            return err("EXCEPTION", e.toString());
        }
    }

    private static void reg() {
        try { reg0(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void reg0() throws Exception {
        // ═══ 设备信息 ═══
        def("battery_status", "查电池：电量、充电状态、温度", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            Intent i = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            JSONObject o = new JSONObject();
            o.put("percent", level < 0 ? -1 : level * 100 / scale);
            o.put("charging", i.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING);
            o.put("plugged", i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
            o.put("tempC", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0);
            return ok(o);
        }});

        def("device_info", "查设备：型号、系统、分辨率、存储", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            JSONObject o = new JSONObject();
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            File f = Environment.getExternalStorageDirectory();
            StatFs sf = new StatFs(f.getAbsolutePath());
            o.put("storageFreeGB", Math.round(sf.getAvailableBytes() / 1073741824.0 * 10) / 10.0);
            o.put("storageTotalGB", Math.round(sf.getTotalBytes() / 1073741824.0 * 10) / 10.0);
            return ok(o);
        }});

        def("screen_state", "查屏幕：亮灭状态", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return ok(new JSONObject().put("screenOn", pm.isInteractive()));
        }});

        // ═══ 通知（可点击！这是 adb 通知做不到的）═══
        def("notify_post", "发可点击通知：点了会打开 url（Web UI 或任意链接）",
            schema(props("title", prop("string", "标题"), "text", prop("string", "正文"),
                         "url", prop("string", "点击后打开的链接，可选")), "title", "text"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("pi", "pi 指令", NotificationManager.IMPORTANCE_DEFAULT));
                Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, "pi") : new Notification.Builder(ctx);
                b.setSmallIcon(android.R.drawable.ic_dialog_info)
                 .setContentTitle(a.optString("title")).setContentText(a.optString("text"))
                 .setStyle(new Notification.BigTextStyle().bigText(a.optString("text")))
                 .setAutoCancel(true);
                String url = a.optString("url", "");
                if (!url.isEmpty()) {
                    b.setContentIntent(PendingIntent.getActivity(ctx, notifId,
                            new Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE));
                }
                int id = notifId++;
                nm.notify(id, b.build());
                return ok(new JSONObject().put("id", id));
            }});

        def("notify_cancel", "撤销通知", schema(props("id", prop("number", "notify_post 返回的 id")), "id"),
            new H() { public JSONObject run(JSONObject a) {
                ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(a.optInt("id"));
                return ok("已撤销");
            }});

        // ═══ 剪贴板 ═══
        def("clipboard_write", "写剪贴板", schema(props("text", prop("string", "内容")), "text"),
            new H() { public JSONObject run(JSONObject a) {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("pi", a.optString("text")));
                return ok("已写入剪贴板");
            }});

        def("clipboard_read", "读剪贴板（Android10+ 后台读取可能被系统拒绝）", schema(props()), new H() { public JSONObject run(JSONObject a) {
            try {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    return ok(cm.getPrimaryClip().getItemAt(0).coerceToText(ctx).toString());
                }
                return err("EMPTY", "剪贴板为空或被系统限制");
            } catch (Exception e) { return err("RESTRICTED", "Android 10+ 限制后台读取剪贴板: " + e); }
        }});

        // ═══ TTS / 震动 / 手电 ═══
        def("tts_speak", "语音朗读（中文 TTS）", schema(props("text", prop("string", "要念的话")), "text"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                if (!ttsInit()) return err("TTS_FAIL", "TTS 引擎初始化失败（手机可能没有中文语音包）");
                String text = a.optString("text");
                new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pi");
                }});
                return ok("开始朗读");
            }});

        def("vibrate", "震动", schema(props("ms", prop("number", "毫秒，默认 300"))), new H() { public JSONObject run(JSONObject a) {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            long ms = a.optLong("ms", 300);
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
            return ok("震动 " + ms + "ms");
        }});

        def("flashlight", "手电筒开关", schema(props("on", prop("boolean", "true 开 / false 关")), "on"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                String id = null;
                for (String cid : cm.getCameraIdList()) {
                    if (cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) { id = cid; break; }
                }
                if (id == null) return err("NO_FLASH", "没有闪光灯");
                cm.setTorchMode(id, a.optBoolean("on"));
                return ok("手电筒 " + (a.optBoolean("on") ? "开" : "关"));
            }});

        // ═══ 音量 / 媒体 / 亮度 ═══
        def("volume_get", "查音量（media/ring/alarm/call/notify）", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            JSONObject o = new JSONObject();
            int[][] st = {{3,2,4,0,5}};
            String[] names = {"media","ring","alarm","call","notify"};
            for (int i = 0; i < names.length; i++) {
                o.put(names[i], am.getStreamVolume(st[0][i]) + "/" + am.getStreamMaxVolume(st[0][i]));
            }
            return ok(o);
        }});

        def("volume_set", "设音量", schema(props("stream", prop("string", "media/ring/alarm/call/notify"),
                "percent", prop("number", "0-100")), "stream", "percent"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                Map<String,Integer> m = new LinkedHashMap<>();
                m.put("media",3); m.put("ring",2); m.put("alarm",4); m.put("call",0); m.put("notify",5);
                Integer st = m.get(a.optString("stream"));
                if (st == null) return err("BAD_STREAM", "stream 取值: media/ring/alarm/call/notify");
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(st);
                am.setStreamVolume(st, Math.max(0, Math.min(max, (int) Math.round(a.optDouble("percent") / 100.0 * max))), 0);
                return ok(am.getStreamVolume(st) + "/" + max);
            }});

        def("media_play_pause", "媒体播放/暂停（控制正在放的歌/视频）", schema(props()), new H() { public JSONObject run(JSONObject a) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            am.dispatchMediaKeyEvent(down); am.dispatchMediaKeyEvent(up);
            return ok("已发送播放/暂停键");
        }});

        def("brightness_set", "设屏幕亮度（0-255，需 WRITE_SETTINGS）",
            schema(props("value", prop("number", "0-255")), "value"), new H() { public JSONObject run(JSONObject a) throws Exception {
                int v = Math.max(1, Math.min(255, a.optInt("value")));
                try {
                    android.content.ContentResolver cr = ctx.getContentResolver();
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v);
                    return ok("亮度 " + v);
                } catch (SecurityException e) {
                    return err("NO_PERM", "需要授权: adbc shell appops set com.binbin.pibridge WRITE_SETTINGS allow");
                }
            }});

        def("brightness_get", "查屏幕亮度", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            return ok(Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1));
        }});

        // ═══ 网络 ═══
        def("network_info", "查网络：WiFi/流量、SSID、信号", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            JSONObject o = new JSONObject();
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            o.put("online", nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            o.put("wifi", nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                o.put("ssid", wi.getSSID()); o.put("ip", ipStr(wi.getIpAddress())); o.put("linkSpeedMbps", wi.getLinkSpeed());
            } catch (Exception e) { o.put("wifiDetail", "需定位权限"); }
            return ok(o);
        }});

        // ═══ 应用 ═══
        def("apps_list", "列出已装应用（可按关键词过滤）",
            schema(props("filter", prop("string", "包名/应用名包含关键词"), "limit", prop("number", "上限默认 50"))), new H() { public JSONObject run(JSONObject a) throws Exception {
                PackageManager pm = ctx.getPackageManager();
                List<PackageInfo> all = pm.getInstalledPackages(0);
                String f = a.optString("filter", "").toLowerCase();
                int limit = a.optInt("limit", 50);
                JSONArray arr = new JSONArray(); int n = 0;
                for (PackageInfo pi : all) {
                    String label = pm.getApplicationLabel(pi.applicationInfo).toString();
                    if (!f.isEmpty() && !label.toLowerCase().contains(f) && !pi.packageName.contains(f)) continue;
                    arr.put(label + " (" + pi.packageName + ")");
                    if (++n >= limit) break;
                }
                return ok(new JSONObject().put("total", all.size()).put("shown", n).put("items", arr));
            }});

        def("apps_launch", "启动应用", schema(props("package", prop("string", "包名，如 com.android.chrome")), "package"),
            new H() { public JSONObject run(JSONObject a) throws Exception {
                PackageManager pm = ctx.getPackageManager();
                Intent i = pm.getLaunchIntentForPackage(a.optString("package"));
                if (i == null) return err("NOT_FOUND", "该应用不可启动或不存在");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return ok("已启动");
            }});

        // ═══ 位置 / 短信 / 通话 / 联系人 ═══
        def("location_last", "最近位置（需定位权限）", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String p : new String[]{"gps", "network", "passive"}) {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return err("NO_FIX", "暂无缓存位置");
            return ok(new JSONObject().put("lat", best.getLatitude()).put("lon", best.getLongitude())
                    .put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(best.getTime()))));
        }});

        def("sms_list", "读最近短信（需短信权限）", schema(props("limit", prop("number", "条数默认 10"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            Cursor c = ctx.getContentResolver().query(Uri.parse("content://sms/inbox"),
                    new String[]{"address", "body", "date"}, null, null, "date DESC");
            JSONArray arr = new JSONArray(); int n = a.optInt("limit", 10); int i = 0;
            if (c != null) {
                while (c.moveToNext() && i < n) {
                    arr.put(new JSONObject().put("from", c.getString(0)).put("body", c.getString(1))
                            .put("time", new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(c.getLong(2)))));
                    i++;
                }
                c.close();
            }
            return ok(arr);
        }});

        def("calllog_list", "读最近通话（需通话记录权限）", schema(props("limit", prop("number", "条数默认 10"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.TYPE},
                    null, null, CallLog.Calls.DATE + " DESC");
            JSONArray arr = new JSONArray(); int n = a.optInt("limit", 10); int i = 0;
            if (c != null) {
                while (c.moveToNext() && i < n) {
                    String type = c.getInt(3) == CallLog.Calls.INCOMING_TYPE ? "入" : c.getInt(3) == CallLog.Calls.OUTGOING_TYPE ? "出" : "未接";
                    arr.put(new JSONObject().put("number", c.getString(0)).put("name", c.getString(1))
                            .put("type", type).put("time", new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(c.getLong(2)))));
                    i++;
                }
                c.close();
            }
            return ok(arr);
        }});

        def("contacts_search", "搜联系人", schema(props("q", prop("string", "姓名关键词")), "q"), new H() { public JSONObject run(JSONObject a) throws Exception {
            Cursor c = ctx.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + a.optString("q") + "%"}, null);
            JSONArray arr = new JSONArray(); int n = 20; int i = 0;
            if (c != null) {
                while (c.moveToNext() && i < n) { arr.put(c.getString(0) + " " + c.getString(1)); i++; }
                c.close();
            }
            return ok(arr);
        }});

        // ═══ 文件（共享存储）═══
        def("files_list", "列目录（共享存储）", schema(props("path", prop("string", "默认 /storage/emulated/0")), "path"), new H() { public JSONObject run(JSONObject a) throws Exception {
            File d = new File(a.optString("path", "/storage/emulated/0"));
            File[] fs = d.listFiles();
            if (fs == null) return err("NO_ACCESS", "目录不可读: " + d.getAbsolutePath());
            JSONArray arr = new JSONArray();
            for (File f : fs) {
                arr.put(new JSONObject().put("name", f.getName()).put("dir", f.isDirectory())
                        .put("size", f.length()).put("modified", f.lastModified()));
            }
            return ok(new JSONObject().put("path", d.getAbsolutePath()).put("items", arr));
        }});

        def("files_read_text", "读文本文件", schema(props("path", prop("string", "路径"), "maxKB", prop("number", "最多读 KB 默认 64")), "path"), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(a.optString("path"));
            long max = a.optLong("maxKB", 64) * 1024;
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), max)];
            int n = in.read(b); in.close();
            return ok(new JSONObject().put("size", f.length()).put("truncated", f.length() > max)
                    .put("text", new String(b, 0, Math.max(0, n), "UTF-8")));
        }});

        def("files_write_text", "写文本文件（可新建）", schema(props("path", prop("string", "路径"),
                "content", prop("string", "内容"), "append", prop("boolean", "追加模式")), "path", "content"), new H() { public JSONObject run(JSONObject a) throws Exception {
            File f = new File(a.optString("path"));
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream o = new FileOutputStream(f, a.optBoolean("append"));
            o.write(a.optString("content").getBytes("UTF-8")); o.close();
            MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, null, null);
            return ok(new JSONObject().put("path", f.getAbsolutePath()).put("size", f.length()));
        }});

        // ═══ Termux 联动（免插件！RUN_COMMAND 内置通道）═══
        def("termux_run", "在 Termux 里执行命令（后台模式；命令须为绝对路径）",
            schema(props("path", prop("string", "可执行文件绝对路径，如 /data/data/com.termux/files/usr/bin/bash"),
                    "args", prop("array", "参数数组"), "background", prop("boolean", "默认 true 后台执行")),
                    "path"), new H() { public JSONObject run(JSONObject a) throws Exception {
                Intent i = new Intent();
                i.setClassName("com.termux", "com.termux.app.RunCommandService");
                i.setAction("com.termux.RUN_COMMAND");
                i.putExtra("com.termux.RUN_COMMAND_PATH", a.optString("path"));
                JSONArray ar = a.optJSONArray("args");
                if (ar != null) {
                    String[] ss = new String[ar.length()];
                    for (int k = 0; k < ar.length(); k++) ss[k] = ar.getString(k);
                    i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", ss);
                }
                i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", a.optBoolean("background", true));
                try {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                    else ctx.startService(i);
                    return ok("已发往 Termux");
                } catch (Exception e) {
                    return err("TERMUX_FAIL", e + "（检查 termux.properties 里 allow-external-apps=true）");
                }
            }});

        // ═══ UI 操控（需辅助服务开启）═══
        def("ui_back", "按返回键", schema(props()), new H() { public JSONObject run(JSONObject a) {
            return AdbService.global(AdbService.GLOBAL_BACK) ? ok("已返回") : err("NO_SERVICE", "辅助服务未开启");
        }});
        def("ui_home", "按主页键", schema(props()), new H() { public JSONObject run(JSONObject a) {
            return AdbService.global(AdbService.GLOBAL_HOME) ? ok("已回主页") : err("NO_SERVICE", "辅助服务未开启");
        }});
        def("ui_recents", "打开最近任务", schema(props()), new H() { public JSONObject run(JSONObject a) {
            return AdbService.global(AdbService.GLOBAL_RECENTS) ? ok("已打开最近任务") : err("NO_SERVICE", "辅助服务未开启");
        }});
        def("ui_tap", "模拟点击屏幕坐标", schema(props("x", prop("number", "x"), "y", prop("number", "y")), "x", "y"), new H() { public JSONObject run(JSONObject a) {
            return AdbService.tap(a.optInt("x"), a.optInt("y")) ? ok("已点击") : err("NO_SERVICE", "辅助服务未开启");
        }});
        def("ui_swipe", "模拟滑动", schema(props("x1", prop("number", "起x"), "y1", prop("number", "起y"),
                "x2", prop("number", "终x"), "y2", prop("number", "终y"), "ms", prop("number", "时长默认300")),
                "x1", "y1", "x2", "y2"), new H() { public JSONObject run(JSONObject a) {
            return AdbService.swipe(a.optInt("x1"), a.optInt("y1"), a.optInt("x2"), a.optInt("y2"), a.optLong("ms", 300))
                    ? ok("已滑动") : err("NO_SERVICE", "辅助服务未开启");
        }});

        // ═══ 感知与直设（比截图喂视觉更快更准）═══
        def("ui_screen_read", "读取当前屏幕控件树（结构化 JSON：文本/ID/坐标/可点击性），AI 直接理解界面", schema(props()), new H() { public JSONObject run(JSONObject a) {
            JSONArray nodes = AdbService.readTree();
            if (nodes == null) return err("NO_SERVICE", "辅助服务未开启或无活动窗口");
            try { return ok(new JSONObject().put("count", nodes.length()).put("nodes", nodes)); }
            catch (Exception e) { return err("INTERNAL", e.toString()); }
        }});
        def("ui_set_text", "直接设置当前焦点输入框的文本（无需剪贴板粘贴菜单）", schema(props("text", prop("string", "要设置的文本")), "text"), new H() { public JSONObject run(JSONObject a) {
            String r = AdbService.setText(a.optString("text", ""));
            return r.equals("已设置") ? ok(r) : err("SET_FAIL", r);
        }});
        def("screenshot", "截取当前屏幕并保存（无障碍通道，返回 PNG 路径）", schema(props()), new H() { public JSONObject run(JSONObject a) {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] out = new String[2];
            AdbService.screenshot(new AdbService.ShotCb() { public void onShot(boolean ok, String msg) {
                out[0] = ok ? "1" : "0"; out[1] = msg; latch.countDown();
            }});
            try { latch.await(8, TimeUnit.SECONDS); } catch (Exception ignore) {}
            if (out[0] == null) return err("TIMEOUT", "截图超时");
            return out[0].equals("1") ? ok(out[1]) : err("SHOT_FAIL", out[1]);
        }});

        // ═══ 环境引擎（工作台自带 pi 环境）═══
        def("env_status", "查工作台 pi 环境状态（就绪/安装中/路径）", schema(props()), new H() { public JSONObject run(JSONObject a) {
            return ok(EnvInstaller.status());
        }});
        def("env_install", "安装 pi 环境（若未就绪则后台自动：下载→解压→二阶段），完成后 pi 可用", schema(props()), new H() { public JSONObject run(JSONObject a) {
            if (EnvInstaller.isReady()) return ok("环境已就绪");
            EnvInstaller.installAsync(new EnvInstaller.Cb() {
                public void onEvent(String line) { android.util.Log.i("PiBridge", "env: " + line); }
                public void onDone(boolean ok, String msg) { android.util.Log.i("PiBridge", "env done: " + msg); }
            });
            return ok("安装已后台启动，可用 env_status 轮询");
        }});
        def("setkey", "保存 API Key 与默认模型（写入小丘的 auth.json/settings.json）",
            schema(props("provider", prop("string", "供应商，如 zai-coding-cn"),
                    "key", prop("string", "API Key"),
                    "model", prop("string", "默认模型，如 glm-5.3-flash")),
                    "provider", "key"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String provider = a.optString("provider", "zai-coding-cn");
            String key = a.optString("key", "");
            String model = a.optString("model", "");
            if (key.length() < 8) return err("BAD", "Key 太短");
            File dir = new File(EnvInstaller.HOME, ".pi/agent");
            if (!dir.isDirectory()) dir.mkdirs();
            File af = new File(dir, "auth.json");
            JSONObject auth = new JSONObject();
            try { if (af.canRead()) auth = new JSONObject(readFile(af)); } catch (Exception ignore) {}
            auth.put(provider, new JSONObject().put("type", "api_key").put("key", key));
            write(af, auth.toString());
            if (model.length() > 0) {
                File sf = new File(dir, "settings.json");
                JSONObject st = new JSONObject();
                try { if (sf.canRead()) st = new JSONObject(readFile(sf)); } catch (Exception ignore) {}
                st.put("defaultProvider", provider).put("defaultModel", model);
                write(sf, st.toString());
            }
            return ok("已保存（" + provider + "，key 尾号 " + key.substring(Math.max(0, key.length() - 4)) + "）");
        }});
        // ═══ 设备感知（pi-api 能力原生吸收）═══
        def("sensors_read", "读取手机传感器瞬时值（加速度/磁场/光照/接近/重力）", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            android.hardware.SensorManager sm = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return err("NO_SENSOR", "无传感器服务");
            JSONObject out = new JSONObject();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            android.hardware.SensorEventListener li = new android.hardware.SensorEventListener() {
                public void onSensorChanged(android.hardware.SensorEvent ev) {
                    try {
                        String t = ev.sensor.getStringType();
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < ev.values.length && i < 3; i++) sb.append(i > 0 ? "," : "").append(String.format("%.2f", ev.values[i]));
                        if (!out.has(t)) out.put(t, sb.toString());
                    } catch (Exception ignore) {}
                }
                public void onAccuracyChanged(android.hardware.Sensor s2, int ac) {}
            };
            String[] names = {"accelerometer", "magnetic", "light", "proximity", "gravity"};
            int[] types = {android.hardware.Sensor.TYPE_ACCELEROMETER, android.hardware.Sensor.TYPE_MAGNETIC_FIELD,
                    android.hardware.Sensor.TYPE_LIGHT, android.hardware.Sensor.TYPE_PROXIMITY, android.hardware.Sensor.TYPE_GRAVITY};
            boolean any = false;
            for (int i = 0; i < types.length; i++) {
                android.hardware.Sensor s2 = sm.getDefaultSensor(types[i]);
                if (s2 != null) { sm.registerListener(li, s2, android.hardware.SensorManager.SENSOR_DELAY_NORMAL); any = true; }
            }
            if (!any) return err("NO_SENSOR", "无可用传感器");
            try { latch.await(900, TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
            sm.unregisterListener(li);
            JSONObject friendly = new JSONObject();
            java.util.Iterator<String> it = out.keys();
            while (it.hasNext()) {
                String k = it.next();
                String fk = k;
                for (String n : names) if (k.endsWith(n)) fk = n;
                friendly.put(fk, out.get(k));
            }
            return ok(friendly);
        }});
        def("mic_record", "麦克风录音 N 秒保存 WAV 16k（语音识别用）",
            schema(props("seconds", prop("number", "录音秒数默认10，上限120"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            int sec = a.optInt("seconds", 10);
            if (sec < 2) sec = 2; if (sec > 120) sec = 120;
            File wav = WavUtil.record(ctx, sec);
            return ok(wav.getAbsolutePath() + " " + wav.length() + "B");
        }});
        def("voice_chat", "语音对话（VAD 自动断句 + 持续会话上下文）：说完自动停→识别→pi 回答",
            schema(props("max_seconds", prop("number", "最长录音秒默认20"))), new H() { public JSONObject run(JSONObject a) throws Exception {
            int maxSec = a.optInt("max_seconds", 20);
            if (maxSec < 4) maxSec = 4; if (maxSec > 60) maxSec = 60;
            File wav = WavUtil.recordVad(ctx, maxSec, 2, 1200);
            JSONObject sttRes = Tools.call("stt_transcribe", new JSONObject().put("file", wav.getAbsolutePath()));
            JSONObject sttEnv = sttRes.optJSONObject("structured") != null ? sttRes.getJSONObject("structured") : sttRes;
            String heard = "";
            Object d = sttEnv.opt("data");
            if (d instanceof String) heard = (String) d;
            else if (d instanceof JSONObject) heard = ((JSONObject) d).optString("text", "");
            if (heard.isEmpty() || heard.startsWith("("))
                return ok(new JSONObject().put("heard", "").put("reply", "没听清，请靠近手机再说一遍"));
            heard = heard.replace("'", "'\''");
            JSONObject piRes = Tools.call("env_run", new JSONObject()
                .put("cmd", "cd ~ && pi -p --session-id xiaoqiu-voice '" + heard + "'")
                .put("timeout_sec", 150));
            JSONObject pd = piRes.optJSONObject("data") != null ? piRes.getJSONObject("data") : new JSONObject();
            String reply = pd.optString("output", "pi 无响应");
            return ok(new JSONObject().put("heard", heard).put("reply", reply));
        }});

        def("stt_transcribe", "语音转文字（SenseVoice 离线模型，输入 WAV 路径）",
            schema(props("file", prop("string", "WAV 文件路径（16k 单声道）")), "file"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String file = a.optString("file", "");
            if (file.isEmpty()) return err("BAD", "缺文件路径");
            File mf = new File(EnvInstaller.HOME + "/sherpa/model/model.int8.onnx");
            File tk = new File(EnvInstaller.HOME + "/sherpa/model/tokens.txt");
            if (!mf.exists() || !tk.exists()) return err("NO_MODEL", "模型未下载（239M，稍候自动完成）");
            if (sttRec == null) {
                String nat = ctx.getApplicationInfo().nativeLibraryDir;
                System.load(nat + "/libonnxruntime.so");
                System.load(nat + "/libsherpa-onnx-jni.so");
                com.k2fsa.sherpa.onnx.OfflineModelConfig mc = new com.k2fsa.sherpa.onnx.OfflineModelConfig();
                mc.senseVoice = new com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                        mf.getAbsolutePath(), "", false, new com.k2fsa.sherpa.onnx.QnnConfig());
                mc.tokens = tk.getAbsolutePath();
                mc.numThreads = 2;
                android.util.Log.i("PiBridge", "STT-DIAG java侧 tokens=" + mc.tokens + " model=" + mc.senseVoice.model);
                mc.debug = true;  // 临时：native 打印收到的配置
                com.k2fsa.sherpa.onnx.OfflineRecognizerConfig cfg = new com.k2fsa.sherpa.onnx.OfflineRecognizerConfig();
                cfg.modelConfig = mc;
                cfg.decodingMethod = "greedy_search";
                sttRec = new com.k2fsa.sherpa.onnx.OfflineRecognizer(null, cfg);
            }
            float[] samples = WavUtil.readWav(file);
            com.k2fsa.sherpa.onnx.OfflineStream st = sttRec.createStream();
            st.acceptWaveform(samples, 16000);
            sttRec.decode(st);
            String text = sttRec.getResult(st).getText();
            st.release();
            return ok(text.isEmpty() ? "(未识别到语音)" : text);
        }});

        // ═══ 权限中心（向导/设置页数据源）═══
        def("perm_status", "查询小丘权限与环境状态（无障碍/悬浮窗/所有文件/队列桥/环境）", schema(props()), new H() { public JSONObject run(JSONObject a) throws Exception {
            JSONObject o = new JSONObject();
            String enabled = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            o.put("accessibility", enabled != null && enabled.contains("com.pihost"));
            boolean overlay = false;
            try { overlay = Settings.canDrawOverlays(ctx); } catch (Throwable ignore) {}
            o.put("overlay", overlay);
            boolean allFiles = false;
            try { allFiles = (Build.VERSION.SDK_INT < 30) || android.os.Environment.isExternalStorageManager(); } catch (Throwable ignore) {}
            o.put("allFiles", allFiles);
            File q = new File("/storage/emulated/0/Download/pibridge-queue");
            o.put("queueBridge", q.isDirectory());
            JSONObject env = EnvInstaller.status();
            o.put("envReady", env.optBoolean("ready", false));
            File pui = new File("/data/data/com.pihost/files/home/.pi/agent/npm/node_modules/pi-web-ui/bin/pi-web-ui.mjs");
            o.put("webui", pui.exists());
            o.put("floatball", com.binbin.pibridge.FloatBall.isOn());
            return ok(o);
        }});
        def("open_permission_settings", "打开指定权限的系统设置页", schema(props("type", prop("string", "a11y=无障碍 overlay=悬浮窗 allfiles=所有文件")), "type"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String t = a.optString("type", "");
            Intent i;
            if (t.equals("a11y")) i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            else if (t.equals("overlay")) {
                i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:com.pihost"));
            } else if (t.equals("allfiles")) {
                i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:com.pihost"));
            } else return err("BAD", "type 须为 a11y/overlay/allfiles");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return ok("已打开设置页");
        }});

        // ═══ L2 特权通道（经队列桥 → ZeroTermux → adbc shell，shell 权限）═══
        def("l2_exec", "以 shell 特权执行系统命令（静默授权/系统设置/输入注入/强制停止应用等）。普通命令请用 env_run",
            schema(props("cmd", prop("string", "特权命令"), "timeout_sec", prop("number", "超时秒默认30")), "cmd"), new H() { public JSONObject run(JSONObject a) throws Exception {
            String cmd = a.optString("cmd", "");
            if (cmd.contains("\n") || cmd.isEmpty()) return err("BAD", "命令需单行且非空");
            int timeout = a.optInt("timeout_sec", 30);
            if (timeout < 5) timeout = 5; if (timeout > 300) timeout = 300;
            File qdir = new File("/storage/emulated/0/Download/pibridge-queue");
            if (!qdir.isDirectory()) return err("NO_QUEUE", "队列桥未部署（ZeroTermux 侧缺失）");
            String id = String.valueOf(System.currentTimeMillis());
            File cmdFile = new File(qdir, "l2-cmd-" + id + ".sh");
            write(cmdFile, "#!/system/bin/sh\n" + cmd + "\n");
            cmdFile.setReadable(true, false); cmdFile.setExecutable(true, false);
            File job = new File(qdir, "l2-" + id + ".cmd");
            write(job, "adbc shell sh /storage/emulated/0/Download/pibridge-queue/l2-cmd-" + id + ".sh"
                    + " > /storage/emulated/0/Download/pibridge-queue/l2-out-" + id + ".txt 2>&1\n"
                    + "echo __DONE >> /storage/emulated/0/Download/pibridge-queue/l2-out-" + id + ".txt\n"
                    + "chmod 644 /storage/emulated/0/Download/pibridge-queue/l2-out-" + id + ".txt\n");
            job.setReadable(true, false);
            // 轮询输出
            File out = new File(qdir, "l2-out-" + id + ".txt");
            long deadline = System.currentTimeMillis() + timeout * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (out.canRead()) {
                    String s = readFile(out);
                    if (s.contains("__DONE")) {
                        s = s.replace("__DONE", "").trim();
                        return ok(s.isEmpty() ? "(无输出，执行成功)" : (s.length() > 8000 ? s.substring(0, 8000) : s));
                    }
                }
                Thread.sleep(1000);
            }
            return err("TIMEOUT", "特权执行超时（队列或 adbc 通道异常）");
        }});

        def("floatball", "开关小丘悬浮球（on=开 off=关 缺省=切换）", schema(props("on", prop("string", "on/off，缺省切换"))), new H() { public JSONObject run(JSONObject a) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx))
                return err("NO_OVERLAY", "请先授权悬浮窗");
            String on = a.optString("on", "toggle");
            if (on.equals("on") || (on.equals("toggle") && !FloatBall.isOn())) FloatBall.showAsync(ctx);
            else if (on.equals("off") || on.equals("toggle")) FloatBall.hideAsync(ctx);
            return ok("指令已发，当前：" + (FloatBall.isOn() ? "开" : "关"));
        }});

        def("env_run", "在工作台 pi 环境内执行 shell 命令（pi/node/npm/全部 Linux 工具可用），返回输出", 
            schema(props("cmd", prop("string", "命令"), "timeout_sec", prop("number", "超时秒默认120")), "cmd"), new H() { public JSONObject run(JSONObject a) throws Exception {
            if (!EnvInstaller.isReady()) return err("NOT_READY", "环境未就绪，先调 env_install");
            String cmd = a.optString("cmd", "");
            int timeout = a.optInt("timeout_sec", 120);
            if (timeout < 5) timeout = 5; if (timeout > 900) timeout = 900;
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(new java.io.File(EnvInstaller.HOME));
            java.util.Map<String, String> env = pb.environment();
            env.put("HOME", EnvInstaller.HOME);
            env.put("PREFIX", EnvInstaller.PREFIX);
            env.put("PATH", EnvInstaller.PREFIX + "/bin:" + EnvInstaller.PREFIX + "/bin/applets:/system/bin");
            env.put("LD_LIBRARY_PATH", EnvInstaller.PREFIX + "/lib");
            env.put("TMPDIR", EnvInstaller.PREFIX + "/tmp");
            env.put("LANG", "en_US.UTF-8");
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        synchronized (sb) { if (sb.length() < 16000) sb.append(line).append('\n'); }
                    }
                } catch (Exception ignore) {}
            });
            reader.start();
            if (!p.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return err("TIMEOUT", "超时（已部分输出）\n" + sb); }
            reader.join(3000);
            String out = sb.toString();
            if (out.length() > 12000) out = out.substring(0, 12000) + "\n…(截断)";
            return ok(new JSONObject().put("exit", p.exitValue()).put("output", out));
        }});

        def("app_request_permission", "弹出系统授权对话框（用于设置页不显示的自定义权限，如 Termux 命令）",
            schema(props("permission", prop("string", "权限全名")), "permission"), new H() { public JSONObject run(JSONObject a) throws Exception {
                Intent i = new Intent(ctx, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra("req_perm", a.optString("permission"));
                ctx.startActivity(i);
                return ok("已在手机上弹出授权对话框，请点允许");
            }});

        def("termux_queue", "把 bash 脚本投到队列桥，Termux 每分钟自动执行（无需 RUN_COMMAND 权限，确定性 100%）",
            schema(props("script", prop("string", "bash 脚本内容")), "script"), new H() { public JSONObject run(JSONObject a) throws Exception {
                File dir = new File("/storage/emulated/0/Download/pibridge-queue");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, System.currentTimeMillis() + ".cmd");
                FileOutputStream o = new FileOutputStream(f);
                o.write(("#!/data/data/com.termux/files/usr/bin/bash\n" + a.optString("script") + "\n").getBytes("UTF-8"));
                o.close();
                return ok(new JSONObject().put("file", f.getName()).put("latency", "≤1分钟").put("log", "Termux: $PREFIX/tmp/queue-runner.log"));
            }});

        def("tools_list", "列出全部可用工具", schema(props()), new H() { public JSONObject run(JSONObject a) {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, Tool> e : REG.entrySet()) arr.put(e.getKey() + " — " + e.getValue().desc);
            return ok(arr);
        }});
    }

    static synchronized boolean ttsInit() {
        if (ttsReady) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程：不能 await（onInit 也投递在主线程→死锁）。异步初始化，本次先失败，预热线程通常会先行完成
            if (tts == null) tts = new TextToSpeech(ctx, st -> { ttsReady = (st == TextToSpeech.SUCCESS); });
            return false;
        }
        final CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = {false};
        tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
            public void onInit(int st) { ok[0] = (st == TextToSpeech.SUCCESS); ttsReady = ok[0]; l.countDown(); }
        });
        try { l.await(3, TimeUnit.SECONDS); } catch (Exception ignore) {}
        ttsReady = ok[0];
        if (ttsReady) { try { tts.setLanguage(new Locale("zh", "CN")); } catch (Exception ignore) {} }
        return ttsReady;
    }

    static String ipStr(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    /** IntentFilter 不能用匿名类继承简写，补个小类 */

    static com.k2fsa.sherpa.onnx.OfflineRecognizer sttRec;

    static String readFile(File f) throws Exception {
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = br.readLine()) != null) sb.append(l);
        br.close();
        return sb.toString();
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(s.getBytes("UTF-8"));
        fo.close();
    }
}
