package ru.rasconsole.ui.cluster;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com._1c.v8.ibis.admin.IInfoBaseInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.ISessionInfo;

import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.table.Col;

/**
 * Мастер регламентных работ: блокировка сеансов и регламентных заданий сразу в нескольких ИБ,
 * завершение оставшихся сеансов, затем снятие блокировки.
 */
final class Maintenance {

    private Maintenance() {
    }

    /** Что сделать с выбранными базами. */
    private record Plan(boolean lock, Date from, Date to, String message, String code, boolean jobs,
                        boolean terminate, Set<String> excludedApps, List<IInfoBaseInfoShort> bases, boolean scheduled) {
    }

    /** Результат по базе для отчёта. */
    record Result(String base, String action, String result) {
    }

    /** Открыть мастер; {@code preselected} — базы, отмеченные заранее. */
    static void open(ClusterCtx ctx, List<IInfoBaseInfoShort> preselected, Runnable after) {
        Fx.call(ctx.cluster(), "Список информационных баз", c -> {
            List<IInfoBaseInfoShort> list = c.getInfoBasesShort(ctx.clusterId);
            ctx.rememberInfoBases(list);
            return list;
        }, all -> {
            if (all.isEmpty()) {
                Dialogs.info("Регламентные работы", "В кластере нет информационных баз.");
                return;
            }
            Set<UUID> pre = new HashSet<>();
            for (IInfoBaseInfoShort b : preselected) pre.add(b.getInfoBaseId());
            ask(ctx, all, pre).ifPresent(plan -> {
                if (plan.scheduled()) {
                    schedule(ctx, plan);
                    return;
                }
                String what = (plan.lock() ? "Установить блокировку" : "Снять блокировку") + " в базах: " + plan.bases().size()
                    + (plan.lock() && plan.terminate() ? "\nи завершить сеансы этих баз" : "");
                if (Dialogs.confirmDanger(ctx.conn.config(), "Регламентные работы", what + "?")) {
                    execute(ctx, plan, 0, new ArrayList<>(), after);
                }
            });
        });
    }

