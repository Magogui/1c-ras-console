package ru.rasconsole.ui.table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;

/**
 * Таблица объектов API с колонками {@link Col}: поиск по всем колонкам, сортировка,
 * подсветка строк, экспорт видимых строк в CSV. При обновлении сохраняет выделение по ключу.
 */
public final class EntityTable<T> {

    private static final List<String> ROW_STYLES = List.of("row-blocked", "row-blocker", "row-warning");

    private final TableView<T> table = new TableView<>();
    private final List<Col<T>> cols;
    private final Function<T, Object> key;
    private final ObservableList<T> source = FXCollections.observableArrayList();
    private final FilteredList<T> filtered = new FilteredList<>(source, t -> true);
    private Function<T, String> rowStyle;
    private Consumer<T> onDoubleClick;
    private String filterText = "";

    /** Отбор по значению колонки: равно (или не равно) показанному тексту ячейки. */
    private record Cond(Col<?> col, String text, boolean exclude) {
    }

    private final List<Cond> conds = new ArrayList<>();
    private final java.util.Map<Col<T>, TableColumn<T, Object>> columns = new java.util.LinkedHashMap<>();
    private final List<Runnable> filterListeners = new ArrayList<>();
    /** Ячейка, по которой последний раз щёлкнули (для пунктов отбора в контекстном меню). */
    private Col<T> clickedCol;
    private T clickedItem;

