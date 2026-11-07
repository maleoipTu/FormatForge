package com.formatforge.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Convert {

    public static List<Formats.FormatSpec> targetsFor(SourceFile src) {
        List<Formats.FormatSpec> out = new ArrayList<Formats.FormatSpec>();
        String[] ids = idsFor(src);
        for (String id : ids) {
            Formats.FormatSpec f = Formats.BY_ID.get(id);
            if (f != null) out.add(f);
        }
        return out;
    }

    private static String[] idsFor(SourceFile src) {
        switch (src.category) {
            case IMAGE:
                return new String[]{"png", "jpg", "webp", "webpl", "bmp", "b64", "zip"};
            case AUDIO:
                return new String[]{"wav", "m4a", "b64", "zip"};
            case VIDEO:
                return new String[]{"m4a", "wav", "b64", "zip"};
            case TEXT:
                return new String[]{"txt", "md", "html", "csv", "tsv", "json", "xml", "b64", "unb64", "zip", "tar", "gz"};
            case DOCUMENT:
                if ("xlsx".equals(src.kind) || "ods".equals(src.kind)) {
                    return new String[]{"csv", "tsv", "json", "html", "txt", "zip", "b64"};
                }
                return new String[]{"txt", "md", "html", "zip", "b64"};
            case ARCHIVE:
                switch (src.kind) {
                    case "zip":
                        return new String[]{"unzip", "zip", "b64"};
                    case "gzip":
                        return new String[]{"ungz", "gz", "b64"};
                    case "tar":
                        return new String[]{"untar", "tar", "b64"};
                    default:
                        return new String[]{"b64", "zip"};
                }
            default:
                return new String[]{"b64", "zip", "tar", "gz"};
        }
    }

    public static Output run(SourceFile src, Formats.FormatSpec target,
                             Map<String, Object> settings, Util.Progress progress) throws Exception {
        final Util.Progress parent = progress;
        Util.Progress readProgress = new Util.Progress() {
            @Override
            public void onProgress(int percent, String message) {
                if (parent != null) parent.onProgress(percent * 5 / 100, message);
            }

            @Override
            public boolean isCancelled() {
                return parent != null && parent.isCancelled();
            }
        };
        src.ensureCached(readProgress);
        if (progress != null && progress.isCancelled()) throw new InterruptedException("Отменено");

        String id = target.id;
        if ("b64".equals(id) || "unb64".equals(id)) {
            return Base64Conv.convert(src, target, settings, progress);
        }
        if ("zip".equals(id) || "tar".equals(id) || "gz".equals(id)
                || "unzip".equals(id) || "untar".equals(id) || "ungz".equals(id)) {
            return ArchiveConv.convert(src, target, settings, progress);
        }
        if ("wav".equals(id) || "m4a".equals(id)) {
            return AudioConv.convert(src, target, settings, progress);
        }
        boolean docSource = src.category == Formats.Category.DOCUMENT;
        boolean docTarget = "txt".equals(id) || "md".equals(id) || "html".equals(id)
                || "csv".equals(id) || "tsv".equals(id) || "json".equals(id);
        if (docSource && docTarget) {
            return DocConv.convert(src, target, settings, progress);
        }
        if (src.category == Formats.Category.IMAGE) {
            return ImageConv.convert(src, target, settings, progress);
        }
        return TextConv.convert(src, target, settings, progress);
    }

    private Convert() {
    }
}
