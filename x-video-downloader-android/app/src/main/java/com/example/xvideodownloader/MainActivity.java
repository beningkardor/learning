package com.example.xvideodownloader;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String API_BASE = "https://api.fxtwitter.com/2/status/";
    private static final int STORAGE_REQUEST = 42;
    private static final int NOTIFICATION_REQUEST = 43;

    private EditText linkInput;
    private Button parseButton;
    private Button resumeButton;
    private FlowProgressView progress;
    private TextView fileInfo;
    private TextView progressText;
    private TextView statusText;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String pendingSource;
    private String pendingVideoUrl;
    private String pendingFileName;
    private long pendingSize;

    private final Runnable poll = new Runnable() {
        @Override public void run() { updateDownloadUi(); main.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        linkInput = findViewById(R.id.linkInput);
        parseButton = findViewById(R.id.parseButton);
        resumeButton = findViewById(R.id.resumeButton);
        progress = findViewById(R.id.progress);
        fileInfo = findViewById(R.id.fileInfo);
        progressText = findViewById(R.id.progressText);
        statusText = findViewById(R.id.statusText);
        parseButton.setOnClickListener(v -> parseLink());
        resumeButton.setOnClickListener(v -> { DownloadService.resume(this); show("已继续后台下载，切换到其他 App 也不会主动中断。", true); });
    }

    @Override protected void onResume() { super.onResume(); main.post(poll); updateDownloadUi(); }
    @Override protected void onPause() { main.removeCallbacks(poll); super.onPause(); }

    private void parseLink() {
        String input = linkInput.getText().toString().trim();
        if (input.isEmpty()) { show("请先粘贴视频链接。", false); return; }
        setBusy(true);
        show("正在查找视频资源…", false);
        executor.execute(() -> {
            try {
                VideoInfo info = resolve(input);
                pendingSource = input;
                pendingVideoUrl = info.url;
                pendingFileName = info.fileName;
                pendingSize = info.size;
                main.post(() -> {
                    setBusy(false);
                    fileInfo.setText(info.fileName + (info.size > 0 ? "\n视频大小：" + formatSize(info.size) : "\n视频大小：读取中"));
                    show("已找到视频资源，下载会在后台继续。", true);
                    requestAndStartDownload();
                });
            } catch (Exception e) {
                main.post(() -> { setBusy(false); show("解析失败：" + e.getMessage(), false); });
            }
        });
    }

    private void requestAndStartDownload() {
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_REQUEST);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
            return;
        }
        startDownloadNow();
    }

    private void startDownloadNow() {
        DownloadService.startOrResume(this, pendingSource, pendingVideoUrl, pendingFileName, pendingSize);
        show("下载已开始。你可以切换到后台，失败后点“继续下载”会从已下载部分接着来。", true);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == STORAGE_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            requestAndStartDownload();
        } else if (requestCode == NOTIFICATION_REQUEST) {
            startDownloadNow();
        } else if (requestCode == STORAGE_REQUEST) {
            show("没有存储权限，无法保存视频。", false);
        }
    }

    private void updateDownloadUi() {
        android.content.SharedPreferences sp = getSharedPreferences(DownloadService.PREFS, MODE_PRIVATE);
        String state = sp.getString(DownloadService.KEY_STATUS, "");
        if (state.isEmpty()) return;
        String name = sp.getString(DownloadService.KEY_FILE_NAME, "视频");
        long bytes = sp.getLong(DownloadService.KEY_BYTES, 0);
        long total = sp.getLong(DownloadService.KEY_TOTAL, 0);
        float ratio = total > 0 ? (float) bytes / (float) total : 0f;
        progress.setProgress(ratio);
        progress.setActive("downloading".equals(state));
        fileInfo.setText(name + (total > 0 ? "\n视频大小：" + formatSize(total) : "\n视频大小：读取中"));
        if (total > 0) progressText.setText(formatSize(bytes) + " / " + formatSize(total) + "（" + Math.round(ratio * 100) + "%）");
        else progressText.setText(formatSize(bytes) + " 已下载");
        resumeButton.setVisibility("failed".equals(state) ? View.VISIBLE : View.GONE);
        if ("downloading".equals(state)) show("后台下载中…切换应用不会主动取消。", true);
        else if ("failed".equals(state)) show("下载失败，但已保留已下载部分：" + sp.getString(DownloadService.KEY_ERROR, "网络中断"), false);
        else if ("completed".equals(state)) show("下载完成，已保存到 Movies/XDownloader。", true);
    }

    private VideoInfo resolve(String input) throws Exception {
        String id = StatusLink.extractId(input);
        if (id != null) {
            JSONObject root = fetchJson(API_BASE + id);
            if (root.optInt("code", 0) != 200) throw new Exception(root.optString("message", "公开帖子不可用"));
            JSONObject status = root.optJSONObject("status");
            JSONObject media = status == null ? null : status.optJSONObject("media");
            JSONArray videos = media == null ? null : media.optJSONArray("videos");
            if (videos == null || videos.length() == 0) throw new Exception("这个链接没有可下载的普通视频，或视频是外部播放器。 ");
            JSONObject video = videos.getJSONObject(0);
            String url = chooseMp4(video);
            if (url.isEmpty()) throw new Exception("解析结果没有视频地址。");
            long size = video.optLong("filesize", 0);
            if (size <= 0) size = probeSize(url);
            return new VideoInfo(url, "XVideo_" + id + ".mp4", size);
        }
        return resolveGeneric(input);
    }

    private VideoInfo resolveGeneric(String input) throws Exception {
        String normalized = input.replace("&amp;", "&");
        VideoInfo direct = probeVideo(normalized);
        if (direct != null) return direct;
        HttpURLConnection conn = (HttpURLConnection) new URL(normalized).openConnection();
        conn.setConnectTimeout(15000); conn.setReadTimeout(20000); conn.setRequestProperty("User-Agent", "XVideoDownloader/0.2");
        if (conn.getResponseCode() >= 400) throw new Exception("网页无法访问（HTTP " + conn.getResponseCode() + "）");
        String html = readLimited(conn.getInputStream(), 4 * 1024 * 1024); conn.disconnect();
        Set<String> candidates = new LinkedHashSet<>();
        String[] regexes = {
                "(?i)(?:property|name)\\s*=\\s*[\\\"'](?:og:video(?::secure_url)?|twitter:player:stream)[\\\"'][^>]*content\\s*=\\s*[\\\"']([^\\\"']+)",
                "(?i)content\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*(?:property|name)\\s*=\\s*[\\\"'](?:og:video(?::secure_url)?|twitter:player:stream)",
                "(?i)<(?:video|source)[^>]+(?:src|data-src)\\s*=\\s*[\\\"']([^\\\"']+)",
                "(?i)(https?://[^\\\"'<>\\s]+\\.(?:mp4|webm|m4v)(?:\\?[^\\\"'<>\\s]*)?)"
        };
        for (String regex : regexes) { Matcher m = Pattern.compile(regex).matcher(html); while (m.find()) candidates.add(m.group(1)); }
        for (String candidate : candidates) {
            String url = resolveUrl(normalized, candidate.replace("&amp;", "&"));
            VideoInfo found = probeVideo(url);
            if (found != null) return found;
        }
        throw new Exception("没有找到网页公开暴露的视频资源；m3u8/HLS 和受保护播放器暂不支持。 ");
    }

    private VideoInfo probeVideo(String value) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(value).openConnection();
            conn.setConnectTimeout(10000); conn.setReadTimeout(15000); conn.setRequestProperty("Range", "bytes=0-0");
            conn.setRequestProperty("User-Agent", "XVideoDownloader/0.2");
            int code = conn.getResponseCode(); String type = conn.getContentType();
            long size = parseTotal(conn.getHeaderField("Content-Range"));
            if (size <= 0) size = conn.getContentLengthLong();
            conn.disconnect();
            boolean video = type != null && type.toLowerCase().startsWith("video/");
            if (video || isMediaExtension(value)) return new VideoInfo(value, genericName(value), size);
        } catch (Exception ignored) { }
        return null;
    }

    private long probeSize(String value) { VideoInfo info = probeVideo(value); return info == null ? 0 : info.size; }

    private JSONObject fetchJson(String endpoint) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(15000); conn.setReadTimeout(20000); conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "XVideoDownloader/0.2 (personal use)");
        int code = conn.getResponseCode(); InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) throw new Exception("网络无响应");
        String body = readLimited(stream, 2 * 1024 * 1024); conn.disconnect();
        if (code >= 400) throw new Exception("解析服务暂时不可用（HTTP " + code + "）");
        return new JSONObject(body);
    }

    private String chooseMp4(JSONObject video) {
        String best = ""; long bitrate = -1; JSONArray formats = video.optJSONArray("formats");
        if (formats != null) for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i); if (f == null) continue;
            if ("mp4".equalsIgnoreCase(f.optString("container")) && !f.optString("url").isEmpty() && f.optLong("bitrate") >= bitrate) {
                best = f.optString("url"); bitrate = f.optLong("bitrate");
            }
        }
        return best.isEmpty() ? video.optString("url", "") : best;
    }

    private String readLimited(InputStream stream, int limit) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null && result.length() < limit) result.append(line).append('\n');
        reader.close(); return result.toString();
    }

    private String resolveUrl(String base, String value) throws Exception { return new URL(new URL(base), value).toString(); }
    private boolean isMediaExtension(String url) { return url.toLowerCase().matches(".*\\.(mp4|webm|m4v)(\\?.*)?$"); }
    private String genericName(String url) { return "Video_" + Math.abs(url.hashCode()) + ".mp4"; }
    private long parseTotal(String value) { try { return value == null ? 0 : Long.parseLong(value.substring(value.lastIndexOf('/') + 1)); } catch (Exception e) { return 0; } }
    private String formatSize(long bytes) { if (bytes >= 1024L * 1024L * 1024L) return String.format("%.2f GB", bytes / 1073741824.0); if (bytes >= 1024L * 1024L) return String.format("%.1f MB", bytes / 1048576.0); if (bytes >= 1024L) return String.format("%.1f KB", bytes / 1024.0); return bytes + " B"; }
    private void setBusy(boolean busy) { parseButton.setEnabled(!busy); }
    private void show(String text, boolean ok) { statusText.setText(text); statusText.setTextColor(getColor(ok ? R.color.accent_dark : R.color.ink)); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    static final class VideoInfo { final String url, fileName; final long size; VideoInfo(String u, String n, long s) { url = u; fileName = n; size = s; } }

    static final class StatusLink {
        static String extractId(String value) {
            if (TextUtils.isEmpty(value)) return null; String[] parts = value.split("/");
            for (int i = 0; i < parts.length - 1; i++) { String c = parts[i + 1].split("\\?")[0].split("#")[0]; if (parts[i].equalsIgnoreCase("status") && c.matches("\\d{5,30}")) return c; }
            return null;
        }
    }
}
