package com.formatforge.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Formats {

    public enum Category {
        IMAGE("Изображения", 0xFF6D8BFF),
        AUDIO("Аудио", 0xFF58E1C1),
        VIDEO("Видео", 0xFFFFB35C),
        TEXT("Текст и код", 0xFFB78BFF),
        DOCUMENT("Документы", 0xFFFF8FA3),
        ARCHIVE("Архивы", 0xFFFFD166),
        BINARY("Бинарные данные", 0xFF8FA3C4);

        public final String title;
        public final int color;

        Category(String title, int color) {
            this.title = title;
            this.color = color;
        }
    }

    public static final class SettingSpec {
        public enum Kind { SLIDER, CHOICE, SWITCH, TEXT }

        public Kind kind;
        public String key;
        public String title;
        public String hint;
        public int min, max, step, def, defIndex;
        public String[] labels;
        public Object[] values;
        public boolean defBool;
        public String defText;

        public static SettingSpec slider(String key, String title, int min, int max, int step, int def) {
            SettingSpec s = new SettingSpec();
            s.kind = Kind.SLIDER;
            s.key = key; s.title = title;
            s.min = min; s.max = max; s.step = step; s.def = def;
            return s;
        }

        public static SettingSpec choice(String key, String title, String[] labels, Object[] values, int defIndex) {
            SettingSpec s = new SettingSpec();
            s.kind = Kind.CHOICE;
            s.key = key; s.title = title;
            s.labels = labels; s.values = values; s.defIndex = defIndex;
            return s;
        }

        public static SettingSpec chooser(String key, String title, String hint, String[] labels, Object[] values, int defIndex) {
            SettingSpec s = choice(key, title, labels, values, defIndex);
            s.hint = hint;
            return s;
        }

        public static SettingSpec sw(String key, String title, boolean def) {
            SettingSpec s = new SettingSpec();
            s.kind = Kind.SWITCH;
            s.key = key; s.title = title; s.defBool = def;
            return s;
        }

        public static SettingSpec text(String key, String title, String def, String hint) {
            SettingSpec s = new SettingSpec();
            s.kind = Kind.TEXT;
            s.key = key; s.title = title; s.defText = def; s.hint = hint;
            return s;
        }

        public Object defaultValue() {
            switch (kind) {
                case SLIDER: return def;
                case CHOICE: return values[defIndex];
                case SWITCH: return defBool;
                default: return defText;
            }
        }
    }

    public static final class FormatSpec {
        public final String id;
        public final String label;
        public final String ext;
        public final String mime;
        public final Category category;
        public final String description;
        public final List<SettingSpec> settings = new ArrayList<SettingSpec>();
        public boolean multiOutput = false;

        public FormatSpec(String id, String label, String ext, String mime, Category category, String description) {
            this.id = id; this.label = label; this.ext = ext;
            this.mime = mime; this.category = category; this.description = description;
        }

        public FormatSpec def(SettingSpec... specs) {
            settings.addAll(Arrays.asList(specs));
            return this;
        }

        public FormatSpec def(List<SettingSpec> specs) {
            settings.addAll(specs);
            return this;
        }

        public Map<String, Object> defaults() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (SettingSpec s : settings) m.put(s.key, s.defaultValue());
            return m;
        }
    }

    public static final Map<String, Category> EXT_CATEGORY = new HashMap<String, Category>();
    public static final Map<String, String> EXT_MIME = new HashMap<String, String>();
    public static final List<FormatSpec> TARGETS = new ArrayList<FormatSpec>();
    public static final Map<String, FormatSpec> BY_ID = new LinkedHashMap<String, FormatSpec>();

    private static final String[] ENC_LABELS = {"Авто", "UTF-8", "UTF-16 LE", "UTF-16 BE", "Windows-1251", "ISO-8859-1"};
    private static final Object[] ENC_VALUES = {"auto", "UTF-8", "UTF-16LE", "UTF-16BE", "windows-1251", "ISO-8859-1"};

    private static final String[] EOL_LABELS = {"LF · Unix", "CRLF · Windows", "CR · Mac"};
    private static final Object[] EOL_VALUES = {"\n", "\r\n", "\r"};

    private static final String[] DIM_LABELS = {"Оригинал", "4096 px", "2048 px", "1600 px", "1280 px", "1024 px", "800 px", "512 px"};
    private static final Object[] DIM_VALUES = {0, 4096, 2048, 1600, 1280, 1024, 800, 512};

    private static final String[] FIT_LABELS = {"Вписать", "Квадрат", "Растянуть"};
    private static final Object[] FIT_VALUES = {"inside", "square", "stretch"};

    private static final String[] ROT_LABELS = {"Как в файле", "0°", "90°", "180°", "270°"};
    private static final Object[] ROT_VALUES = {-1, 0, 90, 180, 270};

    private static final String[] BG_LABELS = {"Белый", "Чёрный", "Серый"};
    private static final Object[] BG_VALUES = {"white", "black", "gray"};

    private static final String[] RATE_LABELS = {"8 000 Гц", "11 025 Гц", "16 000 Гц", "22 050 Гц", "32 000 Гц", "44 100 Гц", "48 000 Гц", "96 000 Гц"};
    private static final Object[] RATE_VALUES = {8000, 11025, 16000, 22050, 32000, 44100, 48000, 96000};

    private static final String[] CH_LABELS = {"Моно", "Стерео"};
    private static final Object[] CH_VALUES = {1, 2};

    private static final String[] DEPTH_LABELS = {"8 бит", "16 бит", "24 бита", "32 бита"};
    private static final Object[] DEPTH_VALUES = {8, 16, 24, 32};

    private static final String[] BR_LABELS = {"64 кбит/с", "96 кбит/с", "128 кбит/с", "192 кбит/с", "256 кбит/с", "320 кбит/с"};
    private static final Object[] BR_VALUES = {64000, 96000, 128000, 192000, 256000, 320000};

    private static final String[] ZIP_LEVEL_LABELS = {"Без сжатия", "Быстро", "Обычное", "Максимум"};
    private static final Object[] ZIP_LEVEL_VALUES = {0, 1, 6, 9};

    private static final String[] DELIM_LABELS = {"Авто", "Запятая", "Точка с запятой", "Табуляция", "Вертикаль"};
    private static final Object[] DELIM_VALUES = {"auto", ",", ";", "\t", "|"};

    static {
        map("image", Category.IMAGE, "png", "jpg", "jpeg", "jpe", "jfif", "gif", "bmp", "dib", "webp", "heic", "heif", "avif");
        map("audio", Category.AUDIO, "mp3", "m4a", "aac", "wav", "wave", "ogg", "oga", "opus", "flac", "amr", "awb", "mid", "midi", "mka");
        map("video", Category.VIDEO, "mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "ts", "mts", "avi", "flv", "wmv");
        map("text", Category.TEXT, "txt", "text", "md", "markdown", "html", "htm", "xhtml", "csv", "tsv", "json", "xml",
                "yaml", "yml", "log", "ini", "cfg", "conf", "srt", "vtt", "js", "mjs", "css", "java", "kt", "kts", "py",
                "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "sh", "bash", "zsh", "properties", "gradle",
                "sql", "tex", "nfo", "asc");
        map("doc", Category.DOCUMENT, "docx", "docm", "dotx", "odt", "ott", "pptx", "pptm", "odp", "otp", "xlsx", "xlsm", "ods", "ots", "epub", "fb2");
        map("archive", Category.ARCHIVE, "zip", "jar", "apk", "war", "ear", "ipa", "aab", "gz", "tgz", "tar", "7z", "rar", "bz2", "xz", "zst");

        // ---------- images ----------
        add(new FormatSpec("png", "PNG", "png", "image/png", Category.IMAGE,
                "Без потерь, поддерживает прозрачность")
                .def(SettingSpec.choice("maxDim", "Максимальный размер", DIM_LABELS, DIM_VALUES, 0),
                        SettingSpec.choice("fit", "Режим масштабирования", FIT_LABELS, FIT_VALUES, 0),
                        SettingSpec.choice("rotate", "Поворот", ROT_LABELS, ROT_VALUES, 0),
                        SettingSpec.sw("grayscale", "Оттенки серого", false)));

        add(new FormatSpec("jpg", "JPEG", "jpg", "image/jpeg", Category.IMAGE,
                "Компактный формат с настройкой качества")
                .def(SettingSpec.slider("quality", "Качество", 1, 100, 1, 90),
                        SettingSpec.choice("maxDim", "Максимальный размер", DIM_LABELS, DIM_VALUES, 0),
                        SettingSpec.choice("fit", "Режим масштабирования", FIT_LABELS, FIT_VALUES, 0),
                        SettingSpec.choice("rotate", "Поворот", ROT_LABELS, ROT_VALUES, 0),
                        SettingSpec.choice("bg", "Фон при прозрачности", BG_LABELS, BG_VALUES, 0),
                        SettingSpec.sw("grayscale", "Оттенки серого", false)));

        add(new FormatSpec("webp", "WebP", "webp", "image/webp", Category.IMAGE,
                "Современный формат: меньше вес, есть прозрачность")
                .def(SettingSpec.slider("quality", "Качество", 1, 100, 1, 88),
                        SettingSpec.choice("maxDim", "Максимальный размер", DIM_LABELS, DIM_VALUES, 0),
                        SettingSpec.choice("fit", "Режим масштабирования", FIT_LABELS, FIT_VALUES, 0),
                        SettingSpec.choice("rotate", "Поворот", ROT_LABELS, ROT_VALUES, 0),
                        SettingSpec.sw("grayscale", "Оттенки серого", false)));

        add(new FormatSpec("webpl", "WebP lossless", "webp", "image/webp", Category.IMAGE,
                "WebP без потерь (Android 11+)")
                .def(SettingSpec.choice("maxDim", "Максимальный размер", DIM_LABELS, DIM_VALUES, 0),
                        SettingSpec.choice("fit", "Режим масштабирования", FIT_LABELS, FIT_VALUES, 0),
                        SettingSpec.choice("rotate", "Поворот", ROT_LABELS, ROT_VALUES, 0),
                        SettingSpec.sw("grayscale", "Оттенки серого", false)));

        add(new FormatSpec("bmp", "BMP", "bmp", "image/bmp", Category.IMAGE,
                "Классический несжатый растровый формат")
                .def(SettingSpec.choice("maxDim", "Максимальный размер", DIM_LABELS, DIM_VALUES, 0),
                        SettingSpec.choice("fit", "Режим масштабирования", FIT_LABELS, FIT_VALUES, 0),
                        SettingSpec.choice("rotate", "Поворот", ROT_LABELS, ROT_VALUES, 0),
                        SettingSpec.sw("grayscale", "Оттенки серого", false)));

        // ---------- audio ----------
        add(new FormatSpec("wav", "WAV", "wav", "audio/wav", Category.AUDIO,
                "Несжатый PCM: точность без потерь")
                .def(SettingSpec.choice("rate", "Частота дискретизации", RATE_LABELS, RATE_VALUES, 5),
                        SettingSpec.choice("channels", "Каналы", CH_LABELS, CH_VALUES, 1),
                        SettingSpec.choice("depth", "Разрядность", DEPTH_LABELS, DEPTH_VALUES, 1),
                        SettingSpec.text("trimStart", "Обрезать начало, сек", "0", "0 — без обрезки"),
                        SettingSpec.text("trimEnd", "Обрезать конец, сек", "0", "0 — до конца")));

        add(new FormatSpec("m4a", "M4A · AAC", "m4a", "audio/mp4", Category.AUDIO,
                "Компактный аудиоформат, совместим с Android")
                .def(SettingSpec.choice("bitrate", "Битрейт", BR_LABELS, BR_VALUES, 3),
                        SettingSpec.choice("rate", "Частота дискретизации", RATE_LABELS, RATE_VALUES, 5),
                        SettingSpec.choice("channels", "Каналы", CH_LABELS, CH_VALUES, 1),
                        SettingSpec.text("trimStart", "Обрезать начало, сек", "0", "0 — без обрезки"),
                        SettingSpec.text("trimEnd", "Обрезать конец, сек", "0", "0 — до конца")));

        // ---------- text ----------
        add(new FormatSpec("txt", "TXT", "txt", "text/plain", Category.TEXT,
                "Простой текст с выбором кодировки")
                .def(textSettings()));

        add(new FormatSpec("md", "Markdown", "md", "text/markdown", Category.TEXT,
                "Разметка с заголовками и списками")
                .def(textSettings()));

        add(new FormatSpec("html", "HTML", "html", "text/html", Category.TEXT,
                "Веб-страница с готовым оформлением")
                .def(textSettings(SettingSpec.text("title", "Заголовок страницы", "", "Необязательно"))));

        add(new FormatSpec("csv", "CSV", "csv", "text/csv", Category.TEXT,
                "Таблица: значения через разделитель")
                .def(textSettings(
                        SettingSpec.choice("eol", "Переносы строк", EOL_LABELS, EOL_VALUES, 1),
                        SettingSpec.choice("delimiter", "Разделитель", DELIM_LABELS, DELIM_VALUES, 0),
                        SettingSpec.sw("header", "Первая строка — заголовки", true))));

        add(new FormatSpec("tsv", "TSV", "tsv", "text/tab-separated-values", Category.TEXT,
                "Таблица с разделением табуляцией")
                .def(textSettings(
                        SettingSpec.choice("eol", "Переносы строк", EOL_LABELS, EOL_VALUES, 1),
                        SettingSpec.choice("delimiter", "Разделитель", DELIM_LABELS, DELIM_VALUES, 3),
                        SettingSpec.sw("header", "Первая строка — заголовки", true))));

        add(new FormatSpec("json", "JSON", "json", "application/json", Category.TEXT,
                "Структурированные данные, удобно для API")
                .def(SettingSpec.choice("encoding", "Кодировка результата", ENC_LABELS, ENC_VALUES, 1),
                        SettingSpec.sw("pretty", "Форматировать с отступами", true),
                        SettingSpec.choice("eol", "Переносы строк", EOL_LABELS, EOL_VALUES, 0),
                        SettingSpec.text("sheet", "Лист таблицы", "1", "Номер листа для XLSX/ODS")));

        add(new FormatSpec("xml", "XML", "xml", "application/xml", Category.TEXT,
                "Иерархические данные с форматированием")
                .def(SettingSpec.choice("encoding", "Кодировка результата", ENC_LABELS, ENC_VALUES, 1),
                        SettingSpec.sw("pretty", "Форматировать с отступами", true),
                        SettingSpec.choice("indent", "Отступ", new String[]{"2 пробела", "4 пробела", "Таб"}, new Object[]{2, 4, 9}, 0),
                        SettingSpec.choice("eol", "Переносы строк", EOL_LABELS, EOL_VALUES, 0)));

        add(new FormatSpec("b64", "Base64", "txt", "text/plain", Category.TEXT,
                "Текстовая упаковка любого файла")
                .def(SettingSpec.slider("wrap", "Длина строки", 0, 100, 4, 76)));

        // ---------- archive ----------
        add(new FormatSpec("zip", "ZIP", "zip", "application/zip", Category.ARCHIVE,
                "Упаковать файл в архив с настройкой сжатия")
                .def(SettingSpec.choice("level", "Сжатие", ZIP_LEVEL_LABELS, ZIP_LEVEL_VALUES, 2),
                        SettingSpec.text("entryName", "Имя внутри архива", "", "Пусто — исходное имя")));

        add(new FormatSpec("tar", "TAR", "tar", "application/x-tar", Category.ARCHIVE,
                "Архив без сжатия, стандарт Unix")
                .def(SettingSpec.text("entryName", "Имя внутри архива", "", "Пусто — исходное имя")));

        add(new FormatSpec("gz", "GZIP", "gz", "application/gzip", Category.ARCHIVE,
                "Сжатие GZIP для одного файла"));

        FormatSpec unzip = new FormatSpec("unzip", "Распаковать ZIP", "zip", "application/zip", Category.ARCHIVE,
                "Извлечь все файлы из ZIP-архива");
        unzip.multiOutput = true;
        add(unzip);

        FormatSpec untar = new FormatSpec("untar", "Распаковать TAR", "tar", "application/x-tar", Category.ARCHIVE,
                "Извлечь все файлы из TAR-архива");
        untar.multiOutput = true;
        add(untar);

        FormatSpec ungz = new FormatSpec("ungz", "Распаковать GZIP", "gz", "application/gzip", Category.ARCHIVE,
                "Распаковать GZIP-файл");
        ungz.multiOutput = true;
        add(ungz);

        add(new FormatSpec("unb64", "Base64 → файл", "bin", "application/octet-stream", Category.TEXT,
                "Декодировать Base64 обратно в файл")
                .def(SettingSpec.text("ext", "Расширение результата", "bin", "например jpg, pdf, zip")));

        for (FormatSpec f : TARGETS) BY_ID.put(f.id, f);
    }

    private static List<SettingSpec> textSettings(SettingSpec... extra) {
        List<SettingSpec> list = new ArrayList<SettingSpec>();
        list.add(SettingSpec.chooser("srcEncoding", "Кодировка источника", "Авто — определить автоматически", ENC_LABELS, ENC_VALUES, 0));
        list.add(SettingSpec.choice("encoding", "Кодировка результата", ENC_LABELS, ENC_VALUES, 1));
        list.add(SettingSpec.choice("eol", "Переносы строк", EOL_LABELS, EOL_VALUES, 0));
        list.add(SettingSpec.sw("bom", "BOM в начале файла", false));
        list.addAll(Arrays.asList(extra));
        list.add(SettingSpec.text("sheet", "Лист таблицы", "1", "Номер листа для XLSX/ODS"));
        return list;
    }

    private static void map(String mimePrefix, Category cat, String... exts) {
        for (String e : exts) {
            EXT_CATEGORY.put(e, cat);
            EXT_MIME.put(e, mimePrefix);
        }
    }

    private static void add(FormatSpec f) {
        TARGETS.add(f);
    }

    public static String mimeForExt(String ext) {
        FormatSpec f = BY_ID.get(ext);
        if (f != null) return f.mime;
        String p = EXT_MIME.get(ext);
        if (p == null) return "application/octet-stream";
        switch (p) {
            case "image": return "image/" + ext;
            case "audio": return "audio/" + ext;
            case "video": return "video/" + ext;
            default: return "application/octet-stream";
        }
    }

    private Formats() {
    }
}
