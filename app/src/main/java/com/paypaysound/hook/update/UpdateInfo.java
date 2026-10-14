package com.paypaysound.hook.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * GitHub の最新リリース（releases/latest）から取り出した更新情報。
 *
 * タグ名をそのままバージョン名として扱うので、リリースのタグは
 * versionName と一致させること（versionName 1.01 ならタグは v1.01）。
 */
public final class UpdateInfo {

    /** タグ名から先頭の v を外したもの。例: "1.01" */
    public final String versionName;

    /** リリースのタグ名そのもの。例: "v1.01" */
    public final String tagName;

    /** 添付されている APK のダウンロード URL。 */
    public final String apkUrl;

    /** リリース本文。更新内容としてダイアログに出す。 */
    public final String notes;

    private UpdateInfo(String versionName, String tagName, String apkUrl, String notes) {
        this.versionName = versionName;
        this.tagName = tagName;
        this.apkUrl = apkUrl;
        this.notes = notes;
    }

    public static UpdateInfo fromLatestReleaseJson(String json) throws JSONException {
        JSONObject release = new JSONObject(json);
        String tagName = release.getString("tag_name");
        String apkUrl = findApkUrl(release.optJSONArray("assets"));
        if (apkUrl == null) {
            throw new JSONException("リリースに APK が添付されていません: " + tagName);
        }
        return new UpdateInfo(stripLeadingV(tagName), tagName, apkUrl,
                release.optString("body", "").trim());
    }

    /** アセットの中から最初の .apk を探す。 */
    private static String findApkUrl(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            if (asset.optString("name", "").toLowerCase(Locale.US).endsWith(".apk")) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private static String stripLeadingV(String tagName) {
        if (tagName.startsWith("v") || tagName.startsWith("V")) {
            return tagName.substring(1);
        }
        return tagName;
    }

    /** インストール済みより新しいリリースか。 */
    public boolean isNewerThan(String installedVersionName) {
        return compareVersionNames(versionName, installedVersionName) > 0;
    }

    /**
     * バージョン名を区切りごとの数値で比べる。
     * 文字列比較だと "1.10" が "1.9" より古いと判定されてしまうため。
     */
    static int compareVersionNames(String left, String right) {
        String[] leftParts = left.split("[.]");
        String[] rightParts = right.split("[.]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = parseLeadingNumber(i < leftParts.length ? leftParts[i] : "0");
            int rightValue = parseLeadingNumber(i < rightParts.length ? rightParts[i] : "0");
            if (leftValue != rightValue) {
                return leftValue < rightValue ? -1 : 1;
            }
        }
        return 0;
    }

    /** "01-beta" のような紛れ込みがあっても先頭の数字だけ見る。 */
    private static int parseLeadingNumber(String part) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char character = part.charAt(i);
            if (character < '0' || character > '9') {
                break;
            }
            digits.append(character);
        }
        if (digits.length() == 0) {
            return 0;
        }
        return Integer.parseInt(digits.toString());
    }
}
