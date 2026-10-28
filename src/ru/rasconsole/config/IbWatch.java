package ru.rasconsole.config;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Наблюдение за информационными базами: кто вошёл в базу и кто вышел.
 * <p>
 * Список баз — в {@code watch.properties}. При каждом фоновом опросе сеансы наблюдаемых баз сравниваются
 * с прошлым опросом, изменения пишутся в {@code sessions-ГГГГ-ММ.tsv} в папке истории.
 * Сеанс короче интервала опроса может не попасть в журнал.
 */
public final class IbWatch {

    /** Наблюдаемая база. */
    public record Entry(String address, UUID clusterId, String clusterName, UUID ibId, String ibName) {
        String key() {
            return address + "|" + clusterId + "|" + ibId;
        }
    }

    /** Сеанс на момент опроса. */
    public record Session(UUID sid, int number, String user, String host, String app, Date startedAt) {
    }

    /** Строка журнала. */
    public record Event(String time, String event, String server, String cluster, String ib, String session,
                        String user, String host, String app, String startedAt) {
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final Path file;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    /** Сеансы базы при прошлом опросе: ключ базы → sid → сеанс. */
    private final Map<String, Map<UUID, Session>> last = new HashMap<>();
    private volatile Path logDir;

    public IbWatch(Path dir, Path logDir) {
        this.file = dir.resolve("watch.properties");
        this.logDir = logDir;
        load();
    }

    public void setLogDir(Path dir) {
        this.logDir = dir;
    }

    public Path logDir() {
        return logDir;
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean contains(String address, UUID clusterId, UUID ibId) {
        for (Entry e : entries) {
            if (e.address().equals(address) && e.clusterId().equals(clusterId) && e.ibId().equals(ibId)) return true;
        }
        return false;
    }

    /** Есть ли в кластере наблюдаемые базы (чтобы не разбирать сеансы зря). */
    public boolean watchesCluster(String address, UUID clusterId) {
        for (Entry e : entries) if (e.address().equals(address) && e.clusterId().equals(clusterId)) return true;
        return false;
    }

    public synchronized void add(Entry e) {
        if (contains(e.address(), e.clusterId(), e.ibId())) return;
        entries.add(e);
        save();
    }

    public synchronized void remove(String address, UUID clusterId, UUID ibId) {
        entries.removeIf(e -> e.address().equals(address) && e.clusterId().equals(clusterId) && e.ibId().equals(ibId));
        last.remove(address + "|" + clusterId + "|" + ibId);
        save();
    }

    /**
     * Сравнить сеансы наблюдаемых баз кластера с прошлым опросом и записать входы и выходы.
     * @param sessions сеансы всех баз кластера по ID базы (вызывается из потока соединения)
     */
    public synchronized void update(String address, String serverName, UUID clusterId, String clusterName,
                                    Map<UUID, List<Session>> sessions) {
        List<String> lines = new ArrayList<>();
        String now = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date());
        for (Entry e : entries) {
            if (!e.address().equals(address) || !e.clusterId().equals(clusterId)) continue;
            Map<UUID, Session> cur = new HashMap<>();
            for (Session s : sessions.getOrDefault(e.ibId(), List.of())) cur.put(s.sid(), s);
            Map<UUID, Session> prev = last.get(e.key());
            if (prev == null) {
                // первый опрос после запуска: кто уже в базе
                for (Session s : cur.values()) lines.add(line(now, "в базе", serverName, clusterName, e.ibName(), s));
            } else {
                for (Session s : cur.values()) if (!prev.containsKey(s.sid())) lines.add(line(now, "вход", serverName, clusterName, e.ibName(), s));
                for (Session s : prev.values()) if (!cur.containsKey(s.sid())) lines.add(line(now, "выход", serverName, clusterName, e.ibName(), s));
            }
            last.put(e.key(), cur);
        }
        if (!lines.isEmpty()) append(lines);
    }

    private static String line(String now, String event, String server, String cluster, String ib, Session s) {
        return String.join("\t", now, event, clean(server), clean(cluster), clean(ib), Integer.toString(s.number()),
            clean(s.user()), clean(s.host()), clean(s.app()),
            s.startedAt() == null ? "" : new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(s.startedAt()));
    }

    /** Без табуляций и переводов строк; не формула Excel (файл открывают в Excel). */
    private static String clean(String s) {
        return s == null ? "" : ru.rasconsole.ui.table.EntityTable.safeCell(s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
    }

    private void append(List<String> lines) {
        Path dir = logDir;
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Path f = dir.resolve("sessions-" + LocalDate.now().format(MONTH) + ".tsv");
            boolean fresh = !Files.exists(f);
            try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) w.write("Время\tСобытие\tСервер\tКластер\tИБ\tСеанс\tПользователь\tКомпьютер\tПриложение\tНачало сеанса\n");
                for (String l : lines) {
                    w.write(l);
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            System.err.println("Журнал входов: " + e.getMessage());
        }
    }

    /** Журнал за текущий и прошлый месяц (новые записи — первыми). */
    public List<Event> read(int maxRows) {
        List<Event> r = new ArrayList<>();
        Path dir = logDir;
        if (dir == null) return r;
        LocalDate today = LocalDate.now();
        for (LocalDate m : new LocalDate[] {today.minusMonths(1), today}) {
            Path f = dir.resolve("sessions-" + m.format(MONTH) + ".tsv");
            if (!Files.exists(f)) continue;
            try {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 1; i < lines.size(); i++) {
                    String[] p = lines.get(i).split("\t", -1);
                    if (p.length < 10) continue;
                    r.add(new Event(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9]));
                }
            } catch (IOException e) {
                System.err.println("Журнал входов: " + e.getMessage());
            }
        }
        java.util.Collections.reverse(r);
        return r.size() > maxRows ? new ArrayList<>(r.subList(0, maxRows)) : r;
    }

    // ---- файл ----

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader rd = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(rd);
        } catch (IOException e) {
            System.err.println("watch.properties: " + e.getMessage());
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
                p.store(w, "RAS console: watched infobases");
            }
        } catch (IOException e) {
            System.err.println("watch.properties: " + e.getMessage());
        }
    }
}
