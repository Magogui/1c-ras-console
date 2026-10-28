package ru.rasconsole.ras;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.ILicenseInfo;
import com._1c.v8.ibis.admin.IPortRangeInfo;
import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;
import com._1c.v8.ibis.admin.IWorkingServerInfo;

import ru.rasconsole.config.ServerConfig;
import ru.rasconsole.config.Xlsx;
import ru.rasconsole.config.Xlsx.Sheet;

/**
 * Отчёт по серверам для инвентаризации: серверы, кластеры, рабочие серверы, базы, процессы, лицензии.
 * Опрос без диалогов: кластеры без сохранённого пароля попадают в отчёт с пометкой «нужна авторизация».
 * Используется окном консоли и командой {@code RasCli report}.
 */
public final class ServerReport {

    private final Sheet servers = new Sheet("Серверы", List.of("Сервер", "Адрес", "Группа", "Продуктивный", "Состояние",
        "Версия RAS", "Кластеров", "Баз", "Сеансов", "Лицензий"));
    private final Sheet clusters = new Sheet("Кластеры", List.of("Сервер", "Кластер", "Компьютер", "Порт", "Состояние",
        "Рабочих серверов", "Баз", "Процессов", "Память процессов, МБ", "Сеансов", "Активных", "Спящих", "Лицензий",
        "Ожидают блокировок"));
    private final Sheet workers = new Sheet("Рабочие серверы", List.of("Сервер", "Кластер", "Имя", "Компьютер", "Порт",
        "Центральный", "Диапазоны портов", "Макс. память процессов, МБ", "ИБ на процесс", "Соединений на процесс"));
    private final Sheet bases = new Sheet("Информационные базы", List.of("Сервер", "Кластер", "Имя", "Описание", "Сеансов",
        "Пользователей", "Лицензий"));
    private final Sheet processes = new Sheet("Рабочие процессы", List.of("Сервер", "Кластер", "Компьютер", "Порт", "PID",
        "Активен", "Запущен", "Память, МБ", "Соединений", "Ср. время вызова, мс", "Доступная производительность"));
    private final Sheet licenses = new Sheet("Лицензии", List.of("Сервер", "Кластер", "Кому выдана", "Пользователь", "Компьютер",
        "ИБ", "Лицензия", "Серия / номер комплекта", "Сетевой ключ", "Выдана сервером"));

    /** Данные одного кластера (собираются в потоке соединения). */
    private record ClusterData(IClusterInfo info, String status, List<IWorkingServerInfo> workers,
                               List<IInfoBaseInfoShort> bases, List<IWorkingProcessInfo> processes, List<ISessionInfo> sessions) {
    }

    private record ServerData(ServerConfig cfg, String status, String version, List<ClusterData> clusters) {
    }

