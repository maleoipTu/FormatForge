package com.formatforge.app;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Map;

public final class AudioConv {

    private static final int CHUNK_FRAMES = 4096;
    private static final int WINDOW_FRAMES = 1 << 16;

    private static final class Pcm {
        File file;
        int rate;
        int channels;
        long frames;
    }

    public static Output convert(SourceFile src, Formats.FormatSpec target,
                                 Map<String, Object> s, Util.Progress p) throws Exception {
        double trimStart = Math.max(0, ImageConv.dbl(s, "trimStart", 0));
        double trimEnd = Math.max(0, ImageConv.dbl(s, "trimEnd", 0));
        int outRate = ImageConv.num(s, "rate", 44100);
        int outCh = ImageConv.num(s, "channels", 2);

        step(p, 2, "Декодирование аудио…");
        Pcm pcm = decode(src, p);
        if (pcm.frames <= 0) throw new Exception("Не удалось декодировать аудиодорожку");

        long startFrame = Math.min(pcm.frames, Math.round(trimStart * pcm.rate));
        long endFrame = trimEnd > 0 ? Math.min(pcm.frames, Math.round(trimEnd * pcm.rate)) : pcm.frames;
        if (endFrame <= startFrame) throw new Exception("Пустой диапазон после обрезки");
        long srcFrames = endFrame - startFrame;
        long outFrames = Math.max(1, Math.round(srcFrames * (double) outRate / pcm.rate));

        String base = src.safeBaseName();
        Output result = new Output();
        RandomAccessFile raf = new RandomAccessFile(pcm.file, "r");
        try {
            Source source = new Source(raf, pcm.rate, pcm.channels, startFrame, endFrame, outRate, outCh);
            if ("wav".equals(target.id)) {
                int depth = ImageConv.num(s, "depth", 16);
                File out = Util.newTempFile(base + "-ff.wav");
                writeWav(source, out, outFrames, outRate, outCh, depth, p);
                result.add(out, base + ".wav", "audio/wav");
                result.summary = Util.humanSize(out.length());
                pcm.file.delete();
            } else {
                int bitrate = ImageConv.num(s, "bitrate", 192000);
                File out = Util.newTempFile(base + "-ff.m4a");
                try {
                    encodeAac(source, out, outFrames, outRate, outCh, bitrate, p);
                } catch (Exception e) {
                    pcm.file.delete();
                    throw new Exception("Не удалось закодировать AAC: " + e.getMessage());
                }
                result.add(out, base + ".m4a", "audio/mp4");
                result.summary = Util.humanSize(out.length());
                pcm.file.delete();
            }
        } finally {
            raf.close();
        }
        step(p, 100, "Готово");
        return result;
    }

    // ---------------- decode ----------------

