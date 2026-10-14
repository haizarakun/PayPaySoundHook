package com.paypaysound.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.paypaysound.hook.diag.DiagnosticsLog;
import com.paypaysound.hook.sound.SoundSlot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 選んだ音声ファイルを、フックから読める場所（本アプリの内部ストレージ）に取り込む。
 */
public final class SoundStore {

    private static final int BUFFER_SIZE = 16 * 1024;

    private SoundStore() {
    }

    /**
     * 選んだファイルを取り込み、その枠を ON にする。
     *
     * @return 取り込めたら true
     */
    public static boolean save(Context context, SoundSlot slot, Uri source) {
        File destination = slot.file(context);
        InputStream input = null;
        OutputStream output = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            input = resolver.openInputStream(source);
            if (input == null) {
                DiagnosticsLog.w(slot.label() + ": 選んだファイルを開けませんでした");
                return false;
            }
            output = new FileOutputStream(destination);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            DiagnosticsLog.i(slot.label() + ": 音源を取り込みました (" + destination.length() + " バイト)");
            // 取り込んだら ON にしておく。
            slot.setEnabled(context, true);
            return true;
        } catch (Exception error) {
            DiagnosticsLog.w(slot.label() + ": 音源の取り込みに失敗しました", error);
            destination.delete();
            return false;
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
    }

    private static void closeQuietly(Object stream) {
        if (stream == null) {
            return;
        }
        try {
            if (stream instanceof InputStream) {
                ((InputStream) stream).close();
            } else if (stream instanceof OutputStream) {
                ((OutputStream) stream).close();
            }
        } catch (Exception ignored) {
            // 閉じられなくても報告することがない。
        }
    }
}
