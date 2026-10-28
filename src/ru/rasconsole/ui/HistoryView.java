package ru.rasconsole.ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import javafx.geometry.Insets;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.util.StringConverter;

/** Графики истории: сеансы, память rphost, лицензии и ожидания на блокировках за выбранный период. */
final class HistoryView {

    private record Period(String title, int hours) {
        @Override
        public String toString() {
            return title;
        }
    }

    private record ClusterItem(UUID id, String title) {
        @Override
        public String toString() {
            return title;
        }
    }

    private HistoryView() {
    }

    static void open(Monitor monitor) {
        History history = monitor.history();
        Dialog<Void> d = Dialogs.prepare(new Dialog<>(), "История показателей");
        d.initModality(Modality.NONE);
        d.setHeaderText(monitor.settings().history
            ? "Показатели записываются раз в минуту, пока открыта сводка или включено фоновое наблюдение. Хранятся "
                + history.keepDays() + " дн.\nПапка: " + history.dir()
            : "Запись истории выключена — графики покажут только уже записанные данные.\n"
                + "Включить: «Настройки…» в сводке или «Файл → Настройки…».");
        ComboBox<Period> period = new ComboBox<>();
        period.getItems().addAll(new Period("6 часов", 6), new Period("Сутки", 24), new Period("Неделя", 24 * 7),
            new Period("Месяц", 24 * 30));
        period.getSelectionModel().select(1);
        ComboBox<ClusterItem> cluster = new ComboBox<>();
        Label peaks = new Label();
        peaks.setWrapText(true);
        GridPane charts = new GridPane();
        charts.setHgap(8);
        charts.setVgap(8);
        Runnable reload = () -> {
            List<History.Point> pts = history.load(period.getValue().hours());
            // список кластеров — из данных периода
            Map<UUID, String> clusters = new LinkedHashMap<>();
            for (History.Point p : pts) clusters.put(p.clusterId(), p.server() + " / " + p.cluster());
            ClusterItem cur = cluster.getValue();
            cluster.getItems().setAll(clusters.entrySet().stream().map(e -> new ClusterItem(e.getKey(), e.getValue())).toList());
            ClusterItem sel = cur != null && clusters.containsKey(cur.id()) ? cluster.getItems().stream()
                .filter(c -> c.id().equals(cur.id())).findFirst().orElse(null) : cluster.getItems().isEmpty() ? null : cluster.getItems().get(0);
            cluster.setValue(sel);
            charts.getChildren().clear();
            if (sel == null) {
                peaks.setText(monitor.settings().history
                    ? "За выбранный период данных нет. Откройте сводку по серверам или включите фоновое наблюдение."
                    : "Данных нет: запись истории выключена. Включите её в настройках.");
                return;
            }
            List<History.Point> own = pts.stream().filter(p -> p.clusterId().equals(sel.id())).toList();
            own = thin(own, 600);
            charts.add(chart("Сеансы", own, Map.of("Всего", p -> p.sessions(), "Активных", p -> p.active())), 0, 0);
            charts.add(chart("Память rphost, МБ", own, Map.of("Всего", p -> p.rphostKb() / 1024.0,
                "Макс. на процесс", p -> p.maxProcessKb() / 1024.0)), 1, 0);
            charts.add(chart("Лицензии", own, Map.of("Занято", p -> p.licenses())), 0, 1);
            charts.add(chart("Ожидания и долгие вызовы", own, Map.of("Ждут блокировок", p -> p.blocked(),
                "Самый долгий вызов, с", p -> p.longestCallMs() / 1000.0)), 1, 1);
            peaks.setText(peaks(own));
        };
        // reload сам меняет список и значение кластера — защищаемся от повторного входа
        boolean[] busy = {false};
        Runnable guarded = () -> {
            if (busy[0]) return;
            busy[0] = true;
            try {
                reload.run();
            } finally {
                busy[0] = false;
            }
        };
        period.setOnAction(e -> guarded.run());
        cluster.setOnAction(e -> guarded.run());
        HBox top = new HBox(8, new Label("Кластер:"), cluster, new Label("Период:"), period);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(cluster, Priority.ALWAYS);
        VBox box = new VBox(8, top, peaks, charts);
        box.setPadding(new Insets(4));
        box.setPrefSize(1150, 720);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(new ButtonType("Закрыть", ButtonBar.ButtonData.CANCEL_CLOSE));
        guarded.run();
        d.show();
    }

    /** Не больше max точек на график (усреднение не нужно — берём каждую n-ю). */
    private static List<History.Point> thin(List<History.Point> pts, int max) {
        if (pts.size() <= max) return pts;
        int step = (int) Math.ceil(pts.size() / (double) max);
        List<History.Point> r = new ArrayList<>();
        for (int i = 0; i < pts.size(); i += step) r.add(pts.get(i));
        return r;
    }

    private static LineChart<Number, Number> chart(String title, List<History.Point> pts,
                                                   Map<String, ToDoubleFunction<History.Point>> series) {
        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setAutoRanging(true);
        SimpleDateFormat fmt = new SimpleDateFormat(pts.isEmpty() || pts.get(pts.size() - 1).time() - pts.get(0).time() > 86_400_000L
            ? "dd.MM HH:mm" : "HH:mm");
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                return fmt.format(new Date(n.longValue()));
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        NumberAxis y = new NumberAxis();
        LineChart<Number, Number> c = new LineChart<>(x, y);
        c.setTitle(title);
        c.setCreateSymbols(pts.size() < 60);
        c.setAnimated(false);
        c.setPrefSize(560, 300);
        for (Map.Entry<String, ToDoubleFunction<History.Point>> e : new java.util.TreeMap<>(series).entrySet()) {
            XYChart.Series<Number, Number> s = new XYChart.Series<>();
            s.setName(e.getKey());
            for (History.Point p : pts) s.getData().add(new XYChart.Data<>(p.time(), e.getValue().applyAsDouble(p)));
            c.getData().add(s);
        }
        return c;
    }

    private static String peaks(List<History.Point> pts) {
        if (pts.isEmpty()) return "";
        History.Point s = pts.get(0);
        History.Point l = pts.get(0);
        History.Point m = pts.get(0);
        for (History.Point p : pts) {
            if (p.sessions() > s.sessions()) s = p;
            if (p.licenses() > l.licenses()) l = p;
            if (p.rphostKb() > m.rphostKb()) m = p;
        }
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        return "Пики за период: сеансов " + s.sessions() + " (" + f.format(new Date(s.time())) + "), лицензий "
            + l.licenses() + " (" + f.format(new Date(l.time())) + "), память rphost " + Fmt.bytes(m.rphostKb() * 1024)
            + " (" + f.format(new Date(m.time())) + "). Точек: " + pts.size() + ".";
    }
}
