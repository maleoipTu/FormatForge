package com.formatforge.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ImageCodec {

    private ImageCodec() {
    }

    public static boolean isCustomSource(String ext) {
        switch (ext) {
            case "ico": case "tga": case "icb": case "vda": case "vst":
            case "ppm": case "pgm": case "pbm": case "pnm":
            case "qoi": case "wbmp": case "tif": case "tiff":
                return true;
            default:
                return false;
        }
    }

    public static Bitmap decode(File f, String ext) throws IOException {
        byte[] data = readAll(f, 256 * 1024 * 1024);
        switch (ext) {
            case "ico": return decodeIco(data);
            case "tga": case "icb": case "vda": case "vst": return decodeTga(data);
            case "ppm": case "pgm": case "pbm": case "pnm": return decodePnm(data);
            case "qoi": return decodeQoi(data);
            case "wbmp": return decodeWbmp(data);
            case "tif": case "tiff": return decodeTiff(data);
            default: throw new IOException("Неизвестный формат изображения");
        }
    }

    public static boolean encode(Bitmap b, String id, File out, Map<String, Object> s) throws IOException {
        switch (id) {
            case "gif":
                writeGif(flatten(b, Color.WHITE), out, ImageConv.num(s, "colors", 256));
                return true;
            case "tiff":
                writeTiff(b, out);
                return true;
            case "ico":
                writeIco(b, out, ImageConv.num(s, "size", 256));
                return true;
            case "tga":
                writeTga(b, out);
                return true;
            case "ppm":
                writePnm(b, out, 6);
                return true;
            case "pgm":
                writePnm(b, out, 5);
                return true;
            case "pbm":
                writePnm(b, out, 4);
                return true;
            case "qoi":
                writeQoi(b, out);
                return true;
            case "wbmp":
                writeWbmp(b, out);
                return true;
            default:
                return false;
        }
    }

    private static byte[] readAll(File f, int limit) throws IOException {
        long len = f.length();
        if (len <= 0 || len > limit) throw new IOException("Файл слишком большой");
        byte[] data = new byte[(int) len];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < data.length) {
                int r = in.read(data, off, data.length - off);
                if (r < 0) break;
                off += r;
            }
            if (off != data.length) throw new IOException("Не удалось прочитать файл");
        } finally {
            in.close();
        }
        return data;
    }

    // ---------------- ICO ----------------

    private static Bitmap decodeIco(byte[] d) throws IOException {
        if (d.length < 6 || d[0] != 0 || d[1] != 0 || d[2] != 1 || d[3] != 0) {
            throw new IOException("Повреждённый ICO");
        }
        int count = u16(d, 4);
        if (count <= 0) throw new IOException("Пустой ICO");
        int best = -1;
        int bestArea = -1;
        for (int i = 0; i < count; i++) {
            int o = 6 + i * 16;
            if (o + 16 > d.length) break;
            int w = d[o] & 0xFF;
            int h = d[o + 1] & 0xFF;
            if (w == 0) w = 256;
            if (h == 0) h = 256;
            int area = w * h;
            if (area > bestArea) {
                bestArea = area;
                best = o;
            }
        }
        if (best < 0) throw new IOException("Повреждённый ICO");
        int size = le32(d, best + 8);
        int off = le32(d, best + 12);
        if (off < 0 || size <= 0 || off + size > d.length) throw new IOException("Повреждённый ICO");
        if (size >= 8 && (d[off] & 0xFF) == 0x89 && d[off + 1] == 'P'
                && d[off + 2] == 'N' && d[off + 3] == 'G') {
            Bitmap b = BitmapFactory.decodeByteArray(d, off, size);
            if (b == null) throw new IOException("Не удалось декодировать ICO");
            return b;
        }
        return decodeDib(d, off, size);
    }

    private static Bitmap decodeDib(byte[] d, int off, int size) throws IOException {
        if (size < 40) throw new IOException("Повреждённый ICO");
        int hs = le32(d, off);
        int w = le32(d, off + 4);
        int h = le32(d, off + 8) / 2;
        int bpp = u16(d, off + 14);
        int comp = le32(d, off + 16);
        if (hs < 40 || w <= 0 || h <= 0 || w > 8192 || h > 8192 || comp != 0) {
            throw new IOException("ICO: неподдерживаемый DIB");
        }
        int palette = 0;
        if (bpp <= 8) palette = (1 << bpp) * 4;
        int pixelOff = off + hs + palette;
        int rowSize = ((w * bpp + 31) / 32) * 4;
        if (pixelOff + rowSize * h > d.length) throw new IOException("Повреждённый ICO");
        int[] px = new int[w * h];
        boolean anyAlpha = false;
        for (int y = 0; y < h; y++) {
            int row = pixelOff + (h - 1 - y) * rowSize;
            for (int x = 0; x < w; x++) {
                int b, g, r, a = 255;
                if (bpp == 32) {
                    int p = row + x * 4;
                    b = d[p] & 0xFF; g = d[p + 1] & 0xFF; r = d[p + 2] & 0xFF; a = d[p + 3] & 0xFF;
                    if (a != 0) anyAlpha = true;
                } else if (bpp == 24) {
                    int p = row + x * 3;
                    b = d[p] & 0xFF; g = d[p + 1] & 0xFF; r = d[p + 2] & 0xFF;
                } else if (bpp == 8 || bpp == 4 || bpp == 1) {
                    int idx;
                    if (bpp == 8) idx = d[row + x] & 0xFF;
                    else if (bpp == 4) {
                        int v = d[row + (x >> 1)] & 0xFF;
                        idx = (x & 1) == 0 ? (v >> 4) : (v & 0x0F);
                    } else {
                        int v = d[row + (x >> 3)] & 0xFF;
                        idx = (v >> (7 - (x & 7))) & 1;
                    }
                    int p = off + hs + idx * 4;
                    if (p + 3 >= d.length) { b = g = r = 0; }
                    else { b = d[p] & 0xFF; g = d[p + 1] & 0xFF; r = d[p + 2] & 0xFF; }
                } else {
                    throw new IOException("ICO: неподдерживаемая глубина цвета");
                }
                px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        if (bpp == 32 && !anyAlpha) {
            for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    // ---------------- TGA ----------------

    private static Bitmap decodeTga(byte[] d) throws IOException {
        if (d.length < 18) throw new IOException("Повреждённый TGA");
        int idLen = d[0] & 0xFF;
        int cmType = d[1] & 0xFF;
        int type = d[2] & 0xFF;
        int w = u16(d, 12);
        int h = u16(d, 14);
        int bpp = d[16] & 0xFF;
        int desc = d[17] & 0xFF;
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || cmType != 0) {
            throw new IOException("TGA: неподдерживаемый формат");
        }
        boolean gray = type == 3 || type == 11;
        boolean rle = type == 10 || type == 11;
        if (!(type == 2 || type == 3 || type == 10 || type == 11)) {
            throw new IOException("TGA: неподдерживаемый тип");
        }
        if ((gray && bpp != 8) || (!gray && bpp != 24 && bpp != 32)) {
            throw new IOException("TGA: неподдерживаемая глубина цвета");
        }
        int bppBytes = bpp / 8;
        int count = w * h;
        byte[] raw = new byte[count * bppBytes];
        int pos = 18 + idLen;
        int out = 0;
        if (rle) {
            while (out < raw.length && pos < d.length) {
                int packet = d[pos++] & 0xFF;
                int n = (packet & 0x7F) + 1;
                if ((packet & 0x80) != 0) {
                    if (pos + bppBytes > d.length) break;
                    for (int i = 0; i < n && out < raw.length; i++) {
                        System.arraycopy(d, pos, raw, out, bppBytes);
                        out += bppBytes;
                    }
                    pos += bppBytes;
                } else {
                    int bytes = n * bppBytes;
                    if (pos + bytes > d.length) bytes = d.length - pos;
                    System.arraycopy(d, pos, raw, out, bytes);
                    out += bytes;
                    pos += n * bppBytes;
                }
            }
        } else {
            int bytes = Math.min(raw.length, d.length - pos);
            System.arraycopy(d, pos, raw, 0, bytes);
            out = bytes;
        }
        if (out < raw.length) throw new IOException("TGA: нехватка данных");
        int[] px = new int[count];
        boolean top = (desc & 0x20) != 0;
        for (int y = 0; y < h; y++) {
            int sy = top ? y : (h - 1 - y);
            for (int x = 0; x < w; x++) {
                int p = (sy * w + x) * bppBytes;
                int b, g, r, a = 255;
                if (gray) {
                    b = g = r = raw[p] & 0xFF;
                } else {
                    b = raw[p] & 0xFF; g = raw[p + 1] & 0xFF; r = raw[p + 2] & 0xFF;
                    if (bppBytes == 4) a = raw[p + 3] & 0xFF;
                }
                px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    // ---------------- PNM ----------------

    private static final class PnmReader {
        final byte[] d;
        int p;

        PnmReader(byte[] d) {
            this.d = d;
        }

        String token() throws IOException {
            while (p < d.length) {
                int c = d[p] & 0xFF;
                if (c == '#') {
                    while (p < d.length && d[p] != '\n' && d[p] != '\r') p++;
                } else if (c <= 32) {
                    p++;
                } else {
                    break;
                }
            }
            int start = p;
            while (p < d.length && (d[p] & 0xFF) > 32) p++;
            if (start == p) throw new IOException("PNM: неожиданный конец файла");
            return new String(d, start, p - start, java.nio.charset.StandardCharsets.US_ASCII);
        }

        int number() throws IOException {
            try {
                return Integer.parseInt(token());
            } catch (NumberFormatException e) {
                throw new IOException("PNM: некорректное число");
            }
        }

        void consumeSpace() {
            if (p < d.length && d[p] == '\r' && p + 1 < d.length && d[p + 1] == '\n') p += 2;
            else if (p < d.length && (d[p] & 0xFF) <= 32) p++;
        }
    }

    private static Bitmap decodePnm(byte[] d) throws IOException {
        PnmReader r = new PnmReader(d);
        String magic = r.token();
        if (!magic.startsWith("P") || magic.length() != 2) throw new IOException("Повреждённый PNM");
        int kind = magic.charAt(1) - '0';
        if (kind < 1 || kind > 6) throw new IOException("PNM: неподдерживаемый тип");
        int w = r.number();
        int h = r.number();
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || (long) w * h > 64_000_000L) {
            throw new IOException("PNM: слишком большой размер");
        }
        int max = kind == 1 || kind == 4 ? 1 : r.number();
        if (max <= 0) throw new IOException("PNM: некорректный максимум");
        int[] px = new int[w * h];
        if (kind == 1 || kind == 2 || kind == 3) {
            for (int i = 0; i < px.length; i++) {
                if (kind == 1) {
                    int v = r.number();
                    int g = v == 0 ? 255 : 0;
                    px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
                } else if (kind == 2) {
                    int g = scale(r.number(), max);
                    px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
                } else {
                    int rr = scale(r.number(), max);
                    int gg = scale(r.number(), max);
                    int bb = scale(r.number(), max);
                    px[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                }
            }
        } else {
            r.consumeSpace();
            int p = r.p;
            if (kind == 4) {
                int rowBytes = (w + 7) / 8;
                if (p + rowBytes * h > d.length) throw new IOException("PNM: нехватка данных");
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int bit = (d[p + y * rowBytes + (x >> 3)] >> (7 - (x & 7))) & 1;
                        int g = bit == 0 ? 255 : 0;
                        px[y * w + x] = 0xFF000000 | (g << 16) | (g << 8) | g;
                    }
                }
            } else if (kind == 5) {
                int bytes = max > 255 ? 2 : 1;
                if (p + (long) bytes * w * h > d.length) throw new IOException("PNM: нехватка данных");
                for (int i = 0; i < px.length; i++) {
                    int v = bytes == 1 ? (d[p + i] & 0xFF) : ((d[p + i * 2] & 0xFF) << 8 | (d[p + i * 2 + 1] & 0xFF));
                    int g = scale(v, max);
                    px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
                }
            } else {
                int bytes = max > 255 ? 2 : 1;
                if (p + (long) bytes * 3 * w * h > d.length) throw new IOException("PNM: нехватка данных");
                for (int i = 0; i < px.length; i++) {
                    int q = p + i * 3 * bytes;
                    int rr, gg, bb;
                    if (bytes == 1) {
                        rr = d[q] & 0xFF; gg = d[q + 1] & 0xFF; bb = d[q + 2] & 0xFF;
                    } else {
                        rr = ((d[q] & 0xFF) << 8) | (d[q + 1] & 0xFF);
                        gg = ((d[q + 2] & 0xFF) << 8) | (d[q + 3] & 0xFF);
                        bb = ((d[q + 4] & 0xFF) << 8) | (d[q + 5] & 0xFF);
                    }
                    px[i] = 0xFF000000 | (scale(rr, max) << 16) | (scale(gg, max) << 8) | scale(bb, max);
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int scale(int v, int max) {
        if (max == 255) return v;
        int x = v * 255 / max;
        return x < 0 ? 0 : (x > 255 ? 255 : x);
    }

    // ---------------- QOI ----------------

    private static Bitmap decodeQoi(byte[] d) throws IOException {
        if (d.length < 14 || d[0] != 'q' || d[1] != 'o' || d[2] != 'i' || d[3] != 'f') {
            throw new IOException("Повреждённый QOI");
        }
        int w = be32(d, 4);
        int h = be32(d, 8);
        int channels = d[12] & 0xFF;
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || (long) w * h > 64_000_000L
                || (channels != 3 && channels != 4)) {
            throw new IOException("QOI: неподдерживаемый файл");
        }
        int[] index = new int[64];
        int r = 0, g = 0, b = 0, a = 255;
        int px = 0;
        int p = 14;
        int total = w * h;
        int[] out = new int[total];
        while (px < total && p < d.length) {
            int op = d[p++] & 0xFF;
            if (op == 0xFE) {
                if (p + 3 > d.length) break;
                r = d[p++] & 0xFF; g = d[p++] & 0xFF; b = d[p++] & 0xFF;
            } else if (op == 0xFF) {
                if (p + 4 > d.length) break;
                r = d[p++] & 0xFF; g = d[p++] & 0xFF; b = d[p++] & 0xFF; a = d[p++] & 0xFF;
            } else if (op < 0x40) {
                int v = index[op];
                a = (v >>> 24) & 0xFF; r = (v >> 16) & 0xFF; g = (v >> 8) & 0xFF; b = v & 0xFF;
            } else if (op < 0x80) {
                r = (r + ((op >> 4) & 3) - 2) & 0xFF;
                g = (g + ((op >> 2) & 3) - 2) & 0xFF;
                b = (b + (op & 3) - 2) & 0xFF;
            } else if (op < 0xC0) {
                if (p >= d.length) break;
                int b2 = d[p++] & 0xFF;
                int dg = (op & 0x3F) - 32;
                r = (r + dg + ((b2 >> 4) & 0x0F) - 8) & 0xFF;
                g = (g + dg) & 0xFF;
                b = (b + dg + (b2 & 0x0F) - 8) & 0xFF;
            } else {
                int run = (op & 0x3F) + 1;
                for (int i = 0; i < run && px < total; i++) {
                    out[px++] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                continue;
            }
            int v = (a << 24) | (r << 16) | (g << 8) | b;
            index[qoiHash(r, g, b, a)] = v;
            out[px++] = v;
        }
        if (px < total) throw new IOException("QOI: нехватка данных");
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    // ---------------- WBMP ----------------

    private static Bitmap decodeWbmp(byte[] d) throws IOException {
        if (d.length < 4 || d[0] != 0 || d[1] != 0) throw new IOException("Повреждённый WBMP");
        int p = 2;
        int w = 0, h = 0;
        for (int i = 0; i < 5 && p < d.length; i++) {
            int v = d[p++] & 0xFF;
            w = (w << 7) | (v & 0x7F);
            if ((v & 0x80) == 0) break;
        }
        for (int i = 0; i < 5 && p < d.length; i++) {
            int v = d[p++] & 0xFF;
            h = (h << 7) | (v & 0x7F);
            if ((v & 0x80) == 0) break;
        }
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) throw new IOException("WBMP: неподдерживаемый размер");
        int rowBytes = (w + 7) / 8;
        if (p + (long) rowBytes * h > d.length) throw new IOException("WBMP: нехватка данных");
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int bit = (d[p + y * rowBytes + (x >> 3)] >> (7 - (x & 7))) & 1;
                int g = bit == 0 ? 0 : 255;
                px[y * w + x] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    // ---------------- TIFF ----------------

    private static Bitmap decodeTiff(byte[] d) throws IOException {
        if (d.length < 8) throw new IOException("Повреждённый TIFF");
        boolean le;
        if (d[0] == 'I' && d[1] == 'I') le = true;
        else if (d[0] == 'M' && d[1] == 'M') le = false;
        else throw new IOException("Повреждённый TIFF");
        if (u16(d, 2, le) != 42) throw new IOException("Повреждённый TIFF");
        int ifd = u32(d, 4, le);
        if (ifd < 8 || ifd + 2 > d.length) throw new IOException("Повреждённый TIFF");
        int count = u16(d, ifd, le);
        HashMap<Integer, long[]> tags = new HashMap<Integer, long[]>();
        for (int i = 0; i < count; i++) {
            int p = ifd + 2 + i * 12;
            if (p + 12 > d.length) break;
            int tag = u16(d, p, le);
            int type = u16(d, p + 2, le);
            long n = u32(d, p + 4, le) & 0xFFFFFFFFL;
            long v = u32(d, p + 8, le) & 0xFFFFFFFFL;
            int unit;
            switch (type) {
                case 1: case 2: case 6: case 7: unit = 1; break;
                case 3: case 8: unit = 2; break;
                case 4: case 9: case 11: unit = 4; break;
                case 5: case 10: case 12: unit = 8; break;
                default: unit = 0;
            }
            if (unit == 0 || n <= 0 || n > 100000) continue;
            long total = n * unit;
            int off = total <= 4 ? p + 8 : (int) v;
            if (off < 0 || off + total > d.length) continue;
            long[] vals = new long[(int) n];
            for (int j = 0; j < n; j++) {
                int q = off + j * unit;
                if (unit == 1) vals[j] = d[q] & 0xFF;
                else if (unit == 2) vals[j] = u16(d, q, le);
                else if (unit == 4) vals[j] = u32(d, q, le) & 0xFFFFFFFFL;
                else vals[j] = 0;
            }
            tags.put(tag, vals);
        }
        int width = (int) tag(tags, 256, 0);
        int height = (int) tag(tags, 257, 0);
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384
                || (long) width * height > 64_000_000L) {
            throw new IOException("TIFF: неподдерживаемый размер");
        }
        int compression = (int) tag(tags, 259, 1);
        int photometric = (int) tag(tags, 262, 2);
        int samples = (int) tag(tags, 277, 1);
        int planar = (int) tag(tags, 284, 1);
        int predictor = (int) tag(tags, 317, 1);
        int orientation = (int) tag(tags, 274, 1);
        long[] bitsTag = tags.get(258);
        int[] bits = new int[Math.max(1, samples)];
        int defaultBits = photometric == 2 ? 8 : 1;
        for (int i = 0; i < bits.length; i++) {
            bits[i] = bitsTag == null ? defaultBits : (int) bitsTag[Math.min(i, bitsTag.length - 1)];
            if (bits[i] <= 0 || bits[i] > 16) throw new IOException("TIFF: неподдерживаемая разрядность");
        }
        if (planar != 1) throw new IOException("TIFF: раздельные каналы не поддерживаются");
        if (photometric != 0 && photometric != 1 && photometric != 2 && photometric != 3) {
            throw new IOException("TIFF: неподдерживаемый цветовой режим");
        }
        long[] offsets = tags.get(273);
        long[] counts = tags.get(279);
        if (offsets == null || counts == null || offsets.length == 0) {
            throw new IOException("TIFF: нет данных изображения");
        }
        int rowsPerStrip = (int) tag(tags, 278, height);
        if (rowsPerStrip <= 0) rowsPerStrip = height;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int i = 0; i < offsets.length; i++) {
            int off = (int) offsets[i];
            int len = i < counts.length ? (int) counts[i] : 0;
            if (off < 0 || len < 0 || off + len > d.length) throw new IOException("TIFF: повреждённые полосы");
            byte[] src = Arrays.copyOfRange(d, off, off + len);
            byte[] dec;
            switch (compression) {
                case 1: dec = src; break;
                case 32773: dec = packBits(src); break;
                case 8: case 32946: dec = inflate(src); break;
                case 5: dec = tiffLzw(src); break;
                default: throw new IOException("TIFF: сжатие " + compression + " не поддерживается");
            }
            raw.write(dec, 0, dec.length);
        }
        byte[] data = raw.toByteArray();
        int bytesPerSample = (bits[0] + 7) / 8;
        int sumBits = 0;
        for (int i = 0; i < samples; i++) sumBits += bits[Math.min(i, bits.length - 1)];
        long rowBits = (long) width * sumBits;
        int rowBytes = (int) ((rowBits + 7) / 8);
        long need = (long) rowBytes * height;
        if (data.length < need) throw new IOException("TIFF: нехватка данных");
        if (bits[0] < 8 && samples > 1) throw new IOException("TIFF: упакованные каналы не поддерживаются");
        if (predictor == 2 && bytesPerSample > 0 && bits[0] % 8 == 0) {
            int rowSamples = width * samples;
            int bps = bytesPerSample;
            for (int y = 0; y < height; y++) {
                int row = y * rowSamples * bps;
                for (int i = samples; i < rowSamples; i++) {
                    if (bps == 1) {
                        data[row + i] = (byte) (data[row + i] + data[row + i - samples]);
                    } else if (bps == 2) {
                        int a = u16(data, row + i * 2, le);
                        int b = u16(data, row + (i - samples) * 2, le);
                        int v = (a + b) & 0xFFFF;
                        data[row + i * 2] = (byte) (le ? (v & 0xFF) : (v >> 8));
                        data[row + i * 2 + 1] = (byte) (le ? (v >> 8) : (v & 0xFF));
                    }
                }
            }
        }
        long[] colorMap = tags.get(320);
        int[] px = new int[width * height];
        int maxVal = (1 << bits[0]) - 1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int baseSample = (y * width + x) * samples;
                int r, g, b, a = 255;
                if (photometric == 2) {
                    r = sampleAt(data, baseSample, bits[0], le);
                    g = sampleAt(data, baseSample + 1, bits[1], le);
                    b = sampleAt(data, baseSample + 2, bits[2], le);
                    if (samples >= 4) a = sampleAt(data, baseSample + 3, bits[3], le);
                    if (bits[0] > 8) { r >>= bits[0] - 8; g >>= bits[1] - 8; b >>= bits[2] - 8; }
                    if (samples >= 4 && bits[3] > 8) a >>= bits[3] - 8;
                } else if (photometric == 3) {
                    int idx = samplePacked(data, y, x, bits[0], width, le);
                    if (colorMap == null || colorMap.length < 3 * (idx + 1)) {
                        throw new IOException("TIFF: нет палитры");
                    }
                    int n = colorMap.length / 3;
                    r = (int) (colorMap[idx] >> 8);
                    g = (int) (colorMap[n + idx] >> 8);
                    b = (int) (colorMap[2 * n + idx] >> 8);
                } else {
                    int v = bits[0] < 8 ? samplePacked(data, y, x, bits[0], width, le)
                            : sampleAt(data, baseSample, bits[0], le);
                    if (bits[0] < 8) v = v * 255 / ((1 << bits[0]) - 1);
                    else if (bits[0] > 8) v >>= bits[0] - 8;
                    if (photometric == 0) v = 255 - v;
                    r = g = b = v;
                    if (samples >= 2) {
                        a = sampleAt(data, baseSample + 1, bits[1], le);
                        if (bits[1] > 8) a >>= bits[1] - 8;
                    }
                }
                px[y * width + x] = (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        if (orientation == 3) {
            for (int i = 0; i < px.length / 2; i++) {
                int t = px[i]; px[i] = px[px.length - 1 - i]; px[px.length - 1 - i] = t;
            }
        } else if (orientation == 6 || orientation == 8) {
            int[] out = new int[px.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (orientation == 6) out[x * height + (height - 1 - y)] = px[y * width + x];
                    else out[(width - 1 - x) * height + y] = px[y * width + x];
                }
            }
            return Bitmap.createBitmap(out, height, width, Bitmap.Config.ARGB_8888);
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int sampleAt(byte[] d, int sample, int bits, boolean le) {
        if (bits == 8) return d[sample] & 0xFF;
        if (bits == 16) return u16(d, sample * 2, le);
        if (bits == 4) return (d[sample / 2] >> ((sample & 1) == 0 ? 4 : 0)) & 0x0F;
        if (bits == 2) return (d[sample / 4] >> (6 - 2 * (sample & 3))) & 0x03;
        if (bits == 1) return (d[sample / 8] >> (7 - (sample & 7))) & 0x01;
        return 0;
    }

    private static int samplePacked(byte[] d, int y, int x, int bits, int width, boolean le) {
        int rowBytes = (width * bits + 7) / 8;
        int bit = y * rowBytes * 8 + x * bits;
        int p = bit >> 3;
        if (p >= d.length) return 0;
        int shift = 8 - bits - (bit & 7);
        if (shift >= 0) return (d[p] >> shift) & ((1 << bits) - 1);
        int v = (d[p] & 0xFF) << (-shift);
        if (p + 1 < d.length) v |= (d[p + 1] & 0xFF) >> (8 + shift);
        return v & ((1 << bits) - 1);
    }

    private static byte[] packBits(byte[] src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length * 2);
        int i = 0;
        while (i < src.length) {
            int n = src[i++];
            if (n >= 0) {
                int len = n + 1;
                for (int j = 0; j < len && i < src.length; j++) out.write(src[i++]);
            } else if (n != -128) {
                int len = 1 - n;
                if (i < src.length) {
                    byte v = src[i++];
                    for (int j = 0; j < len; j++) out.write(v);
                }
            }
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] src) throws IOException {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        try {
            inf.setInput(src);
            ByteArrayOutputStream out = new ByteArrayOutputStream(src.length * 3);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                } else out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("TIFF: ошибка Deflate");
        } finally {
            inf.end();
        }
    }

    private static final class MsbReader {
        final byte[] d;
        int bit;

        MsbReader(byte[] d) { this.d = d; }

        int read(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                int p = bit >> 3;
                if (p >= d.length) return -1;
                v = (v << 1) | ((d[p] >> (7 - (bit & 7))) & 1);
                bit++;
            }
            return v;
        }
    }

    private static byte[] tiffLzw(byte[] src) throws IOException {
        MsbReader in = new MsbReader(src);
        byte[][] dict = new byte[4096][];
        resetTiffDict(dict);
        int next = 258;
        int codeSize = 9;
        byte[] prev = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length * 3);
        while (true) {
            int code = in.read(codeSize);
            if (code < 0) break;
            if (code == 256) {
                resetTiffDict(dict);
                next = 258;
                codeSize = 9;
                prev = null;
                continue;
            }
            if (code == 257) break;
            byte[] entry;
            if (code < next && dict[code] != null) {
                entry = dict[code];
            } else if (code == next && prev != null) {
                entry = concat(prev, prev[0]);
            } else {
                throw new IOException("TIFF: ошибка LZW");
            }
            out.write(entry, 0, entry.length);
            if (prev != null && next < 4096) {
                dict[next++] = concat(prev, entry[0]);
                if (next == (1 << codeSize) - 1 && codeSize < 12) codeSize++;
            }
            prev = entry;
        }
        return out.toByteArray();
    }

    private static void resetTiffDict(byte[][] dict) {
        for (int i = 0; i < 256; i++) dict[i] = new byte[]{(byte) i};
        for (int i = 256; i < dict.length; i++) dict[i] = null;
    }

    private static byte[] concat(byte[] a, byte b) {
        byte[] r = new byte[a.length + 1];
        System.arraycopy(a, 0, r, 0, a.length);
        r[a.length] = b;
        return r;
    }

    private static long tag(HashMap<Integer, long[]> tags, int id, long def) {
        long[] v = tags.get(id);
        return v == null || v.length == 0 ? def : v[0];
    }

    private static int u16(byte[] d, int p, boolean le) {
        if (p + 1 >= d.length) return 0;
        return le ? ((d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8))
                : (((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF));
    }

    private static int u32(byte[] d, int p, boolean le) {
        if (p + 3 >= d.length) return 0;
        if (le) return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    // ---------------- encoders ----------------

    private static Bitmap flatten(Bitmap b, int color) {
        if (!b.hasAlpha()) return b;
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(color);
        c.drawBitmap(b, 0, 0, null);
        return out;
    }

    private static void writeGif(Bitmap b, File out, int colors) throws IOException {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int want = Math.max(2, Math.min(256, colors));
        int[] palette = quantize(px, want);
        int bits = 1;
        while ((1 << bits) < palette.length) bits++;
        int tableSize = 1 << bits;
        int[] remap = new int[32768];
        Arrays.fill(remap, -1);
        byte[] idx = new byte[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i] & 0xFFFFFF;
            int key = ((c >> 19) & 0x1F) << 10 | ((c >> 11) & 0x1F) << 5 | ((c >> 3) & 0x1F);
            int v = remap[key];
            if (v < 0) {
                v = nearest(palette, c);
                remap[key] = v;
            }
            idx[i] = (byte) v;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write('G'); bo.write('I'); bo.write('F'); bo.write('8'); bo.write('9'); bo.write('a');
        le16(bo, w); le16(bo, h);
        bo.write(0x80 | ((bits - 1) << 4) | (bits - 1));
        bo.write(0); bo.write(0);
        for (int i = 0; i < tableSize; i++) {
            int c = i < palette.length ? palette[i] : 0;
            bo.write((c >> 16) & 0xFF); bo.write((c >> 8) & 0xFF); bo.write(c & 0xFF);
        }
        bo.write(0x2C); le16(bo, 0); le16(bo, 0); le16(bo, w); le16(bo, h); bo.write(0);
        int minCode = Math.max(2, bits);
        byte[] lzw = gifLzw(idx, minCode);
        bo.write(minCode);
        for (int i = 0; i < lzw.length; i += 255) {
            int n = Math.min(255, lzw.length - i);
            bo.write(n);
            bo.write(lzw, i, n);
        }
        bo.write(0);
        bo.write(0x3B);
        FileOutputStream fos = new FileOutputStream(out);
        try { fos.write(bo.toByteArray()); } finally { fos.close(); }
    }

    private static int[] quantize(int[] px, int want) {
        int step = Math.max(1, (int) Math.sqrt(px.length / 20000.0));
        List<Integer> sample = new ArrayList<Integer>();
        for (int i = 0; i < px.length; i += step) sample.add(px[i] & 0xFFFFFF);
        if (sample.isEmpty()) sample.add(0);
        List<int[]> boxes = new ArrayList<int[]>();
        boxes.add(bounds(sample, 0, sample.size()));
        while (boxes.size() < want) {
            int bi = -1;
            int bestRange = 0;
            for (int i = 0; i < boxes.size(); i++) {
                int[] b = boxes.get(i);
                if (b[7] <= 1) continue;
                int rr = b[8] - b[3], gg = b[9] - b[4], bb = b[10] - b[5];
                int range = Math.max(rr, Math.max(gg, bb));
                if (range > bestRange) { bestRange = range; bi = i; }
            }
            if (bi < 0 || bestRange == 0) break;
            int[] b = boxes.get(bi);
            int rr = b[8] - b[3], gg = b[9] - b[4], bb = b[10] - b[5];
            int ch = (rr >= gg && rr >= bb) ? 0 : (gg >= bb ? 1 : 2);
            final int channel = ch;
            Integer[] arr = sample.subList(b[6], b[6] + b[7]).toArray(new Integer[0]);
            Arrays.sort(arr, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer c) {
                    return Integer.compare((a >> (16 - channel * 8)) & 0xFF, (c >> (16 - channel * 8)) & 0xFF);
                }
            });
            int mid = arr.length / 2;
            for (int i = 0; i < arr.length; i++) sample.set(b[6] + i, arr[i]);
            int[] left = bounds(sample, b[6], mid);
            int[] right = bounds(sample, b[6] + mid, arr.length - mid);
            boxes.set(bi, left);
            boxes.add(right);
        }
        int[] palette = new int[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            palette[i] = (b[0] << 16) | (b[1] << 8) | b[2];
        }
        return palette;
    }

    private static int[] bounds(List<Integer> list, int from, int count) {
        int rmin = 255, gmin = 255, bmin = 255, rmax = 0, gmax = 0, bmax = 0;
        long rs = 0, gs = 0, bs = 0;
        if (count <= 0) count = 1;
        if (from + count > list.size()) count = list.size() - from;
        for (int i = from; i < from + count; i++) {
            int c = list.get(i);
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (r < rmin) rmin = r; if (r > rmax) rmax = r;
            if (g < gmin) gmin = g; if (g > gmax) gmax = g;
            if (b < bmin) bmin = b; if (b > bmax) bmax = b;
            rs += r; gs += g; bs += b;
        }
        return new int[]{(int) (rs / count), (int) (gs / count), (int) (bs / count),
                rmin, gmin, bmin, from, count, rmax, gmax, bmax};
    }

    private static int nearest(int[] palette, int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int best = 0;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int p = palette[i];
            int dr = r - ((p >> 16) & 0xFF);
            int dg = g - ((p >> 8) & 0xFF);
            int db = b - (p & 0xFF);
            int d = dr * dr + dg * dg + db * db;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static byte[] gifLzw(byte[] idx, int minCode) {
        int clear = 1 << minCode;
        int end = clear + 1;
        int next = end + 1;
        int codeSize = minCode + 1;
        HashMap<Integer, Integer> dict = new HashMap<Integer, Integer>();
        BitOut out = new BitOut();
        int cur = idx.length > 0 ? idx[0] & 0xFF : 0;
        writeCode(out, clear, codeSize);
        for (int i = 1; i < idx.length; i++) {
            int c = idx[i] & 0xFF;
            int key = (cur << 8) | c;
            Integer v = dict.get(key);
            if (v != null) {
                cur = v;
            } else {
                writeCode(out, cur, codeSize);
                if (next < 4096) {
                    dict.put(key, next++);
                    if (next > (1 << codeSize) && codeSize < 12) codeSize++;
                } else {
                    writeCode(out, clear, codeSize);
                    dict.clear();
                    next = end + 1;
                    codeSize = minCode + 1;
                }
                cur = c;
            }
        }
        writeCode(out, cur, codeSize);
        writeCode(out, end, codeSize);
        if (out.bitCnt > 0) out.write(out.bitBuf & 0xFF);
        return out.toByteArray();
    }

    private static void writeCode(BitOut out, int code, int size) {
        out.bitBuf |= code << out.bitCnt;
        out.bitCnt += size;
        while (out.bitCnt >= 8) {
            out.write(out.bitBuf & 0xFF);
            out.bitBuf >>= 8;
            out.bitCnt -= 8;
        }
    }

    private static final class BitOut extends ByteArrayOutputStream {
        int bitBuf;
        int bitCnt;
    }

    private static void writeTiff(Bitmap b, File out) throws IOException {
        int w = b.getWidth();
        int h = b.getHeight();
        boolean alpha = b.hasAlpha();
        int samples = alpha ? 4 : 3;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        byte[] raw = new byte[w * h * samples];
        int p = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            raw[p++] = (byte) ((c >> 16) & 0xFF);
            raw[p++] = (byte) ((c >> 8) & 0xFF);
            raw[p++] = (byte) (c & 0xFF);
            if (alpha) raw[p++] = (byte) ((c >>> 24) & 0xFF);
        }
        int tags = alpha ? 11 : 10;
        int ifdSize = 2 + tags * 12 + 4;
        int bitsOff = 8 + ifdSize;
        int bitsSize = samples * 2;
        int stripOff = bitsOff + bitsSize;
        int stripOffSize = 4;
        int countOff = stripOff + stripOffSize;
        int countSize = 4;
        int extraOff = countOff + countSize;
        int pixelOff = extraOff + (alpha ? 2 : 0);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write('I'); bo.write('I'); le16(bo, 42); le32(bo, 8);
        le16(bo, tags);
        entry(bo, 256, 4, 1, w);
        entry(bo, 257, 4, 1, h);
        entry(bo, 258, 3, samples, bitsOff);
        entry(bo, 259, 3, 1, 1);
        entry(bo, 262, 3, 1, 2);
        entry(bo, 273, 4, 1, pixelOff);
        entry(bo, 277, 3, 1, samples);
        entry(bo, 278, 4, 1, h);
        entry(bo, 279, 4, 1, raw.length);
        entry(bo, 284, 3, 1, 1);
        if (alpha) entry(bo, 338, 3, 1, 2);
        le32(bo, 0);
        for (int i = 0; i < samples; i++) le16(bo, 8);
        le32(bo, pixelOff);
        le32(bo, raw.length);
        if (alpha) le16(bo, 2);
        bo.write(raw);
        FileOutputStream fos = new FileOutputStream(out);
        try { fos.write(bo.toByteArray()); } finally { fos.close(); }
    }

    private static void entry(ByteArrayOutputStream bo, int tag, int type, int count, int value) {
        le16(bo, tag); le16(bo, type); le32(bo, count);
        if (type == 3 && count == 1) { le16(bo, value); le16(bo, 0); }
        else le32(bo, value);
    }

    private static void writeIco(Bitmap b, File out, int size) throws IOException {
        int s = Math.max(16, Math.min(256, size));
        int sw = b.getWidth(), sh = b.getHeight();
        float scale = Math.min(s / (float) sw, s / (float) sh);
        int dw = Math.max(1, Math.round(sw * scale));
        int dh = Math.max(1, Math.round(sh * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(b, dw, dh, true);
        Bitmap square = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(square);
        c.drawBitmap(scaled, (s - dw) / 2f, (s - dh) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (scaled != b) scaled.recycle();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        square.compress(Bitmap.CompressFormat.PNG, 100, png);
        square.recycle();
        byte[] img = png.toByteArray();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        le16(bo, 0); le16(bo, 1); le16(bo, 1);
        bo.write(s >= 256 ? 0 : s);
        bo.write(s >= 256 ? 0 : s);
        bo.write(0); bo.write(0);
        le16(bo, 1); le16(bo, 32);
        le32(bo, img.length); le32(bo, 22);
        bo.write(img);
        FileOutputStream fos = new FileOutputStream(out);
        try { fos.write(bo.toByteArray()); } finally { fos.close(); }
    }

    private static void writeTga(Bitmap b, File out) throws IOException {
        int w = b.getWidth(), h = b.getHeight();
        boolean alpha = b.hasAlpha();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        byte[] head = new byte[18];
        head[2] = 2;
        head[12] = (byte) (w & 0xFF); head[13] = (byte) ((w >> 8) & 0xFF);
        head[14] = (byte) (h & 0xFF); head[15] = (byte) ((h >> 8) & 0xFF);
        head[16] = (byte) (alpha ? 32 : 24);
        head[17] = (byte) (0x20 | (alpha ? 8 : 0));
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(head);
            byte[] row = new byte[w * (alpha ? 4 : 3)];
            for (int y = 0; y < h; y++) {
                int q = 0;
                for (int x = 0; x < w; x++) {
                    int c = px[y * w + x];
                    row[q++] = (byte) (c & 0xFF);
                    row[q++] = (byte) ((c >> 8) & 0xFF);
                    row[q++] = (byte) ((c >> 16) & 0xFF);
                    if (alpha) row[q++] = (byte) ((c >>> 24) & 0xFF);
                }
                fos.write(row);
            }
        } finally { fos.close(); }
    }

    private static void writePnm(Bitmap b, File out, int kind) throws IOException {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            if (kind == 6) {
                fos.write(("P6\n" + w + " " + h + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                byte[] row = new byte[w * 3];
                for (int y = 0; y < h; y++) {
                    int q = 0;
                    for (int x = 0; x < w; x++) {
                        int c = px[y * w + x];
                        row[q++] = (byte) ((c >> 16) & 0xFF);
                        row[q++] = (byte) ((c >> 8) & 0xFF);
                        row[q++] = (byte) (c & 0xFF);
                    }
                    fos.write(row);
                }
            } else if (kind == 5) {
                fos.write(("P5\n" + w + " " + h + "\n255\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                byte[] row = new byte[w];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) row[x] = (byte) lum(px[y * w + x]);
                    fos.write(row);
                }
            } else {
                fos.write(("P4\n" + w + " " + h + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                int rowBytes = (w + 7) / 8;
                byte[] row = new byte[rowBytes];
                for (int y = 0; y < h; y++) {
                    Arrays.fill(row, (byte) 0);
                    for (int x = 0; x < w; x++) {
                        if (lum(px[y * w + x]) >= 128) row[x >> 3] |= (byte) (1 << (7 - (x & 7)));
                    }
                    fos.write(row);
                }
            }
        } finally { fos.close(); }
    }

    private static void writeQoi(Bitmap b, File out) throws IOException {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write('q'); bo.write('o'); bo.write('i'); bo.write('f');
        be32(bo, w); be32(bo, h);
        bo.write(4); bo.write(0);
        int[] index = new int[64];
        int pr = 0, pg = 0, pb = 0, pa = 255;
        int run = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b2 = c & 0xFF, a = (c >>> 24) & 0xFF;
            if (r == pr && g == pg && b2 == pb && a == pa) {
                run++;
                if (run == 62 || i == px.length - 1) {
                    bo.write(0xC0 | (run - 1));
                    run = 0;
                }
                continue;
            }
            if (run > 0) { bo.write(0xC0 | (run - 1)); run = 0; }
            int hsh = qoiHash(r, g, b2, a);
            if (index[hsh] == c) {
                bo.write(hsh);
            } else {
                index[hsh] = c;
                if (a == pa) {
                    int dr = r - pr, dg = g - pg, db = b2 - pb;
                    if (dr >= -2 && dr <= 1 && dg >= -2 && dg <= 1 && db >= -2 && db <= 1) {
                        bo.write(0x40 | ((dr + 2) << 4) | ((dg + 2) << 2) | (db + 2));
                    } else {
                        int drdg = dr - dg, dbdg = db - dg;
                        if (dg >= -32 && dg <= 31 && drdg >= -8 && drdg <= 7 && dbdg >= -8 && dbdg <= 7) {
                            bo.write(0x80 | (dg + 32));
                            bo.write(((drdg + 8) << 4) | (dbdg + 8));
                        } else {
                            bo.write(0xFE); bo.write(r); bo.write(g); bo.write(b2);
                        }
                    }
                } else {
                    bo.write(0xFF); bo.write(r); bo.write(g); bo.write(b2); bo.write(a);
                }
            }
            pr = r; pg = g; pb = b2; pa = a;
        }
        for (int i = 0; i < 7; i++) bo.write(0);
        bo.write(1);
        FileOutputStream fos = new FileOutputStream(out);
        try { fos.write(bo.toByteArray()); } finally { fos.close(); }
    }

    private static void writeWbmp(Bitmap b, File out) throws IOException {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(0); bo.write(0);
        writeWbmpInt(bo, w);
        writeWbmpInt(bo, h);
        int rowBytes = (w + 7) / 8;
        byte[] row = new byte[rowBytes];
        for (int y = 0; y < h; y++) {
            Arrays.fill(row, (byte) 0);
            for (int x = 0; x < w; x++) {
                if (lum(px[y * w + x]) >= 128) row[x >> 3] |= (byte) (1 << (7 - (x & 7)));
            }
            bo.write(row);
        }
        FileOutputStream fos = new FileOutputStream(out);
        try { fos.write(bo.toByteArray()); } finally { fos.close(); }
    }

    private static void writeWbmpInt(ByteArrayOutputStream o, int v) {
        if (v == 0) { o.write(0); return; }
        int n = 0;
        int t = v;
        while (t > 0) { n++; t >>= 7; }
        for (int i = n - 1; i >= 0; i--) {
            int b = (v >> (i * 7)) & 0x7F;
            if (i > 0) b |= 0x80;
            o.write(b);
        }
    }

    private static int qoiHash(int r, int g, int b, int a) {
        return (r * 3 + g * 5 + b * 7 + a * 11) & 63;
    }

    private static int lum(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int u16(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
    }

    private static int be32(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }

    private static void be32(ByteArrayOutputStream o, int v) {
        o.write((v >> 24) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 8) & 0xFF); o.write(v & 0xFF);
    }
}
