package io.github.fanfront.isaac;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.Locale;

/**
 * Online-only Android host for the existing mobile web edition.
 * No game code, proprietary assets or downloadable game payloads are bundled.
 */
public final class MainActivity extends Activity {
    private static final int FILE_REQUEST = 42;
    private static final String BLOB_PREFIX = "isaac-apk-blob:";
    private static final int MAX_BLOB_BYTES = 8 * 1024 * 1024;

    private WebView webView;
    private Uri home;
    private android.webkit.ValueCallback<Uri[]> fileCallback;
    private String pendingFileName = "isaac-save.zip";
    private String pendingMimeType = "application/zip";
    private long lastBackPress;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        home = Uri.parse(getString(R.string.game_url));
        if (!"https".equalsIgnoreCase(home.getScheme()) || home.getHost() == null) {
            throw new IllegalStateException("game_url must be an HTTPS URL with a host");
        }

        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        webView = new WebView(this);
        webView.setBackgroundColor(0xff000000);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true); // IndexedDB: saves, options and mods.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Android's document picker.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri target = request.getUrl();
                if (isHomeOrigin(target)) return false;
                // Never navigate the app to an unrelated site. Open HTTPS links externally.
                if ("https".equalsIgnoreCase(target.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, target));
                    } catch (Exception ignored) {
                        Toast.makeText(MainActivity.this, "No browser available", Toast.LENGTH_SHORT).show();
                    }
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                    android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                    return true;
                } catch (Exception error) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "File picker unavailable", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                    String defaultValue, JsPromptResult result) {
                if (!message.startsWith(BLOB_PREFIX)) return false;
                if (!isHomeOrigin(Uri.parse(url))) {
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
                    if (encoded.length() > ((MAX_BLOB_BYTES + 2L) / 3L * 4L + 8L)) {
                        throw new IllegalArgumentException("Save too large");
                    }
                    byte[] data = Base64.decode(encoded, Base64.DEFAULT);
                    if (data.length > MAX_BLOB_BYTES) throw new IllegalArgumentException("Save too large");
                    storeInDownloads(pendingFileName, pendingMimeType, data);
                    result.confirm("");
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "Save export failed", Toast.LENGTH_LONG).show();
                    result.cancel();
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimeType, long contentLength) {
                pendingFileName = sanitizeFilename(URLUtil.guessFileName(url, contentDisposition, mimeType));
                pendingMimeType = mimeType != null ? mimeType : "application/octet-stream";
                if (url.startsWith("blob:")) {
                    // WebView's DownloadManager cannot fetch blob: URLs. Ask the
                    // trusted page to pass small save exports through a JS prompt.
                    String script = "(async()=>{try{"
                        + "const response=await fetch(" + JSONObject.quote(url) + ");"
                        + "const blob=await response.blob();"
                        + "if(blob.size>" + MAX_BLOB_BYTES + "){alert('Export exceeds 8 MB');return;}"
                        + "const reader=new FileReader();"
                        + "reader.onload=()=>window.prompt('" + BLOB_PREFIX + "'+reader.result,'');"
                        + "reader.readAsDataURL(blob);"
                        + "}catch(e){alert('Export failed');}})();";
                    webView.evaluateJavascript(script, null);
                } else if (url.startsWith("https://")) {
                    try {
                        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                        request.setTitle(pendingFileName);
                        request.setMimeType(pendingMimeType);
                        request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, pendingFileName);
                        String cookies = CookieManager.getInstance().getCookie(url);
                        if (cookies != null) request.addRequestHeader("Cookie", cookies);
                        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                        Toast.makeText(MainActivity.this, "Downloading to Downloads", Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        Toast.makeText(MainActivity.this, "Download failed", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        webView.loadUrl(home.toString());
    }

    private boolean isHomeOrigin(Uri url) {
        if (url == null) return false;
        return "https".equalsIgnoreCase(url.getScheme())
            && home.getHost().equalsIgnoreCase(url.getHost())
            && effectivePort(url) == effectivePort(home);
    }

    private static int effectivePort(Uri url) {
        return url.getPort() == -1 ? 443 : url.getPort();
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
                    Toast.makeText(this, "Saved to Downloads/Isaac", Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                if (item != null) getContentResolver().delete(item, null, null);
                runOnUiThread(() ->
                    Toast.makeText(this, "Save export failed", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); // Keep the WebView and live run on rotation.
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
        if (webView != null && !isHomeOrigin(Uri.parse(webView.getUrl()))
                && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPress < 1800) {
            super.onBackPressed();
            return;
        }
        lastBackPress = now;
        // The game already maps Escape to its pause/back action.
        webView.evaluateJavascript(
            "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true}));"
            + "document.dispatchEvent(new KeyboardEvent('keyup',{key:'Escape',code:'Escape',bubbles:true}));",
            null);
        Toast.makeText(this, "Back again to exit", Toast.LENGTH_SHORT).show();
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
