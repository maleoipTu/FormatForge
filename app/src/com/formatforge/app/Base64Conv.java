package com.formatforge.app;

import android.util.Base64;

import java.io.File;
import java.util.Map;

public final class Base64Conv {

    public static Output convert(SourceFile src, Formats.FormatSpec target,
                                 Map<String, Object> s, Util.Progress p) throws Exception {
        String base = src.safeBaseName();
        Output out = new Output();
        if (p != null) p.onProgress(20, "Чтение…");

        if ("b64".equals(target.id)) {
            byte[] data = Util.readAll(src.cache);
            String enc = Base64.encodeToString(data, Base64.NO_WRAP);
            int wrap = ImageConv.num(s, "wrap", 76);
            if (wrap > 0) enc = wrapLines(enc, wrap);
            File f = Util.newTempFile(base + "-b64.txt");
            Util.writeAll(f, enc.getBytes("UTF-8"));
            out.add(f, base + ".base64.txt", "text/plain");
            out.summary = Util.humanSize(data.length) + " → " + Util.humanSize(f.length());
            if (p != null) p.onProgress(100, "Готово");
            return out;
        }

        byte[] raw = Util.readAll(src.cache);
        String text = new String(raw, "UTF-8").replaceAll("\\s+", "");
        byte[] data;
        try {
            data = Base64.decode(text, Base64.DEFAULT);
        } catch (Exception e) {
            throw new Exception("Файл не является корректным Base64");
        }
        String ext = ImageConv.str(s, "ext", "bin").toLowerCase().replaceAll("[^a-z0-9]", "");
        if (ext.isEmpty()) ext = "bin";
        String stem = base;
        if (stem.toLowerCase().endsWith("-base64")) stem = stem.substring(0, stem.length() - 7);
        if (stem.toLowerCase().endsWith(".base64")) stem = stem.substring(0, stem.length() - 7);
        if (stem.toLowerCase().endsWith("_base64")) stem = stem.substring(0, stem.length() - 7);
        String name = stem + "." + ext;
        File f = Util.newTempFile(stem + "-dec." + ext);
        Util.writeAll(f, data);
        out.add(f, name, Formats.mimeForExt(ext));
        out.summary = Util.humanSize(raw.length) + " → " + Util.humanSize(data.length);
        if (p != null) p.onProgress(100, "Готово");
        return out;
    }

    private static String wrapLines(String s, int width) {
        StringBuilder sb = new StringBuilder(s.length() + s.length() / width + 4);
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(s.length(), i + width);
            sb.append(s, i, end).append('\n');
            i = end;
        }
        return sb.toString();
    }

    private Base64Conv() {
    }
}