    /** Собрать отчёт по соединениям (параллельно по серверам). */
    public static CompletableFuture<ServerReport> collect(List<RasConnection> conns) {
        List<CompletableFuture<ServerData>> fs = new ArrayList<>();
        for (RasConnection c : conns) fs.add(server(c));
        return CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).thenApply(v -> {
            ServerReport r = new ServerReport();
            for (CompletableFuture<ServerData> f : fs) r.add(f.join());
            return r;
        });
    }

    private static CompletableFuture<ServerData> server(RasConnection conn) {
        ServerConfig cfg = conn.config();
        conn.resetRetry();
        return conn.submit(AuthKind.NONE, null, c -> c.getClusters()).thenCompose(list -> {
            List<CompletableFuture<ClusterData>> fs = new ArrayList<>();
            for (IClusterInfo ci : list) fs.add(cluster(conn, ci));
            return CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).thenApply(v -> {
                List<ClusterData> r = new ArrayList<>();
                for (CompletableFuture<ClusterData> f : fs) r.add(f.join());
                return new ServerData(cfg, "OK", conn.agentVersion(), r);
            });
        }).exceptionally(e -> new ServerData(cfg, "нет связи: " + NetErrors.describe(e), conn.agentVersion(), List.of()));
    }

    private static CompletableFuture<ClusterData> cluster(RasConnection conn, IClusterInfo ci) {
        UUID cid = ci.getClusterId();
        return conn.submit(AuthKind.CLUSTER, cid, c -> new ClusterData(ci, "OK", c.getWorkingServers(cid), c.getInfoBasesShort(cid),
            c.getWorkingProcesses(cid), c.getSessions(cid)))
            .exceptionally(e -> {
                Throwable t = e;
                while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException)) t = t.getCause();
                String status = t instanceof AuthRequiredException ? "нужна авторизация (пароль не сохранён)"
                    : "ошибка: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                return new ClusterData(ci, status, List.of(), List.of(), List.of(), List.of());
            });
    }

    private void add(ServerData s) {
        String srv = s.cfg().display();
        int baseCount = 0;
        int sessionCount = 0;
        int licenseCount = 0;
        for (ClusterData cd : s.clusters()) {
            String cl = cd.info().getName();
            Map<UUID, String> ibNames = new HashMap<>();
            for (IInfoBaseInfoShort b : cd.bases()) ibNames.put(b.getInfoBaseId(), b.getName());
            int active = 0;
            int hib = 0;
            int lic = 0;
            int blocked = 0;
            Map<UUID, Integer> ibSessions = new HashMap<>();
            Map<UUID, Integer> ibLicenses = new HashMap<>();
            Map<UUID, Set<String>> ibUsers = new HashMap<>();
            for (ISessionInfo ss : cd.sessions()) {
                if (ss.getDurationCurrent() > 0) active++;
                if (ss.getHibernate()) hib++;
                if (ss.getBlockedByLs() != 0 || ss.getBlockedByDbms() != 0) blocked++;
                ibSessions.merge(ss.getInfoBaseId(), 1, Integer::sum);
                ibUsers.computeIfAbsent(ss.getInfoBaseId(), k -> new HashSet<>()).add(String.valueOf(ss.getUserName()));
                if (ss.getLicenses() != null) {
                    for (ILicenseInfo l : ss.getLicenses()) {
                        lic++;
                        ibLicenses.merge(ss.getInfoBaseId(), 1, Integer::sum);
                        licenses.row(srv, cl, "Сеанс " + ss.getSessionId(), ss.getUserName(), ss.getHost(),
                            ibNames.getOrDefault(ss.getInfoBaseId(), ""), l.getShortPresentation(), l.getSeries(), l.isNet(),
                            l.isIssuedByServer());
                    }
                }
            }
            long mem = 0;
            for (IWorkingProcessInfo p : cd.processes()) {
                mem += p.getMemorySize();
                processes.row(srv, cl, p.getHostName(), p.getMainPort(), p.getPid(), p.getRunning() != 0, p.getStartedAt(),
                    p.getMemorySize() / 1024, p.getConnections(), p.getAvgCallTime(), p.getAvailablePerfomance());
                if (p.getLicense() != null) {
                    for (ILicenseInfo l : p.getLicense()) {
                        lic++;
                        licenses.row(srv, cl, "Процесс " + p.getHostName() + ":" + p.getMainPort(), "", p.getHostName(), "",
                            l.getShortPresentation(), l.getSeries(), l.isNet(), l.isIssuedByServer());
                    }
                }
            }
            for (IWorkingServerInfo w : cd.workers()) {
                workers.row(srv, cl, w.getName(), w.getHostName(), w.getMainPort(), w.isMainServer(), ranges(w.getPortRanges()),
                    w.getWorkingProcessMemoryLimit() / 1024 / 1024, w.getInfoBasesPerWorkingProcessLimit(),
                    w.getConnectionsPerWorkingProcessLimit());
            }
            for (IInfoBaseInfoShort b : cd.bases()) {
                bases.row(srv, cl, b.getName(), b.getDescr(), ibSessions.getOrDefault(b.getInfoBaseId(), 0),
                    ibUsers.getOrDefault(b.getInfoBaseId(), Set.of()).size(), ibLicenses.getOrDefault(b.getInfoBaseId(), 0));
            }
            boolean ok = cd.status().equals("OK");
            clusters.row(srv, cl, cd.info().getHostName(), cd.info().getMainPort(), cd.status(),
                ok ? cd.workers().size() : null, ok ? cd.bases().size() : null, ok ? cd.processes().size() : null,
                ok ? mem / 1024 : null, ok ? cd.sessions().size() : null, ok ? active : null, ok ? hib : null, ok ? lic : null,
                ok ? blocked : null);
            baseCount += cd.bases().size();
            sessionCount += cd.sessions().size();
            licenseCount += lic;
        }
        boolean up = s.status().equals("OK");
        servers.row(s.cfg().name(), s.cfg().address(), s.cfg().group(), s.cfg().prod(), s.status(), s.version(),
            up ? s.clusters().size() : null, up ? baseCount : null, up ? sessionCount : null, up ? licenseCount : null);
    }

    private static String ranges(List<IPortRangeInfo> list) {
        if (list == null) return "";
        StringBuilder sb = new StringBuilder();
        for (IPortRangeInfo r : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.getLowBound()).append(':').append(r.getHighBound());
        }
        return sb.toString();
    }

    public List<Sheet> sheets() {
        return List.of(servers, clusters, workers, bases, processes, licenses);
    }

    /** Записать в .xlsx или .html (по расширению файла). */
    public void write(Path file) throws java.io.IOException {
        String n = file.getFileName().toString().toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) {
            Xlsx.writeHtml(file, "Отчёт по серверам 1С", sheets());
        } else {
            Xlsx.write(file, sheets());
        }
    }
}
