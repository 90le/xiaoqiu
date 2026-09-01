package com.binbin.pibridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
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
}
