package ru.rasconsole.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Шаблоны новых ИБ (%APPDATA%/RasConsole/ib-templates.properties): СУБД, сервер, пользователь, язык,
 * смещение дат, выдача лицензий. Пароль пользователя СУБД хранится отдельно, зашифрованным
 * в {@link CredentialStore}.
 */
public final class IbTemplates {

    /** Параметры шаблона. */
    public record Template(String name, String dbms, String dbServer, String dbUser, String locale, int dateOffset,
                           int licenseDistribution, boolean jobsDenied) {
    }

    private final Path file;
    private final Map<String, Template> templates = new LinkedHashMap<>();

    public IbTemplates(Path dir) {
        this.file = dir.resolve("ib-templates.properties");
        load();
    }

    public Map<String, Template> all() {
        return templates;
    }

    public void put(Template t) {
        templates.put(t.name(), t);
        save();
    }

    public void remove(String name) {
        templates.remove(name);
        save();
    }

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
            int n = Integer.parseInt(p.getProperty("count", "0"));
            for (int i = 0; i < n; i++) {
                String k = "tpl." + i + ".";
                String name = p.getProperty(k + "name");
                if (name == null) continue;
                templates.put(name, new Template(name, p.getProperty(k + "dbms", "PostgreSQL"), p.getProperty(k + "dbServer", ""),
                    p.getProperty(k + "dbUser", ""), p.getProperty(k + "locale", "ru"),
                    Integer.parseInt(p.getProperty(k + "dateOffset", "0")),
                    Integer.parseInt(p.getProperty(k + "licenseDistribution", "0")),
                    Boolean.parseBoolean(p.getProperty(k + "jobsDenied", "false"))));
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("Шаблоны ИБ: " + e.getMessage());
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("count", Integer.toString(templates.size()));
        int i = 0;
        for (Template t : templates.values()) {
            String k = "tpl." + i++ + ".";
            p.setProperty(k + "name", t.name());
            p.setProperty(k + "dbms", t.dbms());
            p.setProperty(k + "dbServer", t.dbServer());
            p.setProperty(k + "dbUser", t.dbUser());
            p.setProperty(k + "locale", t.locale());
            p.setProperty(k + "dateOffset", Integer.toString(t.dateOffset()));
            p.setProperty(k + "licenseDistribution", Integer.toString(t.licenseDistribution()));
            p.setProperty(k + "jobsDenied", Boolean.toString(t.jobsDenied()));
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "RAS console infobase templates");
            }
        } catch (IOException e) {
            System.err.println("Шаблоны ИБ: " + e.getMessage());
        }
    }
}
