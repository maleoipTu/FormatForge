package com.formatforge.app;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public final class VideoConv {

    private static final int TIMEOUT_US = 10000;

    public static Output convert(SourceFile src, Formats.FormatSpec target,
                                 Map<String, Object> s, Util.Progress p) throws Exception {
        if (src.category == Formats.Category.IMAGE) {
            return convertImage(src, target, s, p);
        }
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(src.cache.getAbsolutePath());
        int vTrack = -1, aTrack = -1;
        MediaFormat vFmt = null, aFmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;
            if (mime.startsWith("video/") && vTrack < 0) {
                vTrack = i;
                vFmt = f;
            } else if (mime.startsWith("audio/") && aTrack < 0) {
                aTrack = i;
                aFmt = f;
            }
        }
        if (vTrack < 0 || vFmt == null) {
            ex.release();
            throw new Exception("В файле нет видеодорожки");
        }
        ex.selectTrack(vTrack);

        int srcW = getInt(vFmt, MediaFormat.KEY_WIDTH, 0);
        int srcH = getInt(vFmt, MediaFormat.KEY_HEIGHT, 0);
        if (srcW <= 0 || srcH <= 0) {
            ex.release();
            throw new Exception("Не удалось определить размер видео");
        }
        int rotation = getInt(vFmt, MediaFormat.KEY_ROTATION, 0);
        int maxDim = ImageConv.num(s, "maxDim", 0);
        int[] dims = customDims(srcW, srcH, ImageConv.num(s, "width", 0),
                ImageConv.num(s, "height", 0), maxDim);
        int dstW = dims[0], dstH = dims[1];

        int fps = ImageConv.num(s, "fps", -1);
        if (fps < 0) {
            float f = 30f;
            try {
                if (vFmt.containsKey(MediaFormat.KEY_FRAME_RATE)) f = vFmt.getInteger(MediaFormat.KEY_FRAME_RATE);
            } catch (Exception ignored) {
            }
            fps = Math.round(f);
        }
        if (fps < 1) fps = 30;
        if (fps > 60) fps = 60;
        int bitrate = ImageConv.num(s, "bitrate", 4_000_000);
        if (bitrate < 200_000) bitrate = 200_000;
        bitrate = compressionBitrate(s, bitrate);
        boolean wantAudio = ImageConv.bool(s, "audio", true) && aTrack >= 0 && !"webm".equals(target.id);

        String videoMime;
        if ("webm".equals(target.id)) {
            videoMime = findWebmEncoder();
        } else {
            videoMime = MediaFormat.MIMETYPE_VIDEO_AVC;
        }

        MediaExtractor audioEx = null;
        MediaFormat audioFormat = null;
        File audioFile = null;
        boolean audio = false;
        if (wantAudio) {
            try {
                Map<String, Object> as = new HashMap<String, Object>();
                as.put("bitrate", ImageConv.num(s, "abitrate", 192000));
                as.put("rate", 44100);
                as.put("channels", 2);
                as.put("trimStart", "0");
                as.put("trimEnd", "0");
                Formats.FormatSpec m4a = Formats.BY_ID.get("m4a");
                Output ao = AudioConv.convert(src, m4a, as, null);
                audioFile = ao.first().file;
                audioEx = new MediaExtractor();
                audioEx.setDataSource(audioFile.getAbsolutePath());
                for (int i = 0; i < audioEx.getTrackCount(); i++) {
                    MediaFormat f = audioEx.getTrackFormat(i);
                    String m = f.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) {
                        audioEx.selectTrack(i);
                        audioFormat = f;
                        break;
                    }
                }
                audio = audioFormat != null;
            } catch (Exception e) {
                audio = false;
            }
        }

        step(p, 3, "Подготовка кодеков…");
        MediaCodec encoder = null;
        MediaCodec decoder = null;
        MediaMuxer muxer = null;
        File out = null;
        boolean ok = false;
        try {
            encoder = MediaCodec.createEncoderByType(videoMime);
            MediaFormat ef = MediaFormat.createVideoFormat(videoMime, dstW, dstH);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            decoder = MediaCodec.createDecoderByType(vFmt.getString(MediaFormat.KEY_MIME));
            decoder.configure(vFmt, null, null, 0);

            String base = src.safeBaseName();
            out = Util.newTempFile(base + "-ff." + target.ext);
            muxer = new MediaMuxer(out.getAbsolutePath(), muxerFormat(target.id));

            decoder.start();
            encoder.start();
            step(p, 6, "Транскодирование…");

            transcode(ex, vFmt, decoder, encoder, muxer, audioEx, audioFormat, audio,
                    dstW, dstH, rotation, p);

            try { muxer.stop(); } catch (Exception ignored) { }
            ok = true;
            Output result = new Output();
            result.add(out, base + "." + target.ext, target.mime);
            result.summary = Util.humanSize(out.length());
            if (aTrack >= 0 && !audio) result.summary += " · без звука";
            step(p, 100, "Готово");
            return result;
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) { }
                decoder.release();
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) { }
                encoder.release();
            }
            if (muxer != null) muxer.release();
            ex.release();
            if (audioEx != null) audioEx.release();
            if (audioFile != null) audioFile.delete();
            if (!ok && out != null) out.delete();
        }
    }

    private static Output convertImage(SourceFile src, Formats.FormatSpec target,
                                       Map<String, Object> s, Util.Progress p) throws Exception {
        String id = target.id;
        if (!("mp4".equals(id) || "m4v".equals(id) || "mov".equals(id)
                || "3gp".equals(id) || "3g2".equals(id) || "webm".equals(id))) {
            throw new Exception("Этот видеоформат не поддерживается для изображений");
        }
        step(p, 2, "Подготовка изображения…");
        Bitmap bmp = ImageConv.load(src, s, p);
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        File out = null;
        boolean ok = false;
        try {
            bmp = ImageConv.flatten(bmp, 0xFFFFFFFF);
            int maxDim = ImageConv.num(s, "maxDim", 0);
            boolean custom = ImageConv.num(s, "width", 0) > 0 || ImageConv.num(s, "height", 0) > 0;
            int[] dims = custom
                    ? customDims(bmp.getWidth(), bmp.getHeight(), ImageConv.num(s, "width", 0),
                            ImageConv.num(s, "height", 0), 0)
                    : fitEven(bmp.getWidth(), bmp.getHeight(), maxDim);
            int dstW = dims[0], dstH = dims[1];
            if (bmp.getWidth() != dstW || bmp.getHeight() != dstH) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, dstW, dstH, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            int fps = ImageConv.num(s, "fps", -1);
            if (fps < 0) fps = 1;
            if (fps < 1) fps = 1;
            if (fps > 60) fps = 60;
            int duration = ImageConv.num(s, "duration", 5);
            if (duration < 1) duration = 1;
            if (duration > 60) duration = 60;
            int totalFrames = fps * duration;
            int bitrate = ImageConv.num(s, "bitrate", 4_000_000);
            if (bitrate < 200_000) bitrate = 200_000;
            bitrate = compressionBitrate(s, bitrate);

            String videoMime = "webm".equals(id) ? findWebmEncoder()
                    : MediaFormat.MIMETYPE_VIDEO_AVC;
            step(p, 40, "Подготовка кодеков…");
            encoder = MediaCodec.createEncoderByType(videoMime);
            MediaFormat ef = MediaFormat.createVideoFormat(videoMime, dstW, dstH);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            String base = src.safeBaseName();
            out = Util.newTempFile(base + "-ff." + target.ext);
            muxer = new MediaMuxer(out.getAbsolutePath(), muxerFormat(id));
            encoder.start();
            step(p, 45, "Кодирование кадров…");

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int vOut = -1;
            boolean muxStarted = false;
            boolean encOutDone = false;
            long frameDuration = 1_000_000L / fps;
            for (int frame = 0; frame < totalFrames && !encOutDone; frame++) {
                if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
                int idx = -1;
                for (int tries = 0; tries < 200 && idx < 0; tries++) {
                    idx = encoder.dequeueInputBuffer(TIMEOUT_US);
                }
                if (idx < 0) throw new IOException("Кодер видео перегружен");
                Image dst = encoder.getInputImage(idx);
                if (dst == null) throw new IOException("Кодер не поддерживает YUV-буферы");
                try {
                    fillImage(dst, bmp);
                } finally {
                    dst.close();
                }
                int flags = frame == totalFrames - 1 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                encoder.queueInputBuffer(idx, 0, dstW * dstH * 3 / 2, frame * frameDuration, flags);

                while (true) {
                    int oi = encoder.dequeueOutputBuffer(info, 0);
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxStarted) {
                            vOut = muxer.addTrack(encoder.getOutputFormat());
                            muxer.start();
                            muxStarted = true;
                        }
                    } else if (oi >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0 && muxStarted) {
                            ByteBuffer ob = encoder.getOutputBuffer(oi);
                            if (ob != null) {
                                ob.position(info.offset);
                                ob.limit(info.offset + info.size);
                                muxer.writeSampleData(vOut, ob, info);
                            }
                        }
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(oi, false);
                        if (eos) {
                            encOutDone = true;
                            break;
                        }
                    } else {
                        break;
                    }
                }
                if (p != null) {
                    int pct = 45 + (int) ((frame + 1) * 50L / totalFrames);
                    p.onProgress(pct, "Кодирование кадров…");
                }
            }

            int guard = 0;
            while (!encOutDone) {
                if (++guard > 10000) throw new IOException("Кодер не завершился");
                int oi = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxStarted) {
                        vOut = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxStarted = true;
                    }
                } else if (oi >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted) {
                        ByteBuffer ob = encoder.getOutputBuffer(oi);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            muxer.writeSampleData(vOut, ob, info);
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(oi, false);
                    if (eos) encOutDone = true;
                }
            }
            if (!muxStarted) throw new IOException("Кодер не выдал видеодорожку");
            try { muxer.stop(); } catch (Exception ignored) { }
            ok = true;
            Output result = new Output();
            result.add(out, src.safeBaseName() + "." + target.ext, target.mime);
            result.summary = Util.humanSize(out.length()) + " · " + duration + " с";
            step(p, 100, "Готово");
            return result;
        } finally {
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) { }
                encoder.release();
            }
            if (muxer != null) muxer.release();
            bmp.recycle();
            if (!ok && out != null) out.delete();
        }
    }

    private static void transcode(MediaExtractor ex, MediaFormat vFmt, MediaCodec decoder,
                                  MediaCodec encoder, MediaMuxer muxer, MediaExtractor audioEx,
                                  MediaFormat audioFormat, boolean audio, int dstW, int dstH,
                                  int rotation, Util.Progress p) throws Exception {
        long durationUs = getLong(vFmt, MediaFormat.KEY_DURATION, 0);
        ByteBuffer[] decIn = decoder.getInputBuffers();
        MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        boolean decInDone = false, decOutDone = false, encOutDone = false;
        long firstPts = -1;
        int vOut = -1, aOut = -1;
        boolean muxStarted = false;
        int guard = 0;
        while (!encOutDone) {
            if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
            if (++guard > 200_000_000) throw new IOException("Превышено время транскодирования");

            int ei = encoder.dequeueOutputBuffer(encInfo, 0);
            while (ei >= 0) {
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    encInfo.size = 0;
                }
                if (encInfo.size > 0 && muxStarted) {
                    ByteBuffer ob = encoder.getOutputBuffer(ei);
                    if (ob != null) {
                        ob.position(encInfo.offset);
                        ob.limit(encInfo.offset + encInfo.size);
                        muxer.writeSampleData(vOut, ob, encInfo);
                    }
                }
                boolean eos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(ei, false);
                if (eos) {
                    encOutDone = true;
                    break;
                }
                ei = encoder.dequeueOutputBuffer(encInfo, 0);
            }
            if (ei == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxStarted) {
                MediaFormat of = encoder.getOutputFormat();
                if (rotation != 0) {
                    try { of.setInteger(MediaFormat.KEY_ROTATION, rotation); } catch (Exception ignored) { }
                }
                vOut = muxer.addTrack(of);
                if (audio) aOut = muxer.addTrack(audioFormat);
                muxer.start();
                muxStarted = true;
                if (audio) writeAudio(muxer, aOut, audioEx);
            }
            if (encOutDone) break;

            if (!decInDone) {
                int in = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (in >= 0) {
                    ByteBuffer ib = decIn[in];
                    int size = ex.readSampleData(ib, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        decInDone = true;
                    } else {
                        long t = ex.getSampleTime();
                        decoder.queueInputBuffer(in, 0, size, t, 0);
                        ex.advance();
                        if (p != null && durationUs > 0 && t > 0) {
                            int pct = 6 + (int) Math.min(50, t * 50 / durationUs);
                            p.onProgress(pct, "Декодирование видео…");
                        }
                    }
                }
            }

            if (!decOutDone) {
                int di = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US);
                if (di >= 0) {
                    boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    Image img = null;
                    try {
                        img = decoder.getOutputImage(di);
                    } catch (Exception ignored) {
                    }
                    if (img != null) {
                        if (firstPts < 0) firstPts = decInfo.presentationTimeUs;
                        long pts = decInfo.presentationTimeUs - firstPts;
                        if (pts < 0) pts = 0;
                        feedEncoder(encoder, img, dstW, dstH, pts, eos);
                        img.close();
                    } else if (eos) {
                        queueEos(encoder, 0);
                    }
                    decoder.releaseOutputBuffer(di, false);
                    if (eos) decOutDone = true;
                }
            }
        }
        if (!muxStarted) throw new IOException("Кодер не выдал видеодорожку");
        if (p != null) p.onProgress(92, "Запись файла…");
    }

    private static void feedEncoder(MediaCodec encoder, Image src, int dstW, int dstH,
                                    long pts, boolean eos) throws Exception {
        int idx = -1;
        for (int tries = 0; tries < 200 && idx < 0; tries++) {
            idx = encoder.dequeueInputBuffer(TIMEOUT_US);
        }
        if (idx < 0) throw new IOException("Кодер видео перегружен");
        Image dst = encoder.getInputImage(idx);
        if (dst == null) throw new IOException("Кодер не поддерживает YUV-буферы");
        try {
            if (src.getWidth() == dstW && src.getHeight() == dstH) {
                copyImage(src, dst);
            } else {
                Bitmap bmp = imageToBitmap(src);
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, dstW, dstH, true);
                fillImage(dst, scaled);
                if (scaled != bmp) scaled.recycle();
                bmp.recycle();
            }
        } finally {
            dst.close();
        }
        int flags = eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
        encoder.queueInputBuffer(idx, 0, dstW * dstH * 3 / 2, pts, flags);
    }

    private static void queueEos(MediaCodec encoder, long pts) throws Exception {
        int idx = -1;
        for (int tries = 0; tries < 200 && idx < 0; tries++) {
            idx = encoder.dequeueInputBuffer(TIMEOUT_US);
        }
        if (idx >= 0) {
            encoder.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
    }

    private static void writeAudio(MediaMuxer muxer, int track, MediaExtractor ex) throws IOException {
        if (track < 0 || ex == null) return;
        int max = 64 * 1024;
        try {
            MediaFormat f = ex.getTrackFormat(0);
            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                max = Math.max(max, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
        } catch (Exception ignored) {
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(max);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            muxer.writeSampleData(track, buf, info);
            ex.advance();
        }
    }

    private static void copyImage(Image src, Image dst) throws IOException {
        Image.Plane[] sp = src.getPlanes();
        Image.Plane[] dp = dst.getPlanes();
        int planes = Math.min(sp.length, dp.length);
        for (int i = 0; i < planes; i++) {
            int w = i == 0 ? dst.getWidth() : (dst.getWidth() + 1) / 2;
            int h = i == 0 ? dst.getHeight() : (dst.getHeight() + 1) / 2;
            copyPlane(sp[i], dp[i], w, h);
        }
    }

    private static void copyPlane(Image.Plane sp, Image.Plane dp, int w, int h) {
        ByteBuffer sb = sp.getBuffer();
        ByteBuffer db = dp.getBuffer();
        int sRow = sp.getRowStride(), sPix = sp.getPixelStride();
        int dRow = dp.getRowStride(), dPix = dp.getPixelStride();
        if (sRow == dRow && sPix == dPix) {
            int bytes = Math.min(sb.remaining(), db.remaining());
            ByteBuffer a = sb.duplicate();
            ByteBuffer b = db.duplicate();
            a.position(0);
            b.position(0);
            if (bytes > 0) {
                byte[] tmp = new byte[Math.min(bytes, 64 * 1024)];
                int left = bytes;
                while (left > 0) {
                    int n = Math.min(tmp.length, left);
                    a.get(tmp, 0, n);
                    b.put(tmp, 0, n);
                    left -= n;
                }
            }
            return;
        }
        for (int y = 0; y < h; y++) {
            int so = y * sRow;
            int dOff = y * dRow;
            for (int x = 0; x < w; x++) {
                db.put(dOff + x * dPix, sb.get(so + x * sPix));
            }
        }
    }

    private static Bitmap imageToBitmap(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer yb = planes[0].getBuffer();
        ByteBuffer ub = planes[1].getBuffer();
        ByteBuffer vb = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride(), uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride(), vPix = planes[2].getPixelStride();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int yo = y * yRow;
            int uo = (y / 2) * uRow;
            int vo = (y / 2) * vRow;
            for (int x = 0; x < w; x++) {
                int Y = yb.get(yo + x * yPix) & 0xFF;
                int U = (ub.get(uo + (x / 2) * uPix) & 0xFF) - 128;
                int V = (vb.get(vo + (x / 2) * vPix) & 0xFF) - 128;
                int r = Y + ((1436 * V) >> 10);
                int g = Y - ((352 * U + 731 * V) >> 10);
                int b = Y + ((1814 * U) >> 10);
                r = clamp(r); g = clamp(g); b = clamp(b);
                px[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void fillImage(Image dst, Bitmap bmp) {
        int w = dst.getWidth(), h = dst.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        Image.Plane[] p = dst.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        ByteBuffer ub = p[1].getBuffer();
        ByteBuffer vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        for (int y = 0; y < h; y++) {
            int yo = y * yRow;
            int uo = (y / 2) * uRow;
            int vo = (y / 2) * vRow;
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int Y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int U = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int V = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                yb.put(yo + x * yPix, (byte) clamp(Y));
                if ((y & 1) == 0 && (x & 1) == 0) {
                    ub.put(uo + (x / 2) * uPix, (byte) clamp(U));
                    vb.put(vo + (x / 2) * vPix, (byte) clamp(V));
                }
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int compressionBitrate(Map<String, Object> s, int bitrate) {
        String c = ImageConv.str(s, "compression", "auto");
        double f = 1.0;
        if ("min".equals(c)) f = 1.5;
        else if ("low".equals(c)) f = 1.2;
        else if ("medium".equals(c)) f = 0.8;
        else if ("high".equals(c)) f = 0.5;
        else if ("max".equals(c)) f = 0.3;
        return Math.max(200_000, (int) (bitrate * f));
    }

    private static int[] customDims(int w, int h, int cw, int ch, int maxDim) {
        if (cw <= 0 && ch <= 0) return fitEven(w, h, maxDim);
        int nw, nh;
        if (cw > 0 && ch > 0) {
            nw = cw;
            nh = ch;
        } else if (cw > 0) {
            nw = cw;
            nh = Math.max(1, Math.round(h * (cw / (float) w)));
        } else {
            nh = ch;
            nw = Math.max(1, Math.round(w * (ch / (float) h)));
        }
        nw &= ~1;
        nh &= ~1;
        if (nw < 2) nw = 2;
        if (nh < 2) nh = 2;
        return new int[]{nw, nh};
    }

    private static int[] fitEven(int w, int h, int maxDim) {
        int nw = w, nh = h;
        if (maxDim > 0 && Math.max(w, h) > maxDim) {
            float sc = maxDim / (float) Math.max(w, h);
            nw = Math.max(2, Math.round(w * sc));
            nh = Math.max(2, Math.round(h * sc));
        }
        nw &= ~1;
        nh &= ~1;
        if (nw < 2) nw = 2;
        if (nh < 2) nh = 2;
        return new int[]{nw, nh};
    }

    private static int muxerFormat(String id) {
        if ("webm".equals(id)) return MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
        if ("3gp".equals(id) || "3g2".equals(id)) return MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP;
        return MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    }

    private static String findWebmEncoder() throws Exception {
        String[] mimes = {"video/x-vnd.on2.vp9", "video/x-vnd.on2.vp8"};
        for (String m : mimes) {
            try {
                MediaCodec c = MediaCodec.createEncoderByType(m);
                c.release();
                return m;
            } catch (Exception ignored) {
            }
        }
        throw new Exception("Устройство не поддерживает кодирование WebM (VP8/VP9)");
    }

    private static int getInt(MediaFormat f, String key, int def) {
        try {
            if (f.containsKey(key)) return f.getInteger(key);
        } catch (Exception ignored) {
        }
        return def;
    }

    private static long getLong(MediaFormat f, String key, long def) {
        try {
            if (f.containsKey(key)) return f.getLong(key);
        } catch (Exception ignored) {
        }
        return def;
    }

    private static void step(Util.Progress p, int v, String m) {
        if (p != null) p.onProgress(v, m);
    }

    private VideoConv() {
    }
}
