package com.formatforge.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

public final class DocConv {

    private static final class Block {
        final String text;
        final int heading;

        Block(String text, int heading) {
            this.text = text;
            this.heading = heading;
        }
    }

    public static Output convert(SourceFile src, Formats.FormatSpec target,
                                 Map<String, Object> s, Util.Progress p) throws Exception {
        String kind = src.kind;
        String content;
        if ("xlsx".equals(kind) || "ods".equals(kind)) {
            List<String[]> table;
            if ("xlsx".equals(kind)) {
                ZipFile zf = new ZipFile(src.cache);
                try {
                    table = parseXlsx(zf, ImageConv.num(s, "sheet", 1));
                } finally {
                    zf.close();
                }
            } else {
                ZipFile zf = new ZipFile(src.cache);
                try {
                    table = parseOds(zf);
                } finally {
                    zf.close();
                }
            }
            step(p, 60, "Формирование таблицы…");
            content = tableTo(target.id, table, s, ImageConv.bool(s, "header", true));
        } else {
            List<Block> blocks;
            if ("fb2".equals(kind)) {
                blocks = parseFb2(src.cache);
            } else {
                ZipFile zf = new ZipFile(src.cache);
                try {
                    if ("docx".equals(kind)) blocks = parseDocx(zf);
                    else if ("odt".equals(kind) || "odp".equals(kind)) blocks = parseOdt(zf);
                    else if ("pptx".equals(kind)) blocks = parsePptx(zf);
                    else if ("epub".equals(kind)) blocks = parseEpub(zf);
                    else throw new Exception("Формат документа не поддерживается");
                } finally {
                    zf.close();
                }
            }
            step(p, 60, "Формирование текста…");
            if (blocks.isEmpty()) throw new Exception("Не удалось извлечь текст из документа");
            content = blocksTo(target.id, blocks, src, s);
        }

        String outEnc = ImageConv.str(s, "encoding", "UTF-8");
        String eol = ImageConv.str(s, "eol", "\n");
        boolean bom = ImageConv.bool(s, "bom", false);
        File out = Util.newTempFile(src.safeBaseName() + "-doc." + target.ext);
        Util.writeAll(out, Util.encodeText(content, outEnc, eol, bom));
        Output o = new Output();
        o.add(out, src.safeBaseName() + "." + target.ext, target.mime);
        o.summary = Util.humanSize(src.size) + " → " + Util.humanSize(out.length());
        step(p, 100, "Готово");
        return o;
    }

