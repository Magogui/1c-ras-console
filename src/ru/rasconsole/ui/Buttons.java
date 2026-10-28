package ru.rasconsole.ui;

import java.util.List;
import java.util.Locale;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.shape.SVGPath;

/**
 * Иконки и цвет кнопок по их названию — одно место для всех панелей и диалогов.
 * <p>
 * Ключевые команды выделяются цветом: красный — удаление и завершение, зелёный — создание и добавление,
 * оранжевый — блокировки и перезапуск, синий — запуск 1С. Остальные кнопки — обычные, с иконкой.
 * Цвета и цвет линий иконок задаются в console.css и dark.css.
 */
public final class Buttons {

    private static final List<String> STYLES = List.of("btn-danger", "btn-success", "btn-warning", "btn-primary");
    private static final String DONE = "rasconsole.buttons";

    /** Вид команды: иконка (контур 16×16) и цветовая группа. */
    private record Kind(String path, boolean filled, String style) {
    }

    private Buttons() {
    }

    // ---- иконки (контуры в координатах 16×16) ----

    private static final String REFRESH = "M13 8a5 5 0 1 1-1.5-3.5M13 2.5v3h-3";
    private static final String PLUS = "M8 3v10M3 8h10";
    private static final String TRASH = "M3 4.5h10M6.5 4.5V3h3v1.5M4.5 4.5l.7 9h5.6l.7-9M7 7v4M9 7v4";
    private static final String STOP = "M2 8a6 6 0 1 0 12 0a6 6 0 1 0-12 0M5.8 5.8l4.4 4.4M10.2 5.8l-4.4 4.4";
    private static final String PAUSE = "M2 8a6 6 0 1 0 12 0a6 6 0 1 0-12 0M6.5 5.5v5M9.5 5.5v5";
    private static final String PLUG = "M5.5 2v3M10.5 2v3M3.5 5h9v2.5a4.5 4.5 0 0 1-9 0zM8 12v2.5";
    private static final String PENCIL = "M3 13l.8-3.2 7-7 2.4 2.4-7 7zM9.6 4l2.4 2.4";
    private static final String PLAY = "M5 3l8 5-8 5z";
    private static final String COG = "M5.5 8a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2"
        + "M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4";
    private static final String COPY = "M5.5 5.5h7v8h-7zM3.5 10.5v-8h7";
    private static final String LIST = "M5.5 4h8M5.5 8h8M5.5 12h8M2.5 4h.6M2.5 8h.6M2.5 12h.6";
    private static final String DOWNLOAD = "M8 2.5v8M4.5 7l3.5 3.5L11.5 7M3 13.5h10";
    private static final String UPLOAD = "M8 10.5v-8M4.5 6L8 2.5 11.5 6M3 13.5h10";
    private static final String CALENDAR = "M2.5 3.5h11v10h-11zM2.5 6.5h11M5.5 2v3M10.5 2v3M5 9h1.5M7.3 9h1.5M9.6 9h1.5M5 11.2h1.5M7.3 11.2h1.5";
    private static final String LOCK = "M4 7.5h8v6H4zM5.5 7.5V5.5a2.5 2.5 0 0 1 5 0v2M8 10v1.5";
    private static final String UNLOCK = "M4 7.5h8v6H4zM5.5 7.5V5.5a2.5 2.5 0 0 1 4.8-1M8 10v1.5";
    private static final String POWER = "M8 2v5.5M4.6 4.3a5 5 0 1 0 6.8 0";
    private static final String UP = "M8 13V3M4 7l4-4 4 4";
    private static final String DOWN = "M8 3v10M4 9l4 4 4-4";
    private static final String STAR = "M8 1.8l1.9 4 4.3.5-3.2 3 .9 4.3L8 11.4l-3.9 2.2.9-4.3-3.2-3 4.3-.5z";
    private static final String EYE = "M1.5 8s2.5-4.5 6.5-4.5 6.5 4.5 6.5 4.5-2.5 4.5-6.5 4.5S1.5 8 1.5 8zM6 8a2 2 0 1 0 4 0a2 2 0 1 0-4 0";
    private static final String CHART = "M2.5 13.5h11M4.5 11V8M8 11V4.5M11.5 11V6.5";
    private static final String SEARCH = "M3 7a4 4 0 1 0 8 0a4 4 0 1 0-8 0M10 10l4 4";
    private static final String USERS = "M2.5 13c0-2.5 1.6-4 3.8-4s3.8 1.5 3.8 4M4.3 5.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0"
        + "M11 9.2c1.5.3 2.5 1.6 2.5 3.8M10.6 3.6a2 2 0 0 1 0 3.8";
    private static final String COMPARE = "M5.5 2.5v11M10.5 2.5v11M2.5 5h3M10.5 11h3M2.5 8h3M10.5 8h3";
    private static final String CHECK = "M3 8.5l3 3 7-7";
    private static final String SEND = "M2 7.5l12-5-4.5 11.5L7.5 9zM7.5 9L14 2.5";
    private static final String CROSS = "M4 4l8 8M12 4l-8 8";
    private static final String FOLDER = "M2 4.5h4l1.5 1.5H14v7H2z";
    private static final String UNDO = "M5 4L2.5 6.5 5 9M2.5 6.5H10a3.5 3.5 0 0 1 0 7H7";
    private static final String CLOCK = "M2 8a6 6 0 1 0 12 0a6 6 0 1 0-12 0M8 4.5V8l2.5 1.5";
    private static final String REPORT = "M4 1.5h5.5l3 3v10H4zM9.5 1.5v3h3M6 8h4.5M6 10.5h4.5";
    private static final String KEY = "M2 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0M8 8h6M12 8v2.5M10 8v1.5";
    private static final String FILTER = "M2 3h12l-4.5 5.5v4.5l-3 1.5v-6z";
    private static final String FILTER_OUT = "M2 3h12l-4.5 5.5v4.5l-3 1.5v-6zM11 11l3.5 3.5M14.5 11L11 14.5";
    private static final String PULSE = "M1.5 8.5h3l2-5 3 9 2-4h3";
    private static final String TAB = "M2 4.5h12v9H2zM2 4.5V2.5h5v2";

