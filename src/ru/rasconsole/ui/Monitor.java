package ru.rasconsole.ui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;

import ru.rasconsole.config.ServerConfig;
import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.AuthRequiredException;
import ru.rasconsole.ras.RasConnection;

/**
 * Наблюдение за всеми серверами: сводные показатели по кластерам и поиск проблем
 * (процесс превысил память, долгий серверный вызов, ожидания на блокировках, нет связи).
 * <p>
 * Опрос идёт без диалогов авторизации: если пароль не введён и не сохранён, у кластера будет статус
 * «нужна авторизация». В фоне (когда включены уведомления) новые проблемы показываются в трее Windows.
 */
public final class Monitor {

    /** Показатели одного кластера (или строка «нет связи» для сервера). */
    public record Stats(ServerConfig server, String cluster, UUID clusterId, String status, int sessions, int active,
                        int hibernate, int processes, long rphostMemoryKb, long maxProcessMemoryKb, int blocked,
                        long longestCallMs, String longestCallWho, int licenses, List<Problem> problems) {
    }

    /** Проблема; {@code key} — для уведомления только о новых. */
    public record Problem(String key, String text) {
    }

    /** Пороги и режим наблюдения. */
    public static final class Settings {
        public int memoryMb = 4096;
        public int callSec = 60;
        /** «Захвачено СУБД (текущее)»: сеанс держит соединение с СУБД дольше N секунд (0 — не проверять). */
        public int dbProcSec = 60;
        public int intervalSec = 60;
        public boolean notify;
        /** Записывать историю показателей для графиков (по умолчанию выключено). */
        public boolean history;
        /** Папка истории; пусто — %APPDATA%/RasConsole/history. */
        public String historyDir = "";
        public int historyDays = 30;
        /** Папка журнала действий; пусто — %APPDATA%/RasConsole. */
        public String auditDir = "";
        /** Сеансов в кластере больше N — проблема (0 — не проверять). */
        public int sessionsMax;
        /** Занято лицензий в кластере больше N — проблема (0 — не проверять). */
        public int licensesMax;
        /** Ожидание на блокировке дольше N секунд — проблема (0 — любое ожидание). */
        public int lockWaitSec;
        /** Сообщать о восстановлении связи с сервером. */
        public boolean notifyRecovered = true;
        /** Оформление: light или dark. */
        public String theme = "light";
        /** Внешние каналы оповещений. */
        public final Notifier.Channels channels = new Notifier.Channels();
    }

    private final Supplier<List<RasConnection>> connections;
    private final Path file;
    private final Settings settings = new Settings();
    private final Timeline timer = new Timeline();
    private final Set<String> known = new HashSet<>();
    private List<Stats> last = List.of();
    private final List<Consumer<List<Stats>>> listeners = new ArrayList<>();
    private Tray tray;
    private final Path baseDir;
    private History history;
    private boolean collecting;

    public Monitor(Supplier<List<RasConnection>> connections, Path dir) {
        this.connections = connections;
        this.file = dir.resolve("settings.properties");
        this.baseDir = dir;
        load();
        this.history = new History(historyPath(), settings.historyDays);
        this.watch = new ru.rasconsole.config.IbWatch(dir, historyPath());
        timer.setCycleCount(Timeline.INDEFINITE);
        applySchedule();
    }

    private final ru.rasconsole.config.IbWatch watch;

    /** Наблюдаемые базы (журнал входов и выходов). */
    public ru.rasconsole.config.IbWatch watch() {
        return watch;
    }

    /** Список наблюдаемых баз изменился: фоновый опрос нужен, пока есть хотя бы одна база. */
    public void watchChanged() {
        applySchedule();
    }

    public Settings settings() {
        return settings;
    }

    public List<Stats> last() {
        return last;
    }

    public void addListener(Consumer<List<Stats>> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<List<Stats>> l) {
        listeners.remove(l);
    }

    /** Применить изменённые настройки (сохранить, перезапустить фоновый опрос, сменить папки). */
    public void settingsChanged() {
        save();
        if (!history.dir().equals(historyPath()) || history.keepDays() != settings.historyDays) {
            history = new History(historyPath(), settings.historyDays);
        }
        ru.rasconsole.config.AuditLog.setDir(settings.auditDir.isBlank() ? null : Path.of(settings.auditDir));
        watch.setLogDir(historyPath());
        applySchedule();
    }

