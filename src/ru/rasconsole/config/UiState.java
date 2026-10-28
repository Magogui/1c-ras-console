package ru.rasconsole.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Состояние интерфейса между запусками (%APPDATA%/RasConsole/ui.properties):
 * окно, разделитель, раскрытые и выбранный узлы дерева, колонки таблиц.
 */
public final class UiState {

    private static final Properties props = new Properties();
    private static Path file;

    private UiState() {
    }

    public static synchronized void init(Path dir) {
        file = dir.resolve("ui.properties");
        props.clear();
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            System.err.println("Состояние интерфейса: " + e.getMessage());
        }
    }

    public static synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(w, "RAS console UI state");
            }
        } catch (IOException e) {
            System.err.println("Состояние интерфейса: " + e.getMessage());
        }
    }

    public static synchronized String get(String key) {
        return props.getProperty(key);
    }

    public static synchronized void put(String key, String value) {
        if (value == null) props.remove(key);
        else props.setProperty(key, value);
    }

    public static double getDouble(String key, double def) {
        try {
            String v = get(key);
            return v == null ? def : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean getBool(String key, boolean def) {
        String v = get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    /** Список строк, сохранённый как key.count / key.N. */
    public static synchronized List<String> getList(String key) {
        List<String> r = new ArrayList<>();
        int n;
        try {
            n = Integer.parseInt(props.getProperty(key + ".count", "0"));
        } catch (NumberFormatException e) {
            n = 0;
        }
        for (int i = 0; i < n; i++) {
            String v = props.getProperty(key + "." + i);
            if (v != null) r.add(v);
        }
        return r;
    }

    public static synchronized void putList(String key, List<String> values) {
        props.keySet().removeIf(k -> k.toString().startsWith(key + "."));
        props.setProperty(key + ".count", Integer.toString(values.size()));
        for (int i = 0; i < values.size(); i++) props.setProperty(key + "." + i, values.get(i));
    }
}
