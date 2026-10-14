package com.nickwoluff.wearfiles;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 沿用 WearCalculator 的后台请求和主线程回调，读取 GitHub 最新正式 Release。 */
public final class UpdateChecker {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile HttpURLConnection activeConnection;

    public interface Callback {
        void onResult(String tagName);
        void onError(int statusCode);
    }

    public void check(Callback callback) {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/NickWoluff/WearFiles/releases/latest").openConnection();
                activeConnection = connection;
                if (closed) return;
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "WearFiles");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    postError(callback, status);
                    return;
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject release = new JSONObject(body.toString());
                String tag = release.optString("tag_name", "");
                if (tag.isEmpty() || release.optBoolean("draft") || release.optBoolean("prerelease")) {
                    throw new IllegalStateException("Invalid release");
                }
                main.post(() -> { if (!closed) callback.onResult(tag); });
            } catch (Exception exception) {
                postError(callback, -1);
            } finally {
                if (connection != null) connection.disconnect();
                activeConnection = null;
            }
        });
    }

    private void postError(Callback callback, int status) {
        main.post(() -> { if (!closed) callback.onError(status); });
    }

    public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }
}