    /** Папка истории: заданная в настройках или по умолчанию. */
    public Path historyPath() {
        return settings.historyDir == null || settings.historyDir.isBlank() ? defaultHistoryPath() : Path.of(settings.historyDir);
    }

    public Path defaultHistoryPath() {
        return baseDir.resolve("history");
    }

    public Path defaultAuditDir() {
        return baseDir;
    }

    private void applySchedule() {
        timer.stop();
        timer.getKeyFrames().setAll(new KeyFrame(Duration.seconds(Math.max(15, settings.intervalSec)), e -> collect(null)));
        if (settings.notify && tray == null) tray = Tray.create();
        // наблюдение за базами тоже требует фонового опроса, даже если уведомления выключены
        if (settings.notify || (watch != null && !watch.isEmpty())) {
            timer.play();
            collect(null);
        }
    }

    /**
     * Событие не из опроса (например, выполнена запланированная блокировка): в трей и во внешние каналы,
     * если включено фоновое наблюдение.
     */
    public void event(String caption, String text) {
        if (!settings.notify) return;
        if (tray == null) tray = Tray.create();
        if (tray != null) tray.message(caption, text, java.awt.TrayIcon.MessageType.INFO);
        sendExternal(caption, text);
    }

    private void sendExternal(String caption, String text) {
        Notifier.sendAsync(settings.channels, caption, text, err -> Platform.runLater(() -> Fx.status("Оповещение не отправлено: " + err)));
    }

    public void shutdown() {
        timer.stop();
        if (tray != null) tray.remove();
    }

    // ---- сбор ----

