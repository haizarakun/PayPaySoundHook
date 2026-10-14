package com.paypaysound.hook.diag;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.paypaysound.hook.R;
import com.paypaysound.hook.update.ForceUpdateGate;

/**
 * 診断画面。
 *
 * 「音が変わらない」ときに何を確かめればよいかが、開かなくても分かるように
 * 動作条件・差し替え音・直近のログを1枚にまとめて出す。
 * そのままコピーして報告に貼れる。
 */
public class DiagnosticsActivity extends Activity {

    private static final Handler MAIN_THREAD = new Handler(Looper.getMainLooper());

    private TextView reportView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        reportView = (TextView) findViewById(R.id.diagnostics_report);

        findViewById(R.id.diagnostics_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyReport();
            }
        });
        findViewById(R.id.diagnostics_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                shareReport();
            }
        });
        findViewById(R.id.diagnostics_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                DiagnosticsLog.clear();
                loadReport();
                toast("ログを消去しました");
            }
        });
        Button checkUpdate = (Button) findViewById(R.id.diagnostics_check_update);
        checkUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ForceUpdateGate.checkManually(DiagnosticsActivity.this);
            }
        });

        loadReport();
    }

    /**
     * レポートの組み立ては su の実行を含むので、メインスレッドではやらない。
     */
    private void loadReport() {
        reportView.setText("収集中...");
        final Context context = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = DiagnosticsReport.build(context);
                MAIN_THREAD.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            reportView.setText(report);
                        }
                    }
                });
            }
        }, "diagnostics-report").start();
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            toast("クリップボードを使えませんでした");
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("PayPaySoundHook", reportView.getText()));
        toast("コピーしました");
    }

    private void shareReport() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, reportView.getText().toString());
        startActivity(Intent.createChooser(intent, "診断レポートを共有"));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
