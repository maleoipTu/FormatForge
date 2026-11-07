package com.formatforge.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TextConv {

    public static Output convert(SourceFile src, Formats.FormatSpec target,
                                 Map<String, Object> s, Util.Progress p) throws Exception {
        byte[] data = Util.readAll(src.cache);
        String text = Util.decodeText(data, ImageConv.str(s, "srcEncoding", "auto"));
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        step(p, 25, "Преобразование…");

        String result;
        switch (target.id) {
            case "txt":
                result = toTxt(src, text);
                break;
            case "md":
                result = toMd(src, text);
                break;
            case "html":
                result = toHtml(src, text, s);
                break;
            case "csv":
                result = toTable(src, text, s, ",");
                break;
            case "tsv":
                result = toTable(src, text, s, "\t");
                break;
            case "json":
                result = toJson(src, text, s);
                break;
            case "xml":
                result = toXml(src, text, s);
                break;
            default:
                throw new Exception("Неподдерживаемое преобразование");
        }
        step(p, 75, "Запись…");

        String outEnc = ImageConv.str(s, "encoding", "UTF-8");
        String eol = ImageConv.str(s, "eol", "\n");
        boolean bom = ImageConv.bool(s, "bom", false);
        File f = Util.newTempFile(src.safeBaseName() + "-tf." + target.ext);
        Util.writeAll(f, Util.encodeText(result, outEnc, eol, bom));

        Output o = new Output();
        o.add(f, src.safeBaseName() + "." + target.ext, target.mime);
        o.summary = Util.humanSize(data.length) + " → " + Util.humanSize(f.length());
        step(p, 100, "Готово");
        return o;
    }

    // ---------------- source detection ----------------

    static boolean isHtml(String ext) {
        return "html".equals(ext) || "htm".equals(ext) || "xhtml".equals(ext);
    }

    static boolean isMarkdown(String ext) {
        return "md".equals(ext) || "markdown".equals(ext);
    }

    static boolean isCsv(String ext) {
        return "csv".equals(ext) || "tsv".equals(ext);
    }

    static boolean isJson(String ext) {
        return "json".equals(ext);
    }

    static boolean isXml(String ext) {
        return "xml".equals(ext);
    }

    // ---------------- targets ----------------

    static String toTxt(SourceFile src, String text) {
        String ext = src.ext;
        if (isHtml(ext)) return Util.stripHtml(text);
        if (isMarkdown(ext)) return stripMarkdown(text);
        if (isCsv(ext)) {
            List<String[]> rows = parseRows(text, detectDelimiter(text));
            StringBuilder sb = new StringBuilder();
            for (String[] r : rows) sb.append(join(r, "\t")).append('\n');
            return sb.toString().trim();
        }
        if (isJson(ext)) return prettyJson(text);
        return text;
    }

    static String toMd(SourceFile src, String text) {
        String ext = src.ext;
        if (isHtml(ext)) return htmlToMd(text);
        if (isCsv(ext)) return csvToMdTable(parseRows(text, detectDelimiter(text)));
        if (isJson(ext)) return "```json\n" + prettyJson(text) + "\n```";
        if (isXml(ext)) return "```xml\n" + text + "\n```";
        return text;
    }

    static String toHtml(SourceFile src, String text, Map<String, Object> s) {
        String ext = src.ext;
        String title = ImageConv.str(s, "title", "");
        if (title.isEmpty()) title = src.safeBaseName();
        String body;
        if (isHtml(ext)) {
            return text;
        } else if (isMarkdown(ext)) {
            body = mdToHtml(text);
        } else if (isCsv(ext)) {
            body = csvToHtmlTable(parseRows(text, detectDelimiter(text)), ImageConv.bool(s, "header", true));
        } else if (isJson(ext)) {
            body = "<pre><code>" + Util.escapeHtml(prettyJson(text)) + "</code></pre>";
        } else if (isXml(ext)) {
            body = "<pre><code>" + Util.escapeHtml(formatXml(text, 2, true)) + "</code></pre>";
        } else {
            body = textToHtml(text);
        }
        return wrapHtmlPage(title, body);
    }

    static String toTable(SourceFile src, String text, Map<String, Object> s, String targetDelim) throws Exception {
        String ext = src.ext;
        if (isJson(ext)) {
            return jsonToCsv(text, targetDelim, ImageConv.bool(s, "header", true));
        }
        if (isHtml(ext)) {
            text = Util.stripHtml(text);
        }
        if (isMarkdown(ext)) {
            text = stripMarkdown(text);
        }
        List<String[]> rows;
        if (isCsv(ext)) {
            rows = parseRows(text, detectDelimiter(text));
        } else {
            String delim = detectDelimiter(text);
            rows = parseRows(text, delim);
        }
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) sb.append(targetDelim);
                sb.append(quoteCsv(row[i], targetDelim));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String toJson(SourceFile src, String text, Map<String, Object> s) throws Exception {
        String ext = src.ext;
        String json;
        if (isCsv(ext)) {
            json = csvToJson(parseRows(text, detectDelimiter(text)), ImageConv.bool(s, "header", true));
        } else if (isJson(ext)) {
            json = text;
        } else {
            JSONObject obj = new JSONObject();
            obj.put("file", src.name);
            obj.put("content", isHtml(ext) ? Util.stripHtml(text) : text);
            json = obj.toString();
        }
        return ImageConv.bool(s, "pretty", true) ? prettyJson(json) : json;
    }

    static String toXml(SourceFile src, String text, Map<String, Object> s) throws Exception {
        String ext = src.ext;
        String xml;
        if (isJson(ext)) {
            xml = jsonToXml(text);
        } else if (isCsv(ext)) {
            List<String[]> rows = parseRows(text, detectDelimiter(text));
            StringBuilder sb = new StringBuilder("<table>");
            for (String[] row : rows) {
                sb.append("<row>");
                for (String cell : row) sb.append("<cell>").append(Util.escapeHtml(cell)).append("</cell>");
                sb.append("</row>");
            }
            sb.append("</table>");
            xml = sb.toString();
        } else if (isHtml(ext)) {
            xml = "<document><content>" + Util.escapeHtml(Util.stripHtml(text)) + "</content></document>";
        } else {
            StringBuilder sb = new StringBuilder("<document>");
            for (String line : text.split("\n", -1)) {
                sb.append("<line>").append(Util.escapeHtml(line)).append("</line>");
            }
            sb.append("</document>");
            xml = sb.toString();
        }
        return formatXml(xml, ImageConv.num(s, "indent", 2), ImageConv.bool(s, "pretty", true));
    }

    // ---------------- markdown ----------------

    static String stripMarkdown(String md) {
        String s = md.replaceAll("(?m)^#{1,6}\\s*", "");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        s = s.replaceAll("__(.+?)__", "$1");
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "$1");
        s = s.replaceAll("`([^`]+)`", "$1");
        s = s.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("(?m)^\\s*>\\s?", "");
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "• ");
        s = s.replaceAll("```[a-zA-Z0-9]*\\n?", "");
        return s;
    }

    static String mdToHtml(String md) {
        StringBuilder out = new StringBuilder();
        String[] lines = md.split("\n", -1);
        boolean inCode = false;
        boolean inList = false;
        boolean inQuote = false;
        StringBuilder para = new StringBuilder();
        for (String raw : lines) {
            String line = raw;
            if (line.trim().startsWith("```")) {
                flushPara(out, para);
                closeList(out, inList);
                inList = false;
                if (!inCode) out.append("<pre><code>");
                else out.append("</code></pre>");
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                out.append(Util.escapeHtml(line)).append('\n');
                continue;
            }
            if (line.trim().isEmpty()) {
                flushPara(out, para);
                closeList(out, inList);
                inList = false;
                continue;
            }
            if (line.matches("^\\s*(---|\\*\\*\\*)\\s*$")) {
                flushPara(out, para);
                out.append("<hr>");
                continue;
            }
            java.util.regex.Matcher h = java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(line);
            if (h.find()) {
                flushPara(out, para);
                closeList(out, inList);
                int level = h.group(1).length();
                out.append("<h").append(level).append(">")
                        .append(inline(h.group(2))).append("</h").append(level).append(">");
                continue;
            }
            if (line.matches("^\\s*>\\s?.*")) {
                flushPara(out, para);
                if (!inQuote) {
                    out.append("<blockquote>");
                    inQuote = true;
                }
                out.append("<p>").append(inline(line.replaceFirst("^\\s*>\\s?", ""))).append("</p>");
                continue;
            } else if (inQuote) {
                out.append("</blockquote>");
                inQuote = false;
            }
            if (line.matches("^\\s*[-*+]\\s+.*")) {
                flushPara(out, para);
                if (!inList) {
                    out.append("<ul>");
                    inList = true;
                }
                out.append("<li>").append(inline(line.replaceFirst("^\\s*[-*+]\\s+", ""))).append("</li>");
                continue;
            }
            if (inList) {
                out.append("</ul>");
                inList = false;
            }
            if (para.length() > 0) para.append(' ');
            para.append(line);
        }
        flushPara(out, para);
        closeList(out, inList);
        if (inQuote) out.append("</blockquote>");
        if (inCode) out.append("</code></pre>");
        return out.toString();
    }

    private static void closeList(StringBuilder out, boolean inList) {
        if (inList) out.append("</ul>");
    }

    private static void flushPara(StringBuilder out, StringBuilder para) {
        if (para.length() > 0) {
            out.append("<p>").append(inline(para.toString())).append("</p>");
            para.setLength(0);
        }
    }

    private static String inline(String src) {
        String s = Util.escapeHtml(src);
        s = s.replaceAll("!\\[([^\\]]*)\\]\\(([^)]+)\\)", "<img alt=\"$1\" src=\"$2\">");
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("__(.+?)__", "<strong>$1</strong>");
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<em>$1</em>");
        return s;
    }

    static String htmlToMd(String html) {
        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        s = s.replaceAll("(?is)<!--.*?-->", "");
        for (int i = 6; i >= 1; i--) {
            s = s.replaceAll("(?is)<h" + i + "[^>]*>(.*?)</h" + i + ">",
                    "\n" + repeat("#", i) + " $1\n");
        }
        s = s.replaceAll("(?is)<li[^>]*>(.*?)</li>", "\n- $1");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</(p|div|tr|section|article)>", "\n\n");
        s = s.replaceAll("(?is)<b[^>]*>(.*?)</b>", "**$1**");
        s = s.replaceAll("(?is)<strong[^>]*>(.*?)</strong>", "**$1**");
        s = s.replaceAll("(?is)<i[^>]*>(.*?)</i>", "*$1*");
        s = s.replaceAll("(?is)<em[^>]*>(.*?)</em>", "*$1*");
        s = s.replaceAll("(?is)<code[^>]*>(.*?)</code>", "`$1`");
        s = s.replaceAll("(?is)<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");
        s = s.replaceAll("(?is)<img[^>]*alt=\"([^\"]*)\"[^>]*src=\"([^\"]+)\"[^>]*>", "![$1]($2)");
        s = s.replaceAll("<[^>]+>", "");
        s = Util.unescapeEntities(s);
        s = s.replace("\u00A0", " ");
        s = s.replaceAll("[ \\t]+\\n", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static String textToHtml(String text) {
        StringBuilder sb = new StringBuilder();
        for (String block : text.split("\n\\s*\n")) {
            if (block.trim().isEmpty()) continue;
            sb.append("<p>").append(Util.escapeHtml(block.trim()).replace("\n", "<br>")).append("</p>\n");
        }
        return sb.toString();
    }

    static String wrapHtmlPage(String title, String body) {
        return "<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + Util.escapeHtml(title) + "</title>\n<style>\n"
                + ":root{color-scheme:light dark}\n"
                + "body{font-family:-apple-system,Roboto,'Segoe UI',sans-serif;max-width:820px;margin:0 auto;padding:32px 20px;line-height:1.65;color:#1a1f2b;background:#fff}\n"
                + "h1,h2,h3,h4{line-height:1.25;margin-top:1.4em}\n"
                + "pre{background:#f4f6fb;padding:16px;border-radius:12px;overflow:auto}\n"
                + "code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.92em}\n"
                + "table{border-collapse:collapse;width:100%;margin:1em 0}\n"
                + "th,td{border:1px solid #d7dce8;padding:8px 10px;text-align:left}\n"
                + "th{background:#f4f6fb}\n"
                + "blockquote{border-left:3px solid #6D8BFF;margin:1em 0;padding:.2em 1em;color:#555}\n"
                + "@media (prefers-color-scheme:dark){body{background:#12151f;color:#e6eaf5}pre,th{background:#1b2030}th,td{border-color:#2a3348}blockquote{color:#aab}}\n"
                + "</style>\n</head>\n<body>\n" + body + "\n</body>\n</html>\n";
    }

    // ---------------- tables ----------------

    static List<String[]> parseRows(String text, String delim) {
        List<String[]> rows = new ArrayList<String[]>();
        char d = delim.isEmpty() ? ',' : delim.charAt(0);
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == d) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row.toArray(new String[0]));
                row = new ArrayList<String>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row.toArray(new String[0]));
        }
        while (!rows.isEmpty()) {
            String[] last = rows.get(rows.size() - 1);
            if (last.length == 1 && last[0].trim().isEmpty()) rows.remove(rows.size() - 1);
            else break;
        }
        return rows;
    }

    static String detectDelimiter(String text) {
        int limit = Math.min(text.length(), 5000);
        String[] cands = {",", ";", "\t", "|"};
        int best = 0;
        String bestDelim = ",";
        for (String c : cands) {
            int count = 0;
            for (int i = 0; i < limit; i++) if (text.charAt(i) == c.charAt(0)) count++;
            if (count > best) {
                best = count;
                bestDelim = c;
            }
        }
        return bestDelim;
    }

    static String quoteCsv(String v, String delim) {
        if (v == null) return "";
        if (v.contains(delim) || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    static String join(String[] row, String delim) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) sb.append(delim);
            sb.append(row[i]);
        }
        return sb.toString();
    }

    static String csvToMdTable(List<String[]> rows) {
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        for (int r = 0; r < rows.size(); r++) {
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                String v = c < rows.get(r).length ? rows.get(r)[c] : "";
                sb.append(' ').append(v.replace("|", "\\|")).append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                for (int c = 0; c < cols; c++) sb.append(" --- |");
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    static String csvToHtmlTable(List<String[]> rows, boolean header) {
        if (rows.isEmpty()) return "";
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        StringBuilder sb = new StringBuilder("<table>");
        for (int r = 0; r < rows.size(); r++) {
            sb.append("<tr>");
            String tag = (header && r == 0) ? "th" : "td";
            for (int c = 0; c < cols; c++) {
                String v = c < rows.get(r).length ? rows.get(r)[c] : "";
                sb.append('<').append(tag).append('>').append(Util.escapeHtml(v))
                        .append("</").append(tag).append('>');
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    static String csvToJson(List<String[]> rows, boolean header) throws Exception {
        JSONArray arr = new JSONArray();
        if (rows.isEmpty()) return "[]";
        String[] keys;
        int start;
        if (header) {
            keys = rows.get(0);
            start = 1;
        } else {
            int cols = 0;
            for (String[] r : rows) cols = Math.max(cols, r.length);
            keys = new String[cols];
            for (int i = 0; i < cols; i++) keys[i] = "col" + (i + 1);
            start = 0;
        }
        for (int r = start; r < rows.size(); r++) {
            JSONObject o = new JSONObject();
            String[] row = rows.get(r);
            for (int c = 0; c < keys.length; c++) {
                o.put(keys[c], c < row.length ? row[c] : "");
            }
            arr.put(o);
        }
        return arr.toString();
    }

    static String jsonToCsv(String json, String delim, boolean header) throws Exception {
        String t = json.trim();
        JSONArray arr;
        if (t.startsWith("[")) {
            arr = new JSONArray(t);
        } else {
            arr = new JSONArray();
            arr.put(new JSONObject(t));
        }
        if (arr.length() == 0) return "";
        Set<String> keys = new LinkedHashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.get(i);
            if (o instanceof JSONObject) {
                Iterator<String> it = ((JSONObject) o).keys();
                while (it.hasNext()) keys.add(it.next());
            }
        }
        if (keys.isEmpty()) keys.add("value");
        StringBuilder sb = new StringBuilder();
        if (header) {
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(delim);
                sb.append(quoteCsv(k, delim));
                first = false;
            }
            sb.append('\n');
        }
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.get(i);
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(delim);
                String v;
                if (o instanceof JSONObject) {
                    JSONObject jo = (JSONObject) o;
                    v = jo.has(k) ? String.valueOf(jo.get(k)) : "";
                } else {
                    v = String.valueOf(o);
                }
                sb.append(quoteCsv(v, delim));
                first = false;
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------- json / xml ----------------

    static String prettyJson(String json) {
        try {
            String t = json.trim();
            if (t.startsWith("[")) return new JSONArray(t).toString(2);
            if (t.startsWith("{")) return new JSONObject(t).toString(2);
        } catch (Exception ignored) {
        }
        return json;
    }

    static String jsonToXml(String json) throws Exception {
        String t = json.trim();
        StringBuilder sb = new StringBuilder("<root>");
        if (t.startsWith("[")) {
            JSONArray arr = new JSONArray(t);
            for (int i = 0; i < arr.length(); i++) {
                sb.append("<item>");
                writeJsonXml(arr.get(i), sb);
                sb.append("</item>");
            }
        } else {
            writeJsonXml(new JSONObject(t), sb);
        }
        sb.append("</root>");
        return sb.toString();
    }

    private static void writeJsonXml(Object v, StringBuilder sb) throws Exception {
        if (v == null || v == JSONObject.NULL) return;
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String tag = k.replaceAll("[^A-Za-z0-9_.-]", "_");
                if (tag.isEmpty() || !Character.isLetter(tag.charAt(0))) tag = "f_" + tag;
                sb.append('<').append(tag).append('>');
                writeJsonXml(o.get(k), sb);
                sb.append("</").append(tag).append('>');
            }
        } else if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.length(); i++) {
                sb.append("<item>");
                writeJsonXml(arr.get(i), sb);
                sb.append("</item>");
            }
        } else {
            sb.append(Util.escapeHtml(String.valueOf(v)));
        }
    }

    static String formatXml(String xml, int indentSize, boolean pretty) {
        if (!pretty) return xml.replaceAll(">\\s+<", "><").trim();
        String unit = indentSize == 9 ? "\t" : repeat(" ", Math.max(1, indentSize));
        StringBuilder out = new StringBuilder();
        int level = 0;
        int i = 0;
        int n = xml.length();
        while (i < n) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                appendText(out, xml.substring(i), level, unit);
                break;
            }
            if (lt > i) appendText(out, xml.substring(i, lt), level, unit);
            int gt = findTagEnd(xml, lt);
            if (gt < 0) {
                appendText(out, xml.substring(lt), level, unit);
                break;
            }
            String tag = xml.substring(lt, gt + 1);
            if (tag.startsWith("</")) {
                level = Math.max(0, level - 1);
                line(out, level, unit, tag);
            } else if (tag.startsWith("<?") || tag.startsWith("<!")) {
                line(out, level, unit, tag);
            } else if (tag.endsWith("/>")) {
                line(out, level, unit, tag);
            } else {
                line(out, level, unit, tag);
                level++;
            }
            i = gt + 1;
        }
        return out.toString().trim();
    }

    private static int findTagEnd(String s, int start) {
        boolean q = false;
        char quote = 0;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q) {
                if (c == quote) q = false;
            } else if (c == '"' || c == '\'') {
                q = true;
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static void appendText(StringBuilder out, String text, int level, String unit) {
        String t = text.trim();
        if (t.isEmpty()) return;
        line(out, level, unit, t);
    }

    private static void line(StringBuilder out, int level, String unit, String text) {
        for (int i = 0; i < level; i++) out.append(unit);
        out.append(text);
        if (level >= 0) out.append('\n');
    }

    private static void step(Util.Progress p, int v, String m) {
        if (p != null) p.onProgress(v, m);
    }

    private TextConv() {
    }
}
