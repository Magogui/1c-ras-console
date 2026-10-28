package ru.rasconsole.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import ru.rasconsole.ui.Monitor.Problem;
import ru.rasconsole.ui.Monitor.Stats;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.table.EntityTable;
import ru.rasconsole.ui.tree.ConsoleNode;

/** Сводная панель по всем серверам и кластерам. */
final class Dashboard implements ConsoleNode.Disposable, ConsoleNode.Searchable {

    private final Monitor monitor;
    private final BorderPane root = new BorderPane();
    private final EntityTable<Stats> table;
    private final Label summary = new Label();
    private final Timeline timer = new Timeline();
    private final Consumer<List<Stats>> listener = this::show;
    private javafx.scene.control.TextField filter;

    @Override
    public void focusFilter() {
        filter.requestFocus();
    }

    Dashboard(Monitor monitor, Consumer<Stats> open) {
        this.monitor = monitor;
        table = new EntityTable<>(List.of(
            Col.<Stats>of("Сервер", 180, s -> s.server().display()),
            Col.<Stats>of("Кластер", 160, Stats::cluster),
            Col.<Stats>of("Состояние", 200, Stats::status),
            Col.<Stats>of("Сеансов", 70, Stats::sessions),
            Col.<Stats>of("Активных", 70, Stats::active),
            Col.<Stats>of("Спящих", 70, Stats::hibernate),
            Col.<Stats>of("Ожидают блокировок", 90, Stats::blocked),
            Col.<Stats>of("Процессов", 70, Stats::processes),
            Col.<Stats>bytes("Память rphost", s -> s.rphostMemoryKb() * 1024),
            Col.<Stats>bytes("Макс. на процесс", s -> s.maxProcessMemoryKb() * 1024),
            Col.<Stats>of("Самый долгий вызов, с", 90, s -> s.longestCallMs() / 1000),
            Col.<Stats>of("Чей", 220, Stats::longestCallWho),
            Col.<Stats>of("Лицензий", 70, Stats::licenses),
            Col.<Stats>of("Проблемы", 500, s -> {
                StringBuilder sb = new StringBuilder();
                for (Problem p : s.problems()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(p.text());
                }
                return sb.toString();
            })), s -> s.server().address() + "|" + s.clusterId());
        table.setRowStyle(s -> s.status().startsWith("нет связи") || s.status().startsWith("ошибка") ? "row-blocked"
            : !s.problems().isEmpty() ? "row-warning" : null);
        table.setOnDoubleClick(s -> {
            if (s.clusterId() != null) open.accept(s);
        });
        table.setPlaceholder("Опрос серверов…");

        Button refresh = new Button("Обновить");
        refresh.setOnAction(e -> refresh());
        Button hist = new Button("История…");
        hist.setOnAction(e -> HistoryView.open(monitor));
        Button settings = new Button("Настройки…");
        settings.setOnAction(e -> {
            SettingsDialog.open(monitor);
            refresh();
        });
        HBox auto = AutoRefresh.control(timer, this::refresh, "autorefresh.dashboard", 10, true);
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        filter = table.filterField();
        table.bindState("Сводка по серверам");
        ToolBar bar = new ToolBar(refresh, hist, settings, filter, spacer, summary, auto);
        Label header = new Label("Сводка по серверам");
        header.getStyleClass().add("view-title");
        header.setPadding(new Insets(6, 8, 2, 8));
        Label hint = new Label("Двойной щелчок по строке открывает кластер в дереве. Кластеры, для которых пароль ещё не введён"
            + " и не сохранён, показаны со статусом «нужна авторизация».");
        hint.setWrapText(true);
        hint.setPadding(new Insets(0, 8, 4, 8));
        root.setTop(new VBox(header, hint, bar));
        root.setCenter(table.view());
        root.setUserData(this);
        root.sceneProperty().addListener((o, was, now) -> {
            if (now == null) dispose();
        });
        monitor.addListener(listener);
        if (!monitor.last().isEmpty()) show(monitor.last());
        refresh();
    }

    Node node() {
        return root;
    }

    private void refresh() {
        summary.setText("Опрос серверов…");
        monitor.collect(null);
    }

    private void show(List<Stats> stats) {
        table.setItems(stats);
        table.setPlaceholder("Нет данных");
        int problems = 0;
        int sessions = 0;
        for (Stats s : stats) {
            problems += s.problems().size();
            sessions += s.sessions();
        }
        summary.setText("Кластеров: " + stats.stream().filter(s -> s.clusterId() != null).count()
            + ", сеансов: " + sessions + ", проблем: " + problems);
    }

    @Override
    public void dispose() {
        timer.stop();
        monitor.removeListener(listener);
    }
}
