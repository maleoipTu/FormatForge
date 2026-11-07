package com.formatforge.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class Output {

    public static final class Item {
        public final File file;
        public final String name;
        public final String mime;

        public Item(File file, String name, String mime) {
            this.file = file;
            this.name = name;
            this.mime = mime;
        }
    }

    public final List<Item> items = new ArrayList<Item>();
    public boolean multi;
    public String folder;
    public String summary;

    public Item first() {
        return items.isEmpty() ? null : items.get(0);
    }

    public void add(File file, String name, String mime) {
        items.add(new Item(file, name, mime));
    }
}
