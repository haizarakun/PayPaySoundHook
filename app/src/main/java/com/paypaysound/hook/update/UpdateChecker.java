package com.paypaysound.hook.update;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub の最新リリースの確認と APK のダウンロード。
 * 通信はすべてワーカースレッドで行い、結果だけをメインスレッドに返す。
 */
public final class UpdateChecker {

    /** 常に最新リリースを返す API。リリースを作るだけでよく、アプリ側の修正は要らない。 */
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/haizarakun/PayPaySoundHook/releases/latest";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int BUFFER_SIZE = 16 * 1024;

    private static final Handler MAIN_THREAD = new Handler(Looper.getMainLooper());

    public interface CheckCallback {
        void onSuccess(UpdateInfo info);

        void onFailure(Exception error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onSuccess(File apkFile);

        void onFailure(Exception error);
    }

    private UpdateChecker() {
    }

    /** 最新リリースの情報を取得する。 */
    public static void checkAsync(final CheckCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final UpdateInfo info =
                            UpdateInfo.fromLatestReleaseJson(readText(LATEST_RELEASE_URL));
                    MAIN_THREAD.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(info);
                        }
                    });
                } catch (final Exception error) {
                    MAIN_THREAD.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(error);
                        }
                    });
                }
            }
        }, "update-check").start();
    }

    /** APK をダウンロードする。中身が正しいかは署名で確かめる（ApkInstaller 参照）。 */
    public static void downloadAsync(final String apkUrl, final File destination,
                                     final DownloadCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    download(apkUrl, destination, callback);
                    MAIN_THREAD.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSuccess(destination);
                        }
                    });
                } catch (final Exception error) {
                    destination.delete();
                    MAIN_THREAD.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onFailure(error);
                        }
                    });
                }
            }
        }, "update-download").start();
    }

    private static String readText(String url) throws IOException {
        HttpURLConnection connection = openConnection(url);
        try {
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copy(input, output, -1, null);
            return output.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private static void download(String url, File destination, DownloadCallback callback)
            throws IOException {
        HttpURLConnection connection = openConnection(url);
        try {
            int total = connection.getContentLength();
            InputStream input = connection.getInputStream();
            OutputStream output = new FileOutputStream(destination);
            try {
                copy(input, output, total, callback);
            } finally {
                output.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "PayPaySoundHook");
        connection.connect();
        int status = connection.getResponseCode();
        if (status / 100 != 2) {
            throw new IOException("HTTP " + status + " : " + url);
        }
        return connection;
    }

    /** totalBytes が不明（-1）のときは進捗通知を行わない。 */
    private static void copy(InputStream input, OutputStream output, int totalBytes,
                             final DownloadCallback callback) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long readTotal = 0;
        int lastPercent = -1;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            readTotal += read;
            if (callback == null || totalBytes <= 0) {
                continue;
            }
            final int percent = (int) (readTotal * 100 / totalBytes);
            if (percent != lastPercent) {
                lastPercent = percent;
                MAIN_THREAD.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onProgress(percent);
                    }
                });
            }
        }
        output.flush();
    }
}
