package com.paypaysound.hook;

import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.os.ParcelFileDescriptor;

import com.paypaysound.hook.sound.SoundSlot;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PayPay のプロセス内で動くフック本体。
 *
 * Resources#openRawResourceFd / openRawResource を横取りし、
 * getResourceEntryName() で名前が一致したものだけ自前の音声ファイルに差し替える。
 * リソース ID を直接書かないので、PayPay 側の更新で ID が変わっても壊れない。
 *
 * PayPay からは本アプリの内部ストレージを直接読めないので、
 * PayPay 自身のキャッシュに置き直してから開く。
 * 置き直しは、PayPay の権限で空ファイルを作って正しい SELinux ラベルを付けてから、
 * root で中身を流し込む、という順でやる。
 */
public class SoundHook implements IXposedHookLoadPackage {

    private static final String MODULE_PACKAGE = "com.paypaysound.hook";
    private static final String TARGET_PACKAGE = "jp.ne.paypay.android.app";

    private static final String MODULE_FILES_DIR = "/data/data/" + MODULE_PACKAGE + "/files/";
    private static final String STAGING_DIR = "/data/data/" + TARGET_PACKAGE + "/cache/paypaysoundhook/";

    /** プロセスごとに一度だけ置き直す。差し替え後は PayPay の再起動が必要。 */
    private static final Map<String, File> STAGED = new HashMap<String, File>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (MODULE_PACKAGE.equals(param.packageName)) {
            markModuleActive(param);
            return;
        }
        if (!TARGET_PACKAGE.equals(param.packageName)) {
            return;
        }
        hookRawResources(param);
    }

    /**
     * 本アプリ自身にロードされたときは、診断画面向けに「モジュールが生きている」印を立てる。
     * LSPosed のスコープに本アプリが入っていないと、この差し替えは起きない。
     */
    private void markModuleActive(XC_LoadPackage.LoadPackageParam param) {
        try {
            XposedHelpers.findAndHookMethod(
                    MODULE_PACKAGE + ".diag.ModuleStatus",
                    param.classLoader,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true));
        } catch (Throwable error) {
            XposedBridge.log("[PayPaySoundHook] 自己診断用のフックに失敗: " + error);
        }
    }

    private void hookRawResources(XC_LoadPackage.LoadPackageParam param) {
        XposedHelpers.findAndHookMethod(Resources.class, "openRawResourceFd", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam methodParam) {
                        File replacement = replacementFor(methodParam);
                        if (replacement == null) {
                            return;
                        }
                        try {
                            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                                    replacement, ParcelFileDescriptor.MODE_READ_ONLY);
                            methodParam.setResult(new AssetFileDescriptor(
                                    descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH));
                        } catch (Throwable error) {
                            XposedBridge.log("[PayPaySoundHook] 差し替えに失敗: " + error);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(Resources.class, "openRawResource", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam methodParam) {
                        File replacement = replacementFor(methodParam);
                        if (replacement == null) {
                            return;
                        }
                        try {
                            methodParam.setResult(new FileInputStream(replacement));
                        } catch (Throwable error) {
                            XposedBridge.log("[PayPaySoundHook] 差し替えに失敗: " + error);
                        }
                    }
                });
    }

    /**
     * 呼ばれたリソースが差し替え対象なら、PayPay から開けるファイルを返す。
     * 対象外、未設定、OFF のときは null（＝本来の音をそのまま鳴らす）。
     */
    private File replacementFor(XC_MethodHook.MethodHookParam methodParam) {
        String resourceName;
        try {
            resourceName = ((Resources) methodParam.thisObject)
                    .getResourceEntryName((Integer) methodParam.args[0]);
        } catch (Throwable error) {
            return null;
        }
        SoundSlot slot = SoundSlot.forResourceName(resourceName);
        if (slot == null) {
            return null;
        }
        return staged(slot);
    }

    private synchronized File staged(SoundSlot slot) {
        String fileName = slot.fileName();
        if (STAGED.containsKey(fileName)) {
            return STAGED.get(fileName);
        }
        File result = copyIntoTargetCache(fileName);
        STAGED.put(fileName, result);
        return result;
    }

    /**
     * 本アプリの内部ストレージにあるファイルを、PayPay のキャッシュに置き直す。
     * 置けなかった場合（音源が無い、OFF、root が使えない）は null を返す。
     */
    private File copyIntoTargetCache(String fileName) {
        try {
            File directory = new File(STAGING_DIR);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return null;
            }
            File destination = new File(directory, fileName);
            // 先に PayPay の権限で空ファイルを作り、正しい SELinux ラベルを付ける。
            if (destination.exists() && !destination.delete()) {
                return null;
            }
            if (!destination.createNewFile()) {
                return null;
            }
            // 中身だけ root で流し込む。-mm はマウント名前空間を保つため。
            String command = "cat " + MODULE_FILES_DIR + fileName + " > " + destination.getAbsolutePath();
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-mm", "-c", command});
            boolean copied = process.waitFor() == 0;
            process.destroy();

            if (!copied || destination.length() == 0) {
                destination.delete();
                return null;
            }
            return destination;
        } catch (Throwable error) {
            XposedBridge.log("[PayPaySoundHook] 音源の読み出しに失敗: " + error);
            return null;
        }
    }
}
