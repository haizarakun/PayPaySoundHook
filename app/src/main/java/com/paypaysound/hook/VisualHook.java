package com.paypaysound.hook;

import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PayPay のアニメーション・表示テキストを差し替えるフック(見た目版)。
 *
 * SoundHook.java と同じ制約・同じ対処: PayPay から本アプリの内部ストレージを
 * 直接読むことはできないので、PayPay自身のキャッシュに一度コピーしてから開く。
 * コピーは PayPay の権限で空ファイルを作って正しい SELinux ラベルを付けてから、
 * root で中身だけ流し込む(SoundHook.copyIntoTargetCache と同じ手順)。
 *
 * 使い方: 本アプリの内部ストレージ
 *   /data/data/com.paypaysound.hook/files/visual/
 * に、差し替えたいファイルを元のアセット名と同じ名前で置く
 * (root権限のファイルマネージャーで直接置いてよい。アプリ内の画面は無し)。
 * 例: .../files/visual/confetti.lottie
 *     .../files/visual/localization_config.json
 *     .../files/visual/lottie_btn_processing.lottie
 *       ("lottie/btn_processing.lottie" はスラッシュをアンダースコアに置換)
 *
 * 置いたら PayPay を再起動するだけ。消せば元の表示に戻る。
 *
 * SoundHook.handleLoadPackage から1行呼び出す:
 *   new VisualHook().hookAssets(param);
 */
public class VisualHook {

    private static final String MODULE_PACKAGE = "com.paypaysound.hook";
    private static final String TARGET_PACKAGE = "jp.ne.paypay.android.app";

    private static final String MODULE_FILES_DIR = "/data/data/" + MODULE_PACKAGE + "/files/visual/";
    private static final String STAGING_DIR = "/data/data/" + TARGET_PACKAGE + "/cache/paypayvisualhook/";

    /** 差し替え対象の assets ファイル名(今回の調査で確認した全件)。 */
    private static final String[] ASSET_NAMES = {
            "confetti.lottie",
            "lottie_neon_scan.lottie",
            "lottie_sku_barcode_scan.lottie",
            "auth_phone_number.lottie",
            "auth_phone_number_success_check.lottie",
            "lottie_liveness_blink.lottie",
            "lottie_selfie_face.lottie",
            "bell_new_notification.lottie",
            "bell_redesign_notification.lottie",
            "cs_bell_notification.lottie",
            "bg_default_send.lottie",
            "bg_default_receive.lottie",
            "p2p_my_code_scan_animation.lottie",
            "bg_default_group_bill.lottie",
            "bg_default_group_bill_detail_card.lottie",
            "bg_default_group_bill_thumb.lottie",
            "chat_room_typing_status.lottie",
            "icon_check_20px.lottie",
            "icon_loading_20px.lottie",
            "progress.lottie",
            "mini-app_loading.json",
            "mini-app_done.json",
            "no_longer_available_anim.lottie",
            "offline_help.lottie",
            "post_payment_fallback_anim.lottie",
            "mini_app_notification_black.json",
            "mini_app_notification_white.json",
            "mini_app_onboarding_page_1.json",
            "lottie/btn_processing.lottie",
            "lottie/new_feature_v1.lottie",
            "localization_config.json",
    };

    private static final Set<String> TARGET_SET = new HashSet<>();
    static {
        for (String name : ASSET_NAMES) TARGET_SET.add(name);
    }

    /** プロセスごとに一度だけ置き直す(SoundHookのstagedと同じ考え方)。 */
    private static final Map<String, File> staged = new HashMap<>();

    public void hookAssets(XC_LoadPackage.LoadPackageParam param) {
        if (!TARGET_PACKAGE.equals(param.packageName)) return;

        XC_MethodHook replaceHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodParam) {
                String assetName = (String) methodParam.args[0];
                if (assetName == null || !TARGET_SET.contains(assetName)) return;

                File replacement = stagedFile(assetName);
                if (replacement == null) return;

                try {
                    methodParam.setResult(new FileInputStream(replacement));
                } catch (Throwable error) {
                    XposedBridge.log("[PayPayVisualHook] 差し替えに失敗: " + error);
                }
            }
        };

        XposedHelpers.findAndHookMethod(
                AssetManager.class, "open", String.class, replaceHook);
        XposedHelpers.findAndHookMethod(
                AssetManager.class, "open", String.class, int.class, replaceHook);
    }

    private synchronized File stagedFile(String assetName) {
        String localFileName = assetName.replace('/', '_');
        if (staged.containsKey(localFileName)) {
            return staged.get(localFileName);
        }
        File result = copyIntoTargetCache(localFileName);
        staged.put(localFileName, result);
        return result;
    }

    /**
     * 本アプリのストレージにあるファイルを、PayPayのキャッシュにコピーして返す。
     * 置けなかった場合(ファイルが無い、root が使えない)は null を返す。
     * SoundHook.copyIntoTargetCache と同じ手順(先に PayPay 権限で空ファイルを
     * 作って SELinux ラベルを付けてから、root で中身だけ流し込む)。
     */
    private File copyIntoTargetCache(String localFileName) {
        try {
            File dir = new File(STAGING_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            File dest = new File(dir, localFileName);
            if (dest.exists() && !dest.delete()) {
                return null;
            }
            if (!dest.createNewFile()) {
                return null;
            }
            String command = "cat " + MODULE_FILES_DIR + localFileName + " > " + dest.getAbsolutePath();
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-mm", "-c", command});
            boolean copied = process.waitFor() == 0;
            process.destroy();

            if (!copied || dest.length() == 0) {
                dest.delete();
                return null;
            }
            return dest;
        } catch (Throwable error) {
            XposedBridge.log("[PayPayVisualHook] 差し替えファイルの読み出しに失敗: " + error);
            return null;
        }
    }
}
