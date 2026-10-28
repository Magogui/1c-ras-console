package ru.rasconsole.ui.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import ru.rasconsole.ras.RasFn;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.Scope;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.table.EntityTable;

/**
 * Узел-список: таблица объектов (сеансы, базы, процессы…) с действиями над выделенными строками.
 * Если задан {@link #children(Function)}, объекты списка становятся дочерними узлами дерева.
 */
public final class ListNode<T> extends ConsoleNode {

    /** Требование к выделению для действия. */
    public enum Sel { NONE, ONE, MANY }

    @FunctionalInterface
    public interface Handler<T> {
        void run(List<T> selected, ListNode<T> node);
    }

    public record Action<T>(String name, Sel sel, Handler<T> handler) {
    }

    private static final Duration AUTO_REFRESH = Duration.seconds(5);

    private final String title;
    private final Scope scope;
    private final RasFn<List<T>> loader;
    private final List<Col<T>> cols;
    private final List<Action<T>> actions = new ArrayList<>();
    private Function<T, Object> key;
    private Function<T, ConsoleNode> child;
    private Action<T> defaultAction;
    private boolean autoRefreshDefault;
    private String iconKey;
    private Function<T, String> rowStyle;
    private View view;
    /** Показывать число строк в дереве: «Сеансы (12)». null — ещё не известно. */
    private boolean countInTitle;
    private Integer count;

    public ListNode(String title, Scope scope, List<Col<T>> cols, RasFn<List<T>> loader) {
        this.title = title;
        this.scope = scope;
        this.cols = cols;
        this.loader = loader;
    }

    public ListNode<T> key(Function<T, Object> k) {
        this.key = k;
        return this;
    }

    public ListNode<T> children(Function<T, ConsoleNode> f) {
        this.child = f;
        return this;
    }

    public ListNode<T> action(String name, Sel sel, Handler<T> h) {
        actions.add(new Action<>(name, sel, h));
        return this;
    }

    /** Действие по двойному щелчку (тоже попадает в панель). */
    public ListNode<T> defaultAction(String name, Handler<T> h) {
        Action<T> a = new Action<>(name, Sel.ONE, h);
        actions.add(a);
        defaultAction = a;
        return this;
    }

    /** Ключ иконки из {@link ru.rasconsole.ui.Icons}. */
    public ListNode<T> icon(String key) {
        this.iconKey = key;
        return this;
    }

    @Override
    public Node icon() {
        return ru.rasconsole.ui.Icons.get(iconKey);
    }

    /** Подсветка строк: класс CSS (row-blocked, row-blocker, row-warning) или null. */
    public ListNode<T> rowStyle(Function<T, String> f) {
        this.rowStyle = f;
        return this;
    }

    /** Строки, видимые сейчас в таблице с учётом поиска (пусто, если вид не открыт). */
    public List<T> visible() {
        return view == null ? List.of() : view.table.visible();
    }

    /** Задан ли поиск в таблице. */
    public boolean isFiltered() {
        return view != null && view.table.isFiltered();
    }

    public ListNode<T> autoRefresh(boolean on) {
        this.autoRefreshDefault = on;
        return this;
    }

    public Scope scope() {
        return scope;
    }

    @Override
    public String title() {
        return countInTitle && count != null ? title + " (" + count + ")" : title;
    }

    /** Ключ узла — название без счётчика (для запоминания раскрытых узлов и перехода к разделу). */
    @Override
    public Object key() {
        return title;
    }

    /**
     * Показывать в дереве число строк списка. Число загружается сразу в фоне (без запросов пароля:
     * если пароль не сохранён, число появится после первого открытия списка) и обновляется при каждой
     * загрузке таблицы, в том числе при автообновлении.
     */
    public ListNode<T> countInTitle() {
        this.countInTitle = true;
        refreshCount();
        return this;
    }

    /** Перечитать число строк в фоне. */
    public void refreshCount() {
        if (!countInTitle) return;
        RasFn<List<T>> fn = loader;
        (scope.slow() ? scope.conn().submitSlow(scope.auth(), scope.clusterId(), fn)
            : scope.conn().submit(scope.auth(), scope.clusterId(), fn))
            .thenAccept(list -> javafx.application.Platform.runLater(() -> setCount(list.size())))
            .exceptionally(e -> null);
    }

