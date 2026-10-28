package ru.rasconsole.ui.cluster;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com._1c.v8.ibis.admin.IInfoBaseInfo;
import com._1c.v8.ibis.admin.ISessionInfo;

import ru.rasconsole.config.AuditLog;
import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.AuthRequiredException;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.table.EntityTable;

/**
 * Запланированные регламентные работы: в заданное время заблокировать базы (сеансы и регламентные задания)
 * и завершить сеансы, в заданное время снять блокировку. Задания хранятся в {@code schedule.properties}
 * и выполняются, пока консоль запущена (можно свернуть в трей).
 * <p>
 * Окончание блокировки сеансов дополнительно передаётся серверу (deniedTo): если консоль к тому времени
 * будет закрыта, сервер сам перестанет запрещать вход, хотя флажок блокировки останется.
 */
public final class Scheduler {

    /** Задание опоздало больше чем на столько — не выполняется (консоль не была запущена). */
    private static final long LATE_MS = 15 * 60_000L;

    public static final String WAITING = "ожидает";
    public static final String RUNNING = "выполняется";
    public static final String LOCKED = "заблокировано";
    public static final String DONE = "выполнено";
    public static final String SKIPPED = "пропущено";
    public static final String CANCELLED = "отменено";
    public static final String FAILED = "ошибка";

    /** Задание. */
    public static final class Task {
        String id = UUID.randomUUID().toString();
        String address;
        UUID clusterId;
        String clusterName;
        List<UUID> ibIds = new ArrayList<>();
        List<String> ibNames = new ArrayList<>();
        Date lockAt;
        Date unlockAt;
        String message = "";
        String code = "";
        boolean jobs;
        boolean terminate;
        Set<String> excludedApps = new HashSet<>();
        String state = WAITING;
        String result = "";

        String bases() {
            return String.join(", ", ibNames);
        }
    }

    private static Path file;
    private static Supplier<List<RasConnection>> connections = List::of;
    private static BiConsumer<String, String> notifier = (a, b) -> { };
    private static final List<Task> tasks = new ArrayList<>();
    private static Timeline timer;

    private Scheduler() {
    }

    /** Загрузить задания и запустить проверку раз в 20 секунд. */
    public static void init(Path dir, Supplier<List<RasConnection>> conns) {
        file = dir.resolve("schedule.properties");
        connections = conns;
        load();
        timer = new Timeline(new KeyFrame(Duration.seconds(20), e -> tick()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        tick();
    }

    public static void setNotifier(BiConsumer<String, String> n) {
        notifier = n;
    }

    public static void shutdown() {
        if (timer != null) timer.stop();
    }

    /** Ожидающих и активных заданий (для строки состояния). */
    public static long pending() {
        return tasks.stream().filter(t -> t.state.equals(WAITING) || t.state.equals(LOCKED) || t.state.equals(RUNNING)).count();
    }

    static void add(Task t) {
        tasks.add(t);
        save();
    }

    // ---- выполнение ----

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Task t : new ArrayList<>(tasks)) {
            if (t.state.equals(WAITING) && now >= t.lockAt.getTime()) {
                if (now - t.lockAt.getTime() > LATE_MS) {
                    finish(t, SKIPPED, "консоль не была запущена в " + Fmt.date(t.lockAt), false);
                } else {
                    lock(t);
                }
            } else if (t.state.equals(LOCKED) && t.unlockAt != null && now >= t.unlockAt.getTime()) {
                unlock(t, false);
            }
        }
    }

    private static RasConnection conn(Task t) {
        for (RasConnection c : connections.get()) if (c.config().address().equals(t.address)) return c;
        return null;
    }

