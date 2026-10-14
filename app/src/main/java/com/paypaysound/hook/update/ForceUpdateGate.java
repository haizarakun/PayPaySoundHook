package com.paypaysound.hook.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.paypaysound.hook.BuildConfig;
import com.paypaysound.hook.R;
import com.paypaysound.hook.diag.DiagnosticsLog;

import java.io.File;

/**
 * 起動時に更新を検知し、更新が終わるまでアプリを使わせないゲート。
 *
 * 使い方:
 *   起動時の自動チェック … onCreate の最後で ForceUpdateGate.checkOnStart(this)
 *   手動の「更新を確認」 … ForceUpdateGate.checkManually(this)
 *
 * 判定は GitHub の最新リリースのタグ名と versionName の比較で行う。
 * タグは versionName と一致させること（versionName 1.01 ならタグは v1.01）。
 *
 * ダイアログは1つだけ使い回す。更新内容を読ませる状態から、そのまま
 * 進捗表示に切り替わるので、画面が入れ替わって現在地を見失うことがない。
 */
public final class ForceUpdateGate {

    private static final String DOWNLOAD_FILE_NAME = "update.apk";

    private ForceUpdateGate() {
    }

    /** 起動時の自動チェック。確認に失敗しても何も言わない。 */
    public static void checkOnStart(Activity activity) {
        check(activity, false);
    }

    /** 手動チェック。最新だった場合も失敗した場合もその場で知らせる。 */
    public static void checkManually(Activity activity) {
        check(activity, true);
    }

    private static void check(final Activity activity, final boolean reportEveryResult) {
        DiagnosticsLog.i("更新を確認します (現在 " + BuildConfig.VERSION_NAME + ")");
        UpdateChecker.checkAsync(new UpdateChecker.CheckCallback() {
            @Override
            public void onSuccess(UpdateInfo info) {
                if (activity.isFinishing()) {
                    return;
                }
                DiagnosticsLog.i("最新リリース: " + info.tagName);
                if (!info.isNewerThan(BuildConfig.VERSION_NAME)) {
                    if (reportEveryResult) {
                        toast(activity, "お使いのバージョンが最新です");
                    }
                    return;
                }
                new UpdateFlow(activity, info).start();
            }

            @Override
            public void onFailure(Exception error) {
                // 電波が無いだけでアプリが使えなくなるのは困るので、自動チェックの失敗は黙って見送る。
                DiagnosticsLog.w("更新の確認に失敗しました", error);
                if (reportEveryResult) {
                    toast(activity, "更新を確認できませんでした");
                }
            }
        });
    }

    private static void toast(Activity activity, String message) {
        if (!activity.isFinishing()) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 1回分の更新のやりとり。
     * 確認 → ダウンロード → 署名の照合 → インストール、を同じダイアログの中で進める。
     */
    private static final class UpdateFlow {

        private final Activity activity;
        private final UpdateInfo info;

        private AlertDialog dialog;
        private TextView headlineView;
        private TextView notesView;
        private TextView statusView;
        private ProgressBar progressView;

        UpdateFlow(Activity activity, UpdateInfo info) {
            this.activity = activity;
            this.info = info;
        }

        void start() {
            View content = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null);
            headlineView = (TextView) content.findViewById(R.id.update_headline);
            notesView = (TextView) content.findViewById(R.id.update_notes);
            statusView = (TextView) content.findViewById(R.id.update_status);
            progressView = (ProgressBar) content.findViewById(R.id.update_progress);

            headlineView.setText("バージョン " + info.versionName + " が公開されています");
            notesView.setText(info.notes.isEmpty() ? "更新内容の記載はありません。" : info.notes);
            statusView.setText("この更新は必須です。更新するまでアプリは使用できません。");

            dialog = new AlertDialog.Builder(activity)
                    .setTitle("アップデート")
                    .setView(content)
                    .setCancelable(false)
                    .setPositiveButton("今すぐ更新", null)
                    .setNegativeButton("終了", null)
                    .create();
            blockBackKey(dialog);

            // ボタンを押してもダイアログが閉じないよう、show() の後に自前で差し替える。
            dialog.setOnShowListener(new DialogInterface.OnShowListener() {
                @Override
                public void onShow(DialogInterface target) {
                    positiveButton().setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            download();
                        }
                    });
                    negativeButton().setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            dialog.dismiss();
                            activity.finish();
                        }
                    });
                }
            });
            dialog.show();
        }

        private void download() {
            setButtonsEnabled(false);
            statusView.setText("ダウンロードを開始しています...");
            progressView.setProgress(0);
            progressView.setVisibility(View.VISIBLE);

            File destination = new File(activity.getCacheDir(), DOWNLOAD_FILE_NAME);
            UpdateChecker.downloadAsync(info.apkUrl, destination, new UpdateChecker.DownloadCallback() {
                @Override
                public void onProgress(int percent) {
                    progressView.setProgress(percent);
                    statusView.setText("ダウンロード中  " + percent + "%");
                }

                @Override
                public void onSuccess(File apkFile) {
                    install(apkFile);
                }

                @Override
                public void onFailure(Exception error) {
                    DiagnosticsLog.w("更新のダウンロードに失敗しました", error);
                    showRetry("ダウンロードに失敗しました。通信状態を確認してください。");
                }
            });
        }

        private void install(File apkFile) {
            progressView.setIndeterminate(true);
            statusView.setText("署名を確認しています...");

            if (!ApkInstaller.hasSameSignature(activity, apkFile)) {
                // 鍵の違う APK はそもそも上書きできないので、ここで弾く。
                DiagnosticsLog.w("ダウンロードした APK の署名が一致しませんでした");
                apkFile.delete();
                showRetry("ダウンロードした APK の署名が一致しませんでした。");
                return;
            }

            statusView.setText("インストールしています...");
            // root が使えれば無操作で入れ替わる（成功するとこのプロセスはその場で終了する）。
            if (ApkInstaller.installWithRoot(apkFile)) {
                return;
            }
            try {
                DiagnosticsLog.i("root が使えないため、OS のインストーラに渡します");
                ApkInstaller.installWithPackageInstaller(activity, apkFile);
                statusView.setText("インストーラの画面で続けてください。");
                progressView.setVisibility(View.GONE);
            } catch (Exception error) {
                DiagnosticsLog.w("インストールに失敗しました", error);
                showRetry("インストールに失敗しました。");
            }
        }

        private void showRetry(String reason) {
            progressView.setIndeterminate(false);
            progressView.setVisibility(View.GONE);
            statusView.setText(reason);
            positiveButton().setText("再試行");
            setButtonsEnabled(true);
        }

        private void setButtonsEnabled(boolean enabled) {
            positiveButton().setEnabled(enabled);
            negativeButton().setEnabled(enabled);
        }

        private Button positiveButton() {
            return dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        }

        private Button negativeButton() {
            return dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        }

        /** 戻るキーでダイアログを逃げられないようにする。 */
        private void blockBackKey(AlertDialog target) {
            target.setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface ignored, int keyCode, KeyEvent event) {
                    return keyCode == KeyEvent.KEYCODE_BACK;
                }
            });
        }
    }
}
