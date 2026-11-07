package com.formatforge.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class Exporter {

    public static final class Saved {
        public final String name;
        public final Uri uri;
        public final String mime;
        public final String folder;

        Saved(String name, Uri uri, String mime, String folder) {
            this.name = name;
            this.uri = uri;
            this.mime = mime;
            this.folder = folder;
        }
    }

    public static List<Saved> export(Context ctx, Output out) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        List<Saved> saved = new ArrayList<Saved>();
        String folder = Environment.DIRECTORY_DOWNLOADS + "/FormatForge";
        if (out.folder != null && !out.folder.isEmpty()) folder += "/" + out.folder;

        for (Output.Item item : out.items) {
            Uri uri = null;
            try {
                String rel = folder;
                String display = item.name;
                int slash = display.lastIndexOf('/');
                if (slash >= 0) {
                    String sub = display.substring(0, slash);
                    display = display.substring(slash + 1);
                    if (!sub.isEmpty()) rel = rel + "/" + sub;
                }
                if (display.trim().isEmpty()) display = "file";
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, display);
                cv.put(MediaStore.Downloads.MIME_TYPE, item.mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, rel);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("MediaStore отклонил файл");
                InputStream in = new FileInputStream(item.file);
                OutputStream os = cr.openOutputStream(uri);
                if (os == null) {
                    in.close();
                    throw new IOException("Не удалось открыть поток записи");
                }
                try {
                    Util.copy(in, os);
                } finally {
                    in.close();
                    os.close();
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                saved.add(new Saved(display, uri, item.mime, rel));
            } catch (Exception e) {
                if (uri != null) {
                    try { cr.delete(uri, null, null); } catch (Exception ignored) { }
                }
                throw new IOException("Не удалось сохранить «" + item.name + "»: " + e.getMessage());
            }
        }
        return saved;
    }

    public static String niceFolder(String folder) {
        if (folder == null) return "Downloads/FormatForge";
        if (folder.startsWith(Environment.DIRECTORY_DOWNLOADS)) {
            return "Downloads" + folder.substring(Environment.DIRECTORY_DOWNLOADS.length());
        }
        return folder;
    }

    private Exporter() {
    }
}
