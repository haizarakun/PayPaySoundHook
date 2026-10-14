package com.paypaysound.hook.diag;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 診断ログ。
 *
 * 「音が変わらない」「更新できない」といった報告を受け取ったときに、
 * 端末で何が起きていたかを後から追えるようにするための記録。
 *
 * 動作は次の二段構え。
 *   1. 常に logcat へ出す（開発中はこれで足りる）
 *   2. {@link #attach(Context)} を呼んだプロセスでは、メモリ上の直近ログと
 *      内部ストレージのファイルにも残す
 *
 * 本モジュールは PayPay のプロセス内でも動くが、そちらからは本アプリの
 * 内部ストレージに書けない。フック側では attach せず logcat だけに出す。
 */
public final class DiagnosticsLog {

    public static final String TAG = "PayPaySoundHook";

    /** メモリ上に保持する行数。これを超えた分は古いものから捨てる。 */
    private static final int MAX_ENTRIES = 500;

    /** ファイルがこの大きさを超えたら、書き足す前に作り直す。 */
    private static final long MAX_FILE_BYTES = 256 * 1024;

    private static final String LOG_FILE_NAME = "diagnostics.log";

    private static final Deque<String> ENTRIES = new ArrayDeque<String>();

    /** null の間はメモリとファイルには残さず、logcat だけに出す。 */
    private static File logFile;

    private DiagnosticsLog() {
    }

    /**
     * ログをこのプロセスの内部ストレージにも残すようにする。
     * Application か最初の Activity の onCreate で一度だけ呼ぶ。
     */
    public static synchronized void attach(Context context) {
        logFile = new File(context.getFilesDir(), LOG_FILE_NAME);
        if (logFile.length() > MAX_FILE_BYTES) {
            logFile.delete();
        }
    }

    public static void i(String message) {
        Log.i(TAG, message);
        record("I", message);
    }

    public static void w(String message) {
        Log.w(TAG, message);
        record("W", message);
    }

    public static void w(String message, Throwable error) {
        Log.w(TAG, message, error);
        record("W", message + "\n" + stackTraceOf(error));
    }

    public static void e(String message, Throwable error) {
        Log.e(TAG, message, error);
        record("E", message + "\n" + stackTraceOf(error));
    }

    /** 直近のログを古い順に返す。 */
    public static synchronized List<String> snapshot() {
        return new ArrayList<String>(ENTRIES);
    }

    /** 直近のログを1つの文字列にして返す。共有やコピーに使う。 */
    public static String asText() {
        StringBuilder builder = new StringBuilder();
        for (String entry : snapshot()) {
            builder.append(entry).append('\n');
        }
        return builder.toString();
    }

    /** メモリとファイルの両方を消す。 */
    public static synchronized void clear() {
        ENTRIES.clear();
        if (logFile != null) {
            logFile.delete();
        }
    }

    private static synchronized void record(String level, String message) {
        String entry = timestamp() + " " + level + " " + message;
        ENTRIES.addLast(entry);
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.removeFirst();
        }
        appendToFile(entry);
    }

    private static void appendToFile(String entry) {
        if (logFile == null) {
            return;
        }
        FileWriter writer = null;
        try {
            if (logFile.length() > MAX_FILE_BYTES) {
                logFile.delete();
            }
            writer = new FileWriter(logFile, true);
            writer.write(entry);
            writer.write('\n');
        } catch (Exception error) {
            // ログを残せないこと自体でアプリを止めたくないので、握りつぶす。
            Log.w(TAG, "診断ログを書き込めませんでした", error);
        } finally {
            closeQuietly(writer);
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String stackTraceOf(Throwable error) {
        if (error == null) {
            return "";
        }
        StringWriter buffer = new StringWriter();
        error.printStackTrace(new PrintWriter(buffer));
        return buffer.toString().trim();
    }

    private static void closeQuietly(FileWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (Exception ignored) {
            // 閉じられなくても報告することがない。
        }
    }
}
