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
 * ダイアログは1つだけ使い回す。更新内容を読ませる状態から、ダウンロード、
 * インストールの確認まで同じ枠の中で進むので、画面が入れ替わって現在地を
 * 見失うことがない。
 *
 * インストールの直前で必ず一度手を止めて、入れ替え方を選ばせる。root を使うと
 * Android のインストール画面を通らずに入れ替わるため、黙って実行しない。
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
     * 更新内容の確認 → ダウンロード → 署名の照合 → 入れ替え方の確認 → インストール。
     *
     * 主ボタンの役目は段階ごとに変わるので、押されたときの動きを
     * {@link #primaryAction} に差し替えていく。
     */
    private static final class UpdateFlow {

        private final Activity activity;
        private final UpdateInfo info;

        private AlertDialog dialog;
        private TextView headlineView;
        private TextView notesView;
        private TextView statusView;
        private ProgressBar progressView;

        /** 主ボタンを押したときの動き。段階が進むたびに差し替える。 */
        private Runnable primaryAction;

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

            primaryAction = new Runnable() {
                @Override
                public void run() {
                    download();
                }
            };

            dialog = new AlertDialog.Builder(activity)
                    .setTitle("アップデート")
                    .setView(content)
                    .setCancelable(false)
                    .setPositiveButton("今すぐ更新", null)
                    .setNeutralButton("Android の画面で入れる", null)
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
                            primaryAction.run();
                        }
                    });
                    negativeButton().setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            dialog.dismiss();
                            activity.finish();
                        }
                    });
                    // 入れ替え方を選ぶ段階になるまでは出さない。
                    neutralButton().setVisibility(View.GONE);
                    neutralButton().setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            installWithSystemInstaller(downloadedFile());
                        }
                    });
                }
            });
            dialog.show();
        }

        private File downloadedFile() {
            return new File(activity.getCacheDir(), DOWNLOAD_FILE_NAME);
        }

        private void download() {
            setButtonsEnabled(false);
            statusView.setText("ダウンロードを開始しています...");
            progressView.setProgress(0);
            progressView.setVisibility(View.VISIBLE);

            UpdateChecker.downloadAsync(info.apkUrl, downloadedFile(), new UpdateChecker.DownloadCallback() {
                @Override
                public void onProgress(int percent) {
                    progressView.setProgress(percent);
                    statusView.setText("ダウンロード中 " + percent + "%");
                }

                @Override
                public void onSuccess(File apkFile) {
                    verify(apkFile);
                }

                @Override
                public void onFailure(Exception error) {
                    DiagnosticsLog.w("更新のダウンロードに失敗しました", error);
                    showRetry("ダウンロードに失敗しました。通信状態を確認してください。");
                }
            });
        }

        /** 落ちてきた APK が自分と同じ鍵で署名されているかを確かめる。 */
        private void verify(final File apkFile) {
            progressView.setIndeterminate(true);
            statusView.setText("署名を確認しています...");

            if (!ApkInstaller.hasSameSignature(activity, apkFile)) {
                // 鍵の違う APK はそもそも上書きできないので、ここで弾く。
                DiagnosticsLog.w("ダウンロードした APK の署名が一致しませんでした");
                apkFile.delete();
                showRetry("ダウンロードした APK の署名が一致しませんでした。");
                return;
            }
            askHowToInstall(apkFile);
        }

        /**
         * インストールの直前で一度止めて、入れ替え方を選ばせる。
         * root を使う側は Android の確認画面を通らないので、押す前にそう伝える。
         */
        private void askHowToInstall(final File apkFile) {
            progressView.setIndeterminate(false);
            progressView.setVisibility(View.GONE);
            statusView.setText("ダウンロードが完了しました。\n"
                    + "「インストール」を押すと、root 権限で Android の確認画面を通さずに入れ替えます。\n"
                    + "確認画面を見てから入れたい場合は「Android の画面で入れる」を選んでください。");

            primaryAction = new Runnable() {
                @Override
                public void run() {
                    installWithRoot(apkFile);
                }
            };
            positiveButton().setText("インストール");
            neutralButton().setVisibility(View.VISIBLE);
            setButtonsEnabled(true);
        }

        /** root で無操作インストール。成功するとこのプロセスはその場で終了する。 */
        private void installWithRoot(File apkFile) {
            setButtonsEnabled(false);
            neutralButton().setVisibility(View.GONE);
            progressView.setIndeterminate(true);
            progressView.setVisibility(View.VISIBLE);
            statusView.setText("インストールしています...");

            if (ApkInstaller.installWithRoot(apkFile)) {
                return;
            }
            DiagnosticsLog.i("root が使えないため、OS のインストーラに渡します");
            installWithSystemInstaller(apkFile);
        }

        /** OS 標準のインストーラに渡す。確認画面はそちらが出す。 */
        private void installWithSystemInstaller(File apkFile) {
            setButtonsEnabled(false);
            neutralButton().setVisibility(View.GONE);
            try {
                ApkInstaller.installWithPackageInstaller(activity, apkFile);
                progressView.setVisibility(View.GONE);
                statusView.setText("インストーラの画面で続けてください。");
            } catch (Exception error) {
                DiagnosticsLog.w("インストールに失敗しました", error);
                showRetry("インストールに失敗しました。");
            }
        }

        private void showRetry(String reason) {
            progressView.setIndeterminate(false);
            progressView.setVisibility(View.GONE);
            statusView.setText(reason);
            primaryAction = new Runnable() {
                @Override
                public void run() {
                    download();
                }
            };
            positiveButton().setText("再試行");
            neutralButton().setVisibility(View.GONE);
            setButtonsEnabled(true);
        }

        private void setButtonsEnabled(boolean enabled) {
            positiveButton().setEnabled(enabled);
            negativeButton().setEnabled(enabled);
            neutralButton().setEnabled(enabled);
        }

        private Button positiveButton() {
            return dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        }

        private Button negativeButton() {
            return dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        }

        private Button neutralButton() {
            return dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
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
