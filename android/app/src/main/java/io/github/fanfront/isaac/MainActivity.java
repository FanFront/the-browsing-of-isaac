package io.github.fanfront.isaac;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.JsPromptResult;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline host for a standalone portable HTML created from the player's own game.
 * A build can bundle assets/offline.html, or the player can import the same file.
 * No website, CDN or game payload is fetched at runtime.
 */
public final class MainActivity extends Activity {
    private static final int GAME_IMPORT_REQUEST = 43;
    private static final int SAVE_IMPORT_REQUEST = 42;
    private static final String ASSET_ORIGIN = "https://appassets.androidplatform.net";
    private static final String BUNDLED_PAGE = ASSET_ORIGIN + "/assets/offline.html";
    private static final String IMPORTED_PAGE = ASSET_ORIGIN + "/imported/offline.html";
    private static final String BLOB_PREFIX = "isaac-apk-blob:";
    private static final String PAYLOAD_PREFIX = "/payload/c/";
    // Only pinned a0..a3 and b0..b27 archive chunks are served; no general file access.
    private static final Pattern CHUNK_FILE = Pattern.compile(
        "(?:a[0-3]|b(?:[0-9]|1[0-9]|2[0-7]))\\.bin");
    private static final Pattern BYTE_RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");
    private static final Pattern QUERY_RANGE = Pattern.compile("(\\d+)-(\\d+)");
    private static final int MAX_SAVE_EXPORT_BYTES = 8 * 1024 * 1024;
    private static final long MIN_PORTABLE_BYTES = 128 * 1024;
    private static final long MAX_PORTABLE_BYTES = 3L * 1024 * 1024 * 1024;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private File importedFile;
    private TextView status;
    private android.webkit.ValueCallback<Uri[]> fileCallback;
    private String pendingSaveFileName = "isaac-save.zip";
    private String pendingSaveMimeType = "application/zip";
    private long lastBackPress;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        File contentDirectory = new File(getFilesDir(), "offline-game");
        if (!contentDirectory.exists() && !contentDirectory.mkdirs()) {
            throw new IllegalStateException("Cannot create offline game directory");
        }
        importedFile = new File(contentDirectory, "offline.html");

        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/imported/",
                new WebViewAssetLoader.InternalStoragePathHandler(this, contentDirectory))
            .build();

        // A private full-game APK must always prefer its embedded payload.
        // An older imported page should not shadow the bundled game on upgrade.
        if (hasBundledGame()) {
            launchGame(BUNDLED_PAGE);
        } else if (importedFile.isFile() && importedFile.length() >= MIN_PORTABLE_BYTES) {
            launchGame(IMPORTED_PAGE);
        } else {
            showImportScreen("此 APK 已支持完全离线运行，但尚未包含游戏数据。");
        }
    }

    private boolean hasBundledGame() {
        try (InputStream input = getAssets().open("offline.html")) {
            return input.read() >= 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showImportScreen(String message) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setBackgroundColor(0xff16120f);

        TextView title = new TextView(this);
        title.setText("The Browsing of Isaac · 离线版");
        title.setTextSize(22);
        title.setTextColor(0xffe9dfcf);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        status = new TextView(this);
        status.setText(message + "\n\n请选择由本项目 portable.py offline 命令生成的完整 isaac.html，"
            + "而不是普通网页或在线缓存。导入一次后无需网络即可启动。");
        status.setTextColor(0xffd7c9a7);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, padding, 0, padding);
        layout.addView(status);

        Button importButton = new Button(this);
        importButton.setText("选择离线游戏 HTML");
        importButton.setOnClickListener(view -> openGamePicker());
        layout.addView(importButton);
        setContentView(layout);
    }

    private void openGamePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // File managers do not consistently label HTML MIME types.
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, GAME_IMPORT_REQUEST);
        } catch (Exception error) {
            status.setText("无法启动文件选择器：" + error.getMessage());
        }
    }

    private void importPortable(Uri uri) {
        if (status != null) status.setText("正在复制离线资源到应用内部存储…\n大型 HTML 需要足够空间。");
        final File destination = importedFile;
        new Thread(() -> {
            File staging = new File(destination.getParentFile(), "offline.html.pending");
            try {
                byte[] prefix = new byte[512];
                int prefixLen;
                long copied = 0L;
                boolean hasPortableData = false;
                // Stream directly from the document provider. Never load a multi-GB HTML into RAM.
                try (InputStream input = getContentResolver().openInputStream(uri);
                     OutputStream output = new FileOutputStream(staging)) {
                    if (input == null) throw new IllegalArgumentException("无法打开文件");
                    prefixLen = input.read(prefix);
                    if (prefixLen <= 0) throw new IllegalArgumentException("空文件");
                    String start = new String(prefix, 0, prefixLen,
                        java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (!start.contains("<!doctype html") && !start.contains("<html")) {
                        throw new IllegalArgumentException("不是 HTML 文件");
                    }
                    output.write(prefix, 0, prefixLen);
                    copied += prefixLen;
                    byte[] buffer = new byte[128 * 1024];
                    int n;
                    // Verify the generator's portable-data marker across chunk boundaries.
                    byte[] needle = "window.__isaacPortableData".getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII);
                    int match = 0;
                    while ((n = input.read(buffer)) != -1) {
                        copied += n;
                        if (copied > MAX_PORTABLE_BYTES) {
                            throw new IllegalArgumentException("HTML 超出 3 GiB 限制");
                        }
                        output.write(buffer, 0, n);
                        if (!hasPortableData) {
                            for (int i = 0; i < n; i++) {
                                if (buffer[i] == needle[match]) {
                                    if (++match == needle.length) {
                                        hasPortableData = true;
                                        break;
                                    }
                                } else {
                                    match = buffer[i] == needle[0] ? 1 : 0;
                                }
                            }
                        }
                    }
                }
                if (copied < MIN_PORTABLE_BYTES || !hasPortableData) {
                    throw new IllegalArgumentException(
                        "缺少完整离线资源（请使用 portable.py offline 生成文件）");
                }
                if (destination.exists() && !destination.delete()) {
                    throw new IllegalStateException("无法替换现有离线页面");
                }
                if (!staging.renameTo(destination)) {
                    throw new IllegalStateException("无法保存离线页面");
                }
                runOnUiThread(() -> launchGame(IMPORTED_PAGE));
            } catch (Exception error) {
                staging.delete();
                runOnUiThread(() -> {
                    if (status != null) {
                        status.setText("导入失败：" + error.getMessage()
                            + "\n\n请检查 HTML 完整性和手机可用空间，然后重新选择。");
                    }
                });
            }
        }, "isaac-offline-import").start();
    }

    private static WebResourceResponse blockedResponse() {
        WebResourceResponse response = new WebResourceResponse(
            "text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        response.setStatusCodeAndReasonPhrase(403, "Offline mode");
        return response;
    }

    private static boolean isLocalOrigin(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
            && "appassets.androidplatform.net".equalsIgnoreCase(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }


    /**
     * Serves the upstream browser edition's pinned data chunks entirely from
     * uncompressed APK assets. WebResourceRequest gives us Range headers,
     * unlike WebViewAssetLoader.PathHandler; the upstream loader validates
     * HTTP 206, Content-Range, and Content-Length for each requested window.
     */
    private WebResourceResponse serveLocalChunk(WebResourceRequest request) {
        Uri uri = request.getUrl();
        String path = uri.getPath();
        if (path == null || !path.startsWith(PAYLOAD_PREFIX)) return blockedResponse();
        String name = path.substring(PAYLOAD_PREFIX.length());
        if (!CHUNK_FILE.matcher(name).matches()) return blockedResponse();

        String assetName = "payload/c/" + name;
        try {
            long total;
            // Gradle's noCompress("bin") is mandatory for openFd and cheap seeking.
            try (AssetFileDescriptor fd = getAssets().openFd(assetName)) {
                total = fd.getLength();
            }
            if (total <= 0) return blockedResponse();

            String header = null;
            for (Map.Entry<String, String> item : request.getRequestHeaders().entrySet()) {
                if ("range".equalsIgnoreCase(item.getKey())) {
                    header = item.getValue();
                    break;
                }
            }
            // WebView sometimes omits headers in worker requests; the upstream
            // loader also puts the requested byte window in the "w" query.
            String queryWindow = uri.getQueryParameter("w");
            Matcher matcher = header == null ? null : BYTE_RANGE.matcher(header.trim());
            if (header != null && !matcher.matches()) return rangeNotSatisfiable(total);
            if (header == null && queryWindow != null) {
                matcher = QUERY_RANGE.matcher(queryWindow);
                if (!matcher.matches()) return rangeNotSatisfiable(total);
            }
            boolean partial = matcher != null;
            long from = partial ? Long.parseLong(matcher.group(1)) : 0L;
            long to = partial ? Long.parseLong(matcher.group(2)) : total - 1L;
            if (from < 0 || to < from || to >= total) return rangeNotSatisfiable(total);
            long count = to - from + 1L;

            InputStream input = getAssets().open(assetName, AssetManager.ACCESS_STREAMING);
            try {
                skipExactly(input, from);
                WebResourceResponse result = new WebResourceResponse(
                    "application/octet-stream", null, new BoundedInputStream(input, count));
                result.setStatusCodeAndReasonPhrase(
                    partial ? 206 : 200, partial ? "Partial Content" : "OK");
                Map<String, String> responseHeaders = new HashMap<>();
                responseHeaders.put("Accept-Ranges", "bytes");
                responseHeaders.put("Content-Length", Long.toString(count));
                responseHeaders.put("Cache-Control", "no-store");
                if (partial) {
                    responseHeaders.put("Content-Range",
                        "bytes " + from + "-" + to + "/" + total);
                }
                result.setResponseHeaders(responseHeaders);
                return result;
            } catch (Exception error) {
                input.close();
                throw error;
            }
        } catch (Exception failure) {
            android.util.Log.e("IsaacOffline", "Local chunk read failed: " + name, failure);
            return blockedResponse();
        }
    }

    private static WebResourceResponse rangeNotSatisfiable(long total) {
        WebResourceResponse response = new WebResourceResponse(
            "application/octet-stream", null, new ByteArrayInputStream(new byte[0]));
        response.setStatusCodeAndReasonPhrase(416, "Range Not Satisfiable");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Range", "bytes */" + total);
        response.setResponseHeaders(headers);
        return response;
    }

    private static void skipExactly(InputStream input, long amount) throws IOException {
        while (amount > 0) {
            long skipped = input.skip(amount);
            if (skipped > 0) {
                amount -= skipped;
            } else if (input.read() == -1) {
                throw new IOException("Unexpected end of payload asset");
            } else {
                amount--;
            }
        }
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream input, long limit) {
            super(input);
            remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (remaining == 0) return -1;
            int count = in.read(buffer, off, (int) Math.min(remaining, len));
            if (count > 0) remaining -= count;
            return count;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = in.skip(Math.min(Math.max(n, 0L), remaining));
            remaining -= skipped;
            return skipped;
        }
    }

    private void launchGame(String localPage) {
        status = null;
        WebView view = new WebView(this);
        webView = view;
        view.setBackgroundColor(0xff000000);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(view);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true); // Offline IndexedDB: saves and mods.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Game's ZIP / .dat file picker.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView ignored,
                    WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocalOrigin(uri)) {
                    String path = uri.getPath();
                    if (path != null && path.startsWith(PAYLOAD_PREFIX)) {
                        return serveLocalChunk(request);
                    }
                    if (path != null && (path.startsWith("/assets/")
                            || path.startsWith("/imported/"))) {
                        WebResourceResponse response = assetLoader.shouldInterceptRequest(uri);
                        if (response != null) return response;
                    }
                    return blockedResponse();
                }
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return blockedResponse();
                }
                // blob:/data: and internal schemes are required by portable module loader.
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView ignored,
                    WebResourceRequest request) {
                // Never navigate to an external site, even if the HTML contains a link.
                return !request.isForMainFrame()
                    ? false
                    : !localPage.equals(request.getUrl().toString());
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView ignored,
                    android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), SAVE_IMPORT_REQUEST);
                    return true;
                } catch (Exception error) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "文件选择器不可用", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

            @Override
            public boolean onJsPrompt(WebView ignored, String url, String message,
                    String defaultValue, JsPromptResult result) {
                if (!message.startsWith(BLOB_PREFIX)) return false;
                if (!isLocalOrigin(Uri.parse(url))) {
                    result.cancel();
                    return true;
                }
                try {
                    String dataUrl = message.substring(BLOB_PREFIX.length());
                    int comma = dataUrl.indexOf(',');
                    if (comma < 0 || !dataUrl.substring(0, comma).endsWith(";base64")) {
                        throw new IllegalArgumentException("Not a base64 blob");
                    }
                    String encoded = dataUrl.substring(comma + 1);
                    if (encoded.length() > ((MAX_SAVE_EXPORT_BYTES + 2L) / 3L * 4L + 8L)) {
                        throw new IllegalArgumentException("Save too large");
                    }
                    byte[] data = Base64.decode(encoded, Base64.DEFAULT);
                    if (data.length > MAX_SAVE_EXPORT_BYTES) {
                        throw new IllegalArgumentException("Save too large");
                    }
                    storeInDownloads(pendingSaveFileName, pendingSaveMimeType, data);
                    result.confirm("");
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "存档导出失败", Toast.LENGTH_LONG).show();
                    result.cancel();
                }
                return true;
            }
        });

        view.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            pendingSaveFileName = sanitizeFilename(
                URLUtil.guessFileName(url, contentDisposition, mimeType));
            pendingSaveMimeType = mimeType != null ? mimeType : "application/octet-stream";
            if (url.startsWith("blob:")) {
                String script = "(async()=>{try{"
                    + "const response=await fetch(" + JSONObject.quote(url) + ");"
                    + "const blob=await response.blob();"
                    + "if(blob.size>" + MAX_SAVE_EXPORT_BYTES + "){alert('Export exceeds 8 MB');return;}"
                    + "const reader=new FileReader();"
                    + "reader.onload=()=>window.prompt('" + BLOB_PREFIX + "'+reader.result,'');"
                    + "reader.readAsDataURL(blob);"
                    + "}catch(e){alert('Export failed');}})();";
                view.evaluateJavascript(script, null);
            } else {
                Toast.makeText(this, "离线模式不支持网络下载", Toast.LENGTH_SHORT).show();
            }
        });

        view.loadUrl(localPage);
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) return "isaac-save.zip";
        String safe = filename.replaceAll("[^a-zA-Z0-9._() -]", "_");
        if (safe.equals(".") || safe.equals("..") || safe.isEmpty()) return "isaac-save.zip";
        return safe.length() > 120 ? safe.substring(safe.length() - 120) : safe;
    }

    private void storeInDownloads(String filename, String mime, byte[] bytes) {
        new Thread(() -> {
            Uri item = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Isaac");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (item == null) throw new IllegalStateException("Could not create download");
                try (OutputStream stream = getContentResolver().openOutputStream(item)) {
                    if (stream == null) throw new IllegalStateException("Could not open download");
                    stream.write(bytes);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, values, null, null);
                runOnUiThread(() ->
                    Toast.makeText(this, "已保存至 Downloads/Isaac", Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                if (item != null) getContentResolver().delete(item, null, null);
                runOnUiThread(() ->
                    Toast.makeText(this, "存档导出失败", Toast.LENGTH_LONG).show());
            }
        }, "isaac-save-export").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GAME_IMPORT_REQUEST && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importPortable(data.getData());
            return;
        }
        if (requestCode == SAVE_IMPORT_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPress < 1800) {
            super.onBackPressed();
            return;
        }
        lastBackPress = now;
        webView.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true}));"
            + "document.dispatchEvent(new KeyboardEvent('keyup',{key:'Escape',code:'Escape',bubbles:true}));",
            null);
        Toast.makeText(this, "再按一次返回键退出", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