    private static Pcm decode(SourceFile src, Util.Progress p) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(src.cache.getAbsolutePath());
        int track = -1;
        MediaFormat fmt = null;
        long durationUs = 0;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                fmt = f;
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = f.getLong(MediaFormat.KEY_DURATION);
                break;
            }
        }
        if (track < 0) {
            ex.release();
            throw new Exception("В файле нет аудиодорожки");
        }
        ex.selectTrack(track);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        MediaCodec codec;
        try {
            codec = MediaCodec.createDecoderByType(mime);
        } catch (Exception e) {
            ex.release();
            throw new Exception("Кодек " + mime + " не поддерживается устройством");
        }
        codec.configure(fmt, null, null, 0);
        codec.start();

        File pcmFile = Util.newTempFile(src.safeBaseName() + "-dec.pcm");
        FileOutputStream fos = new FileOutputStream(pcmFile);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteBuffer[] inputs = codec.getInputBuffers();
        ByteBuffer[] outputs = codec.getOutputBuffers();
        boolean inputDone = false;
        boolean outputDone = false;
        int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
        long totalBytes = 0;
        int guard = 0;
        try {
            while (!outputDone) {
                if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
                if (++guard > 10_000_000) break;
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = inputs[inIdx];
                        int size = ex.readSampleData(ib, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long t = ex.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, size, t, 0);
                            ex.advance();
                            if (p != null && durationUs > 0 && t > 0) {
                                int pct = 3 + (int) Math.min(30, t * 30 / durationUs);
                                p.onProgress(pct, "Декодирование аудио…");
                            }
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx >= 0) {
                    ByteBuffer ob = outputs[outIdx];
                    if (info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBufferAdapter fl = new FloatBufferAdapter(ob);
                            int samples = info.size / 4;
                            byte[] out = new byte[samples * 2];
                            for (int i = 0; i < samples; i++) {
                                float f = fl.get(i);
                                int v = Math.round(f * 32767f);
                                if (v > 32767) v = 32767;
                                if (v < -32768) v = -32768;
                                out[i * 2] = (byte) (v & 0xFF);
                                out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                            }
                            fos.write(out);
                            totalBytes += out.length;
                        } else {
                            byte[] chunk = new byte[info.size];
                            ob.get(chunk, 0, info.size);
                            fos.write(chunk);
                            totalBytes += chunk.length;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputs = codec.getOutputBuffers();
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputs = codec.getOutputBuffers();
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) { }
            codec.release();
            ex.release();
            fos.close();
        }
        if (channels <= 0) channels = 1;
        Pcm pcm = new Pcm();
        pcm.file = pcmFile;
        pcm.rate = rate;
        pcm.channels = channels;
        pcm.frames = totalBytes / (2L * channels);
        step(p, 40, "Аудио декодировано");
        return pcm;
    }

    private static final class FloatBufferAdapter {
        private final ByteBuffer buf;
        private final FloatBuffer fb;

        FloatBufferAdapter(ByteBuffer b) {
            buf = b;
            FloatBuffer f = null;
            try {
                f = b.asFloatBuffer();
            } catch (Exception ignored) {
            }
            fb = f;
        }

        float get(int i) {
            try {
                return fb != null ? fb.get(i) : buf.getFloat(i * 4);
            } catch (Exception e) {
                return 0f;
            }
        }
    }

    // ---------------- PCM source ----------------

    private static final class Source {
        final RandomAccessFile raf;
        final int inRate;
        final int inCh;
        final int outRate;
        final int outCh;
        final long start;
        final long end;
        final double step;
        final int frameBytes;
        final byte[] window;
        long winStart = -1;
        int winFrames = 0;

        Source(RandomAccessFile raf, int inRate, int inCh, long start, long end, int outRate, int outCh) {
            this.raf = raf;
            this.inRate = inRate;
            this.inCh = inCh;
            this.start = start;
            this.end = end;
            this.outRate = outRate;
            this.outCh = outCh;
            this.step = (double) inRate / outRate;
            this.frameBytes = inCh * 2;
            this.window = new byte[WINDOW_FRAMES * frameBytes];
        }

        long outFrames() {
            return Math.max(1, Math.round((end - start) * (double) outRate / inRate));
        }

        private void ensure(long frame) throws IOException {
            if (winStart >= 0 && frame >= winStart && frame < winStart + winFrames) return;
            winStart = frame;
            long available = Math.min(end - frame, WINDOW_FRAMES);
            if (available <= 0) {
                winFrames = 0;
                return;
            }
            raf.seek(frame * frameBytes);
            int want = (int) (available * frameBytes);
            int off = 0;
            while (off < want) {
                int r = raf.read(window, off, want - off);
                if (r < 0) break;
                off += r;
            }
            winFrames = off / frameBytes;
        }

        short sample(long frame, int ch) throws IOException {
            ensure(frame);
            if (winFrames <= 0) return 0;
            int idx = (int) (frame - winStart);
            if (idx < 0) idx = 0;
            if (idx >= winFrames) idx = winFrames - 1;
            int pos = idx * frameBytes + (ch % inCh) * 2;
            int lo = window[pos] & 0xFF;
            int hi = window[pos + 1];
            return (short) ((hi << 8) | lo);
        }

        short mixed(long frame, int outChannel) throws IOException {
            if (outCh == inCh) return sample(frame, outChannel);
            if (inCh == 1) return sample(frame, 0);
            if (inCh == 2 && outCh == 1) {
                int a = sample(frame, 0);
                int b = sample(frame, 1);
                return (short) ((a + b) / 2);
            }
            return sample(frame, outChannel % inCh);
        }

        int frame(long i, int outChannel) throws IOException {
            double pos = start + i * step;
            long f0 = (long) pos;
            if (f0 >= end) f0 = end - 1;
            double frac = pos - f0;
            long f1 = Math.min(end - 1, f0 + 1);
            int a = mixed(f0, outChannel);
            if (frac <= 0.0000001) return a;
            int b = mixed(f1, outChannel);
            return (int) Math.round(a + (b - a) * frac);
        }
    }

    // ---------------- WAV ----------------

    private static void writeWav(Source source, File out, long outFrames, int outRate, int outCh,
                                 int depth, Util.Progress p) throws Exception {
        int bytesPerSample = depth / 8;
        int blockAlign = outCh * bytesPerSample;
        long dataSize = outFrames * blockAlign;
        FileOutputStream fos = new FileOutputStream(out);
        try {
            writeAscii(fos, "RIFF");
            le32(fos, (int) (36 + dataSize));
            writeAscii(fos, "WAVE");
            writeAscii(fos, "fmt ");
            le32(fos, 16);
            le16(fos, 1);
            le16(fos, outCh);
            le32(fos, outRate);
            le32(fos, outRate * blockAlign);
            le16(fos, blockAlign);
            le16(fos, depth);
            writeAscii(fos, "data");
            le32(fos, (int) dataSize);

            byte[] buf = new byte[CHUNK_FRAMES * blockAlign];
            int at = 0;
            for (long i = 0; i < outFrames; i++) {
                for (int c = 0; c < outCh; c++) {
                    int s = source.frame(i, c);
                    at = putSample(buf, at, s, depth);
                }
                if (at >= buf.length - blockAlign) {
                    fos.write(buf, 0, at);
                    at = 0;
                    if (p != null && (i & 0x3FFF) == 0) {
                        p.onProgress(40 + (int) (i * 55 / outFrames), "Запись WAV…");
                        if (p.isCancelled()) throw new InterruptedException("Отменено");
                    }
                }
            }
            if (at > 0) fos.write(buf, 0, at);
        } finally {
            fos.close();
        }
        step(p, 98, "Запись WAV…");
    }

    private static int putSample(byte[] buf, int at, int s, int depth) {
        if (s > 32767) s = 32767;
        if (s < -32768) s = -32768;
        switch (depth) {
            case 8:
                buf[at++] = (byte) (((s >> 8) & 0xFF) ^ 0x80);
                break;
            case 24:
                buf[at++] = (byte) (s & 0xFF);
                buf[at++] = (byte) ((s >> 8) & 0xFF);
                buf[at++] = (byte) (s < 0 ? 0xFF : 0x00);
                break;
            case 32:
                buf[at++] = 0;
                buf[at++] = 0;
                buf[at++] = (byte) (s & 0xFF);
                buf[at++] = (byte) ((s >> 8) & 0xFF);
                break;
            default:
                buf[at++] = (byte) (s & 0xFF);
                buf[at++] = (byte) ((s >> 8) & 0xFF);
                break;
        }
        return at;
    }

    // ---------------- AAC ----------------

    private static void encodeAac(Source source, File out, long outFrames, int outRate, int outCh,
                                  int bitrate, Util.Progress p) throws Exception {
        int rate = Math.min(48000, outRate);
        if (rate != outRate) {
            source = new Source(source.raf, source.inRate, source.inCh, source.start, source.end, rate, outCh);
            outFrames = Math.max(1, Math.round(outFrames * (double) rate / outRate));
        }
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, outCh);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        boolean started = false;
        byte[] pcmChunk = new byte[CHUNK_FRAMES * outCh * 2];
        long frame = 0;
        int guard = 0;
        try {
            while (frame < outFrames) {
                if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
                if (++guard > 10_000_000) break;
                int frames = (int) Math.min(CHUNK_FRAMES, outFrames - frame);
                int inIdx = codec.dequeueInputBuffer(20000);
                if (inIdx >= 0) {
                    int bytes = 0;
                    for (int i = 0; i < frames; i++) {
                        for (int c = 0; c < outCh; c++) {
                            int s = source.frame(frame + i, c);
                            pcmChunk[bytes++] = (byte) (s & 0xFF);
                            pcmChunk[bytes++] = (byte) ((s >> 8) & 0xFF);
                        }
                    }
                    long pts = frame * 1_000_000L / rate;
                    ByteBuffer ib = codec.getInputBuffers()[inIdx];
                    ib.clear();
                    ib.put(pcmChunk, 0, bytes);
                    codec.queueInputBuffer(inIdx, 0, bytes, pts, 0);
                    frame += frames;
                    if (p != null) p.onProgress(40 + (int) Math.min(55, frame * 55 / outFrames), "Кодирование AAC…");
                }
                while (true) {
                    info = new MediaCodec.BufferInfo();
                    int outIdx = codec.dequeueOutputBuffer(info, 0);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!started) {
                            track = muxer.addTrack(codec.getOutputFormat());
                            muxer.start();
                            started = true;
                        }
                        continue;
                    }
                    if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
                    if (outIdx < 0) break;
                    ByteBuffer ob = codec.getOutputBuffers()[outIdx];
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && started) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        muxer.writeSampleData(track, ob, info);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                }
            }
            boolean eosQueued = false;
            int tries = 0;
            while (!eosQueued && tries++ < 10000) {
                if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
                int inIdx = codec.dequeueInputBuffer(20000);
                if (inIdx >= 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, frame * 1_000_000L / rate,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    eosQueued = true;
                }
            }
            boolean done = false;
            int spins = 0;
            while (!done && spins++ < 10000) {
                info = new MediaCodec.BufferInfo();
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!started) {
                        track = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        started = true;
                    }
                    continue;
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
                if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffers()[outIdx];
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && started) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        muxer.writeSampleData(track, ob, info);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) { }
            codec.release();
            try {
                if (started) muxer.stop();
            } catch (Exception ignored) {
            }
            muxer.release();
        }
        step(p, 98, "Кодирование AAC…");
    }

    // ---------------- io ----------------

    private static void writeAscii(FileOutputStream f, String s) throws IOException {
        f.write(s.getBytes("US-ASCII"));
    }

    private static void le16(FileOutputStream f, int v) throws IOException {
        f.write(v & 0xFF);
        f.write((v >> 8) & 0xFF);
    }

    private static void le32(FileOutputStream f, int v) throws IOException {
        f.write(v & 0xFF);
        f.write((v >> 8) & 0xFF);
        f.write((v >> 16) & 0xFF);
        f.write((v >> 24) & 0xFF);
    }

    private static void step(Util.Progress p, int v, String m) {
        if (p != null) p.onProgress(v, m);
    }

    private AudioConv() {
    }
}