    /** Подобрать вид по названию; порядок проверок важен (более точные — раньше). */
    private static Kind kind(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        // красные: удаление, завершение, разрыв
        if (t.startsWith("удалить завершенные")) return new Kind(TRASH, false, null);
        if (t.startsWith("удалить")) return new Kind(TRASH, false, "btn-danger");
        if (t.startsWith("завершить")) return new Kind(STOP, false, "btn-danger");
        if (t.startsWith("прервать")) return new Kind(PAUSE, false, "btn-danger");
        if (t.startsWith("отключить")) return new Kind(PLUG, false, "btn-danger");
        if (t.startsWith("сбросить")) return new Kind(UNDO, false, "btn-danger");
        if (t.startsWith("отменить задание")) return new Kind(CROSS, false, "btn-danger");
        if (t.startsWith("убрать из избранного")) return new Kind(STAR, false, null);
        // оранжевые: блокировки и перезапуск
        if (t.startsWith("снять блокировку")) return new Kind(UNLOCK, false, "btn-warning");
        if (t.startsWith("блокировка сеансов")) return new Kind(LOCK, false, "btn-warning");
        if (t.startsWith("блокировки")) return new Kind(LOCK, false, null);
        if (t.startsWith("перезапустить")) return new Kind(POWER, false, "btn-warning");
        if (t.startsWith("регламентные работы")) return new Kind(CALENDAR, false, "btn-warning");
        if (t.startsWith("изменить у нескольких")) return new Kind(PENCIL, false, "btn-warning");
        // зелёные: создание и добавление
        if (t.startsWith("добавить") || t.startsWith("создать") || t.startsWith("новый") || t.startsWith("новое"))
            return new Kind(PLUS, false, "btn-success");
        if (t.startsWith("применить")) return new Kind(CHECK, false, "btn-success");
        if (t.startsWith("отправить")) return new Kind(SEND, false, "btn-success");
        // синие: запуск 1С
        if (t.startsWith("открыть в 1с")) return new Kind(PLAY, true, "btn-primary");
        if (t.startsWith("конфигуратор")) return new Kind(COG, false, "btn-primary");
        // обычные, с иконкой
        if (t.startsWith("обновить") || t.startsWith("подключиться")) return new Kind(REFRESH, false, null);
        if (t.startsWith("свойства") || t.startsWith("изменить") || t.startsWith("параметры")) return new Kind(PENCIL, false, null);
        if (t.startsWith("строка подключения") || t.startsWith("копировать")) return new Kind(COPY, false, null);
        if (t.startsWith("список баз")) return new Kind(LIST, false, null);
        if (t.startsWith("экспорт")) return new Kind(DOWNLOAD, false, null);
        if (t.startsWith("импорт")) return new Kind(UPLOAD, false, null);
        if (t.startsWith("выше")) return new Kind(UP, false, null);
        if (t.startsWith("ниже")) return new Kind(DOWN, false, null);
        if (t.contains("избранное")) return new Kind(STAR, true, null);
        if (t.startsWith("наблюдать") || t.startsWith("не наблюдать")) return new Kind(EYE, false, null);
        if (t.startsWith("журнал")) return new Kind(LIST, false, null);
        if (t.startsWith("история") || t.startsWith("сводка") || t.startsWith("текущие значения")
            || t.startsWith("накопленные")) return new Kind(CHART, false, null);
        if (t.startsWith("настройки")) return new Kind(COG, false, null);
        if (t.startsWith("везде") || t.startsWith("найти")) return new Kind(SEARCH, false, null);
        if (t.startsWith("сеансы группы")) return new Kind(USERS, false, null);
        if (t.startsWith("сравнить")) return new Kind(COMPARE, false, null);
        if (t.startsWith("выбрать все")) return new Kind(CHECK, false, null);
        if (t.startsWith("отбор")) return new Kind(FILTER, false, null);
        if (t.startsWith("исключить")) return new Kind(FILTER_OUT, false, null);
        if (t.startsWith("снять отбор") || t.startsWith("снять все отборы")) return new Kind(CROSS, false, null);
        if (t.startsWith("снять все") || t.startsWith("отменить")) return new Kind(CROSS, false, null);
        if (t.startsWith("обзор") || t.equals("открыть")) return new Kind(FOLDER, false, null);
        if (t.startsWith("по умолчанию")) return new Kind(UNDO, false, null);
        if (t.startsWith("сейчас")) return new Kind(CLOCK, false, null);
        if (t.startsWith("отчет")) return new Kind(REPORT, false, null);
        if (t.startsWith("диагностика") || t.startsWith("проверить порты")) return new Kind(PULSE, false, null);
        if (t.startsWith("администратор")) return new Kind(KEY, false, null);
        if (t.startsWith("открыть в новой вкладке")) return new Kind(TAB, false, null);
        return null;
    }

