package com.formatforge.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Locale;

public final class Util {

    private static Context appContext;

    public interface Progress {
        void onProgress(int percent, String message);
        boolean isCancelled();
    }

    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static Context applicationContext() {
        return appContext;
    }

    public static File outputDir() {
        File d = new File(appContext.getCacheDir(), "output");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File newTempFile(String name) {
        File f = new File(outputDir(), name);
        if (f.exists()) f.delete();
        return f;
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " Б";
        double v = bytes / 1024.0;
        String[] units = {"КБ", "МБ", "ГБ", "ТБ"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024.0;
            u++;
        }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, units[u]);
    }

    public static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in, f.length());
        } finally {
            in.close();
        }
    }

    public static byte[] readAll(InputStream in, long hint) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(hint > 0 && hint < Integer.MAX_VALUE ? (int) hint : 8192);
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    public static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    public static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return Charset.forName("UTF-8");
        }
    }

    public static boolean hasBom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }

    public static String decodeText(byte[] data, String requested) {
        try {
            if ("auto".equals(requested) || requested == null) {
                if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
                    return new String(data, 3, data.length - 3, Charset.forName("UTF-8"));
                }
                if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
                    return new String(data, 2, data.length - 2, Charset.forName("UTF-16LE"));
                }
                if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
                    return new String(data, 2, data.length - 2, Charset.forName("UTF-16BE"));
                }
                String utf8 = new String(data, Charset.forName("UTF-8"));
                if (!utf8.contains("\uFFFD")) return utf8;
                return new String(data, Charset.forName("windows-1251"));
            }
            String s = new String(data, charset(requested));
            if (s.startsWith("\uFEFF")) s = s.substring(1);
            return s;
        } catch (Exception e) {
            return new String(data);
        }
    }

    public static byte[] encodeText(String text, String encoding, String eol, boolean bom) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (eol != null && !"\n".equals(eol)) t = t.replace("\n", eol);
        Charset cs = "auto".equals(encoding) || encoding == null ? Charset.forName("UTF-8") : charset(encoding);
        byte[] body = t.getBytes(cs);
        if (!bom) return body;
        if (cs.name().equals("UTF-8")) {
            byte[] out = new byte[body.length + 3];
            out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
            System.arraycopy(body, 0, out, 3, body.length);
            return out;
        }
        if (cs.name().equals("UTF-16LE")) {
            byte[] out = new byte[body.length + 2];
            out[0] = (byte) 0xFF; out[1] = (byte) 0xFE;
            System.arraycopy(body, 0, out, 2, body.length);
            return out;
        }
        if (cs.name().equals("UTF-16BE")) {
            byte[] out = new byte[body.length + 2];
            out[0] = (byte) 0xFE; out[1] = (byte) 0xFF;
            System.arraycopy(body, 0, out, 2, body.length);
            return out;
        }
        return body;
    }

    public static String stripHtml(String html) {
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote|pre)>", "\n");
        s = s.replaceAll("(?i)<li[^>]*>", "• ");
        s = s.replaceAll("(?i)</t[dh]>", "\t");
        s = s.replaceAll("<[^>]+>", "");
        s = unescapeEntities(s);
        s = s.replace("\u00A0", " ");
        s = s.replaceAll("[ \\t]+\\n", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    public static String unescapeEntities(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replaceAll("&#(\\d+);", " ")
                .replaceAll("&#x([0-9a-fA-F]+);", " ");
    }

    public static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Util() {
    }
}
