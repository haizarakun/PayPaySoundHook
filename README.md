# PayPay Sound Hook

![version](https://img.shields.io/badge/version-1.00-blue)
![license](https://img.shields.io/badge/license-personal%20use%20only-orange)
![platform](https://img.shields.io/badge/platform-Android%20(root)-lightgrey)

PayPayアプリ(`jp.ne.paypay.android.app`)の通知音・決済完了音を、自分で選んだ音声ファイルに差し替えるLSPosedモジュールです。

- パッケージ名: `com.paypaysound.hook`
- バージョン: 1.00
- アプリ名・アイコンはビルド時に自由に変更できます(`app/src/main/res/mipmap-xxhdpi/`、`AndroidManifest.xml`)

> **本ツールはPayPay株式会社およびPayPay関連サービスとは一切関係のない、非公式の個人開発ツールです。**
> "PayPay"は各権利者の商標です。本ツールはリバースエンジニアリングによりリソース名を特定し、
> ローカル端末上でのみ動作するもので、PayPay本体のAPKやサーバー通信内容を一切改変・傍受しません。

## 機能

アプリを開いて音声ファイルを選ぶだけで、再ビルド不要で即座に反映されます。

| 対象 | PayPay側のリソース名 |
|---|---|
| 決済完了音 | `payment_complete_pay2_sound` / `payment_complete_pay2_settings_sound` |
| チャット受信音 | `incoming_message` |
| チャット送信音 | `outgoing_message` |

各項目に「▶ 再生」ボタンがあり、アプリ内でテスト再生できます(再生中は「■ 停止」に変わり、いつでも止められます)。

`incoming_message` / `outgoing_message` は送受金の通知専用ではなく、PayPay のチャットのメッセージ音そのものです。チャットを1通送っただけでも送信音が鳴ります。決済完了音だけ差し替えたい場合は、種類ごとに ON/OFF を切って元の音に戻せます。

ON/OFF はフラグではなく「差し替えファイルがあるか」で表しています。OFF にするとファイルを `<名前>.off` に退避するだけなので、選んだ音源は消えません。

### 対応フォーマット

拡張子ではなくファイルの中身(ヘッダ)から自動判別するため、Android標準のMediaPlayer/SoundPoolが対応している形式であれば基本的に使えます。

| 形式 | 対応 |
|---|---|
| MP3 | ○ |
| WAV(PCM) | ○ |
| OGG Vorbis | ○ |
| AAC / M4A | ○ |
| FLAC | ○(API21以上) |

## 動作要件

- root化端末(Magisk / KernelSU)
- LSPosed
- 対象端末で **PayPay自体にSuperuser権限を許可**しておくこと(Magiskアプリの「Superuser」タブから設定)

### 動作確認済み環境

| 項目 | バージョン等 |
|---|---|
| PayPay | 5.69.0（このバージョンで動作確認。リソース名が変わらない限り以降のバージョンでも動きます）|
| Android | 17(Pixel 10a、Magisk)/ Android 15系(Redmi 15 5G、KernelSU LKM) |
| minSdk | 27(Android 8.1)以上 |

リソースIDではなく`getResourceEntryName()`による名前一致でフックしているため、PayPay側のアップデートでID値が変わっても追従できる設計です。ただし**リソース名自体**(`payment_complete_pay2_sound`等)が変更された場合はさすがに追従できません。その場合はIssueで実際に検出されたリソース名を報告してもらえると対応しやすいです(モジュール側は`[PayPaySoundHook]`タグでLSPosedのログに実際のリソース名を出力する仕組みを持っています)。

### なぜPayPay自体にroot権限が必要か

Android 11以降のパッケージ可視性制限により、本モジュールの提供するデータをPayPayプロセスから通常の方法(ContentProvider / `forceQueryable`)で読み取ることができませんでした。この制限を回避するため、PayPay自身のプロセス内で`su`を呼び出し、本モジュールの内部ストレージにあるファイルを直接読み出す方式を採用しています(`su -mm`でマウント名前空間を維持し、SELinuxラベルの問題を回避するため事前に空ファイルを非root権限で作成してからrootで書き込む、という手順を踏んでいます)。

## セットアップ

1. `app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png`(前景)・`app/src/main/res/values/colors.xml`(背景色)はサンプルです。好きなデザインに差し替えてもかまいません
2. Android Studioでビルド(または `./gradlew assembleRelease`)
3. APKをインストール
4. LSPosed Managerでモジュールを有効化し、スコープに `jp.ne.paypay.android.app` を追加
5. Magiskアプリの「Superuser」タブで `jp.ne.paypay.android.app` にroot権限を許可
6. インストールしたアプリを開いて音声ファイルを選択
7. PayPayを完全終了 → 再起動 → 反映確認

### 署名について

このリポジトリには配布用の署名設定を置いていません。そのままビルドした APK はデバッグ鍵で署名されるため、配布には使えません(署名が違う APK は上書きインストールできません)。配布用にするときは、ビルドした APK を手元で `apksigner` で署名し直してください。

## 仕組み

`Resources#openRawResourceFd` / `openRawResource` をフックし、`getResourceEntryName()` で名前一致したリソースのみ差し替えます(リソースIDのハードコードを避けているため、PayPay側のアップデートでID値が変わっても壊れにくい設計です)。

差し替え用の音声はアプリ自身の内部ストレージ(`/data/user/0/com.paypaysound.hook/files/`)に保存され、PayPayプロセス側からは `su -mm` 経由でこのファイルを直接読み出し、PayPay自身のキャッシュディレクトリにコピーしてから再生に利用します。

## リンク

- GitHub: https://github.com/haizarakun/PayPaySoundHook
- 支援(Buy Me a Coffee): https://buymeacoffee.com/akunJP

## アップデート

起動時に GitHub の最新リリースを見に行き、タグ名が手元の versionName より新しければ、その場でダウンロードして入れ替える。root が使えるので、通常はユーザーの操作なしでインストールまで終わる。

| ファイル | 役割 |
| --- | --- |
| `update/UpdateInfo.java` | 最新リリースの JSON（タグ名・APK の URL・本文）を読む |
| `update/UpdateChecker.java` | リリース情報の取得と APK のダウンロード |
| `update/ApkInstaller.java` | 署名の照合、su での無操作インストール、失敗時は OS 標準インストーラ |
| `update/ForceUpdateGate.java` | 起動時の検知とダイアログ |

### 組み込み

AndroidManifest.xml に権限を追加する。

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

あとは MainActivity の `onCreate` の最後で呼ぶだけ。手動の「更新を確認」を置くなら `checkManually` を使う。

```java
ForceUpdateGate.checkOnStart(this);
```

### リリースの作り方

タグ名は versionName と一致させる（versionName が 1.01 ならタグは `v1.01`）。比較は区切りごとの数値で行うので、`1.10` は `1.9` より新しいと判定される。

1. versionCode と versionName を上げてリリースビルド
2. 手元で `apksigner` を使って配布用の鍵で署名し直す
3. GitHub で新しいリリースを作り、タグを `vX.XX` にする
4. APK を1つ添付する（ファイル名は自由。添付の中から最初の .apk を拾う）
5. リリース本文がそのまま更新内容としてダイアログに出る

アプリが見に行く先は次の固定 URL で、GitHub 側が最新リリースへ解決してくれる。リリースのたびにアプリを直す必要はない。

```
https://api.github.com/repos/haizarakun/PayPaySoundHook/releases/latest
```

### 挙動

新しいリリースが見つかった場合、ダイアログは閉じられず戻るキーも効かない。更新するまでアプリは使えない。

ダウンロードした APK は、インストール済みのものと署名が一致するかを確かめてからインストールする。一致しなければ破棄して再試行を促す。

起動時の自動チェックが通信に失敗したときは黙って見送る。電波が無いだけでアプリが使えなくなるのを避けるため。

入れ替えても LSPosed のスコープ設定は残るが、反映には PayPay 側の再起動が必要。

## 診断

アプリ内の診断画面に、動作条件と直近のログを1枚にまとめて出します。音が変わらないときは、まずここを上から確認してください。そのままコピーして報告に貼れます。

| 項目 | 内容 |
| --- | --- |
| 動作条件 | su が通るか、モジュールが実際にロードされているか、PayPay のバージョン |
| 差し替え音 | 種類ごとの ON/OFF、ファイルの大きさ、ヘッダから判定した形式 |
| 直近のログ | 更新チェックやインストールの経過 |

モジュールがロードされているかの判定を働かせるには、LSPosed のスコープに PayPay だけでなく本アプリ自身も入れておく必要があります。

ログは logcat に加えて、アプリの内部ストレージに直近 500 行だけ残します。端末やアカウントを特定できる情報は入れていません。

## ライセンス

個人利用のみを認める独自ライセンスです。オープンソースではありません。再配布・販売・改変版の公開は許可していません。詳しくは [LICENSE.md](LICENSE.md) を参照してください。

## 免責

個人の学習・カスタマイズ目的のツールです。PayPay本体のAPKは一切改変していません。PayPay株式会社とは無関係の非公式ツールです。利用は自己責任でお願いします。
