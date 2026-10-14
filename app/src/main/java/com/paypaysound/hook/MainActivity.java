package com.paypaysound.hook;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.paypaysound.hook.diag.AudioFormats;
import com.paypaysound.hook.diag.DiagnosticsActivity;
import com.paypaysound.hook.diag.DiagnosticsLog;
import com.paypaysound.hook.diag.ModuleStatus;
import com.paypaysound.hook.sound.SoundSlot;
import com.paypaysound.hook.update.ForceUpdateGate;

import java.io.File;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 音の差し替えを操作する画面。
 *
 * 上から「状態」「種類ごとのカード」「診断」の順に並べている。
 * まず効いているかが分かり、次に何を差し替えたかが分かる順番。
 *
 * 行の見た目と状態の更新は {@link #bindRow(SoundSlot)} に集約してあるので、
 * 操作のあとは同じ経路で表示を作り直すだけでよい。
 */
public class MainActivity extends Activity {

    /** 音源を選ぶ画面を開くときの要求コード。枠の並び順を足して区別する。 */
    private static final int REQUEST_PICK_SOUND = 100;

    /** 押せないボタンを薄く見せるときの濃さ。 */
    private static final float DISABLED_ALPHA = 0.4f;

    /** 状態欄に出す PayPay のパッケージ名。 */
    private static final String PAYPAY_PACKAGE = "jp.ne.paypay.android.app";

    private final Map<SoundSlot, View> rows = new EnumMap<SoundSlot, View>(SoundSlot.class);

    /** 試聴中のプレイヤー。鳴らしていなければ null。 */
    private MediaPlayer player;
    private SoundSlot playingSlot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticsLog.attach(this);
        setContentView(R.layout.activity_main);

        bindModuleStatus();

        LinearLayout container = (LinearLayout) findViewById(R.id.slot_container);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (SoundSlot slot : SoundSlot.values()) {
            View row = inflater.inflate(R.layout.item_sound_slot, container, false);
            container.addView(row);
            rows.put(slot, row);
            setUpRow(slot, row);
        }

        findViewById(R.id.open_diagnostics).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, DiagnosticsActivity.class));
            }
        });

        ForceUpdateGate.checkOnStart(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindModuleStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }

    /**
     * 画面上部の状態欄。2 行だけ。
     * 1 行目はモジュールが読み込まれているか、2 行目は PayPay が入っているかとその版。
     * 1 行目が有効にならない限り、何を選んでも音は変わらない。
     */
    private void bindModuleStatus() {
        TextView module = (TextView) findViewById(R.id.status_module);
        TextView paypay = (TextView) findViewById(R.id.status_paypay);
        TextView hint = (TextView) findViewById(R.id.status_hint);

        boolean active = ModuleStatus.isModuleActive();
        if (active) {
            module.setText("モジュール有効 ✅");
            module.setTextColor(color(R.color.ok));
        } else {
            module.setText("モジュール無効 ⚠️");
            module.setTextColor(color(R.color.accent));
        }

        String version = payPayVersion();
        if (version != null) {
            paypay.setText("PayPay（Version " + version + "）✅");
            paypay.setTextColor(color(R.color.ok));
        } else {
            paypay.setText("PayPay が見つかりません ⚠️");
            paypay.setTextColor(color(R.color.accent));
        }

        if (active && version != null) {
            hint.setVisibility(View.GONE);
            return;
        }
        hint.setVisibility(View.VISIBLE);
        if (!active) {
            hint.setText("LSPosed でこのモジュールを有効にし、スコープに PayPay と本アプリの両方を入れてから再起動してください。");
        } else {
            hint.setText("PayPay を端末にインストールしてください。");
        }
    }

    /** PayPay の versionName。入っていなければ null。 */
    private String payPayVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(PAYPAY_PACKAGE, 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return null;
        }
    }

    private void setUpRow(final SoundSlot slot, View row) {
        ((TextView) row.findViewById(R.id.slot_label)).setText(slot.label());

        row.findViewById(R.id.slot_pick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickSound(slot);
            }
        });
        row.findViewById(R.id.slot_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlayback(slot);
            }
        });
        row.findViewById(R.id.slot_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopPlayback();
                slot.clear(MainActivity.this);
                bindRow(slot);
            }
        });
        ((Switch) row.findViewById(R.id.slot_enabled)).setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        if (!button.isPressed()) {
                            // 表示を作り直したときの自動変更。ユーザー操作ではないので何もしない。
                            return;
                        }
                        slot.setEnabled(MainActivity.this, checked);
                        bindRow(slot);
                    }
                });

        bindRow(slot);
    }

    /** 1 行ぶんの表示を、いまのファイルの状態から作り直す。 */
    private void bindRow(SoundSlot slot) {
        View row = rows.get(slot);
        TextView status = (TextView) row.findViewById(R.id.slot_status);
        Switch enabled = (Switch) row.findViewById(R.id.slot_enabled);
        Button play = (Button) row.findViewById(R.id.slot_play);
        Button delete = (Button) row.findViewById(R.id.slot_delete);

        boolean hasSound = slot.hasSound(this);
        boolean isEnabled = slot.isEnabled(this);

        enabled.setChecked(isEnabled);
        setUsable(enabled, hasSound);
        setUsable(play, isEnabled);
        setUsable(delete, hasSound);
        play.setText(slot == playingSlot ? "■ 停止" : "▶ 再生");

        if (!hasSound) {
            status.setText("未設定（PayPay 本来の音）");
            return;
        }
        if (!isEnabled) {
            status.setText("OFF・本来の音のまま（音源は保存済み）");
            return;
        }
        File file = slot.file(this);
        status.setText("設定済み (" + AudioFormats.detect(file) + " / " + readableSize(file.length()) + ")");
    }

    /** 押せないボタンは薄く見せる。背景が自前の図形なので色では表せないため。 */
    private void setUsable(View view, boolean usable) {
        view.setEnabled(usable);
        view.setAlpha(usable ? 1f : DISABLED_ALPHA);
    }

    private String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " バイト";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    private int color(int colorResource) {
        return getResources().getColor(colorResource, getTheme());
    }

    private void pickSound(SoundSlot slot) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, REQUEST_PICK_SOUND + slot.ordinal());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        int index = requestCode - REQUEST_PICK_SOUND;
        if (index < 0 || index >= SoundSlot.values().length) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        SoundSlot slot = SoundSlot.values()[index];
        Uri source = data.getData();
        if (SoundStore.save(this, slot, source)) {
            toast(slot.label() + " を差し替えました。PayPay を完全終了してから開き直してください。");
        } else {
            toast("取り込めませんでした");
        }
        bindRow(slot);
    }

    /** 試聴の開始と停止。別の枠を鳴らしていたら、そちらは止める。 */
    private void togglePlayback(SoundSlot slot) {
        if (playingSlot == slot) {
            stopPlayback();
            return;
        }
        stopPlayback();

        try {
            player = new MediaPlayer();
            player.setDataSource(slot.file(this).getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer target) {
                    stopPlayback();
                }
            });
            player.prepare();
            player.start();
            playingSlot = slot;
        } catch (Exception error) {
            DiagnosticsLog.w(slot.label() + ": 試聴に失敗しました", error);
            stopPlayback();
            toast("この形式は端末で再生できませんでした");
        }
        bindRow(slot);
    }

    private void stopPlayback() {
        SoundSlot previous = playingSlot;
        playingSlot = null;
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // すでに止まっている場合は何もしなくてよい。
            }
            player.release();
            player = null;
        }
        if (previous != null && rows.containsKey(previous)) {
            bindRow(previous);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