    private static java.util.Optional<Plan> ask(ClusterCtx ctx, List<IInfoBaseInfoShort> all, Set<UUID> pre) {
        Dialog<Plan> d = Dialogs.prepare(new Dialog<>(), "Регламентные работы — кластер «" + ctx.name + "»");
        d.setHeaderText("Блокировка начала сеансов и регламентных заданий в нескольких информационных базах");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // базы
        List<CheckBox> boxes = new ArrayList<>();
        VBox list = new VBox(4);
        for (IInfoBaseInfoShort b : all) {
            CheckBox cb = new CheckBox(b.getName() + (b.getDescr() == null || b.getDescr().isBlank() ? "" : " — " + b.getDescr()));
            cb.setUserData(b);
            cb.setMnemonicParsing(false);
            cb.setSelected(pre.contains(b.getInfoBaseId()));
            boxes.add(cb);
            list.getChildren().add(cb);
        }
        ScrollPane sp = new ScrollPane(list);
        sp.setFitToWidth(true);
        sp.setPrefHeight(Math.min(40 + all.size() * 24, 220));
        Button allBtn = new Button("Выбрать все");
        allBtn.setOnAction(e -> boxes.forEach(b -> b.setSelected(true)));
        Button noneBtn = new Button("Снять все");
        noneBtn.setOnAction(e -> boxes.forEach(b -> b.setSelected(false)));

        // режим
        ToggleGroup mode = new ToggleGroup();
        RadioButton lock = new RadioButton("Установить блокировку");
        RadioButton unlock = new RadioButton("Снять блокировку");
        lock.setToggleGroup(mode);
        unlock.setToggleGroup(mode);
        lock.setSelected(true);

        ru.rasconsole.ui.form.DateTimeField from = new ru.rasconsole.ui.form.DateTimeField(new Date());
        ru.rasconsole.ui.form.DateTimeField to = new ru.rasconsole.ui.form.DateTimeField(null);
        to.setPromptText("пусто — до снятия вручную");
        TextField message = new TextField("Выполняются регламентные работы. Попробуйте подключиться позже.");
        TextField code = new TextField();
        code.setPromptText("для входа во время блокировки: /UC<код>");
        CheckBox jobs = new CheckBox("Блокировать регламентные задания");
        jobs.setSelected(true);
        CheckBox terminate = new CheckBox("Завершить текущие сеансы этих баз");
        terminate.setSelected(true);
        FlowPane ex = new FlowPane(12, 6);
        List<CheckBox> exBoxes = new ArrayList<>();
        for (var e : SessionKill.EXCLUDABLE.entrySet()) {
            CheckBox cb = new CheckBox(e.getValue());
            cb.setUserData(e.getKey());
            cb.setSelected(e.getKey().equals("Designer"));
            exBoxes.add(cb);
            ex.getChildren().add(cb);
        }
        CheckBox scheduled = new CheckBox("По расписанию: в момент «Начало» заблокировать"
            + " (и завершить сеансы), в момент «Окончание» снять блокировку");
        scheduled.setWrapText(true);
        Label schedHint = new Label("Задание выполнит эта консоль — она должна быть запущена (можно свернуть в трей)."
            + " Список: Действия → Запланированные работы.");
        schedHint.getStyleClass().add("hint-label");
        schedHint.setWrapText(true);
        schedHint.visibleProperty().bind(scheduled.selectedProperty());
        schedHint.managedProperty().bind(scheduled.selectedProperty());
        CheckBox unlockJobs = new CheckBox("Снять и блокировку регламентных заданий");
        unlockJobs.setSelected(true);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(6);
        g.addRow(0, new Label("Начало:"), from);
        g.addRow(1, new Label("Окончание:"), to);
        g.addRow(2, new Label("Сообщение:"), message);
        g.addRow(3, new Label("Код разрешения:"), code);
        g.add(jobs, 1, 4);
        g.add(terminate, 1, 5);
        g.add(new Label("Не завершать:"), 0, 6);
        g.add(ex, 1, 6);
        g.add(scheduled, 1, 7);
        g.add(schedHint, 1, 8);
        message.setPrefColumnCount(40);
        VBox lockBox = new VBox(g);
        VBox unlockBox = new VBox(unlockJobs);
        lockBox.visibleProperty().bind(lock.selectedProperty());
        lockBox.managedProperty().bind(lock.selectedProperty());
        unlockBox.visibleProperty().bind(unlock.selectedProperty());
        unlockBox.managedProperty().bind(unlock.selectedProperty());
        ex.disableProperty().bind(terminate.selectedProperty().not());

        VBox content = new VBox(8,
            new HBox(8, new Label("Информационные базы:"), allBtn, noneBtn), sp,
            new HBox(16, lock, unlock), lockBox, unlockBox);
        content.setPadding(new Insets(10));
        content.setPrefWidth(760);
        d.getDialogPane().setContent(content);

        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            if (boxes.stream().noneMatch(CheckBox::isSelected)) {
                Dialogs.warn("Не выбрана ни одна база");
                e.consume();
                return;
            }
            try {
                Date f = Fmt.parseDate(from.getText());
                Date t = Fmt.parseDate(to.getText());
                if (lock.isSelected() && scheduled.isSelected()) {
                    if (Fmt.isEmptyDate(f) || f.getTime() < System.currentTimeMillis() - 60_000) {
                        Dialogs.warn("Для работы по расписанию «Начало» должно быть в будущем");
                        e.consume();
                    } else if (!Fmt.isEmptyDate(t) && !t.after(f)) {
                        Dialogs.warn("«Окончание» должно быть позже «Начала»");
                        e.consume();
                    }
                }
            } catch (ParseException ex1) {
                Dialogs.warn("Укажите дату в календаре и время в виде чч:мм");
                e.consume();
            }
        });
        d.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            List<IInfoBaseInfoShort> bases = new ArrayList<>();
            for (CheckBox cb : boxes) if (cb.isSelected()) bases.add((IInfoBaseInfoShort) cb.getUserData());
            Set<String> excluded = new HashSet<>();
            for (CheckBox cb : exBoxes) if (cb.isSelected()) excluded.add((String) cb.getUserData());
            try {
                return lock.isSelected()
                    ? new Plan(true, Fmt.parseDate(from.getText()), Fmt.parseDate(to.getText()), message.getText(),
                        code.getText(), jobs.isSelected(), terminate.isSelected(), excluded, bases, scheduled.isSelected())
                    : new Plan(false, null, null, null, null, unlockJobs.isSelected(), false, excluded, bases, false);
            } catch (ParseException ex2) {
                return null;
            }
        });
        return d.showAndWait();
    }

    /**
     * Запланировать работы. Сначала проверяем доступ к каждой базе (здесь можно ввести и сохранить пароли),
     * потому что в момент выполнения спрашивать будет некого.
     */
    private static void schedule(ClusterCtx ctx, Plan plan) {
        checkAccess(ctx, plan, 0, new ArrayList<>(), problems -> {
            if (!problems.isEmpty() && !Dialogs.confirm("Запланированные работы",
                "Нет доступа к базам:\n" + String.join("\n", problems)
                    + "\n\nБез сохранённого пароля эти базы в назначенное время пропустятся. Всё равно запланировать?")) {
                return;
            }
            Scheduler.Task t = new Scheduler.Task();
            t.address = ctx.conn.config().address();
            t.clusterId = ctx.clusterId;
            t.clusterName = ctx.name;
            for (IInfoBaseInfoShort b : plan.bases()) {
                t.ibIds.add(b.getInfoBaseId());
                t.ibNames.add(b.getName());
            }
            t.lockAt = plan.from();
            t.unlockAt = Fmt.isEmptyDate(plan.to()) ? null : plan.to();
            t.message = plan.message() == null ? "" : plan.message();
            t.code = plan.code() == null ? "" : plan.code();
            t.jobs = plan.jobs();
            t.terminate = plan.terminate();
            t.excludedApps.addAll(plan.excludedApps());
            Scheduler.add(t);
            Fx.audit(ctx.cluster(), "Запланированы регламентные работы: " + t.bases() + ", " + Fmt.date(t.lockAt)
                + (t.unlockAt == null ? "" : " — " + Fmt.date(t.unlockAt)), "OK");
            Dialogs.info("Запланированные работы", "Блокировка баз (" + t.ibIds.size() + ") — " + Fmt.date(t.lockAt)
                + (t.terminate ? ", с завершением сеансов" : "")
                + (t.unlockAt == null ? ".\nСнятие блокировки — вручную." : ".\nСнятие блокировки — " + Fmt.date(t.unlockAt) + ".")
                + "\n\nКонсоль должна быть запущена в это время. Список заданий: Действия → Запланированные работы.");
        });
    }

    private static void checkAccess(ClusterCtx ctx, Plan plan, int i, List<String> problems,
                                    java.util.function.Consumer<List<String>> done) {
        if (i >= plan.bases().size()) {
            done.accept(problems);
            return;
        }
        IInfoBaseInfoShort b = plan.bases().get(i);
        Fx.call(ctx.infoBase(b.getName()), "Проверка доступа к «" + b.getName() + "»",
            c -> c.getInfoBaseInfo(ctx.clusterId, b.getInfoBaseId()),
            v -> checkAccess(ctx, plan, i + 1, problems, done),
            err -> {
                problems.add(b.getName() + ": " + (Fx.isAuthCancel(err) ? "пароль не введён" : Fmt.rootMessage(err)));
                checkAccess(ctx, plan, i + 1, problems, done);
            });
    }

    /** Последовательно обработать базы (авторизация в ИБ может спрашиваться для каждой). */
    private static void execute(ClusterCtx ctx, Plan plan, int i, List<Result> results, Runnable after) {
        if (i >= plan.bases().size()) {
            if (plan.lock() && plan.terminate()) {
                terminateSessions(ctx, plan, results, after);
            } else {
                report(ctx, results, after);
            }
            return;
        }
        IInfoBaseInfoShort b = plan.bases().get(i);
        String action = plan.lock() ? "блокировка" : "снятие блокировки";
        Fx.call(ctx.infoBase(b.getName()), (plan.lock() ? "Блокировка ИБ «" : "Снятие блокировки ИБ «") + b.getName() + "»", c -> {
            IInfoBaseInfo info = c.getInfoBaseInfo(ctx.clusterId, b.getInfoBaseId());
            if (plan.lock()) {
                info.setSessionsDenied(true);
                info.setDeniedFrom(plan.from());
                info.setDeniedTo(plan.to());
                info.setDeniedMessage(plan.message());
                info.setPermissionCode(plan.code());
                if (plan.jobs()) info.setScheduledJobsDenied(true);
            } else {
                info.setSessionsDenied(false);
                info.setDeniedFrom(Fmt.EMPTY_DATE);
                info.setDeniedTo(Fmt.EMPTY_DATE);
                if (plan.jobs()) info.setScheduledJobsDenied(false);
            }
            c.updateInfoBase(ctx.clusterId, info);
            return null;
        }, v -> {
            results.add(new Result(b.getName(), action, "OK"));
            Fx.audit(ctx.infoBase(b.getName()), "Регламент: " + action, "OK");
            execute(ctx, plan, i + 1, results, after);
        }, err -> {
            String r = Fx.isAuthCancel(err) ? "пропущено: нет авторизации" : "ошибка: " + Fmt.rootMessage(err);
            results.add(new Result(b.getName(), action, r));
            Fx.audit(ctx.infoBase(b.getName()), "Регламент: " + action, r);
            execute(ctx, plan, i + 1, results, after);
        });
    }

    private static void terminateSessions(ClusterCtx ctx, Plan plan, List<Result> results, Runnable after) {
        Set<UUID> locked = new HashSet<>();
        for (IInfoBaseInfoShort b : plan.bases()) {
            for (Result r : results) if (r.base().equals(b.getName()) && r.result().equals("OK")) locked.add(b.getInfoBaseId());
        }
        if (locked.isEmpty()) {
            report(ctx, results, after);
            return;
        }
        Fx.call(ctx.cluster(), "Завершение сеансов заблокированных баз", c -> {
            int done = 0;
            int skipped = 0;
            List<String> errors = new ArrayList<>();
            for (ISessionInfo s : c.getSessions(ctx.clusterId)) {
                if (!locked.contains(s.getInfoBaseId())) continue;
                if (s.getAppId() != null && plan.excludedApps().contains(s.getAppId())) {
                    skipped++;
                    continue;
                }
                try {
                    c.terminateSession(ctx.clusterId, s.getSid(), plan.message());
                    done++;
                } catch (RuntimeException e) {
                    errors.add(s.getSessionId() + ": " + e.getMessage());
                }
            }
            return "завершено " + done + (skipped > 0 ? ", оставлено (исключения) " + skipped : "")
                + (errors.isEmpty() ? "" : ", ошибок " + errors.size());
        }, r -> {
            results.add(new Result("(все выбранные)", "завершение сеансов", r));
            Fx.audit(ctx.cluster(), "Регламент: завершение сеансов", r);
            report(ctx, results, after);
        }, err -> {
            results.add(new Result("(все выбранные)", "завершение сеансов", "ошибка: " + Fmt.rootMessage(err)));
            report(ctx, results, after);
        });
    }

    private static void report(ClusterCtx ctx, List<Result> results, Runnable after) {
        Dialogs.table("Регламентные работы — результат", results, List.of(
            Col.<Result>of("Информационная база", 220, Result::base),
            Col.<Result>of("Действие", 180, Result::action),
            Col.<Result>of("Результат", 420, Result::result)));
        after.run();
    }
}
