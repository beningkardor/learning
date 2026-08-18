package com.example.xvideodownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadService extends Service {
    public static final String PREFS = "download_state";
    public static final String KEY_STATUS = "status";
    public static final String KEY_SOURCE = "source";
    public static final String KEY_VIDEO_URL = "video_url";
    public static final String KEY_FILE_NAME = "file_name";
    public static final String KEY_BYTES = "bytes";
    public static final String KEY_TOTAL = "total";
    public static final String KEY_URI = "uri";
    public static final String KEY_PATH = "path";
    public static final String KEY_ERROR = "error";
    private static final String CHANNEL_ID = "video_downloads";
    private static final int NOTIFICATION_ID = 1201;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    public static void startOrResume(Context context, String source, String videoUrl, String fileName, long total) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String oldSource = sp.getString(KEY_SOURCE, "");
        String oldStatus = sp.getString(KEY_STATUS, "");
        boolean sameDownload = source.equals(oldSource) && !"completed".equals(oldStatus);
        SharedPreferences.Editor edit = sp.edit();
        if (!sameDownload) {
            edit.clear().putString(KEY_SOURCE, source).putLong(KEY_BYTES, 0).putLong(KEY_TOTAL, total);
        } else if (total > 0) {
            edit.putLong(KEY_TOTAL, total);
        }
        edit.putString(KEY_VIDEO_URL, videoUrl).putString(KEY_FILE_NAME, fileName)
                .putString(KEY_STATUS, "queued").putString(KEY_ERROR, "").apply();
        startService(context);
    }

    public static void resume(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (TextUtils.isEmpty(sp.getString(KEY_VIDEO_URL, ""))) return;
        sp.edit().putString(KEY_STATUS, "queued").putString(KEY_ERROR, "").apply();
        startService(context);
    }

    private static void startService(Context context) {
        Intent intent = new Intent(context, DownloadService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(buildNotification("准备下载", 0, 0, true));
        if (running.compareAndSet(false, true)) executor.execute(this::download);
        return START_STICKY;
    }

    private void download() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            ensureDestination(sp);
            sp.edit().putString(KEY_STATUS, "downloading").apply();
            String url = sp.getString(KEY_VIDEO_URL, "");
            int refreshes = 0;
            while (!TextUtils.isEmpty(url)) {
                long offset = currentBytes(sp);
                HttpURLConnection conn = open(url, offset);
                int response = conn.getResponseCode();
                if ((response == 403 || response == 404 || response == 410) && refreshes < 2) {
                    String refreshed = refreshXUrl(sp.getString(KEY_SOURCE, ""));
                    conn.disconnect();
                    if (TextUtils.isEmpty(refreshed)) throw new Exception("视频地址已过期，刷新失败");
                    url = refreshed; refreshes++; sp.edit().putString(KEY_VIDEO_URL, url).apply(); continue;
                }
                if (response == 416) {
                    conn.disconnect();
                    if (sp.getLong(KEY_TOTAL, 0) > 0 && offset >= sp.getLong(KEY_TOTAL, 0)) { complete(sp); return; }
                    resetOutput(sp); continue;
                }
                if (response >= 400) { conn.disconnect(); throw new Exception("视频服务器返回 HTTP " + response); }
                if (offset > 0 && response == 200) {
                    conn.disconnect(); resetOutput(sp); continue;
                }
                long total = parseTotal(conn.getHeaderField("Content-Range"));
                if (total <= 0 && conn.getContentLengthLong() > 0) total = offset + conn.getContentLengthLong();
                if (total > 0) sp.edit().putLong(KEY_TOTAL, total).apply();
                stream(conn, sp, offset, total);
                long after = currentBytes(sp);
                long expected = sp.getLong(KEY_TOTAL, 0);
                if (expected <= 0 || after >= expected) { complete(sp); return; }
                throw new Exception("连接提前断开，已保留 " + after + " 字节");
            }
        } catch (Exception e) {
            sp.edit().putString(KEY_STATUS, "failed").putString(KEY_ERROR, e.getMessage() == null ? "网络中断" : e.getMessage()).apply();
            notifyDownload("下载失败，已保留断点", sp.getLong(KEY_BYTES, 0), sp.getLong(KEY_TOTAL, 0), false, true);
        } finally {
            running.set(false); stopSelf();
        }
    }

    private void stream(HttpURLConnection conn, SharedPreferences sp, long offset, long total) throws Exception {
        long written = offset; long lastSave = offset; long lastNotify = 0;
        try (InputStream input = conn.getInputStream(); OutputStream output = openOutput(sp, offset > 0)) {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count); written += count;
                if (written - lastSave >= 512 * 1024) { sp.edit().putLong(KEY_BYTES, written).apply(); lastSave = written; }
                long now = System.currentTimeMillis();
                if (now - lastNotify > 700) { notifyDownload("后台下载中", written, total, true, false); lastNotify = now; }
            }
            output.flush();
        } finally { conn.disconnect(); }
        sp.edit().putLong(KEY_BYTES, written).apply();
    }

    private void ensureDestination(SharedPreferences sp) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            if (!TextUtils.isEmpty(sp.getString(KEY_URI, ""))) return;
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, sp.getString(KEY_FILE_NAME, "video.mp4"));
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XDownloader");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("无法创建手机视频文件");
            sp.edit().putString(KEY_URI, uri.toString()).apply();
        } else {
            if (!TextUtils.isEmpty(sp.getString(KEY_PATH, ""))) return;
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "XDownloader");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建 Movies/XDownloader");
            File file = new File(dir, sp.getString(KEY_FILE_NAME, "video.mp4") + ".part");
            sp.edit().putString(KEY_PATH, file.getAbsolutePath()).apply();
        }
    }

    private OutputStream openOutput(SharedPreferences sp, boolean append) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) return getContentResolver().openOutputStream(Uri.parse(sp.getString(KEY_URI, "")), append ? "wa" : "w");
        return new FileOutputStream(new File(sp.getString(KEY_PATH, "")), append);
    }

    private void resetOutput(SharedPreferences sp) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) { OutputStream output = openOutput(sp, false); output.close(); }
        else { new File(sp.getString(KEY_PATH, "")).delete(); }
        sp.edit().putLong(KEY_BYTES, 0).apply();
    }

    private long currentBytes(SharedPreferences sp) { return sp.getLong(KEY_BYTES, 0); }

    private void complete(SharedPreferences sp) throws Exception {
        long bytes = currentBytes(sp); sp.edit().putLong(KEY_BYTES, bytes).putString(KEY_STATUS, "completed").apply();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues(); values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(Uri.parse(sp.getString(KEY_URI, "")), values, null, null);
        } else {
            File part = new File(sp.getString(KEY_PATH, "")); File done = new File(part.getParentFile(), sp.getString(KEY_FILE_NAME, "video.mp4"));
            if (!part.renameTo(done)) throw new Exception("视频已下载但重命名失败");
        }
        notifyDownload("下载完成", bytes, sp.getLong(KEY_TOTAL, bytes), false, false);
    }

    private HttpURLConnection open(String url, long offset) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000); conn.setReadTimeout(30000); conn.setRequestProperty("User-Agent", "XVideoDownloader/0.2");
        if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
        return conn;
    }

    private String refreshXUrl(String source) {
        String id = extractStatusId(source); if (id == null) return "";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.fxtwitter.com/2/status/" + id).openConnection();
            conn.setConnectTimeout(15000); conn.setReadTimeout(20000); conn.setRequestProperty("User-Agent", "XVideoDownloader/0.2");
            if (conn.getResponseCode() >= 400) return "";
            JSONObject root = new JSONObject(readAll(conn.getInputStream())); conn.disconnect();
            JSONObject media = root.optJSONObject("status").optJSONObject("media"); JSONArray videos = media == null ? null : media.optJSONArray("videos");
            if (videos == null || videos.length() == 0) return "";
            JSONObject video = videos.getJSONObject(0); JSONArray formats = video.optJSONArray("formats"); String best = ""; long bitrate = -1;
            if (formats != null) for (int i = 0; i < formats.length(); i++) { JSONObject f = formats.optJSONObject(i); if (f != null && "mp4".equalsIgnoreCase(f.optString("container")) && f.optLong("bitrate") >= bitrate) { best = f.optString("url"); bitrate = f.optLong("bitrate"); } }
            return best.isEmpty() ? video.optString("url", "") : best;
        } catch (Exception ignored) { return ""; }
    }

    private String readAll(InputStream input) throws Exception { java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = input.read(b)) != -1) out.write(b, 0, n); input.close(); return out.toString("UTF-8"); }
    private String extractStatusId(String value) { if (value == null) return null; String[] p = value.split("/"); for (int i = 0; i < p.length - 1; i++) { String c = p[i + 1].split("\\?")[0].split("#")[0]; if ("status".equalsIgnoreCase(p[i]) && c.matches("\\d{5,30}")) return c; } return null; }
    private long parseTotal(String value) { try { return value == null ? 0 : Long.parseLong(value.substring(value.lastIndexOf('/') + 1)); } catch (Exception e) { return 0; } }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) { NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW); getSystemService(NotificationManager.class).createNotificationChannel(channel); }
    }

    private Notification buildNotification(String title, long bytes, long total, boolean ongoing) {
        Intent intent = new Intent(this, MainActivity.class); PendingIntent pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText("X 视频下载器").setContentIntent(pending).setOngoing(ongoing).setOnlyAlertOnce(true);
        int percent = total > 0 ? (int) Math.min(100, bytes * 100 / total) : 0; builder.setProgress(100, percent, total <= 0);
        return builder.build();
    }

    private void startForegroundCompat(Notification notification) { if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(NOTIFICATION_ID, notification); }
    private void notifyDownload(String title, long bytes, long total, boolean ongoing, boolean failed) { NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE); manager.notify(NOTIFICATION_ID, buildNotification(title, bytes, total, ongoing)); }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
