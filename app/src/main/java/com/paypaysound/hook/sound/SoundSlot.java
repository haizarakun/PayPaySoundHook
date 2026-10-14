package com.paypaysound.hook.sound;

import android.content.Context;

import com.paypaysound.hook.diag.DiagnosticsLog;

import java.io.File;

/**
 * 差し替えられる音の種類と、その ON/OFF。
 *
 * incoming_message / outgoing_message は送受金の通知専用ではなく、
 * PayPay のチャットのメッセージ音そのもの。チャットを1通送っただけでも鳴るので、
 * 支払い完了音だけ差し替えたい人のために種類ごとに切れるようにしてある。
 *
 * ON/OFF はフラグを別に持たず「差し替えファイルがあるか」で表す。
 * フック側は PayPay のプロセスから su でこのファイルを読むだけなので、
 * 設定を伝えるための仕組みを増やさずに済む。
 * OFF のときはファイルを <名前>.off に退避するだけなので、選んだ音源は消えない。
 */
public enum SoundSlot {

    /** 支払いが完了したときの音。2つのリソース名が同じ音を指している。 */
    PAYMENT_COMPLETE(
            "payment_complete",
            "支払い完了音",
            new String[]{"payment_complete_pay2_sound", "payment_complete_pay2_settings_sound"}),

    /** チャットでメッセージを受け取ったときの音。送金を受け取ったときにも鳴る。 */
    INCOMING_MESSAGE(
            "incoming_message",
            "チャット受信音",
            new String[]{"incoming_message"}),

    /** チャットでメッセージを送ったときの音。送金したときにも鳴る。 */
    OUTGOING_MESSAGE(
            "outgoing_message",
            "チャット送信音",
            new String[]{"outgoing_message"});

    /** OFF にした差し替えファイルに付ける拡張子。 */
    private static final String DISABLED_SUFFIX = ".off";

    private final String fileName;
    private final String label;
    private final String[] resourceNames;

    SoundSlot(String fileName, String label, String[] resourceNames) {
        this.fileName = fileName;
        this.label = label;
        this.resourceNames = resourceNames;
    }

    /** 画面に出す名前。 */
    public String label() {
        return label;
    }

    /** 内部ストレージでのファイル名。フック側はこの名前でファイルを探す。 */
    public String fileName() {
        return fileName;
    }

    /** この種類が受け持つ PayPay 側のリソース名。 */
    public String[] resourceNames() {
        return resourceNames.clone();
    }

    /** フック側が探しに来るファイル。 */
    public File file(Context context) {
        return new File(context.getFilesDir(), fileName);
    }

    private File disabledFile(Context context) {
        return new File(context.getFilesDir(), fileName + DISABLED_SUFFIX);
    }

    /** リソース名から種類を引く。該当しなければ null。 */
    public static SoundSlot forResourceName(String resourceName) {
        for (SoundSlot slot : values()) {
            for (String candidate : slot.resourceNames) {
                if (candidate.equals(resourceName)) {
                    return slot;
                }
            }
        }
        return null;
    }

    /** 音源が選ばれているか（ON/OFF は問わない）。 */
    public boolean hasSound(Context context) {
        return file(context).isFile() || disabledFile(context).isFile();
    }

    /** 今この音が差し替わるか。 */
    public boolean isEnabled(Context context) {
        return file(context).isFile();
    }

    /**
     * ON/OFF を切り替える。音源が選ばれていなければ何もしない。
     *
     * @return 切り替えに成功したら true
     */
    public boolean setEnabled(Context context, boolean enabled) {
        File active = file(context);
        File disabled = disabledFile(context);

        if (enabled) {
            if (active.isFile()) {
                // 新しい音源を取り込んだ直後など。古い退避ファイルが残っていれば捨てる。
                disabled.delete();
                return true;
            }
            if (!disabled.isFile()) {
                DiagnosticsLog.w(label + ": 音源が選ばれていないので ON にできません");
                return false;
            }
            return rename(disabled, active, "ON");
        }

        if (!active.isFile()) {
            return true;
        }
        // 退避先が残っていると rename に失敗する端末があるので、先に消す。
        if (disabled.isFile()) {
            disabled.delete();
        }
        return rename(active, disabled, "OFF");
    }

    /** 選んだ音源を捨てて、元の音に戻す。 */
    public void clear(Context context) {
        file(context).delete();
        disabledFile(context).delete();
        DiagnosticsLog.i(label + ": 音源を削除しました");
    }

    private boolean rename(File from, File to, String operation) {
        if (from.renameTo(to)) {
            DiagnosticsLog.i(label + ": " + operation + " にしました");
            return true;
        }
        DiagnosticsLog.w(label + ": " + operation + " への切り替えに失敗しました");
        return false;
    }
}
