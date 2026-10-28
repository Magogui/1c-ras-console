package ru.rasconsole.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Журнал действий администратора: %APPDATA%\RasConsole\audit.log, одна строка на действие,
 * поля разделены табуляцией: время, пользователь Windows, сервер, объект, действие, результат.
 */
public final class AuditLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long MAX_SIZE = 10L * 1024 * 1024;
    private static Path file;
    private static Path defaultDir;

    private AuditLog() {
    }

    /** Папка по умолчанию — каталог настроек; папка из настроек (audit.dir в settings.properties) имеет приоритет. */
    public static void init(Path dir) {
        defaultDir = dir;
        String custom = null;
        Path settings = dir.resolve("settings.properties");
        if (Files.exists(settings)) {
            java.util.Properties p = new java.util.Properties();
            try (java.io.Reader r = Files.newBufferedReader(settings, StandardCharsets.UTF_8)) {
                p.load(r);
                custom = p.getProperty("audit.dir");
            } catch (IOException e) {
                System.err.println("Журнал действий: " + e.getMessage());
            }
        }
        setDir(custom == null || custom.isBlank() ? null : Path.of(custom));
    }

    /** Сменить папку журнала; null — папка по умолчанию. */
    public static synchronized void setDir(Path dir) {
        Path d = dir != null ? dir : defaultDir;
        if (d != null) file = d.resolve("audit.log");
    }

    public static Path file() {
        return file;
    }

    /** Записать действие. Ошибки записи в журнал не мешают работе консоли. */
    public static synchronized void write(String server, String target, String action, String result) {
        if (file == null) return;
        String user = System.getenv("USERDOMAIN") == null ? System.getProperty("user.name")
            : System.getenv("USERDOMAIN") + "\\" + System.getProperty("user.name");
        String line = String.join("\t", LocalDateTime.now().format(TS), clean(user), clean(server), clean(target),
            clean(action), clean(result)) + System.lineSeparator();
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_SIZE) {
                Files.move(file, file.resolveSibling("audit.1.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Журнал действий: " + e.getMessage());
        }
    }

    /** Последние записи, новые сверху. Каждая запись — 6 полей. */
    public static synchronized List<String[]> tail(int max) {
        List<String[]> rows = new ArrayList<>();
        if (file == null || !Files.exists(file)) return rows;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && rows.size() < max; i--) {
                String[] f = lines.get(i).split("\t", -1);
                if (f.length >= 6) rows.add(f);
            }
        } catch (IOException e) {
            System.err.println("Журнал действий: " + e.getMessage());
        }
        return rows;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
