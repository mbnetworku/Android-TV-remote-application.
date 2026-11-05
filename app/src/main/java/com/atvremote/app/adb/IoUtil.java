package com.atvremote.app.adb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** File and Base64 helpers that work on Android 5.0+ (java.nio.file and java.util.Base64 need Android 8). */
public final class IoUtil {

    private IoUtil() {
    }

    public static byte[] read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(32, f.length()));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xFF) << 16;
            if (i + 1 < d.length) b |= (d[i + 1] & 0xFF) << 8;
            if (i + 2 < d.length) b |= d[i + 2] & 0xFF;
            sb.append(B64[(b >> 18) & 63]).append(B64[(b >> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(b >> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[b & 63] : '=');
        }
        return sb.toString();
    }
}
