package ru.rasconsole.ui.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import ru.rasconsole.ras.RasFn;
import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.Scope;
import ru.rasconsole.ui.form.BeanForm;

/**
 * Узел одного объекта (кластер, рабочий сервер, ИБ, профиль…): справа — его свойства,
 * в дереве — фиксированные дочерние разделы.
 */
public final class ObjectNode<T> extends ConsoleNode {

    /** Действие; название может меняться (например, «В избранное» / «Убрать из избранного»). */
    public record Action<T>(java.util.function.Supplier<String> name, Consumer<ObjectNode<T>> run) {
    }

    private final Scope scope;
    private final Class<?> type;
    private final Function<T, String> titleFn;
    private final Object key;
    private T item;
    private RasFn<T> reloader;
    private Function<T, RasFn<?>> saver;
    private String saveTitle;
    private Function<ObjectNode<T>, List<ConsoleNode>> sections;
    private final List<Action<T>> actions = new ArrayList<>();
    private BorderPane viewRoot;
    private Label header;
    private String iconKey;

    public ObjectNode(Scope scope, Class<?> type, T item, Object key, Function<T, String> titleFn) {
        this.scope = scope;
        this.type = type;
        this.item = item;
        this.key = key;
        this.titleFn = titleFn;
    }

    /** Как перечитать объект с сервера. */
    public ObjectNode<T> reloader(RasFn<T> r) {
        this.reloader = r;
        return this;
    }

    /** Как сохранить изменённый объект; без него форма только для чтения. */
    public ObjectNode<T> saver(String title, Function<T, RasFn<?>> s) {
        this.saveTitle = title;
        this.saver = s;
        return this;
    }

    public ObjectNode<T> sections(Function<ObjectNode<T>, List<ConsoleNode>> s) {
        this.sections = s;
        return this;
    }

    /** Ключ иконки из {@link ru.rasconsole.ui.Icons}. */
    public ObjectNode<T> icon(String key) {
        this.iconKey = key;
        return this;
    }

    @Override
    public Node icon() {
        return ru.rasconsole.ui.Icons.get(iconKey);
    }

    public ObjectNode<T> action(String name, Consumer<ObjectNode<T>> run) {
        actions.add(new Action<>(() -> name, run));
        return this;
    }

    /** Действие с изменяемым названием (пересчитывается после выполнения и при открытии меню). */
    public ObjectNode<T> action(java.util.function.Supplier<String> name, Consumer<ObjectNode<T>> run) {
        actions.add(new Action<>(name, run));
        return this;
    }

    public T item() {
        return item;
    }

    public Scope scope() {
        return scope;
    }

    @Override
    public String title() {
        return titleFn.apply(item);
    }

    @Override
    public Object key() {
        return key;
    }

    @Override
    public boolean isLeaf() {
        return sections == null;
    }

    @Override
    public void loadChildren(Consumer<List<ConsoleNode>> done) {
        done.accept(sections.apply(this));
    }

    @Override
    public Node createView() {
        viewRoot = new BorderPane();
        javafx.scene.layout.FlowPane bar = ru.rasconsole.ui.Buttons.bar();
        if (reloader != null) {
            Button r = new Button("Обновить");
            r.setOnAction(e -> reload());
            bar.getChildren().add(r);
        }
        if (saver != null) {
            Button ed = new Button("Изменить…");
            ed.setOnAction(e -> edit());
            bar.getChildren().add(ed);
        }
        if (!actions.isEmpty() && !bar.getChildren().isEmpty()) bar.getChildren().add(ru.rasconsole.ui.Buttons.separator());
        for (Action<T> a : actions) {
            Button b = new Button(a.name().get());
            b.setOnAction(e -> {
                a.run().accept(this);
                b.setText(a.name().get());
            });
            bar.getChildren().add(b);
        }
        header = new Label(title());
        header.getStyleClass().add("view-title");
        header.setPadding(new Insets(6, 8, 2, 8));
        viewRoot.setTop(new VBox(header, bar));
        showItem();
        if (reloader != null) reload();
        return viewRoot;
    }

    private void showItem() {
        if (viewRoot != null) viewRoot.setCenter(new BeanForm(item, type, null, false).node());
        if (header != null) header.setText(title());
    }

    public void reload() {
        if (reloader == null) return;
        Fx.call(scope, title(), reloader, v -> {
            if (v != null) {
                item = v;
                showItem();
                if (treeItem != null) treeItem.refreshTitle();
            }
        }, err -> { });
    }

    public void edit() {
        if (saver == null) return;
        if (Dialogs.editBean(saveTitle, item, type, null, true, null)) {
            Fx.call(scope, saveTitle, saver.apply(item), v -> {
                Fx.audit(scope, saveTitle, "OK");
                showItem();
                reload();
                if (treeItem != null) treeItem.refreshTitle();
            }, err -> {
                if (!Fx.isAuthCancel(err)) {
                    Fx.audit(scope, saveTitle, "ошибка: " + ru.rasconsole.ui.Fmt.rootMessage(err));
                    Dialogs.error(saveTitle, err);
                }
                reload();
            });
        }
    }

    @Override
    public List<MenuItem> menu(NodeTreeItem treeItem) {
        List<MenuItem> m = new ArrayList<>();
        if (saver != null) m.add(item("Свойства…", this::edit));
        if (sections != null) m.add(item("Обновить", treeItem::reload));
        for (Action<T> a : actions) m.add(item(a.name().get(), () -> a.run().accept(this)));
        return m;
    }
}
