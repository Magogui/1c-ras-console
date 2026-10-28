package ru.rasconsole.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * История показателей кластеров (%APPDATA%/RasConsole/history/ГГГГ-ММ-ДД.csv): пишется при каждом опросе
 * сводки или фонового наблюдения, не чаще раза в минуту на кластер, хранится 30 дней.
 */
final class History {

    /** Одна точка. */
    record Point(long time, String server, String cluster, UUID clusterId, int sessions, int active, int hibernate,
                 int blocked, int processes, long rphostKb, long maxProcessKb, int licenses, long longestCallMs) {
    }

    private static final long MIN_INTERVAL_MS = 60_000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path dir;
    private final int keepDays;
    private final Map<UUID, Long> lastWrite = new HashMap<>();

    /** @param dir папка с файлами истории, @param keepDays сколько дней хранить */
    History(Path dir, int keepDays) {
        this.dir = dir;
        this.keepDays = Math.max(1, keepDays);
        cleanup();
    }

    Path dir() {
        return dir;
    }

    int keepDays() {
        return keepDays;
    }

    /** Записать показатели кластеров (кластеры без связи и без авторизации пропускаются). */
    synchronized void record(List<Monitor.Stats> stats) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Monitor.Stats s : stats) {
            if (s.clusterId() == null || !(s.status().equals("OK") || s.status().equals("есть проблемы"))) continue;
            Long prev = lastWrite.get(s.clusterId());
            if (prev != null && now - prev < MIN_INTERVAL_MS) continue;
            lastWrite.put(s.clusterId(), now);
            sb.append(now).append(';').append(clean(s.server().display())).append(';').append(clean(s.cluster())).append(';')
                .append(s.clusterId()).append(';').append(s.sessions()).append(';').append(s.active()).append(';')
                .append(s.hibernate()).append(';').append(s.blocked()).append(';').append(s.processes()).append(';')
                .append(s.rphostMemoryKb()).append(';').append(s.maxProcessMemoryKb()).append(';').append(s.licenses()).append(';')
                .append(s.longestCallMs()).append("\r\n");
        }
        if (sb.length() == 0) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(LocalDate.now().format(DAY) + ".csv"), sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("История: " + e.getMessage());
        }
    }

    /** Точки за последние {@code hours} часов. */
    synchronized List<Point> load(int hours) {
        long from = System.currentTimeMillis() - hours * 3_600_000L;
        List<Point> r = new ArrayList<>();
        LocalDate first = LocalDate.now().minusDays(hours / 24 + 1);
        for (LocalDate d = first; !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            Path f = dir.resolve(d.format(DAY) + ".csv");
            if (!Files.exists(f)) continue;
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] p = line.split(";", -1);
                    if (p.length < 13) continue;
                    long t = Long.parseLong(p[0]);
                    if (t < from) continue;
                    r.add(new Point(t, p[1], p[2], UUID.fromString(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                        Integer.parseInt(p[6]), Integer.parseInt(p[7]), Integer.parseInt(p[8]), Long.parseLong(p[9]),
                        Long.parseLong(p[10]), Integer.parseInt(p[11]), Long.parseLong(p[12])));
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("История " + f + ": " + e.getMessage());
            }
        }
        return r;
    }

    private void cleanup() {
        if (!Files.isDirectory(dir)) return;
        LocalDate limit = LocalDate.now().minusDays(keepDays);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.csv")) {
            for (Path f : ds) {
                String n = f.getFileName().toString().replace(".csv", "");
                try {
                    if (LocalDate.parse(n, DAY).isBefore(limit)) Files.deleteIfExists(f);
                } catch (RuntimeException ignored) {
                    // чужой файл — не трогаем
                }
            }
        } catch (IOException e) {
            System.err.println("История: " + e.getMessage());
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }
}