    /**
     * Панель команд, которая переносит кнопки на следующую строку, а не прячет их за «»»:
     * ключевые (цветные) кнопки всегда видны.
     */
    public static javafx.scene.layout.FlowPane bar() {
        javafx.scene.layout.FlowPane f = new javafx.scene.layout.FlowPane(4, 4);
        f.getStyleClass().add("tool-bar");
        f.setPadding(new javafx.geometry.Insets(4, 6, 4, 6));
        f.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return f;
    }

    /** Вертикальный разделитель для {@link #bar()}. */
    public static javafx.scene.control.Separator separator() {
        javafx.scene.control.Separator s = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
        s.setPrefHeight(22);
        return s;
    }

    /** Иконка команды (для пунктов меню) или null. */
    public static Node icon(String text) {
        Kind k = kind(text);
        return k == null ? null : glyph(k);
    }

    private static Node glyph(Kind k) {
        SVGPath p = new SVGPath();
        p.setContent(k.path());
        p.getStyleClass().add(k.filled() ? "btn-icon-fill" : "btn-icon");
        p.setStrokeWidth(1.6);
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        Group g = new Group(p);
        g.getStyleClass().add("btn-glyph");
        return g;
    }

    /** Оформить кнопку по названию; при смене названия оформление пересчитывается. */
    public static <B extends ButtonBase> B decorate(B b) {
        if (b.getProperties().containsKey(DONE)) return b;
        b.getProperties().put(DONE, Boolean.TRUE);
        apply(b);
        b.textProperty().addListener((o, was, now) -> apply(b));
        return b;
    }

    private static void apply(ButtonBase b) {
        b.getStyleClass().removeAll(STYLES);
        Kind k = kind(b.getText());
        if (k == null) {
            if (b.getGraphic() != null && b.getGraphic().getStyleClass().contains("btn-glyph")) b.setGraphic(null);
            return;
        }
        // свою картинку (например, у вкладок) не заменяем
        if (b.getGraphic() == null || b.getGraphic().getStyleClass().contains("btn-glyph")) b.setGraphic(glyph(k));
        if (k.style() != null) b.getStyleClass().add(k.style());
    }

    /** Оформить все кнопки внутри узла (панели инструментов, в том числе спрятанные в «»», и кнопки диалога). */
    public static void decorateAll(Node root) {
        if (root == null) return;
        if (root instanceof Button b) decorate(b);
        if (root instanceof ToolBar tb) {
            for (Node n : tb.getItems()) decorateAll(n);
        }
        if (root instanceof ButtonBar bb) {
            for (Node n : bb.getButtons()) decorateAll(n);
        }
        if (root instanceof javafx.scene.control.ScrollPane sp) decorateAll(sp.getContent());
        if (root instanceof javafx.scene.control.TabPane tp) {
            for (javafx.scene.control.Tab t : tp.getTabs()) decorateAll(t.getContent());
        }
        if (root instanceof Parent p) {
            for (Node n : p.getChildrenUnmodifiable()) decorateAll(n);
        }
    }

    /** Иконки в пунктах меню (без цвета). */
    public static void decorateMenu(List<MenuItem> items) {
        for (MenuItem mi : items) {
            if (mi.getGraphic() == null) {
                Node icon = icon(mi.getText());
                if (icon != null) mi.setGraphic(icon);
            }
            if (mi instanceof Menu m) decorateMenu(m.getItems());
        }
    }
}
