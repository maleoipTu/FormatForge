package com.formatforge.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class WebpAnimation {

    static final class Frame {
        int x, y, w, h, duration;
        boolean dispose;
        boolean blend;
        boolean loopStart;
        byte[] data;
    }

    final int width, height;
    final int backgroundColor;
    final int loopCount;
    final List<Frame> frames;

    private WebpAnimation(int width, int height, int backgroundColor, int loopCount,
                          List<Frame> frames) {
        this.width = width;
        this.height = height;
        this.backgroundColor = backgroundColor;
        this.loopCount = loopCount;
        this.frames = frames;
    }

    static WebpAnimation parse(File file) throws IOException {
        return parse(readAll(file));
    }

    static WebpAnimation parse(InputStream in) throws IOException {
        return parse(readAll(in));
    }

    private static WebpAnimation parse(byte[] b) {
        if (b.length < 12 || !isFourcc(b, 0, "RIFF") || !isFourcc(b, 8, "WEBP")) return null;
        int canvasW = 0, canvasH = 0, bg = 0xFFFFFFFF, loops = 0;
        boolean animated = false;
        List<Frame> frames = new ArrayList<Frame>();
        int pos = 12;
        while (pos + 8 <= b.length) {
            int size = le32(b, pos + 4);
            int data = pos + 8;
            if (size < 0 || data > b.length || size > b.length - data) break;
            if (isFourcc(b, pos, "VP8X") && size >= 10) {
                animated = (b[data] & 0x02) != 0;
                canvasW = le24(b, data + 4) + 1;
                canvasH = le24(b, data + 7) + 1;
            } else if (isFourcc(b, pos, "ANIM") && size >= 6) {
                bg = le32(b, data);
                loops = le16(b, data + 4);
            } else if (isFourcc(b, pos, "ANMF") && size >= 16) {
                Frame f = new Frame();
                f.x = le24(b, data) * 2;
                f.y = le24(b, data + 3) * 2;
                f.w = le24(b, data + 6) + 1;
                f.h = le24(b, data + 9) + 1;
                f.duration = le24(b, data + 12);
                int flags = b[data + 15] & 0xFF;
                f.dispose = (flags & 0x01) != 0;
                f.blend = (flags & 0x02) != 0;
                f.data = new byte[size - 16];
                System.arraycopy(b, data + 16, f.data, 0, f.data.length);
                frames.add(f);
            }
            pos = data + size + (size & 1);
        }
        if (!animated && frames.isEmpty()) return null;
        if (frames.isEmpty()) return null;
        if (canvasW <= 0 || canvasH <= 0) {
            for (Frame f : frames) {
                canvasW = Math.max(canvasW, f.x + f.w);
                canvasH = Math.max(canvasH, f.y + f.h);
            }
        }
        if (canvasW <= 0 || canvasH <= 0) return null;
        return new WebpAnimation(canvasW, canvasH, bg, loops, frames);
    }

    static byte[] frameToWebp(Frame f) throws IOException {
        byte[] alph = null, vp8 = null, vp8l = null;
        int pos = 0;
        while (pos + 8 <= f.data.length) {
            int size = le32(f.data, pos + 4);
            int data = pos + 8;
            if (size < 0 || data > f.data.length || size > f.data.length - data) break;
            if (isFourcc(f.data, pos, "ALPH")) {
                alph = slice(f.data, data, size);
            } else if (isFourcc(f.data, pos, "VP8 ")) {
                vp8 = slice(f.data, data, size);
            } else if (isFourcc(f.data, pos, "VP8L")) {
                vp8l = slice(f.data, data, size);
            }
            pos = data + size + (size & 1);
        }
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        if (vp8l != null) {
            writeChunk(chunks, "VP8L", vp8l);
        } else if (vp8 != null) {
            if (alph != null) {
                byte[] vp8x = new byte[10];
                vp8x[0] = 0x10;
                put24(vp8x, 4, Math.max(0, f.w - 1));
                put24(vp8x, 7, Math.max(0, f.h - 1));
                writeChunk(chunks, "VP8X", vp8x);
                writeChunk(chunks, "ALPH", alph);
            }
            writeChunk(chunks, "VP8 ", vp8);
        } else {
            throw new IOException("Не найден кадр WebP");
        }
        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 12);
        out.write(new byte[]{'R', 'I', 'F', 'F'});
        write32(out, body.length + 4);
        out.write(new byte[]{'W', 'E', 'B', 'P'});
        out.write(body);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String fourcc, byte[] data)
            throws IOException {
        out.write(fourcc.getBytes("US-ASCII"));
        write32(out, data.length);
        out.write(data);
        if ((data.length & 1) != 0) out.write(0);
    }

    private static void write32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static byte[] readAll(File file) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            return readAll(in, file.length());
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return readAll(in, 0);
    }

    private static byte[] readAll(InputStream in, long hint) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, hint));
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    private static boolean isFourcc(byte[] b, int off, String s) {
        if (off < 0 || off + 4 > b.length || s.length() != 4) return false;
        return b[off] == s.charAt(0) && b[off + 1] == s.charAt(1)
                && b[off + 2] == s.charAt(2) && b[off + 3] == s.charAt(3);
    }

    private static int le16(byte[] b, int off) {
        if (off < 0 || off + 2 > b.length) return 0;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le24(byte[] b, int off) {
        if (off < 0 || off + 3 > b.length) return 0;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16);
    }

    private static int le32(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) return -1;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void put24(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
    }

    private WebpAnimation() {
        throw new AssertionError();
    }
}