    private static void lock(Task t) {
        RasConnection conn = conn(t);
        if (conn == null) {
            finish(t, FAILED, "сервер " + t.address + " удалён из списка", true);
            return;
        }
        t.state = RUNNING;
        save();
        conn.resetRetry();
        List<String> report = new ArrayList<>();
        Set<UUID> locked = new HashSet<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < t.ibIds.size(); i++) {
            UUID ib = t.ibIds.get(i);
            String name = t.ibNames.get(i);
            chain = chain.thenCompose(v -> conn.submit(AuthKind.INFOBASE, t.clusterId, c -> {
                IInfoBaseInfo info = c.getInfoBaseInfo(t.clusterId, ib);
                info.setSessionsDenied(true);
                info.setDeniedFrom(t.lockAt);
                info.setDeniedTo(t.unlockAt == null ? Fmt.EMPTY_DATE : t.unlockAt);
                info.setDeniedMessage(t.message);
                info.setPermissionCode(t.code);
                if (t.jobs) info.setScheduledJobsDenied(true);
                c.updateInfoBase(t.clusterId, info);
                return null;
            }).handle((r, e) -> {
                String res = e == null ? "OK" : describe(e);
                if (e == null) locked.add(ib);
                report.add(name + ": блокировка — " + res);
                AuditLog.write(conn.config().display(), name, "Запланированная блокировка", res);
                return null;
            }));
        }
        if (t.terminate) {
            chain = chain.thenCompose(v -> locked.isEmpty() ? CompletableFuture.completedFuture(null)
                : conn.submit(AuthKind.CLUSTER, t.clusterId, c -> {
                    int done = 0;
                    int skipped = 0;
                    int errors = 0;
                    for (ISessionInfo s : c.getSessions(t.clusterId)) {
                        if (!locked.contains(s.getInfoBaseId())) continue;
                        if (s.getAppId() != null && t.excludedApps.contains(s.getAppId())) {
                            skipped++;
                            continue;
                        }
                        try {
                            c.terminateSession(t.clusterId, s.getSid(), t.message);
                            done++;
                        } catch (RuntimeException e) {
                            errors++;
                        }
                    }
                    return "завершено сеансов " + done + (skipped > 0 ? ", оставлено " + skipped : "")
                        + (errors > 0 ? ", ошибок " + errors : "");
                }).handle((r, e) -> {
                    String res = e == null ? r : describe(e);
                    report.add("Сеансы: " + res);
                    AuditLog.write(conn.config().display(), t.clusterName, "Запланированное завершение сеансов", res);
                    return null;
                }));
        }
        chain.whenComplete((v, e) -> Platform.runLater(() -> {
            boolean ok = !locked.isEmpty();
            String text = String.join("\n", report);
            if (!ok) {
                finish(t, FAILED, text, true);
            } else if (t.unlockAt == null) {
                finish(t, DONE, text, true);
            } else {
                t.state = LOCKED;
                t.result = text;
                save();
                notifier.accept("Консоль RAS: базы заблокированы (" + locked.size() + ")", t.clusterName + "\n" + text
                    + "\nСнятие блокировки: " + Fmt.date(t.unlockAt));
            }
        }));
    }

    /** Снять блокировку, установленную заданием. */
    private static void unlock(Task t, boolean manual) {
        RasConnection conn = conn(t);
        if (conn == null) {
            finish(t, FAILED, "сервер " + t.address + " удалён из списка", true);
            return;
        }
        t.state = RUNNING;
        save();
        conn.resetRetry();
        List<String> report = new ArrayList<>();
        int[] okCount = {0};
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < t.ibIds.size(); i++) {
            UUID ib = t.ibIds.get(i);
            String name = t.ibNames.get(i);
            chain = chain.thenCompose(v -> conn.submit(AuthKind.INFOBASE, t.clusterId, c -> {
                IInfoBaseInfo info = c.getInfoBaseInfo(t.clusterId, ib);
                info.setSessionsDenied(false);
                info.setDeniedFrom(Fmt.EMPTY_DATE);
                info.setDeniedTo(Fmt.EMPTY_DATE);
                if (t.jobs) info.setScheduledJobsDenied(false);
                c.updateInfoBase(t.clusterId, info);
                return null;
            }).handle((r, e) -> {
                String res = e == null ? "OK" : describe(e);
                if (e == null) okCount[0]++;
                report.add(name + ": снятие блокировки — " + res);
                AuditLog.write(conn.config().display(), name, "Запланированное снятие блокировки", res);
                return null;
            }));
        }
        chain.whenComplete((v, e) -> Platform.runLater(() ->
            finish(t, okCount[0] == t.ibIds.size() ? DONE : FAILED, (manual ? "снято вручную. " : "") + String.join("\n", report), true)));
    }

    private static String describe(Throwable e) {
        Throwable t = Fx.unwrap(e);
        return t instanceof AuthRequiredException ? "нет сохранённого пароля (откройте базу в консоли и войдите с «Запомнить пароль»)"
            : "ошибка: " + Fmt.rootMessage(t);
    }

    private static void finish(Task t, String state, String result, boolean notify) {
        t.state = state;
        t.result = result;
        save();
        if (notify) notifier.accept("Консоль RAS: регламентные работы — " + state, t.clusterName + " (" + t.bases() + ")\n" + result);
        Fx.status("Запланированные работы «" + t.clusterName + "»: " + state);
    }

    // ---- окно списка ----

    /** Список заданий с отменой и ручным снятием блокировки. */
    public static void showTasks() {
        Dialog<Void> d = Dialogs.prepare(new Dialog<>(), "Запланированные работы");
        d.setHeaderText("Блокировки баз по расписанию. Выполняются, пока консоль запущена (можно свернуть в трей).");
        EntityTable<Task> table = new EntityTable<>(List.of(
            Col.<Task>of("Состояние", 110, t -> t.state),
            Col.<Task>of("Блокировка", 140, t -> t.lockAt),
            Col.<Task>of("Снятие", 140, t -> t.unlockAt == null ? "вручную" : Fmt.date(t.unlockAt)),
            Col.<Task>of("Сервер", 160, t -> t.address),
            Col.<Task>of("Кластер", 140, t -> t.clusterName),
            Col.<Task>of("Базы", 220, Task::bases),
            Col.<Task>of("Завершать сеансы", t -> t.terminate),
            Col.<Task>of("Регл. задания", t -> t.jobs),
            Col.<Task>of("Сообщение", 220, t -> t.message),
            Col.<Task>of("Результат", 420, t -> t.result.replace('\n', ';'))), t -> t.id);
        table.setRowStyle(t -> t.state.equals(FAILED) || t.state.equals(SKIPPED) ? "row-blocked"
            : t.state.equals(LOCKED) ? "row-warning" : null);
        Runnable refresh = () -> table.setItems(new ArrayList<>(tasks));
        refresh.run();
        Button cancel = new Button("Отменить задание");
        cancel.setOnAction(e -> {
            for (Task t : table.selected()) {
                if (t.state.equals(WAITING)) {
                    t.state = CANCELLED;
                    t.result = "отменено " + Fmt.date(new Date());
                } else if (t.state.equals(LOCKED)
                    && Dialogs.confirm("Запланированные работы", "Базы «" + t.bases() + "» уже заблокированы. Снять блокировку сейчас?")) {
                    unlock(t, true);
                }
            }
            save();
            refresh.run();
        });
        Button unlockNow = new Button("Снять блокировку сейчас");
        unlockNow.setOnAction(e -> {
            for (Task t : table.selected()) if (t.state.equals(LOCKED)) unlock(t, true);
            refresh.run();
        });
        Button clean = new Button("Удалить завершённые");
        clean.setOnAction(e -> {
            tasks.removeIf(t -> !t.state.equals(WAITING) && !t.state.equals(LOCKED) && !t.state.equals(RUNNING));
            save();
            refresh.run();
        });
        Button upd = new Button("Обновить");
        upd.setOnAction(e -> refresh.run());
        Label hint = new Label("Новое задание: «Регламентные работы…» в меню кластера или списка баз, флажок «По расписанию».");
        hint.getStyleClass().add("hint-label");
        HBox bar = new HBox(8, upd, cancel, unlockNow, clean);
        table.view().setPrefSize(1100, 380);
        VBox box = new VBox(8, bar, table.view(), hint);
        VBox.setVgrow(table.view(), Priority.ALWAYS);
        box.setPadding(new Insets(6));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(new ButtonType("Закрыть", ButtonBar.ButtonData.CANCEL_CLOSE));
        d.showAndWait();
    }

    // ---- файл ----

    private static void load() {
        tasks.clear();
        if (file == null || !Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            System.err.println("schedule.properties: " + e.getMessage());
            return;
        }
        int n = Integer.parseInt(p.getProperty("count", "0"));
        for (int i = 0; i < n; i++) {
            String pre = "task." + i + ".";
            try {
                Task t = new Task();
                t.id = p.getProperty(pre + "id");
                t.address = p.getProperty(pre + "server");
                t.clusterId = UUID.fromString(p.getProperty(pre + "cluster"));
                t.clusterName = p.getProperty(pre + "clusterName", "");
                for (String s : p.getProperty(pre + "ibIds", "").split(",")) if (!s.isBlank()) t.ibIds.add(UUID.fromString(s));
                t.ibNames.addAll(List.of(p.getProperty(pre + "ibNames", "").split("\t", -1)));
                t.lockAt = new Date(Long.parseLong(p.getProperty(pre + "lockAt")));
                String u = p.getProperty(pre + "unlockAt", "");
                t.unlockAt = u.isEmpty() ? null : new Date(Long.parseLong(u));
                t.message = p.getProperty(pre + "message", "");
                t.code = loadCode(t.id, p.getProperty(pre + "code", ""));
                t.jobs = Boolean.parseBoolean(p.getProperty(pre + "jobs", "false"));
                t.terminate = Boolean.parseBoolean(p.getProperty(pre + "terminate", "false"));
                for (String s : p.getProperty(pre + "excluded", "").split(",")) if (!s.isBlank()) t.excludedApps.add(s);
                t.state = p.getProperty(pre + "state", WAITING);
                // выполнение прервалось закрытием программы — считаем ошибкой, а не «выполняется»
                if (t.state.equals(RUNNING)) t.state = FAILED;
                t.result = p.getProperty(pre + "result", "");
                if (t.ibIds.size() == t.ibNames.size()) tasks.add(t);
            } catch (RuntimeException ignored) {
                // повреждённое задание пропускается
            }
        }
    }

    private static String codeKey(String id) {
        return "schedule|" + id;
    }

    /** @return false — хранилища нет (тогда код остаётся в файле, как раньше) */
    private static boolean putCode(Task t) {
        ru.rasconsole.config.CredentialStore cs = Fx.credentialStore();
        if (cs == null || !cs.available()) return false;
        if (t.code.isEmpty()) return true;
        boolean done = !t.state.equals(WAITING) && !t.state.equals(LOCKED) && !t.state.equals(RUNNING);
        if (done) {
            cs.removeSecret(codeKey(t.id));
        } else if (cs.secret(codeKey(t.id)) == null || !t.code.equals(cs.secret(codeKey(t.id)).password())) {
            cs.putSecret(codeKey(t.id), new ru.rasconsole.ras.Credentials("", t.code));
        }
        return true;
    }

    /** Код из хранилища; старые файлы с кодом в открытом виде читаются как раньше. */
    private static String loadCode(String id, String legacy) {
        ru.rasconsole.config.CredentialStore cs = Fx.credentialStore();
        ru.rasconsole.ras.Credentials c = cs == null ? null : cs.secret(codeKey(id));
        return c != null ? c.password() : legacy;
    }

    private static void save() {
        if (file == null) return;
        Properties p = new Properties();
        p.setProperty("count", Integer.toString(tasks.size()));
        int i = 0;
        for (Task t : tasks) {
            String pre = "task." + i++ + ".";
            p.setProperty(pre + "id", t.id);
            p.setProperty(pre + "server", t.address);
            p.setProperty(pre + "cluster", t.clusterId.toString());
            p.setProperty(pre + "clusterName", t.clusterName);
            List<String> ids = new ArrayList<>();
            for (UUID u : t.ibIds) ids.add(u.toString());
            p.setProperty(pre + "ibIds", String.join(",", ids));
            p.setProperty(pre + "ibNames", String.join("\t", t.ibNames));
            p.setProperty(pre + "lockAt", Long.toString(t.lockAt.getTime()));
            p.setProperty(pre + "unlockAt", t.unlockAt == null ? "" : Long.toString(t.unlockAt.getTime()));
            p.setProperty(pre + "message", t.message);
            // код разрешения позволяет войти в заблокированную базу — храним зашифрованным
            if (!putCode(t)) p.setProperty(pre + "code", t.code);
            p.setProperty(pre + "jobs", Boolean.toString(t.jobs));
            p.setProperty(pre + "terminate", Boolean.toString(t.terminate));
            p.setProperty(pre + "excluded", String.join(",", t.excludedApps));
            p.setProperty(pre + "state", t.state);
            p.setProperty(pre + "result", t.result);
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "RAS console: scheduled maintenance");
            }
        } catch (IOException e) {
            System.err.println("schedule.properties: " + e.getMessage());
        }
    }
}