    private void setCount(int n) {
        if (count != null && count == n) return;
        count = n;
        if (treeItem() != null) treeItem().refreshTitle();
    }

    @Override
    public boolean isLeaf() {
        return child == null;
    }

    @Override
    public void loadChildren(Consumer<List<ConsoleNode>> done) {
        Fx.call(scope, title, loader, list -> {
            List<ConsoleNode> nodes = new ArrayList<>();
            for (T t : list) nodes.add(child.apply(t));
            done.accept(nodes);
        }, err -> done.accept(List.of()));
    }

    /** Перечитать таблицу и дочерние узлы. */
    public void refresh() {
        if (view != null) view.reload();
        reloadTree();
    }

    @Override
    public Node createView() {
        view = new View();
        view.reload();
        return view.root;
    }

    @Override
    public List<MenuItem> menu(NodeTreeItem item) {
        List<MenuItem> m = new ArrayList<>();
        m.add(item("Обновить", this::refresh));
        for (Action<T> a : actions) {
            if (a.sel() == Sel.NONE) m.add(item(a.name(), () -> a.handler().run(List.of(), this)));
        }
        return m;
    }

    private final class View implements Disposable, Searchable {
        final BorderPane root = new BorderPane();
        final EntityTable<T> table = new EntityTable<>(cols, key);
        final Label count = new Label();
        final Button clearConds = new Button();
        TextField filter;
        final Timeline timer = new Timeline();
        final List<Runnable> enablers = new ArrayList<>();
        boolean loading;

