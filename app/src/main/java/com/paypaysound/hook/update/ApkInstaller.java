package com.paypaysound.hook.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.paypaysound.hook.diag.DiagnosticsLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * ダウンロードした APK のインストール。
 *
 * 本アプリは root 前提なので、まず su 経由で無操作インストールを試す。
 * それが使えない環境では、OS 標準のインストーラにフォールバックする。
 *
 * どちらの経路でも、新旧の APK が同じ鍵で署名されていないと上書きは拒否される。
 */
public final class ApkInstaller {

    /** installd から読める場所に置き直してから入れる。 */
    private static final String STAGING_PATH = "/data/local/tmp/paypaysoundhook-update.apk";

    /** インストール結果の通知を受け取るアクション名。 */
    public static final String ACTION_INSTALL_RESULT =
            "com.paypaysound.hook.action.INSTALL_RESULT";

    private static final int BUFFER_SIZE = 16 * 1024;

    private ApkInstaller() {
    }

    /**
     * ダウンロードした APK が、インストール済みの本アプリと同じ鍵で署名されているか確かめる。
     * 鍵が違えばどのみち上書きできないので、インストールを試す前に弾く。
     *
     * @return 同じ鍵なら true。判定できなかった場合も false
     */
    public static boolean hasSameSignature(Context context, File apkFile) {
        try {
            PackageManager manager = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;

            PackageInfo downloaded = manager.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
            if (downloaded == null || installed == null) {
                DiagnosticsLog.w("APK の情報を読み取れませんでした");
                return false;
            }
            if (!context.getPackageName().equals(downloaded.packageName)) {
                DiagnosticsLog.w("別のパッケージの APK です: " + downloaded.packageName);
                return false;
            }

            Set<String> downloadedSignatures = signaturesOf(downloaded);
            downloadedSignatures.retainAll(signaturesOf(installed));
            return !downloadedSignatures.isEmpty();
        } catch (Exception error) {
            DiagnosticsLog.w("署名の確認に失敗しました", error);
            return false;
        }
    }

    private static Set<String> signaturesOf(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        Set<String> result = new HashSet<String>();
        if (signatures != null) {
            for (Signature signature : signatures) {
                result.add(signature.toCharsString());
            }
        }
        return result;
    }

    /**
     * su 経由で上書きインストールする。
     * 成功するとこのプロセスは OS に終了させられるため、戻り値を見られるとは限らない。
     *
     * @return pm install が成功したら true
     */
    public static boolean installWithRoot(File apkFile) {
        String apkPath = apkFile.getAbsolutePath();
        String command =
                "cat " + apkPath + " > " + STAGING_PATH + "; "
                        + "chmod 644 " + STAGING_PATH + "; "
                        + "pm install -r -d " + STAGING_PATH + "; "
                        + "result=$?; "
                        + "rm -f " + STAGING_PATH + "; "
                        + "exit $result";
        return runAsRoot(command);
    }

    private static boolean runAsRoot(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            boolean succeeded = process.waitFor() == 0;
            if (!succeeded) {
                DiagnosticsLog.w("pm install が失敗しました");
            }
            return succeeded;
        } catch (Exception error) {
            DiagnosticsLog.w("root でのインストールに失敗しました", error);
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * OS 標準のインストーラに渡す（ユーザーの確認操作が入る）。
     * AndroidManifest.xml に REQUEST_INSTALL_PACKAGES 権限が必要。
     */
    public static void installWithPackageInstaller(Context context, File apkFile) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            writeApkToSession(session, apkFile);
            session.commit(createResultSender(context, sessionId));
        } finally {
            session.close();
        }
    }

    private static void writeApkToSession(PackageInstaller.Session session, File apkFile)
            throws IOException {
        OutputStream output = session.openWrite("update.apk", 0, apkFile.length());
        InputStream input = new FileInputStream(apkFile);
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            session.fsync(output);
        } finally {
            input.close();
            output.close();
        }
    }

    private static android.content.IntentSender createResultSender(Context context, int sessionId) {
        Intent intent = new Intent(ACTION_INSTALL_RESULT).setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags | PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).getIntentSender();
    }
}
