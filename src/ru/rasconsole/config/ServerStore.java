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
 * Список серверов RAS в %APPDATA%\RasConsole\servers.properties.
 * Пароли сюда не пишутся.
 */
public final class ServerStore {

    private final Path file;

    public ServerStore() {
        String appData = System.getenv("APPDATA");
        Path dir = appData != null ? Path.of(appData, "RasConsole") : Path.of(System.getProperty("user.home"), ".rasconsole");
        this.file = dir.resolve("servers.properties");
    }

    public Path file() {
        return file;
    }

    /** Каталог настроек (%APPDATA%\RasConsole). */
    public Path dir() {
        return file.getParent();
    }

    public List<ServerConfig> load() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            return read(file);
        } catch (IOException e) {
            System.err.println("Не удалось прочитать " + file + ": " + e);
            return new ArrayList<>();
        }
    }

    public void save(List<ServerConfig> servers) {
        try {
            write(file, servers);
        } catch (IOException e) {
            System.err.println("Не удалось сохранить " + file + ": " + e);
        }
    }

    /** Прочитать список серверов из файла (тот же формат используется для импорта). */
    public static List<ServerConfig> read(Path file) throws IOException {
        List<ServerConfig> result = new ArrayList<>();
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        // файл может прийти от другого человека (импорт): ограничиваем размер и проверяем адреса
        int count = Math.min(parseInt(p.getProperty("count"), 0), 10_000);
        for (int i = 0; i < count; i++) {
            String k = "server." + i + ".";
            String host = p.getProperty(k + "host");
            if (host == null || !ServerConfig.validHost(host.trim())) continue;
            if (!ServerConfig.validPort(parseInt(p.getProperty(k + "port"), ServerConfig.DEFAULT_PORT))) continue;
            result.add(new ServerConfig(
                p.getProperty(k + "name"),
                host,
                parseInt(p.getProperty(k + "port"), ServerConfig.DEFAULT_PORT),
                parseInt(p.getProperty(k + "timeout"), ServerConfig.DEFAULT_TIMEOUT_SEC),
                p.getProperty(k + "agentUser"),
                p.getProperty(k + "group"),
                Boolean.parseBoolean(p.getProperty(k + "prod"))));
        }
        return result;
    }

    /** Записать список серверов (без паролей). */
    public static void write(Path file, List<ServerConfig> servers) throws IOException {
        Properties p = new Properties();
        p.setProperty("count", Integer.toString(servers.size()));
        for (int i = 0; i < servers.size(); i++) {
            ServerConfig s = servers.get(i);
            String k = "server." + i + ".";
            p.setProperty(k + "name", s.name());
            p.setProperty(k + "host", s.host());
            p.setProperty(k + "port", Integer.toString(s.port()));
            p.setProperty(k + "timeout", Integer.toString(s.timeoutSec()));
            p.setProperty(k + "agentUser", s.agentUser());
            p.setProperty(k + "group", s.group());
            p.setProperty(k + "prod", Boolean.toString(s.prod()));
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "RAS console servers");
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
