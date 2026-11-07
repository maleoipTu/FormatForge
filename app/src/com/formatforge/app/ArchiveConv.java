package com.formatforge.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ArchiveConv {

    public static Output convert(SourceFile src, Formats.FormatSpec target,
                                 Map<String, Object> s, Util.Progress p) throws Exception {
        String id = target.id;
        if ("zip".equals(id)) return packZip(src, target, s, p);
        if ("tar".equals(id)) return packTar(src, target, s, p);
        if ("gz".equals(id)) return packGzip(src, target, p);
        if ("unzip".equals(id)) return unzip(src, p);
        if ("untar".equals(id)) return untar(src, p);
        return ungzip(src, p);
    }

    private static Output packZip(SourceFile src, Formats.FormatSpec target,
                                  Map<String, Object> s, Util.Progress p) throws Exception {
        String base = src.safeBaseName();
        File out = Util.newTempFile(base + "-ff.zip");
        int level = ImageConv.num(s, "level", 6);
        String entry = ImageConv.str(s, "entryName", "");
        if (entry.isEmpty()) entry = src.name;
        entry = clean(entry);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)));
        try {
            zos.setLevel(level);
            if (level == 0) {
                zos.setMethod(ZipOutputStream.STORED);
            }
            ZipEntry ze = new ZipEntry(entry);
            if (level == 0) {
                ze.setSize(src.cache.length());
                ze.setCompressedSize(src.cache.length());
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                InputStream crcIn = new FileInputStream(src.cache);
                try {
                    byte[] b = new byte[65536];
                    int r;
                    while ((r = crcIn.read(b)) > 0) crc.update(b, 0, r);
                } finally {
                    crcIn.close();
                }
                ze.setCrc(crc.getValue());
            }
            zos.putNextEntry(ze);
            InputStream in = new FileInputStream(src.cache);
            try {
                copyProgress(in, zos, src.cache.length(), p, "Сжатие…", 5, 95);
            } finally {
                in.close();
            }
            zos.closeEntry();
        } finally {
            zos.close();
        }
        Output o = new Output();
        o.add(out, base + ".zip", "application/zip");
        step(p, 100, "Готово");
        return o;
    }

    private static Output packTar(SourceFile src, Formats.FormatSpec target,
                                  Map<String, Object> s, Util.Progress p) throws Exception {
        String base = src.safeBaseName();
        File out = Util.newTempFile(base + "-ff.tar");
        String entry = ImageConv.str(s, "entryName", "");
        if (entry.isEmpty()) entry = src.name;
        entry = clean(entry);
        OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
        TarWriter tw = new TarWriter(os);
        try {
            InputStream in = new FileInputStream(src.cache);
            try {
                tw.addFile(entry, src.cache.length(), src.cache.lastModified(), in,
                        new Util.Progress() {
                            @Override
                            public void onProgress(int percent, String message) {
                                if (p != null) p.onProgress(5 + percent * 90 / 100, message);
                            }

                            @Override
                            public boolean isCancelled() {
                                return p != null && p.isCancelled();
                            }
                        });
            } finally {
                in.close();
            }
            tw.finish();
        } finally {
            os.close();
        }
        Output o = new Output();
        o.add(out, base + ".tar", "application/x-tar");
        step(p, 100, "Готово");
        return o;
    }

    private static Output packGzip(SourceFile src, Formats.FormatSpec target, Util.Progress p) throws Exception {
        String base = src.safeBaseName();
        File out = Util.newTempFile(base + "-ff.gz");
        GZIPOutputStream gz = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(out)));
        try {
            InputStream in = new FileInputStream(src.cache);
            try {
                copyProgress(in, gz, src.cache.length(), p, "Сжатие…", 5, 95);
            } finally {
                in.close();
            }
        } finally {
            gz.close();
        }
        Output o = new Output();
        o.add(out, base + ".gz", "application/gzip");
        step(p, 100, "Готово");
        return o;
    }

    private static Output unzip(SourceFile src, Util.Progress p) throws Exception {
        String base = src.safeBaseName();
        File dir = new File(Util.outputDir(), base + "-unzip");
        deleteRecursive(dir);
        dir.mkdirs();
        Output out = new Output();
        out.multi = true;
        out.folder = base;
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(src.cache)));
        try {
            ZipEntry e;
            int count = 0;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
                String name = clean(e.getName());
                if (name.isEmpty()) continue;
                File f = new File(dir, name);
                if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath())) continue;
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    int r;
                    while ((r = zin.read(buf)) > 0) fos.write(buf, 0, r);
                } finally {
                    fos.close();
                }
                out.add(f, name, guessMime(name));
                count++;
                step(p, Math.min(90, 5 + count * 3), "Извлечение: " + name);
            }
            out.summary = "Извлечено файлов: " + count;
        } finally {
            zin.close();
        }
        if (out.items.isEmpty()) throw new IOException("Архив пуст или повреждён");
        step(p, 100, "Готово");
        return out;
    }

    private static Output untar(SourceFile src, Util.Progress p) throws Exception {
        String base = src.safeBaseName();
        File dir = new File(Util.outputDir(), base + "-untar");
        deleteRecursive(dir);
        dir.mkdirs();
        Output out = new Output();
        out.multi = true;
        out.folder = base;
        TarReader tr = new TarReader(new BufferedInputStream(new FileInputStream(src.cache)));
        try {
            int count = 0;
            TarReader.Entry e;
            byte[] buf = new byte[65536];
            while ((e = tr.next()) != null) {
                if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
                String name = clean(e.name);
                if (name.isEmpty()) {
                    tr.skipData();
                    continue;
                }
                File f = new File(dir, name);
                if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath())) {
                    tr.skipData();
                    continue;
                }
                if (e.directory) {
                    f.mkdirs();
                    tr.skipData();
                    continue;
                }
                f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    long left = e.size;
                    while (left > 0) {
                        int want = (int) Math.min(buf.length, left);
                        int r = tr.readData(buf, want);
                        if (r < 0) break;
                        fos.write(buf, 0, r);
                        left -= r;
                    }
                } finally {
                    fos.close();
                }
                out.add(f, name, guessMime(name));
                count++;
                step(p, Math.min(90, 5 + count * 3), "Извлечение: " + name);
            }
            out.summary = "Извлечено файлов: " + count;
        } finally {
            tr.close();
        }
        if (out.items.isEmpty()) throw new IOException("Архив пуст или повреждён");
        step(p, 100, "Готово");
        return out;
    }

    private static Output ungzip(SourceFile src, Util.Progress p) throws Exception {
        String name = src.name;
        String lower = name.toLowerCase();
        String outName;
        if (lower.endsWith(".tgz")) {
            outName = src.safeBaseName() + ".tar";
        } else if (lower.endsWith(".gz")) {
            outName = name.substring(0, name.length() - 3);
        } else {
            outName = src.safeBaseName() + ".out";
        }
        outName = clean(outName);
        if (outName.isEmpty()) outName = src.safeBaseName() + ".out";
        File out = Util.newTempFile("gunzip-" + outName);
        GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(new FileInputStream(src.cache)));
        FileOutputStream fos = new FileOutputStream(out);
        try {
            copyProgress(gz, fos, src.size > 0 ? src.size : 0, p, "Распаковка…", 5, 95);
        } finally {
            fos.close();
            gz.close();
        }
        Output o = new Output();
        o.add(out, outName, guessMime(outName));
        o.summary = Util.humanSize(out.length());
        step(p, 100, "Готово");
        return o;
    }

    private static void copyProgress(InputStream in, OutputStream out, long total,
                                     Util.Progress p, String msg, int from, int to) throws IOException, InterruptedException {
        byte[] buf = new byte[65536];
        long done = 0;
        int r;
        int last = -1;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
            done += r;
            if (p != null) {
                if (p.isCancelled()) throw new InterruptedException("Отменено");
                if (total > 0) {
                    int pct = from + (int) (done * (to - from) / total);
                    if (pct != last) {
                        last = pct;
                        p.onProgress(Math.min(to, pct), msg);
                    }
                }
            }
        }
    }

    private static String clean(String name) {
        String s = name.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        String[] parts = s.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }

    private static String guessMime(String name) {
        return Formats.mimeForExt(SourceFile.extensionOf(name));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private static void step(Util.Progress p, int v, String m) {
        if (p != null) p.onProgress(v, m);
    }

    private static final class TarWriter {
        private final OutputStream out;

        TarWriter(OutputStream out) {
            this.out = out;
        }

        void addFile(String name, long size, long mtime, InputStream in, Util.Progress p) throws IOException, InterruptedException {
            byte[] header = new byte[512];
            byte[] nameBytes = name.getBytes(Charset.forName("UTF-8"));
            if (nameBytes.length <= 100) {
                System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
            } else {
                int split = -1;
                for (int i = nameBytes.length - 1; i >= 0; i--) {
                    if (nameBytes[i] == '/') {
                        int prefixLen = i;
                        int rest = nameBytes.length - i - 1;
                        if (prefixLen <= 155 && rest <= 100) {
                            split = i;
                            break;
                        }
                    }
                }
                if (split < 0) throw new IOException("Слишком длинное имя для TAR: " + name);
                System.arraycopy(nameBytes, 0, header, 345, split);
                System.arraycopy(nameBytes, split + 1, header, 0, nameBytes.length - split - 1);
            }
            putOctal(header, 100, 8, 0644);
            putOctal(header, 108, 8, 0);
            putOctal(header, 116, 8, 0);
            putOctal(header, 124, 12, size);
            putOctal(header, 136, 12, mtime / 1000L);
            header[156] = '0';
            byte[] magic = "ustar".getBytes(Charset.forName("US-ASCII"));
            System.arraycopy(magic, 0, header, 257, 5);
            header[262] = 0;
            header[263] = '0';
            header[264] = '0';
            for (int i = 148; i < 156; i++) header[i] = ' ';
            long sum = 0;
            for (byte b : header) sum += (b & 0xFF);
            putOctal(header, 148, 7, sum);
            header[155] = ' ';
            out.write(header);

            byte[] buf = new byte[65536];
            long left = size;
            long done = 0;
            while (left > 0) {
                if (p != null && p.isCancelled()) throw new InterruptedException("Отменено");
                int want = (int) Math.min(buf.length, left);
                int r = in.read(buf, 0, want);
                if (r < 0) break;
                out.write(buf, 0, r);
                left -= r;
                done += r;
                if (p != null && size > 0) p.onProgress((int) (done * 100 / size), "Упаковка…");
            }
            int pad = (int) ((512 - (size % 512)) % 512);
            if (pad > 0) out.write(new byte[pad]);
        }

        void finish() throws IOException {
            out.write(new byte[1024]);
            out.flush();
        }

        private static void putOctal(byte[] b, int off, int len, long value) {
            String s = Long.toOctalString(value);
            while (s.length() < len - 1) s = "0" + s;
            if (s.length() > len - 1) s = s.substring(s.length() - (len - 1));
            byte[] bytes = s.getBytes(Charset.forName("US-ASCII"));
            System.arraycopy(bytes, 0, b, off, bytes.length);
            b[off + len - 1] = 0;
        }
    }

    private static final class TarReader {
        static final class Entry {
            String name;
            long size;
            boolean directory;
        }

        private final InputStream in;
        private long dataLeft;
        private long padding;
        private String pendingLongName;

        TarReader(InputStream in) {
            this.in = in;
        }

        Entry next() throws IOException {
            skipData();
            byte[] header = new byte[512];
            int r = readFully(header);
            if (r <= 0) return null;
            boolean zero = true;
            for (byte b : header) {
                if (b != 0) {
                    zero = false;
                    break;
                }
            }
            if (zero) return null;
            String name = field(header, 0, 100);
            String prefix = field(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            long size = parseOctal(header, 124, 12);
            char type = (char) (header[156] & 0xFF);
            dataLeft = size;
            padding = (512 - (size % 512)) % 512;
            if (type == 'L') {
                long cap = Math.min(size, 1024 * 1024);
                byte[] data = new byte[(int) cap];
                int total = 0;
                while (total < data.length) {
                    int got = in.read(data, total, data.length - total);
                    if (got < 0) break;
                    total += got;
                }
                dataLeft = size - total;
                skipData();
                int end = 0;
                while (end < total && data[end] != 0) end++;
                pendingLongName = new String(data, 0, end, Charset.forName("UTF-8")).trim();
                return next();
            }
            Entry e = new Entry();
            if (pendingLongName != null && !pendingLongName.isEmpty()) {
                e.name = pendingLongName;
            } else {
                e.name = name;
            }
            pendingLongName = null;
            e.size = size;
            e.directory = type == '5';
            return e;
        }

        int readData(byte[] dst, int len) throws IOException {
            if (dataLeft <= 0) {
                finishPadding();
                return -1;
            }
            int want = (int) Math.min(len, Math.min(dataLeft, dst.length));
            int r = in.read(dst, 0, want);
            if (r < 0) {
                dataLeft = 0;
                finishPadding();
                return -1;
            }
            dataLeft -= r;
            if (dataLeft == 0) finishPadding();
            return r;
        }

        void skipData() throws IOException {
            if (dataLeft > 0) skip(dataLeft);
            dataLeft = 0;
            finishPadding();
        }

        private void finishPadding() throws IOException {
            if (padding > 0) {
                skip(padding);
                padding = 0;
            }
        }

        private void skip(long n) throws IOException {
            while (n > 0) {
                long r = in.skip(n);
                if (r <= 0) break;
                n -= r;
            }
        }

        private int readFully(byte[] buf) throws IOException {
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) return off == 0 ? -1 : off;
                off += r;
            }
            return off;
        }

        private static String field(byte[] b, int off, int len) {
            int end = off;
            while (end < off + len && b[end] != 0) end++;
            return new String(b, off, end - off, Charset.forName("UTF-8")).trim();
        }

        private static long parseOctal(byte[] b, int off, int len) {
            long v = 0;
            for (int i = off; i < off + len; i++) {
                int c = b[i] & 0xFF;
                if (c == 0 || c == ' ') continue;
                if (c < '0' || c > '7') continue;
                v = v * 8 + (c - '0');
            }
            return v;
        }

        void close() throws IOException {
            in.close();
        }
    }

    private ArchiveConv() {
    }
}