    public EntityTable(List<Col<T>> cols, Function<T, Object> key) {
        this.cols = cols;
        this.key = key;
        SortedList<T> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("Нет данных"));
        table.setTableMenuButtonVisible(true);
        for (Col<T> c : cols) {
            TableColumn<T, Object> tc = new TableColumn<>(c.title());
            tc.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(value(c, cd.getValue())));
            tc.setCellFactory(col -> new TableCell<>() {
                @Override
                protected void updateItem(Object item, boolean empty) {
                    super.updateItem(item, empty);
                    String text = empty || item == null ? null : c.text().apply(item);
                    setText(text);
                    // длинный текст обрезается шириной колонки — показываем его целиком во всплывающей подсказке
                    setTooltip(text != null && text.length() > 40 ? new javafx.scene.control.Tooltip(text) : null);
                    if (getTooltip() != null) getTooltip().setWrapText(true);
                    if (getTooltip() != null) getTooltip().setMaxWidth(700);
                }

                {
                    // запомнить ячейку под курсором: по ней строятся пункты «Отбор…» контекстного меню
                    addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                        clickedCol = c;
                        clickedItem = getTableRow() == null ? null : getTableRow().getItem();
                    });
                }
            });
            columns.put(c, tc);
            tc.setComparator(EntityTable::compare);
            if (c.width() > 0) tc.setPrefWidth(c.width());
            else tc.setPrefWidth(Math.max(90, c.title().length() * 8 + 20));
            table.getColumns().add(tc);
        }
        ContextMenu menu = new ContextMenu();
        attachFilterMenu(menu);
        table.setContextMenu(menu);
        table.setRowFactory(tv -> {
            TableRow<T> row = new TableRow<>() {
                @Override
                protected void updateItem(T item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll(ROW_STYLES);
                    if (!empty && item != null && rowStyle != null) {
                        String s = rowStyle.apply(item);
                        if (s != null) getStyleClass().add(s);
                    }
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty() && onDoubleClick != null) {
                    onDoubleClick.accept(row.getItem());
                }
            });
            return row;
        });
    }

    private static <T> Object value(Col<T> c, T t) {
        try {
            return c.value().apply(t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Comparable ca && a.getClass().isInstance(b)) return ca.compareTo(b);
        return a.toString().compareToIgnoreCase(b.toString());
    }

    public TableView<T> view() {
        return table;
    }

    public void setPlaceholder(String text) {
        table.setPlaceholder(new Label(text));
    }

    /** Класс CSS строки: row-blocked, row-blocker, row-warning или null. */
    public void setRowStyle(Function<T, String> f) {
        this.rowStyle = f;
        table.refresh();
    }

    public void setOnDoubleClick(Consumer<T> c) {
        this.onDoubleClick = c;
    }

    /** Поле поиска, фильтрующее таблицу по тексту любой колонки. */
    public TextField filterField() {
        TextField f = new TextField();
        f.setPromptText("Поиск по всем колонкам…");
        f.getStyleClass().add("filter-field");
        f.textProperty().addListener((o, was, now) -> setFilter(now));
        return f;
    }

    public void setFilter(String text) {
        filterText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    /** Вызывается при любом изменении поиска или отборов (обновить счётчик строк). */
    public void addFilterListener(Runnable r) {
        filterListeners.add(r);
    }

    private void applyFilter() {
        filtered.setPredicate(filterText.isEmpty() && conds.isEmpty() ? t -> true : t -> matchesConds(t) && matches(t));
        // значок воронки у колонок с отбором
        for (java.util.Map.Entry<Col<T>, TableColumn<T, Object>> e : columns.entrySet()) {
            boolean on = conds.stream().anyMatch(c -> c.col() == e.getKey());
            e.getValue().setGraphic(on ? funnel() : null);
        }
        for (Runnable r : filterListeners) r.run();
    }

    private static javafx.scene.Node funnel() {
        javafx.scene.shape.SVGPath p = new javafx.scene.shape.SVGPath();
        p.setContent("M1 1.5h10L7 6.5v4L5 11.5v-5z");
        p.getStyleClass().add("filter-mark");
        return p;
    }

    /** Текст ячейки так, как он показан в таблице (по нему и сравнивается отбор). */
    private static <T> String cellText(Col<T> c, T t) {
        Object v = value(c, t);
        return v == null ? "" : String.valueOf(c.text().apply(v));
    }

    @SuppressWarnings("unchecked")
    private boolean matchesConds(T t) {
        for (Cond k : conds) {
            boolean eq = cellText((Col<T>) k.col(), t).equals(k.text());
            if (eq == k.exclude()) return false;
        }
        return true;
    }

    private void addCond(Col<T> col, String text, boolean exclude) {
        conds.removeIf(k -> k.col() == col && k.text().equals(text));
        conds.add(new Cond(col, text, exclude));
        applyFilter();
    }

    /** Снять все отборы по значению (поиск по тексту не трогается). */
    public void clearConds() {
        conds.clear();
        applyFilter();
    }

    /**
     * Добавить в контекстное меню пункты отбора по значению ячейки под курсором.
     * Пункты пересобираются при каждом открытии меню и стоят первыми.
     */
    public void attachFilterMenu(ContextMenu menu) {
        menu.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWING, e -> {
            menu.getItems().removeIf(mi -> "filter".equals(mi.getUserData()));
            List<MenuItem> items = filterItems();
            if (!items.isEmpty() && menu.getItems().size() > 0) items.add(sep());
            menu.getItems().addAll(0, items);
            ru.rasconsole.ui.Buttons.decorateMenu(items);
        });
    }

    private static MenuItem sep() {
        SeparatorMenuItem s = new SeparatorMenuItem();
        s.setUserData("filter");
        return s;
    }

    private static MenuItem item(String text, Runnable r) {
        MenuItem mi = new MenuItem(text);
        mi.setUserData("filter");
        mi.setMnemonicParsing(false);
        mi.setOnAction(e -> r.run());
        return mi;
    }

    private List<MenuItem> filterItems() {
        List<MenuItem> items = new ArrayList<>();
        Col<T> col = clickedCol;
        T row = clickedItem;
        if (col != null && row != null) {
            String text = cellText(col, row);
            String shown = text.isEmpty() ? "(пусто)" : text.length() > 40 ? text.substring(0, 39) + "…" : text;
            items.add(item("Отбор: «" + col.title() + "» = " + shown, () -> addCond(col, text, false)));
            items.add(item("Исключить: «" + col.title() + "» ≠ " + shown, () -> addCond(col, text, true)));
            items.add(item("Копировать значение", () -> {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(text);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            }));
            if (conds.stream().anyMatch(k -> k.col() == col)) {
                items.add(item("Снять отбор по «" + col.title() + "»", () -> {
                    conds.removeIf(k -> k.col() == col);
                    applyFilter();
                }));
            }
        }
        if (!conds.isEmpty()) {
            items.add(item("Снять все отборы (" + conds.size() + ")", this::clearConds));
        }
        return items;
    }

    /** Описание действующих отборов: «Пользователь = ИвановАА; ИБ ≠ buh». */
    public String condsText() {
        StringBuilder sb = new StringBuilder();
        for (Cond k : conds) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(k.col().title()).append(k.exclude() ? " ≠ " : " = ").append(k.text().isEmpty() ? "(пусто)" : k.text());
        }
        return sb.toString();
    }

    private boolean matches(T t) {
        if (filterText.isEmpty()) return true;
        // все слова запроса должны встретиться в строке (в любых колонках)
        String row = rowText(t);
        for (String w : filterText.split("\\s+")) {
            if (!row.contains(w)) return false;
        }
        return true;
    }

    private String rowText(T t) {
        StringBuilder sb = new StringBuilder();
        for (Col<T> c : cols) {
            Object v = value(c, t);
            if (v != null) sb.append(c.text().apply(v)).append('\u0001');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Заменить строки, сохранив сортировку, фильтр и выделение. */
    public void setItems(List<T> items) {
        Set<Object> selected = new HashSet<>();
        if (key != null) {
            for (T t : table.getSelectionModel().getSelectedItems()) selected.add(key.apply(t));
        }
        source.setAll(items);
        if (key != null && !selected.isEmpty()) {
            for (int i = 0; i < table.getItems().size(); i++) {
                if (selected.contains(key.apply(table.getItems().get(i)))) table.getSelectionModel().select(i);
            }
        }
    }

    public List<T> selected() {
        return new ArrayList<>(table.getSelectionModel().getSelectedItems());
    }

    /** Строки, видимые с учётом поиска, в порядке сортировки. */
    public List<T> visible() {
        return new ArrayList<>(table.getItems());
    }

    public int total() {
        return source.size();
    }

    public boolean isFiltered() {
        return !filterText.isEmpty() || !conds.isEmpty();
    }

    /**
     * Запоминать ширину, порядок и видимость колонок между запусками.
     * @param id имя таблицы (например, заголовок раздела)
     */
    public void bindState(String id) {
        String base = "table." + id.replaceAll("[^\\p{L}\\p{N}]+", "_") + ".";
        java.util.Map<String, TableColumn<T, ?>> byTitle = new java.util.LinkedHashMap<>();
        for (TableColumn<T, ?> c : table.getColumns()) byTitle.put(c.getText(), c);
        // порядок
        List<String> order = ru.rasconsole.config.UiState.getList(base + "order");
        if (!order.isEmpty()) {
            List<TableColumn<T, ?>> sorted = new ArrayList<>();
            for (String t : order) if (byTitle.containsKey(t)) sorted.add(byTitle.get(t));
            for (TableColumn<T, ?> c : byTitle.values()) if (!sorted.contains(c)) sorted.add(c);
            table.getColumns().setAll(sorted);
        }
        for (TableColumn<T, ?> c : byTitle.values()) {
            String k = base + "col." + c.getText().replaceAll("[^\\p{L}\\p{N}]+", "_");
            double w = ru.rasconsole.config.UiState.getDouble(k + ".w", -1);
            if (w > 10) c.setPrefWidth(w);
            c.setVisible(ru.rasconsole.config.UiState.getBool(k + ".visible", true));
            c.widthProperty().addListener((o, was, now) -> ru.rasconsole.config.UiState.put(k + ".w",
                Long.toString(Math.round(now.doubleValue()))));
            c.visibleProperty().addListener((o, was, now) -> ru.rasconsole.config.UiState.put(k + ".visible", now.toString()));
        }
        table.getColumns().addListener((javafx.collections.ListChangeListener<TableColumn<T, ?>>) ch -> {
            List<String> titles = new ArrayList<>();
            for (TableColumn<T, ?> c : table.getColumns()) titles.add(c.getText());
            ru.rasconsole.config.UiState.putList(base + "order", titles);
        });
    }

    /** Скопировать выделенные строки (видимые колонки, через табуляцию) в буфер обмена — для Excel. */
    public void copySelected() {
        List<T> sel = selected();
        if (sel.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        List<TableColumn<T, ?>> visibleCols = new ArrayList<>();
        for (TableColumn<T, ?> tc : table.getColumns()) if (tc.isVisible()) visibleCols.add(tc);
        for (int i = 0; i < visibleCols.size(); i++) {
            if (i > 0) sb.append('\t');
            sb.append(visibleCols.get(i).getText());
        }
        sb.append('\n');
        for (T t : sel) {
            for (int i = 0; i < visibleCols.size(); i++) {
                if (i > 0) sb.append('\t');
                Col<T> c = cols.get(indexOfCol(visibleCols.get(i)));
                Object v = value(c, t);
                sb.append(v == null ? "" : safeCell(c.text().apply(v).replace('\t', ' ').replace('\n', ' ')));
            }
            sb.append('\n');
        }
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(sb.toString());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
    }

    /** Индекс колонки в исходном списке (порядок на экране мог измениться). */
    private int indexOfCol(TableColumn<T, ?> tc) {
        for (int i = 0; i < cols.size(); i++) if (cols.get(i).title().equals(tc.getText())) return i;
        return 0;
    }

    /** Экспорт видимых строк в CSV (UTF-8 с BOM, разделитель «;» — открывается в Excel). */
    public void exportCsv(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("﻿");
        List<TableColumn<T, ?>> visibleCols = new ArrayList<>();
        for (TableColumn<T, ?> tc : table.getColumns()) if (tc.isVisible()) visibleCols.add(tc);
        for (int i = 0; i < visibleCols.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(csv(visibleCols.get(i).getText()));
        }
        sb.append("\r\n");
        for (T t : table.getItems()) {
            for (int i = 0; i < visibleCols.size(); i++) {
                if (i > 0) sb.append(';');
                Col<T> c = cols.get(indexOfCol(visibleCols.get(i)));
                Object v = value(c, t);
                sb.append(csv(v == null ? "" : c.text().apply(v)));
            }
            sb.append("\r\n");
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Текст, который Excel не примет за формулу. Описания блокировок, имена компьютеров и пользователей
     * приходят с сервера и могут начинаться с «=», «+», «-», «@» — такая ячейка выполнилась бы как формула.
     * Числа (в том числе отрицательные) не трогаем.
     */
    public static String safeCell(String s) {
        if (s == null || s.isEmpty()) return s;
        char c = s.charAt(0);
        boolean risky = c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
        if (!risky || s.matches("[-+]?\\d+([.,]\\d+)?")) return s;
        return "'" + s;
    }

    private static String csv(String s) {
        if (s == null) return "";
        s = safeCell(s);
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