    private static String blocksTo(String id, List<Block> blocks, SourceFile src, Map<String, Object> s) {
        StringBuilder sb = new StringBuilder();
        if ("html".equals(id)) {
            String title = ImageConv.str(s, "title", "");
            if (title.isEmpty()) title = src.safeBaseName();
            for (Block b : blocks) {
                if (b.heading > 0) {
                    int h = Math.min(6, b.heading);
                    sb.append("<h").append(h).append('>')
                            .append(Util.escapeHtml(b.text)).append("</h").append(h).append(">\n");
                } else {
                    sb.append("<p>").append(Util.escapeHtml(b.text).replace("\n", "<br>")).append("</p>\n");
                }
            }
            return TextConv.wrapHtmlPage(title, sb.toString());
        }
        for (Block b : blocks) {
            if (b.heading > 0) {
                if ("md".equals(id)) {
                    sb.append(repeat("#", Math.min(6, b.heading))).append(' ');
                }
                sb.append(b.text).append("\n\n");
            } else {
                sb.append(b.text).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    // ---------------- DOCX ----------------

    private static List<Block> parseDocx(ZipFile zf) throws Exception {
        InputStream in = entry(zf, "word/document.xml");
        if (in == null) throw new Exception("Файл не похож на документ Word");
        List<Block> blocks = new ArrayList<Block>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = null;
            boolean inText = false;
            int heading = 0;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                String name = local(p.getName());
                if (ev == XmlPullParser.START_TAG) {
                    if ("p".equals(name)) {
                        sb = new StringBuilder();
                        heading = 0;
                    } else if ("pStyle".equals(name) && sb != null) {
                        heading = headingLevel(attr(p, "val"));
                    } else if ("outlineLvl".equals(name) && sb != null) {
                        String v = attr(p, "val");
                        if (v != null) {
                            try {
                                heading = Math.min(6, Integer.parseInt(v.trim()) + 1);
                            } catch (Exception ignored) {
                            }
                        }
                    } else if ("t".equals(name)) {
                        inText = true;
                    } else if ("br".equals(name) && sb != null) {
                        sb.append('\n');
                    } else if ("tab".equals(name) && sb != null) {
                        sb.append('\t');
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (inText && sb != null) sb.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("t".equals(name)) {
                        inText = false;
                    } else if ("p".equals(name) && sb != null) {
                        add(blocks, sb.toString(), heading);
                        sb = null;
                        heading = 0;
                    }
                }
            }
        } finally {
            in.close();
        }
        return blocks;
    }

    private static int headingLevel(String style) {
        if (style == null) return 0;
        String v = style.toLowerCase().replace(" ", "");
        if (v.contains("title")) return 1;
        for (int i = 1; i <= 6; i++) {
            if (v.equals("heading" + i) || v.equals("h" + i) || v.equals("überschrift" + i) || v.equals(String.valueOf(i))) {
                return i;
            }
        }
        return 0;
    }

    // ---------------- ODT ----------------

    private static List<Block> parseOdt(ZipFile zf) throws Exception {
        InputStream in = entry(zf, "content.xml");
        if (in == null) throw new Exception("Файл не похож на документ OpenDocument");
        List<Block> blocks = new ArrayList<Block>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = null;
            int heading = 0;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                String name = local(p.getName());
                if (ev == XmlPullParser.START_TAG) {
                    if ("h".equals(name)) {
                        sb = new StringBuilder();
                        String lvl = attr(p, "outline-level");
                        try {
                            heading = lvl == null ? 1 : Math.min(6, Math.max(1, Integer.parseInt(lvl.trim())));
                        } catch (Exception e) {
                            heading = 1;
                        }
                    } else if ("p".equals(name) && sb == null) {
                        sb = new StringBuilder();
                        heading = 0;
                    } else if ("tab".equals(name) && sb != null) {
                        sb.append('\t');
                    } else if ("line-break".equals(name) && sb != null) {
                        sb.append('\n');
                    } else if ("s".equals(name) && sb != null) {
                        String c = attr(p, "c");
                        int n = 1;
                        try {
                            n = c == null ? 1 : Math.max(1, Integer.parseInt(c.trim()));
                        } catch (Exception ignored) {
                        }
                        for (int i = 0; i < n; i++) sb.append(' ');
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (sb != null) sb.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    if (("h".equals(name) || "p".equals(name)) && sb != null) {
                        add(blocks, sb.toString(), heading);
                        sb = null;
                        heading = 0;
                    }
                }
            }
        } finally {
            in.close();
        }
        return blocks;
    }

    // ---------------- PPTX ----------------

    private static List<Block> parsePptx(ZipFile zf) throws Exception {
        final List<String> slides = new ArrayList<String>();
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.matches("ppt/slides/slide\\d+\\.xml")) slides.add(n);
        }
        Collections.sort(slides, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return number(a) - number(b);
            }
        });
        if (slides.isEmpty()) throw new Exception("В презентации не найдены слайды");
        List<Block> blocks = new ArrayList<Block>();
        int index = 0;
        for (String slide : slides) {
            index++;
            List<Block> slideBlocks = new ArrayList<Block>();
            InputStream in = entry(zf, slide);
            try {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = null;
                boolean inText = false;
                int ev;
                while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                    String name = local(p.getName());
                    if (ev == XmlPullParser.START_TAG) {
                        if ("p".equals(name)) {
                            sb = new StringBuilder();
                        } else if ("t".equals(name)) {
                            inText = true;
                        } else if ("br".equals(name) && sb != null) {
                            sb.append('\n');
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inText && sb != null) sb.append(p.getText());
                    } else if (ev == XmlPullParser.END_TAG) {
                        if ("t".equals(name)) {
                            inText = false;
                        } else if ("p".equals(name) && sb != null) {
                            add(slideBlocks, sb.toString(), 0);
                            sb = null;
                        }
                    }
                }
            } finally {
                in.close();
            }
            if (!slideBlocks.isEmpty()) {
                blocks.add(new Block("Слайд " + index, 2));
                blocks.addAll(slideBlocks);
            }
        }
        return blocks;
    }

    private static int number(String path) {
        try {
            int a = path.lastIndexOf("slide") + 5;
            int b = path.indexOf('.', a);
            return Integer.parseInt(path.substring(a, b));
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- EPUB ----------------

    private static List<Block> parseEpub(ZipFile zf) throws Exception {
        String opfPath = null;
        InputStream container = entry(zf, "META-INF/container.xml");
        if (container != null) {
            try {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new InputStreamReader(container, "UTF-8"));
                int ev;
                while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && "rootfile".equals(local(p.getName()))) {
                        opfPath = attr(p, "full-path");
                        if (opfPath != null) break;
                    }
                }
            } finally {
                container.close();
            }
        }
        if (opfPath == null) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.endsWith(".opf")) {
                    opfPath = n;
                    break;
                }
            }
        }
        if (opfPath == null) throw new Exception("Не найден OPF-файл EPUB");

        List<Block> blocks = new ArrayList<Block>();
        if (opfPath != null) {
            InputStream opfIn = entry(zf, opfPath);
            if (opfIn != null) {
                Map<String, String> manifest = new HashMap<String, String>();
                List<String> spine = new ArrayList<String>();
                try {
                    XmlPullParser p = Xml.newPullParser();
                    p.setInput(new InputStreamReader(opfIn, "UTF-8"));
                    int ev;
                    while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                        String name = local(p.getName());
                        if (ev == XmlPullParser.START_TAG) {
                            if ("item".equals(name)) {
                                String id = attr(p, "id");
                                String href = attr(p, "href");
                                if (id != null && href != null) manifest.put(id, href);
                            } else if ("itemref".equals(name)) {
                                String idref = attr(p, "idref");
                                if (idref != null) spine.add(idref);
                            }
                        }
                    }
                } finally {
                    opfIn.close();
                }
                String dir = "";
                int slash = opfPath.lastIndexOf('/');
                if (slash >= 0) dir = opfPath.substring(0, slash + 1);
                List<String> docs = new ArrayList<String>();
                if (!spine.isEmpty()) {
                    for (String id : spine) {
                        String href = manifest.get(id);
                        if (href != null) docs.add(resolve(dir, href));
                    }
                } else {
                    docs.addAll(manifest.values());
                }
                for (String doc : docs) {
                    InputStream in = entry(zf, doc);
                    if (in == null) continue;
                    String html;
                    try {
                        html = new String(Util.readAll(in, 0), "UTF-8");
                    } finally {
                        in.close();
                    }
                    String txt = Util.stripHtml(html);
                    if (!txt.trim().isEmpty()) blocks.add(new Block(txt.trim(), 0));
                }
            }
        }
        return blocks;
    }

    private static String resolve(String dir, String href) {
        try {
            href = URLDecoder.decode(href, "UTF-8");
        } catch (Exception ignored) {
        }
        int hash = href.indexOf('#');
        if (hash >= 0) href = href.substring(0, hash);
        if (href.startsWith("/")) return href.substring(1);
        return dir + href;
    }

    // ---------------- FB2 ----------------

    private static List<Block> parseFb2(File file) throws Exception {
        List<Block> blocks = new ArrayList<Block>();
        String xml = new String(Util.readAll(file), "UTF-8");
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new StringReader(xml));
        StringBuilder sb = null;
        int titleDepth = 0;
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
            String name = local(p.getName());
            if (ev == XmlPullParser.START_TAG) {
                if ("title".equals(name)) {
                    titleDepth++;
                } else if ("p".equals(name) || "subtitle".equals(name)) {
                    sb = new StringBuilder();
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (sb != null) sb.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                if ("title".equals(name)) {
                    titleDepth = Math.max(0, titleDepth - 1);
                } else if ("p".equals(name) || "subtitle".equals(name)) {
                    if (sb != null) {
                        add(blocks, sb.toString(), titleDepth > 0 ? 2 : 0);
                        sb = null;
                    }
                }
            }
        }
        return blocks;
    }

    // ---------------- XLSX ----------------

    private static List<String[]> parseXlsx(ZipFile zf, int sheetNumber) throws Exception {
        List<String> shared = new ArrayList<String>();
        InputStream ss = entry(zf, "xl/sharedStrings.xml");
        if (ss != null) {
            try {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new InputStreamReader(ss, "UTF-8"));
                StringBuilder cur = null;
                boolean inT = false;
                int ev;
                while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                    String name = local(p.getName());
                    if (ev == XmlPullParser.START_TAG) {
                        if ("si".equals(name)) cur = new StringBuilder();
                        else if ("t".equals(name)) inT = true;
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inT && cur != null) cur.append(p.getText());
                    } else if (ev == XmlPullParser.END_TAG) {
                        if ("t".equals(name)) inT = false;
                        else if ("si".equals(name) && cur != null) {
                            shared.add(cur.toString());
                            cur = null;
                        }
                    }
                }
            } finally {
                ss.close();
            }
        }

        List<String> sheets = new ArrayList<String>();
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.matches("xl/worksheets/sheet\\d+\\.xml")) sheets.add(n);
        }
        if (sheets.isEmpty()) throw new Exception("В книге не найдены листы");
        Collections.sort(sheets, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return number2(a) - number2(b);
            }
        });
        int idx = Math.max(1, Math.min(sheets.size(), sheetNumber)) - 1;
        InputStream in = entry(zf, sheets.get(idx));
        List<String[]> table = new ArrayList<String[]>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new InputStreamReader(in, "UTF-8"));
            List<String> row = null;
            int ev;
            StringBuilder vBuf = null;
            StringBuilder inline = null;
            boolean inV = false;
            boolean inInlineT = false;
            String type = "";
            int col = 0;
            int auto = 0;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                String name = local(p.getName());
                if (ev == XmlPullParser.START_TAG) {
                    if ("row".equals(name)) {
                        row = new ArrayList<String>();
                        auto = 0;
                    } else if ("c".equals(name) && row != null) {
                        String ref = attr(p, "r");
                        col = ref == null ? auto : colFromRef(ref);
                        auto = col + 1;
                        type = attr(p, "t");
                        if (type == null) type = "";
                        vBuf = new StringBuilder();
                        inline = null;
                        inV = false;
                        inInlineT = false;
                    } else if ("v".equals(name) && vBuf != null) {
                        inV = true;
                    } else if ("t".equals(name) && inline != null) {
                        inInlineT = true;
                    } else if ("is".equals(name)) {
                        inline = new StringBuilder();
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (inV && vBuf != null) vBuf.append(p.getText());
                    else if (inInlineT && inline != null) inline.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("v".equals(name)) {
                        inV = false;
                    } else if ("t".equals(name)) {
                        inInlineT = false;
                    } else if ("c".equals(name) && row != null) {
                        String value;
                        if ("s".equals(type)) {
                            try {
                                int si = Integer.parseInt(vBuf.toString().trim());
                                value = si >= 0 && si < shared.size() ? shared.get(si) : "";
                            } catch (Exception e) {
                                value = "";
                            }
                        } else if ("inlineStr".equals(type) && inline != null) {
                            value = inline.toString();
                        } else if ("b".equals(type)) {
                            value = "1".equals(vBuf.toString().trim()) ? "TRUE" : "FALSE";
                        } else {
                            value = vBuf.toString();
                        }
                        ensure(row, col);
                        row.set(col, value);
                        vBuf = null;
                        inline = null;
                    } else if ("row".equals(name) && row != null) {
                        table.add(row.toArray(new String[0]));
                        row = null;
                    }
                }
            }
        } finally {
            in.close();
        }
        return table;
    }

    private static int number2(String path) {
        try {
            int a = path.lastIndexOf("sheet") + 5;
            int b = path.indexOf('.', a);
            return Integer.parseInt(path.substring(a, b));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int colFromRef(String ref) {
        int v = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') v = v * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') v = v * 26 + (c - 'a' + 1);
            else break;
        }
        return Math.max(0, v - 1);
    }

    private static void ensure(List<String> row, int index) {
        while (row.size() <= index) row.add("");
    }

    // ---------------- ODS ----------------

    private static List<String[]> parseOds(ZipFile zf) throws Exception {
        InputStream in = entry(zf, "content.xml");
        if (in == null) throw new Exception("Файл не похож на таблицу OpenDocument");
        List<String[]> table = new ArrayList<String[]>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new InputStreamReader(in, "UTF-8"));
            List<String> row = null;
            StringBuilder cell = null;
            int repeat = 1;
            boolean inCell = false;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                String name = local(p.getName());
                if (ev == XmlPullParser.START_TAG) {
                    if ("table-row".equals(name)) {
                        row = new ArrayList<String>();
                    } else if ("table-cell".equals(name) && row != null) {
                        cell = new StringBuilder();
                        inCell = true;
                        repeat = parseRepeat(attr(p, "number-columns-repeated"));
                    } else if ("p".equals(name) && inCell && cell != null) {
                        if (cell.length() > 0) cell.append('\n');
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (inCell && cell != null) cell.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("table-cell".equals(name) && cell != null) {
                        String v = cell.toString().trim();
                        for (int i = 0; i < repeat; i++) row.add(v);
                        cell = null;
                        inCell = false;
                        repeat = 1;
                    } else if ("table-row".equals(name) && row != null) {
                        table.add(row.toArray(new String[0]));
                        row = null;
                    }
                }
            }
        } finally {
            in.close();
        }
        return table;
    }

    private static int parseRepeat(String v) {
        if (v == null) return 1;
        try {
            return Math.max(1, Math.min(1000, Integer.parseInt(v.trim())));
        } catch (Exception e) {
            return 1;
        }
    }

    // ---------------- table output ----------------

    private static String tableTo(String id, List<String[]> rows, Map<String, Object> s, boolean header) throws Exception {
        if (rows.isEmpty()) return "";
        if ("csv".equals(id)) return tableDelimited(rows, ImageConv.str(s, "delimiter", ","), false);
        if ("tsv".equals(id)) return tableDelimited(rows, "\t", false);
        if ("json".equals(id)) {
            String json = TextConv.csvToJson(rows, header);
            return ImageConv.bool(s, "pretty", true) ? TextConv.prettyJson(json) : json;
        }
        if ("html".equals(id)) {
            return TextConv.wrapHtmlPage("Таблица", TextConv.csvToHtmlTable(rows, true));
        }
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < r.length; i++) {
                if (i > 0) line.append(" | ");
                line.append(r[i]);
            }
            if (line.toString().trim().isEmpty()) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String tableDelimited(List<String[]> rows, String delim, boolean quoteAll) {
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(delim);
                if (quoteAll) sb.append('"').append(row[i].replace("\"", "\"\"")).append('"');
                else sb.append(TextConv.quoteCsv(row[i], delim));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------- helpers ----------------

    private static InputStream entry(ZipFile zf, String name) {
        java.util.zip.ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        try {
            return zf.getInputStream(e);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String local(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf(':');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static String attr(XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        if (v == null) {
            for (int i = 0; i < p.getAttributeCount(); i++) {
                String a = p.getAttributeName(i);
                if (name.equals(a) || (a != null && a.endsWith(":" + name))) {
                    return p.getAttributeValue(i);
                }
            }
        }
        return v;
    }

    private static void add(List<Block> blocks, String text, int heading) {
        if (text == null) return;
        String t = text.replace("\u00A0", " ").trim();
        if (t.isEmpty()) return;
        blocks.add(new Block(t, heading));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static void step(Util.Progress p, int v, String m) {
        if (p != null) p.onProgress(v, m);
    }

    private DocConv() {
    }
}