        View() {
            javafx.scene.layout.FlowPane bar = ru.rasconsole.ui.Buttons.bar();
            Button refresh = new Button("Обновить");
            refresh.setOnAction(e -> reload());
            bar.getChildren().add(refresh);
            if (!actions.isEmpty()) bar.getChildren().add(ru.rasconsole.ui.Buttons.separator());
            ContextMenu ctx = new ContextMenu();
            for (Action<T> a : actions) {
                Button b = new Button(a.name());
                b.setOnAction(e -> runAction(a));
                MenuItem mi = new MenuItem(a.name());
                mi.setOnAction(e -> runAction(a));
                ctx.getItems().add(mi);
                Runnable en = () -> {
                    int n = table.view().getSelectionModel().getSelectedItems().size();
                    boolean ok = switch (a.sel()) {
                        case NONE -> true;
                        case ONE -> n == 1;
                        case MANY -> n >= 1;
                    };
                    b.setDisable(!ok);
                    mi.setDisable(!ok);
                };
                enablers.add(en);
                bar.getChildren().add(b);
            }
            filter = table.filterField();
            filter.textProperty().addListener((o, was, now) -> updateCount());
            Button csv = new Button("Экспорт в CSV…");
            csv.setOnAction(e -> exportCsv());
            Pane spacer = new Pane();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            javafx.scene.layout.HBox auto = ru.rasconsole.ui.AutoRefresh.control(timer, this::reload, "autorefresh.list", 5,
                autoRefreshDefault);
            clearConds.setOnAction(e -> table.clearConds());
            clearConds.getStyleClass().add("clear-filter");
            clearConds.setVisible(false);
            clearConds.setManaged(false);
            ToolBar bar2 = new ToolBar(filter, csv, clearConds, spacer, count, new Separator(), auto);

            table.view().getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<T>) c -> enablers.forEach(Runnable::run));
            enablers.forEach(Runnable::run);
            table.attachFilterMenu(ctx);
            table.view().setContextMenu(ctx);
            table.addFilterListener(this::updateCount);
            table.setOnDoubleClick(t -> {
                if (defaultAction != null) runAction(defaultAction);
            });
            table.bindState(title);
            // горячие клавиши таблицы
            table.view().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                switch (e.getCode()) {
                    case ENTER -> {
                        if (defaultAction != null) {
                            runAction(defaultAction);
                            e.consume();
                        }
                    }
                    case DELETE -> {
                        Action<T> del = deleteAction();
                        if (del != null) {
                            runAction(del);
                            e.consume();
                        }
                    }
                    case C -> {
                        if (e.isShortcutDown()) {
                            table.copySelected();
                            Fx.status("Скопировано строк: " + table.selected().size());
                            e.consume();
                        }
                    }
                    case F -> {
                        if (e.isShortcutDown()) {
                            filter.requestFocus();
                            e.consume();
                        }
                    }
                    default -> { }
                }
            });
            filter.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                    filter.clear();
                    table.view().requestFocus();
                } else if (e.getCode() == javafx.scene.input.KeyCode.DOWN || e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                    table.view().requestFocus();
                    if (table.view().getSelectionModel().isEmpty()) table.view().getSelectionModel().selectFirst();
                }
            });
            if (rowStyle != null) table.setRowStyle(rowStyle);

            Label header = new Label(title);
            header.getStyleClass().add("view-title");
            header.setPadding(new Insets(6, 8, 2, 8));
            root.setTop(new javafx.scene.layout.VBox(header, bar, bar2));
            root.setCenter(table.view());
            root.setUserData(this);
            // остановить таймер, когда вид убран с экрана
            root.sceneProperty().addListener((o, was, now) -> {
                if (now == null) dispose();
            });
        }

        /** Действие для клавиши Delete: удаление, завершение сеанса или отключение соединения. */
        Action<T> deleteAction() {
            for (Action<T> a : actions) {
                String n = a.name();
                if (a.sel() != Sel.NONE && (n.startsWith("Удалить") || n.startsWith("Завершить сеанс") || n.startsWith("Отключить"))) {
                    return a;
                }
            }
            return null;
        }

        /** Поставить фокус в поле поиска (Ctrl+F из главного окна). */
        @Override
        public void focusFilter() {
            filter.requestFocus();
        }

        @Override
        public void setFilter(String text) {
            filter.setText(text);
        }

        void runAction(Action<T> a) {
            List<T> sel = table.selected();
            if (a.sel() == Sel.ONE && sel.size() != 1) return;
            if (a.sel() == Sel.MANY && sel.isEmpty()) return;
            a.handler().run(sel, ListNode.this);
        }

        void reload() {
            if (loading) return;
            loading = true;
            Fx.call(scope, title, loader, list -> {
                loading = false;
                if (countInTitle) setCount(list.size());
                table.setItems(list);
                table.setPlaceholder("Нет данных");
                updateCount();
            }, err -> {
                loading = false;
                timer.stop();
                table.setItems(List.of());
                count.setText("");
                table.setPlaceholder(Fx.isAuthCancel(err) ? "Требуется авторизация. Нажмите «Обновить»."
                    : "Ошибка: " + Fmt.rootMessage(err));
            });
        }

        void updateCount() {
            count.setText(table.isFiltered()
                ? "Найдено: " + table.visible().size() + " из " + table.total()
                : "Всего: " + table.total());
            String conds = table.condsText();
            count.setTooltip(conds.isEmpty() ? null : new javafx.scene.control.Tooltip("Отбор: " + conds));
            clearConds.setVisible(!conds.isEmpty());
            clearConds.setManaged(!conds.isEmpty());
            int n = conds.isEmpty() ? 0 : conds.split("; ").length;
            clearConds.setText("Снять отборы (" + n + ")");
            clearConds.setTooltip(conds.isEmpty() ? null : new javafx.scene.control.Tooltip("Отбор: " + conds));
        }

        void exportCsv() {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Экспорт в CSV");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv"));
            fc.setInitialFileName(title.replaceAll("[\\\\/:*?\"<>|]", "_") + ".csv");
            java.io.File f = fc.showSaveDialog(root.getScene() == null ? null : root.getScene().getWindow());
            if (f == null) return;
            try {
                table.exportCsv(f.toPath());
                Fx.status("Сохранено строк: " + table.visible().size() + " → " + f);
            } catch (java.io.IOException ex) {
                ru.rasconsole.ui.Dialogs.error("Экспорт в CSV", ex);
            }
        }

        @Override
        public void dispose() {
            timer.stop();
        }
    }
}
