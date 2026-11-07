package com.formatforge.app;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.InputStream;
import java.util.Map;

final class SizeEstimator {

    static final class Info {
        long size;
        int width, height;
        long durationMs;
        boolean hasAudio;
    }

    static Info probe(Context ctx, SourceFile src) {
        Info info = new Info();
        info.size = Math.max(0, src.size);
        if (src.category == Formats.Category.IMAGE) {
            probeImage(ctx, src, info);
        } else if (src.category == Formats.Category.VIDEO || src.category == Formats.Category.AUDIO) {
            probeMedia(ctx, src, info);
        }
        return info;
    }

    private static void probeImage(Context ctx, SourceFile src, Info info) {
        try {
            InputStream in = ctx.getContentResolver().openInputStream(src.uri);
            if (in != null) {
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, o);
                    info.width = o.outWidth;
                    info.height = o.outHeight;
                } finally {
                    try { in.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) {
        }
        if ("webp".equals(src.ext) || "image/webp".equals(src.mime)) {
            try {
                InputStream in = ctx.getContentResolver().openInputStream(src.uri);
                if (in != null) {
                    try {
                        WebpAnimation anim = WebpAnimation.parse(in);
                        if (anim != null) {
                            info.width = anim.width;
                            info.height = anim.height;
                            long total = 0;
                            for (WebpAnimation.Frame f : anim.frames) {
                                total += f.duration > 0 ? f.duration : 100;
                            }
                            info.durationMs = total;
                        }
                    } finally {
                        try { in.close(); } catch (Exception ignored) { }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void probeMedia(Context ctx, SourceFile src, Info info) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, src.uri);
            info.width = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            info.height = parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            info.durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            String audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            info.hasAudio = "yes".equalsIgnoreCase(audio) || "true".equalsIgnoreCase(audio);
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    static long estimate(SourceFile src, Formats.FormatSpec target,
                         Map<String, Object> s, Info info) {
        if (info == null) info = new Info();
        if (target.category == Formats.Category.VIDEO) return videoSize(src, target, s, info);
        if (target.category == Formats.Category.IMAGE) return imageSize(src, target, s, info);
        if (target.category == Formats.Category.AUDIO) return audioSize(src, target, s, info);
        return -1;
    }

    private static long videoSize(SourceFile src, Formats.FormatSpec target,
                                  Map<String, Object> s, Info info) {
        long durationMs;
        if (src.category == Formats.Category.IMAGE) {
            int seconds = ImageConv.num(s, "duration", 5);
            if (seconds < 1) seconds = 1;
            if (seconds > 60) seconds = 60;
            durationMs = seconds * 1000L;
        } else {
            durationMs = info.durationMs;
        }
        if (durationMs <= 0) return -1;
        int bitrate = ImageConv.num(s, "bitrate", 4_000_000);
        if (bitrate < 200_000) bitrate = 200_000;
        bitrate = VideoConv.compressionBitrate(s, bitrate);
        int audio = 0;
        if (src.category == Formats.Category.VIDEO && info.hasAudio
                && !"webm".equals(target.id) && ImageConv.bool(s, "audio", true)) {
            audio = ImageConv.num(s, "abitrate", 192000);
        }
        double bytes = (bitrate + audio) * (durationMs / 1000.0) / 8.0;
        return Math.max(1, Math.round(bytes * 1.04));
    }

    private static long imageSize(SourceFile src, Formats.FormatSpec target,
                                  Map<String, Object> s, Info info) {
        int w = info.width, h = info.height;
        if (w <= 0 || h <= 0) return -1;
        int rot = ImageConv.num(s, "rotate", -1);
        if (rot == 90 || rot == 270) {
            int t = w;
            w = h;
            h = t;
        }
        if ("ico".equals(target.id)) {
            int size = ImageConv.num(s, "size", 256);
            if (size < 16) size = 16;
            if (size > 256) size = 256;
            return Math.max(128, Math.round(size * (double) size * 1.15));
        }
        int cw = ImageConv.num(s, "width", 0);
        int ch = ImageConv.num(s, "height", 0);
        int maxDim = ImageConv.num(s, "maxDim", 0);
        if (cw > 0 || ch > 0) {
            if (cw > 0 && ch > 0) {
                w = cw;
                h = ch;
            } else if (cw > 0) {
                h = Math.max(1, Math.round(h * (cw / (float) w)));
                w = cw;
            } else {
                w = Math.max(1, Math.round(w * (ch / (float) h)));
                h = ch;
            }
        } else if (maxDim > 0) {
            String fit = ImageConv.str(s, "fit", "inside");
            if ("square".equals(fit) || "stretch".equals(fit)) {
                w = maxDim;
                h = maxDim;
            } else if (Math.max(w, h) > maxDim) {
                float sc = maxDim / (float) Math.max(w, h);
                w = Math.max(1, Math.round(w * sc));
                h = Math.max(1, Math.round(h * sc));
            }
        }
        long pixels = (long) w * h;
        if (pixels <= 0) return -1;
        double bpp;
        String id = target.id;
        if ("bmp".equals(id) || "tga".equals(id) || "ppm".equals(id)) {
            bpp = 3.0;
        } else if ("pgm".equals(id)) {
            bpp = 1.0;
        } else if ("pbm".equals(id) || "wbmp".equals(id)) {
            bpp = 0.125;
        } else if ("qoi".equals(id)) {
            bpp = 3.6;
        } else if ("tiff".equals(id)) {
            bpp = 3.0;
        } else if ("png".equals(id)) {
            bpp = 1.15 * pngFactor(s);
        } else if ("webpl".equals(id)) {
            bpp = 0.8;
        } else if ("webp".equals(id)) {
            int q = ImageConv.qualityForCompression(s, ImageConv.num(s, "quality", 88));
            bpp = 0.05 + Math.pow(q / 100.0, 2) * 0.35;
        } else if ("gif".equals(id)) {
            int colors = ImageConv.colorsForCompression(s, ImageConv.num(s, "colors", 256));
            bpp = 0.08 + (colors / 256.0) * 0.5;
        } else {
            int q = ImageConv.qualityForCompression(s, ImageConv.num(s, "quality", 90));
            bpp = 0.06 + Math.pow(q / 100.0, 2) * 0.6;
        }
        if (ImageConv.bool(s, "grayscale", false)) bpp *= 0.8;
        long bytes = Math.round(pixels * bpp) + 512;
        return Math.max(64, bytes);
    }

    private static double pngFactor(Map<String, Object> s) {
        String c = ImageConv.str(s, "compression", "auto");
        if ("min".equals(c)) return 1.12;
        if ("low".equals(c)) return 1.04;
        if ("medium".equals(c)) return 0.96;
        if ("high".equals(c)) return 0.9;
        if ("max".equals(c)) return 0.84;
        return 1.0;
    }

    private static long audioSize(SourceFile src, Formats.FormatSpec target,
                                  Map<String, Object> s, Info info) {
        long durationMs = info.durationMs;
        if (durationMs <= 0) return -1;
        if ("wav".equals(target.id)) {
            int rate = ImageConv.num(s, "rate", 44100);
            int channels = ImageConv.num(s, "channels", 2);
            int depth = ImageConv.num(s, "depth", 16);
            return Math.max(44L, Math.round(rate * (double) channels * (depth / 8.0) * durationMs / 1000.0) + 44);
        }
        int bitrate = ImageConv.num(s, "bitrate", 192000);
        if (bitrate < 16000) bitrate = 16000;
        return Math.max(1, Math.round(bitrate * (durationMs / 1000.0) / 8.0 * 1.03));
    }

    private static int parseInt(String v) {
        try {
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private SizeEstimator() {
    }
}
