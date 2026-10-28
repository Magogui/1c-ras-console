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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Избранные информационные базы со всех серверов ({@code favorites.properties}). */
public final class Favorites {

    /** Избранная база: сервер RAS, кластер и база. */
    public record Entry(String address, UUID clusterId, String clusterName, UUID ibId, String ibName) {
    }

    private final Path file;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Favorites(Path dir) {
        this.file = dir.resolve("favorites.properties");
        load();
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    public boolean contains(String address, UUID clusterId, UUID ibId) {
        return find(address, clusterId, ibId) != null;
    }

    private Entry find(String address, UUID clusterId, UUID ibId) {
        for (Entry e : entries) {
            if (e.address().equals(address) && e.clusterId().equals(clusterId) && e.ibId().equals(ibId)) return e;
        }
        return null;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public synchronized void add(Entry e) {
        if (find(e.address(), e.clusterId(), e.ibId()) != null) return;
        entries.add(e);
        changed();
    }

    public synchronized void remove(String address, UUID clusterId, UUID ibId) {
        Entry e = find(address, clusterId, ibId);
        if (e == null) return;
        entries.remove(e);
        changed();
    }

    /** Переместить запись на {@code delta} позиций (порядок в дереве). */
    public synchronized void move(Entry e, int delta) {
        List<Entry> list = new ArrayList<>(entries);
        int i = list.indexOf(e);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return;
        list.remove(i);
        list.add(j, e);
        entries.clear();
        entries.addAll(list);
        changed();
    }

    private void changed() {
        save();
        for (Runnable r : listeners) r.run();
    }

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader rd = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(rd);
        } catch (IOException e) {
            System.err.println("favorites.properties: " + e.getMessage());
            return;
        }
        int n = Integer.parseInt(p.getProperty("count", "0"));
        for (int i = 0; i < n; i++) {
            String pre = "ib." + i + ".";
            try {
                entries.add(new Entry(p.getProperty(pre + "server"), UUID.fromString(p.getProperty(pre + "cluster")),
                    p.getProperty(pre + "clusterName", ""), UUID.fromString(p.getProperty(pre + "id")), p.getProperty(pre + "name", "")));
            } catch (RuntimeException ignored) {
                // повреждённая запись пропускается
            }
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("count", Integer.toString(entries.size()));
        int i = 0;
        for (Entry e : entries) {
            String pre = "ib." + i++ + ".";
            p.setProperty(pre + "server", e.address());
            p.setProperty(pre + "cluster", e.clusterId().toString());
            p.setProperty(pre + "clusterName", e.clusterName() == null ? "" : e.clusterName());
            p.setProperty(pre + "id", e.ibId().toString());
            p.setProperty(pre + "name", e.ibName());
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "RAS console: favorite infobases");
            }
        } catch (IOException e) {
            System.err.println("favorites.properties: " + e.getMessage());
        }
    }
}
