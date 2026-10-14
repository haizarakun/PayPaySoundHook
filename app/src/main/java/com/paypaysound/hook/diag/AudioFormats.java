package com.paypaysound.hook.diag;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 音声ファイルの形式を、拡張子ではなく先頭のバイト列から見分ける。
 *
 * 差し替えた音が鳴らないときに「そもそも何のファイルを置いたのか」を
 * 診断レポートに出すために使う。
 */
public final class AudioFormats {

    private static final int HEADER_SIZE = 12;

    private AudioFormats() {
    }

    /**
     * @return "MP3" "WAV" "OGG" "M4A/AAC" "FLAC" のいずれか。見分けられなければ "不明"
     */
    public static String detect(File file) {
        byte[] header = readHeader(file);
        if (header == null) {
            return "不明";
        }
        if (startsWith(header, "fLaC")) {
            return "FLAC";
        }
        if (startsWith(header, "OggS")) {
            return "OGG";
        }
        if (startsWith(header, "RIFF") && matchesAt(header, 8, "WAVE")) {
            return "WAV";
        }
        if (matchesAt(header, 4, "ftyp")) {
            return "M4A/AAC";
        }
        if (startsWith(header, "ID3")) {
            return "MP3";
        }
        // ID3 タグの無い MP3 はフレーム同期ワードで始まる。
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0) {
            return "MP3";
        }
        return "不明";
    }

    private static byte[] readHeader(File file) {
        if (file == null || !file.isFile() || file.length() < HEADER_SIZE) {
            return null;
        }
        InputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] header = new byte[HEADER_SIZE];
            int read = input.read(header);
            return read == HEADER_SIZE ? header : null;
        } catch (Exception error) {
            return null;
        } finally {
            closeQuietly(input);
        }
    }

    private static boolean startsWith(byte[] header, String text) {
        return matchesAt(header, 0, text);
    }

    private static boolean matchesAt(byte[] header, int offset, String text) {
        if (header.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (header[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (Exception ignored) {
            // 閉じられなくても報告することがない。
        }
    }
}
