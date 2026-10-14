package com.paypaysound.hook.diag;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.paypaysound.hook.sound.SoundSlot;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 不具合報告に貼り付けられる形の診断レポートを組み立てる。
 *
 * 端末やアカウントを特定できる情報は入れない。
 * 入るのは端末の機種名と OS バージョン、各アプリのバージョン、
 * 差し替え音の ON/OFF と形式、直近のログだけ。
 */
public final class DiagnosticsReport {

    /** 音を差し替える対象。ここに居ないと何も起きない。 */
    private static final String PAYPAY_PACKAGE = "jp.ne.paypay.android.app";

    private DiagnosticsReport() {
    }

    public static String build(Context context) {
        StringBuilder report = new StringBuilder();

        report.append("=== PayPaySoundHook 診断レポート ===").append('\n');
        report.append("作成: ").append(now()).append("\n\n");

        report.append("[アプリ]").append('\n');
        PackageInfo self = packageInfo(context, context.getPackageName());
        report.append("バージョン: ").append(versionOf(self)).append('\n');
        report.append("パッケージ: ").append(context.getPackageName()).append("\n\n");

        report.append("[端末]").append('\n');
        report.append("機種: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

        report.append("[動作条件]").append('\n');
        report.append("root: ").append(describeRoot()).append('\n');
        report.append("モジュール有効: ").append(describeModuleStatus()).append('\n');
        PackageInfo payPay = packageInfo(context, PAYPAY_PACKAGE);
        report.append("PayPay: ").append(payPay == null ? "未インストール、または参照不可" : versionOf(payPay))
                .append("\n\n");

        report.append("[差し替え音]").append('\n');
        appendSlots(context, report);
        report.append('\n');

        report.append("[直近のログ]").append('\n');
        String log = DiagnosticsLog.asText();
        report.append(log.isEmpty() ? "記録なし\n" : log);

        return report.toString();
    }

    private static void appendSlots(Context context, StringBuilder report) {
        for (SoundSlot slot : SoundSlot.values()) {
            report.append(slot.label()).append(": ");
            if (!slot.hasSound(context)) {
                report.append("未設定").append('\n');
                continue;
            }
            File file = slot.file(context);
            report.append(slot.isEnabled(context) ? "ON" : "OFF");
            if (file.isFile()) {
                report.append("  ").append(file.length()).append(" バイト")
                        .append("  ").append(AudioFormats.detect(file));
            }
            report.append('\n');
        }
    }

    /**
     * su が通るかを確かめる。
     * Magisk の許可ダイアログが出ることがあるので、診断画面を開いたときだけ呼ぶ。
     */
    private static String describeRoot() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "id -u"});
            return process.waitFor() == 0 ? "使用可" : "拒否された、または利用不可";
        } catch (Exception error) {
            DiagnosticsLog.w("root の確認に失敗しました", error);
            return "利用不可";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String describeModuleStatus() {
        if (ModuleStatus.isModuleActive()) {
            return "有効";
        }
        return "無効、またはスコープに本アプリが入っていません";
    }

    private static PackageInfo packageInfo(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private static String versionOf(PackageInfo info) {
        if (info == null) {
            return "不明";
        }
        return info.versionName + " (" + info.versionCode + ")";
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
