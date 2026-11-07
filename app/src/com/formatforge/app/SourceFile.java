package com.formatforge.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class SourceFile {

    public final Uri uri;
    public final String name;
    public final long size;
    public final String mime;
    public final String ext;
    public final Formats.Category category;
    public final String kind;
    public final File cache;

    private SourceFile(Uri uri, String name, long size, String mime, String ext,
                       Formats.Category category, String kind, File cache) {
        this.uri = uri;
        this.name = name;
        this.size = size;
        this.mime = mime;
        this.ext = ext;
        this.category = category;
        this.kind = kind;
        this.cache = cache;
    }

    public static SourceFile open(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        String name = null;
        long size = -1;
        String mime = cr.getType(uri);
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } finally {
            if (c != null) c.close();
        }
        if (name == null || name.trim().isEmpty()) name = "file";
        String ext = extensionOf(name);

        byte[] head = new byte[600];
        int read = 0;
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in != null) {
                int r;
                while (read < head.length && (r = in.read(head, read, head.length - read)) > 0) read += r;
            }
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }

        String kind = classify(cr, uri, ext, head, read, mime);
        Formats.Category cat;
        if ("docx".equals(kind) || "xlsx".equals(kind) || "pptx".equals(kind)
                || "odt".equals(kind) || "ods".equals(kind) || "odp".equals(kind)
                || "epub".equals(kind) || "fb2".equals(kind)) {
            cat = Formats.Category.DOCUMENT;
        } else if ("text".equals(kind)) {
            cat = Formats.Category.TEXT;
        } else if ("image".equals(kind)) {
            cat = Formats.Category.IMAGE;
        } else if ("audio".equals(kind)) {
            cat = Formats.Category.AUDIO;
        } else if ("video".equals(kind)) {
            cat = Formats.Category.VIDEO;
        } else if ("zip".equals(kind) || "gzip".equals(kind) || "tar".equals(kind) || "archive".equals(kind)) {
            cat = Formats.Category.ARCHIVE;
        } else {
            cat = Formats.Category.BINARY;
        }
        if ("zip".equals(kind)) kind = "zip";

        File cacheDir = new File(ctx.getCacheDir(), "input");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        String unique = Integer.toHexString(uri.toString().hashCode());
        File cache = new File(cacheDir, sanitize(name) + "-" + unique + ".bin");
        return new SourceFile(uri, name, size, mime, ext, cat, kind, cache);
    }

    public String categoryTitle() {
        String k = kindLabel();
        return category.title + (k.isEmpty() ? "" : " · " + k);
    }

    public String kindLabel() {
        switch (kind) {
            case "docx": return "Word";
            case "xlsx": return "Excel";
            case "pptx": return "PowerPoint";
            case "odt": return "OpenDocument Text";
            case "ods": return "OpenDocument Sheet";
            case "odp": return "OpenDocument Slides";
            case "epub": return "EPUB";
            case "fb2": return "FictionBook";
            case "zip": return "ZIP";
            case "gzip": return "GZIP";
            case "tar": return "TAR";
            case "text": return "текст";
            case "image": return "изображение";
            case "audio": return "аудио";
            case "video": return "видео";
            default: return ext.isEmpty() ? "файл" : ext.toUpperCase(Locale.US);
        }
    }

    public void ensureCached(Util.Progress progress) throws IOException {
        if (cache.exists() && cache.length() > 0) return;
        File part = new File(cache.getAbsolutePath() + ".part");
        if (part.exists()) part.delete();
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = Util.applicationContext().getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("Не удалось открыть файл");
            out = new FileOutputStream(part);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                done += r;
                if (size > 0 && progress != null) {
                    progress.onProgress((int) (done * 100 / size), "Чтение файла…");
                }
            }
            out.close();
            out = null;
            if (cache.exists()) cache.delete();
            if (!part.renameTo(cache)) throw new IOException("Не удалось подготовить файл");
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (part.exists() && (!cache.exists() || cache.length() == 0)) part.delete();
        }
    }

    public String safeBaseName() {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return sanitize(base);
    }

    public static String sanitize(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (s.isEmpty()) s = "file";
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    public static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String classify(ContentResolver cr, Uri uri, String ext, byte[] h, int n, String mime) {
        Formats.Category byExt = Formats.EXT_CATEGORY.get(ext);
        if (byExt != null) {
            switch (ext) {
                case "docx": case "docm": case "dotx": return "docx";
                case "xlsx": case "xlsm": case "ods": case "ots": return ext.startsWith("x") ? "xlsx" : "ods";
                case "pptx": case "pptm": case "odp": case "otp": return ext.startsWith("p") ? "pptx" : "odp";
                case "odt": case "ott": return "odt";
                case "epub": return "epub";
                case "fb2": return "fb2";
                case "zip": case "jar": case "apk": case "war": case "ear": case "ipa": case "aab": return "zip";
                case "gz": case "tgz": return "gzip";
                case "tar": return "tar";
                case "7z": case "rar": case "bz2": case "xz": case "zst": return "archive";
            }
            switch (byExt) {
                case IMAGE: return "image";
                case AUDIO: return "audio";
                case VIDEO: return "video";
                case TEXT: return "text";
                case DOCUMENT: return "docx";
                case ARCHIVE: return "archive";
                default: return "binary";
            }
        }
        if (mime != null) {
            if (mime.startsWith("image/")) return "image";
            if (mime.startsWith("audio/")) return "audio";
            if (mime.startsWith("video/")) return "video";
            if (mime.startsWith("text/")) return "text";
        }
        if (n >= 4 && h[0] == 'P' && h[1] == 'K') {
            String z = zipKind(cr, uri);
            if (z != null) return z;
            return "zip";
        }
        if (n >= 2 && (h[0] & 0xFF) == 0x1F && (h[1] & 0xFF) == 0x8B) return "gzip";
        if (n >= 262 && h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r') return "tar";
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "image";
        if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "image";
        if (n >= 3 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "image";
        if (n >= 2 && h[0] == 'B' && h[1] == 'M') return "image";
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') {
            if (h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') return "audio";
            if (h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "image";
            if (h[8] == 'A' && h[9] == 'V' && h[10] == 'I') return "video";
        }
        if (n >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return "audio";
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xFB) return "audio";
        if (n >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return "audio";
        if (n >= 4 && h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return "audio";
        if (n >= 4 && h[0] == 0x1A && h[1] == 0x45 && h[2] == 0xDF && h[3] == 0xA3) return "video";
        if (looksText(h, n)) return "text";
        return "binary";
    }

    private static String zipKind(ContentResolver cr, Uri uri) {
        ZipInputStream zin = null;
        try {
            InputStream in = cr.openInputStream(uri);
            if (in == null) return null;
            zin = new ZipInputStream(in);
            ZipEntry e;
            int guard = 0;
            boolean mimetype = false;
            while ((e = zin.getNextEntry()) != null && guard++ < 200) {
                String name = e.getName();
                if (name.equals("word/document.xml")) return "docx";
                if (name.equals("xl/workbook.xml")) return "xlsx";
                if (name.equals("ppt/presentation.xml")) return "pptx";
                if (name.equals("content.xml")) return "odt";
                if (name.equals("META-INF/container.xml")) return "epub";
                if (name.equals("mimetype")) mimetype = true;
            }
            if (mimetype) return "odt";
        } catch (Exception ignored) {
        } finally {
            if (zin != null) try { zin.close(); } catch (IOException ignored) { }
        }
        return null;
    }

    private static boolean looksText(byte[] h, int n) {
        if (n <= 0) return false;
        int ctrl = 0;
        int i = 0;
        if (n >= 3 && (h[0] & 0xFF) == 0xEF && (h[1] & 0xFF) == 0xBB && (h[2] & 0xFF) == 0xBF) i = 3;
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xFE) return true;
        if (n >= 2 && (h[0] & 0xFF) == 0xFE && (h[1] & 0xFF) == 0xFF) return true;
        for (; i < n; i++) {
            int b = h[i] & 0xFF;
            if (b == 0) return false;
            if (b < 9 || (b > 13 && b < 32)) ctrl++;
        }
        return ctrl * 100 / Math.max(1, n) < 5;
    }
}