    /** Опросить все серверы; результат — в FX-поток (и всем слушателям). */
    public void collect(Consumer<List<Stats>> done) {
        if (collecting) {
            if (done != null) done.accept(last);
            return;
        }
        collecting = true;
        List<CompletableFuture<List<Stats>>> futures = new ArrayList<>();
        for (RasConnection conn : connections.get()) futures.add(collectServer(conn));
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, e) -> {
            List<Stats> all = new ArrayList<>();
            for (CompletableFuture<List<Stats>> f : futures) all.addAll(f.getNow(List.of()));
            Platform.runLater(() -> {
                collecting = false;
                last = all;
                notifyNew(all);
                if (settings.history) history.record(all);
                for (Consumer<List<Stats>> l : new ArrayList<>(listeners)) l.accept(all);
                if (done != null) done.accept(all);
            });
        });
    }

    private CompletableFuture<List<Stats>> collectServer(RasConnection conn) {
        ServerConfig cfg = conn.config();
        return conn.submit(AuthKind.NONE, null, c -> c.getClusters()).thenCompose(clusters -> {
            List<CompletableFuture<Stats>> fs = new ArrayList<>();
            for (IClusterInfo ci : clusters) fs.add(collectCluster(conn, ci));
            return CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).thenApply(v -> {
                List<Stats> r = new ArrayList<>();
                for (CompletableFuture<Stats> f : fs) r.add(f.join());
                return r;
            });
        }).exceptionally(e -> List.of(new Stats(cfg, "", null, "нет связи: " + ru.rasconsole.ras.NetErrors.describe(e), 0, 0, 0, 0, 0, 0, 0, 0, "", 0,
            List.of(new Problem("down|" + cfg.address(), "Нет связи с " + cfg.display())))));
    }

    private CompletableFuture<Stats> collectCluster(RasConnection conn, IClusterInfo ci) {
        ServerConfig cfg = conn.config();
        UUID cid = ci.getClusterId();
        String name = ci.getName();
        long memLimitKb = settings.memoryMb * 1024L;
        long callLimitMs = settings.callSec * 1000L;
        long dbProcLimitMs = settings.dbProcSec * 1000L;
        long lockWaitMs = settings.lockWaitSec * 1000L;
        int sessionsMax = settings.sessionsMax;
        int licensesMax = settings.licensesMax;
        return conn.submit(AuthKind.CLUSTER, cid, c -> {
            List<ISessionInfo> sessions = c.getSessions(cid);
            List<IWorkingProcessInfo> processes = c.getWorkingProcesses(cid);
            Map<UUID, String> ib = new HashMap<>();
            for (IInfoBaseInfoShort b : c.getInfoBasesShort(cid)) ib.put(b.getInfoBaseId(), b.getName());
            if (watch.watchesCluster(cfg.address(), cid)) {
                Map<UUID, List<ru.rasconsole.config.IbWatch.Session>> byIb = new HashMap<>();
                for (ISessionInfo s : sessions) {
                    byIb.computeIfAbsent(s.getInfoBaseId(), k -> new ArrayList<>()).add(new ru.rasconsole.config.IbWatch.Session(
                        s.getSid(), s.getSessionId(), s.getUserName(), s.getHost(), Fmt.app(s.getAppId()), s.getStartedAt()));
                }
                watch.update(cfg.address(), cfg.display(), cid, name, byIb);
            }
            List<Problem> problems = new ArrayList<>();
            int active = 0;
            int hib = 0;
            int blocked = 0;
            int licenses = 0;
            long longest = 0;
            String who = "";
            for (ISessionInfo s : sessions) {
                if (s.getDurationCurrent() > 0) active++;
                if (s.getHibernate()) hib++;
                if ((s.getBlockedByLs() != 0 || s.getBlockedByDbms() != 0) && s.getDurationCurrent() >= lockWaitMs) blocked++;
                if (s.getLicenses() != null) licenses += s.getLicenses().size();
                long d = s.getDurationCurrent();
                String w = s.getSessionId() + (s.getUserName() == null || s.getUserName().isBlank() ? "" : " " + s.getUserName()) + " (" + ib.getOrDefault(s.getInfoBaseId(), "?") + ")";
                if (d > longest) {
                    longest = d;
                    who = w;
                }
                if (callLimitMs > 0 && d > callLimitMs) {
                    problems.add(new Problem("call|" + cid + "|" + s.getSid(),
                        "Время вызова (текущее) " + d / 1000 + " с: сеанс " + w));
                }
                long took = s.getDbProcTook();
                if (dbProcLimitMs > 0 && took > dbProcLimitMs) {
                    String proc = s.getDbProcInfo() == null || s.getDbProcInfo().isBlank() ? "" : ", соединение СУБД " + s.getDbProcInfo();
                    problems.add(new Problem("dbproc|" + cid + "|" + s.getSid(),
                        "Захвачено СУБД (текущее) " + took / 1000 + " с: сеанс " + w + proc));
                }
            }
            if (blocked > 0) {
                problems.add(new Problem("lock|" + cid, "Ожидают блокировок" + (lockWaitMs > 0 ? " дольше " + settings.lockWaitSec + " с" : "")
                    + ": " + blocked + " сеанс(ов)"));
            }
            if (sessionsMax > 0 && sessions.size() > sessionsMax) {
                problems.add(new Problem("sessions|" + cid, "Сеансов " + sessions.size() + " (порог " + sessionsMax + ")"));
            }
            if (licensesMax > 0 && licenses > licensesMax) {
                problems.add(new Problem("licenses|" + cid, "Занято лицензий " + licenses + " (порог " + licensesMax + ")"));
            }
            long total = 0;
            long max = 0;
            for (IWorkingProcessInfo p : processes) {
                total += p.getMemorySize();
                max = Math.max(max, p.getMemorySize());
                if (memLimitKb > 0 && p.getMemorySize() > memLimitKb) {
                    problems.add(new Problem("mem|" + cid + "|" + p.getWorkingProcessId(),
                        "rphost " + p.getHostName() + ":" + p.getMainPort() + " (PID " + p.getPid() + "): память "
                            + Fmt.bytes(p.getMemorySize() * 1024L)));
                }
            }
            return new Stats(cfg, name, cid, problems.isEmpty() ? "OK" : "есть проблемы", sessions.size(), active, hib,
                processes.size(), total, max, blocked, longest, who, licenses, problems);
        }).exceptionally(e -> {
            Throwable t = Fx.unwrap(e);
            String status = t instanceof AuthRequiredException ? "нужна авторизация (откройте кластер и войдите)"
                : "ошибка: " + Fmt.rootMessage(t);
            return new Stats(cfg, name, cid, status, 0, 0, 0, 0, 0, 0, 0, 0, "", 0, List.of());
        });
    }

    /** Показать в трее проблемы, которых не было при прошлом опросе. */
    private void notifyNew(List<Stats> all) {
        Set<String> now = new HashSet<>();
        List<String> fresh = new ArrayList<>();
        Set<String> reachable = new HashSet<>();
        for (Stats s : all) {
            if (s.clusterId() != null) reachable.add(s.server().address());
            for (Problem p : s.problems()) {
                now.add(p.key());
                if (!known.contains(p.key())) {
                    fresh.add((s.cluster().isEmpty() ? "" : s.server().name() + " / " + s.cluster() + ": ") + p.text());
                }
            }
        }
        // связь восстановлена: проблема «нет связи» была, а теперь сервер ответил
        List<String> recovered = new ArrayList<>();
        for (String k : known) {
            if (k.startsWith("down|") && !now.contains(k) && reachable.contains(k.substring(5))) {
                String address = k.substring(5);
                String display = address;
                for (Stats s : all) if (s.server().address().equals(address)) display = s.server().display();
                recovered.add("Связь с " + display + " восстановлена");
            }
        }
        // пока сервер недоступен, его прежние проблемы помним: после восстановления связи не присылать их повторно
        Set<String> seenClusters = new HashSet<>();
        for (Stats s : all) if (s.clusterId() != null) seenClusters.add(s.clusterId().toString());
        for (String k : known) {
            String[] p = k.split("\\|");
            if (!k.startsWith("down|") && p.length > 1 && !seenClusters.contains(p[1])) now.add(k);
        }
        known.clear();
        known.addAll(now);
        if (!settings.notify) return;
        if (!fresh.isEmpty()) {
            String text = String.join("\n", fresh.subList(0, Math.min(5, fresh.size())))
                + (fresh.size() > 5 ? "\n… и ещё " + (fresh.size() - 5) : "");
            String caption = "Консоль RAS: проблемы (" + fresh.size() + ")";
            if (tray != null) tray.message(caption, text, java.awt.TrayIcon.MessageType.WARNING);
            sendExternal(caption, String.join("\n", fresh));
        }
        if (settings.notifyRecovered && !recovered.isEmpty()) {
            String caption = "Консоль RAS: связь восстановлена";
            if (tray != null) tray.message(caption, String.join("\n", recovered), java.awt.TrayIcon.MessageType.INFO);
            sendExternal(caption, String.join("\n", recovered));
        }
    }

    History history() {
        return history;
    }

    public void setOnTrayClick(Runnable r) {
        Tray.onClick = r;
    }

    // ---- настройки ----

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
            settings.memoryMb = Integer.parseInt(p.getProperty("monitor.memoryMb", "4096"));
            settings.callSec = Integer.parseInt(p.getProperty("monitor.callSec", "60"));
            settings.dbProcSec = Integer.parseInt(p.getProperty("monitor.dbProcSec", "60"));
            settings.intervalSec = Integer.parseInt(p.getProperty("monitor.intervalSec", "60"));
            settings.notify = Boolean.parseBoolean(p.getProperty("monitor.notify", "false"));
            settings.history = Boolean.parseBoolean(p.getProperty("history.enabled", "false"));
            settings.historyDir = p.getProperty("history.dir", "");
            settings.historyDays = Integer.parseInt(p.getProperty("history.days", "30"));
            settings.auditDir = p.getProperty("audit.dir", "");
            settings.sessionsMax = Integer.parseInt(p.getProperty("monitor.sessionsMax", "0"));
            settings.licensesMax = Integer.parseInt(p.getProperty("monitor.licensesMax", "0"));
            settings.lockWaitSec = Integer.parseInt(p.getProperty("monitor.lockWaitSec", "0"));
            settings.notifyRecovered = Boolean.parseBoolean(p.getProperty("monitor.notifyRecovered", "true"));
            settings.theme = p.getProperty("ui.theme", "light");
            Notifier.Channels ch = settings.channels;
            ch.telegram = Boolean.parseBoolean(p.getProperty("notify.telegram", "false"));
            ch.telegramChat = p.getProperty("notify.telegram.chat", "");
            ch.webhook = Boolean.parseBoolean(p.getProperty("notify.webhook", "false"));
            ch.webhookUrl = p.getProperty("notify.webhook.url", "");
            ch.email = Boolean.parseBoolean(p.getProperty("notify.email", "false"));
            ch.smtpHost = p.getProperty("notify.smtp.host", "");
            ch.smtpPort = Integer.parseInt(p.getProperty("notify.smtp.port", "465"));
            ch.smtpSecurity = p.getProperty("notify.smtp.security", "ssl");
            ch.smtpUser = p.getProperty("notify.smtp.user", "");
            ch.mailFrom = p.getProperty("notify.mail.from", "");
            ch.mailTo = p.getProperty("notify.mail.to", "");
        } catch (IOException | NumberFormatException e) {
            System.err.println("Настройки наблюдения: " + e.getMessage());
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("monitor.memoryMb", Integer.toString(settings.memoryMb));
        p.setProperty("monitor.callSec", Integer.toString(settings.callSec));
        p.setProperty("monitor.dbProcSec", Integer.toString(settings.dbProcSec));
        p.setProperty("monitor.intervalSec", Integer.toString(settings.intervalSec));
        p.setProperty("monitor.notify", Boolean.toString(settings.notify));
        p.setProperty("history.enabled", Boolean.toString(settings.history));
        p.setProperty("history.dir", settings.historyDir == null ? "" : settings.historyDir);
        p.setProperty("history.days", Integer.toString(settings.historyDays));
        p.setProperty("audit.dir", settings.auditDir == null ? "" : settings.auditDir);
        p.setProperty("monitor.sessionsMax", Integer.toString(settings.sessionsMax));
        p.setProperty("monitor.licensesMax", Integer.toString(settings.licensesMax));
        p.setProperty("monitor.lockWaitSec", Integer.toString(settings.lockWaitSec));
        p.setProperty("monitor.notifyRecovered", Boolean.toString(settings.notifyRecovered));
        p.setProperty("ui.theme", settings.theme);
        Notifier.Channels ch = settings.channels;
        p.setProperty("notify.telegram", Boolean.toString(ch.telegram));
        p.setProperty("notify.telegram.chat", ch.telegramChat);
        p.setProperty("notify.webhook", Boolean.toString(ch.webhook));
        p.setProperty("notify.webhook.url", ch.webhookUrl);
        p.setProperty("notify.email", Boolean.toString(ch.email));
        p.setProperty("notify.smtp.host", ch.smtpHost);
        p.setProperty("notify.smtp.port", Integer.toString(ch.smtpPort));
        p.setProperty("notify.smtp.security", ch.smtpSecurity);
        p.setProperty("notify.smtp.user", ch.smtpUser);
        p.setProperty("notify.mail.from", ch.mailFrom);
        p.setProperty("notify.mail.to", ch.mailTo);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "RAS console settings");
            }
        } catch (IOException e) {
            System.err.println("Настройки наблюдения: " + e.getMessage());
        }
    }

    /** Значок в области уведомлений Windows (AWT SystemTray). */
    static final class Tray {
        static volatile Runnable onClick;
        private final java.awt.TrayIcon icon;

        private Tray(java.awt.TrayIcon icon) {
            this.icon = icon;
        }

        static Tray create() {
            try {
                if (!java.awt.SystemTray.isSupported()) return null;
                // иконка приложения нужного трею размера (16 при 100 % масштабе, 24/32 — при увеличенном)
                java.awt.Dimension size = java.awt.SystemTray.getSystemTray().getTrayIconSize();
                int want = size.width <= 16 ? 16 : size.width <= 24 ? 24 : 32;
                java.awt.Image img;
                try (var in = Monitor.class.getResourceAsStream("/ru/rasconsole/ui/icons/app-" + want + ".png")) {
                    img = javax.imageio.ImageIO.read(in);
                }
                java.awt.TrayIcon ti = new java.awt.TrayIcon(img, "Консоль администрирования RAS");
                ti.setImageAutoSize(true);
                ti.addActionListener(e -> {
                    Runnable r = onClick;
                    if (r != null) Platform.runLater(r);
                });
                java.awt.SystemTray.getSystemTray().add(ti);
                return new Tray(ti);
            } catch (Exception | Error e) {
                System.err.println("Область уведомлений недоступна: " + e);
                return null;
            }
        }

        void message(String caption, String text, java.awt.TrayIcon.MessageType type) {
            java.awt.EventQueue.invokeLater(() -> icon.displayMessage(caption, text, type));
        }

        void remove() {
            java.awt.EventQueue.invokeLater(() -> java.awt.SystemTray.getSystemTray().remove(icon));
        }
    }
}
