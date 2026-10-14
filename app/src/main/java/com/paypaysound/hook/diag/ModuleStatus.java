package com.paypaysound.hook.diag;

/**
 * LSPosed でモジュールが実際に読み込まれているかを、アプリ自身から確かめるための印。
 *
 * フック側で {@link #isModuleActive()} の戻り値を true に差し替える。
 * 差し替えが効いていれば有効、false のままなら
 * 「モジュールが無効」か「スコープに本アプリが入っていない」のどちらか。
 *
 * この判定を働かせるには、LSPosed のスコープに PayPay だけでなく
 * 本アプリ自身も入れておく必要がある。
 */
public final class ModuleStatus {

    private ModuleStatus() {
    }

    /** フックされていなければ false を返す。値を直接書き換えないこと。 */
    public static boolean isModuleActive() {
        return false;
    }
}
