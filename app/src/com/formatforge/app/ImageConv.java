package com.formatforge.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

public final class ImageConv {

    public static Output convert(SourceFile src, Formats.FormatSpec target,
                                 Map<String, Object> s, Util.Progress p) throws Exception {
        Bitmap bmp = load(src, s, p);
        check(p);
        step(p, 55, "Кодирование…");

        String ext = target.ext;
        String base = src.safeBaseName();
        File out = Util.newTempFile(base + "-ff." + ext);
        Output result = new Output();
        result.add(out, base + "." + ext, target.mime);

        if (ImageCodec.encode(bmp, target.id, out, s)) {
            // handled by ImageCodec
        } else if ("bmp".equals(target.id)) {
            BmpWriter.write(flatten(bmp, Color.WHITE), out);
        } else {
            if ("jpg".equals(target.id) || "jpeg".equals(target.id)) {
                bmp = flatten(bmp, bgColor(str(s, "bg", "white")));
            }
            int quality = num(s, "quality", 90);
            Bitmap.CompressFormat fmt;
            switch (target.id) {
                case "png":
                    fmt = Bitmap.CompressFormat.PNG;
                    quality = 100;
                    break;
                case "webpl":
                    fmt = Bitmap.CompressFormat.WEBP;
                    quality = 100;
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            fmt = Bitmap.CompressFormat.valueOf("WEBP_LOSSLESS");
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    break;
                case "webp":
                    fmt = Bitmap.CompressFormat.WEBP;
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            fmt = Bitmap.CompressFormat.valueOf("WEBP_LOSSY");
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    break;
                default:
                    fmt = Bitmap.CompressFormat.JPEG;
                    break;
            }
            FileOutputStream fos = new FileOutputStream(out);
            try {
                if (!bmp.compress(fmt, quality, fos)) throw new IOException("Ошибка кодирования");
            } finally {
                fos.close();
            }
        }
        step(p, 100, "Готово");
        return result;
    }

    static Bitmap load(SourceFile src, Map<String, Object> s, Util.Progress p) throws Exception {
        int maxDim = num(s, "maxDim", 0);
        int rotation = num(s, "rotate", -1);
        Bitmap bmp;
        try {
            if (ImageCodec.isCustomSource(src.ext)) {
                bmp = ImageCodec.decode(src.cache, src.ext);
            } else {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(src.cache.getAbsolutePath(), bounds);
                int w = bounds.outWidth;
                int h = bounds.outHeight;
                if (w <= 0 || h <= 0) throw new IOException("Не удалось прочитать изображение");
                int sample = 1;
                if (maxDim > 0) {
                    while (Math.max(w, h) / (sample * 2) >= maxDim) sample *= 2;
                }
                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = sample;
                opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bmp = BitmapFactory.decodeFile(src.cache.getAbsolutePath(), opt);
            }
        } catch (OutOfMemoryError e) {
            throw new IOException("Недостаточно памяти для декодирования изображения");
        }
        if (bmp == null) throw new IOException("Формат изображения не поддерживается устройством");
        check(p);
        step(p, 12, "Декодирование…");

        int degrees = rotation;
        if (degrees < 0) degrees = exifRotation(src.cache);
        if (degrees % 360 != 0) bmp = rotate(bmp, degrees % 360);
        check(p);

        boolean gray = bool(s, "grayscale", false);
        if (gray) bmp = grayscale(bmp);
        check(p);
        step(p, 35, "Обработка…");

        String fit = str(s, "fit", "inside");
        if (maxDim > 0) {
            if ("square".equals(fit)) {
                bmp = square(bmp, maxDim);
            } else if ("stretch".equals(fit)) {
                bmp = Bitmap.createScaledBitmap(bmp, maxDim, maxDim, true);
            } else {
                int big = Math.max(bmp.getWidth(), bmp.getHeight());
                if (big > maxDim) {
                    float sc = maxDim / (float) big;
                    int nw = Math.max(1, Math.round(bmp.getWidth() * sc));
                    int nh = Math.max(1, Math.round(bmp.getHeight() * sc));
                    bmp = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                }
            }
        }
        check(p);
        return bmp;
    }

    private static void check(Util.Progress p) throws InterruptedException {
        if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
    }

    private static void step(Util.Progress p, int v, String m) {
        if (p != null) p.onProgress(v, m);
    }

    private static Bitmap rotate(Bitmap b, int deg) {
        Matrix m = new Matrix();
        m.postRotate(deg);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private static Bitmap grayscale(Bitmap b) {
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(b, 0, 0, paint);
        b.recycle();
        return out;
    }

    static Bitmap flatten(Bitmap b, int color) {
        if (!b.hasAlpha()) return b;
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(color);
        c.drawBitmap(b, 0, 0, null);
        b.recycle();
        return out;
    }

    private static Bitmap square(Bitmap b, int size) {
        int w = b.getWidth(), h = b.getHeight();
        float scale = Math.max(size / (float) w, size / (float) h);
        int sw = Math.round(w * scale), sh = Math.round(h * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(b, sw, sh, true);
        int left = Math.max(0, (sw - size) / 2);
        int top = Math.max(0, (sh - size) / 2);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(scaled, -left, -top, null);
        if (scaled != b) scaled.recycle();
        b.recycle();
        return out;
    }

    private static int bgColor(String v) {
        if ("black".equals(v)) return Color.BLACK;
        if ("gray".equals(v)) return Color.GRAY;
        return Color.WHITE;
    }

    private static int exifRotation(File f) {
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    static int num(Map<String, Object> s, String k, int def) {
        Object v = s.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    static String str(Map<String, Object> s, String k, String def) {
        Object v = s.get(k);
        return v == null ? def : String.valueOf(v);
    }

    static boolean bool(Map<String, Object> s, String k, boolean def) {
        Object v = s.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    static double dbl(Map<String, Object> s, String k, double def) {
        Object v = s.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim().replace(',', '.'));
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    static final class BmpWriter {

        static void write(Bitmap b, File out) throws IOException {
            int w = b.getWidth();
            int h = b.getHeight();
            int rowSize = (w * 3 + 3) & ~3;
            int dataSize = rowSize * h;
            int fileSize = 54 + dataSize;
            OutputStream os = new FileOutputStream(out);
            try {
                os.write('B');
                os.write('M');
                le32(os, fileSize);
                le32(os, 0);
                le32(os, 54);
                le32(os, 40);
                le32(os, w);
                le32(os, h);
                le16(os, 1);
                le16(os, 24);
                le32(os, 0);
                le32(os, dataSize);
                le32(os, 2835);
                le32(os, 2835);
                le32(os, 0);
                le32(os, 0);
                int[] row = new int[w];
                byte[] pad = new byte[rowSize - w * 3];
                for (int y = h - 1; y >= 0; y--) {
                    b.getPixels(row, 0, w, 0, y, w, 1);
                    for (int x = 0; x < w; x++) {
                        int px = row[x];
                        os.write(px & 0xFF);
                        os.write((px >> 8) & 0xFF);
                        os.write((px >> 16) & 0xFF);
                    }
                    if (pad.length > 0) os.write(pad);
                }
            } finally {
                os.close();
            }
        }

        private static void le16(OutputStream os, int v) throws IOException {
            os.write(v & 0xFF);
            os.write((v >> 8) & 0xFF);
        }

        private static void le32(OutputStream os, int v) throws IOException {
            os.write(v & 0xFF);
            os.write((v >> 8) & 0xFF);
            os.write((v >> 16) & 0xFF);
            os.write((v >> 24) & 0xFF);
        }
    }

    private ImageConv() {
    }
}
