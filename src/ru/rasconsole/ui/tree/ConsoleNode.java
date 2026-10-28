package ru.rasconsole.ui.tree;

import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.MenuItem;

/** Узел дерева консоли. */
public abstract class ConsoleNode {

    NodeTreeItem treeItem;

    /** Элемент дерева, в котором показан узел (null, если узел не в дереве). */
    public NodeTreeItem treeItem() {
        return treeItem;
    }

    /** Перечитать дочерние узлы в дереве, если они уже были загружены. */
    public void reloadTree() {
        if (treeItem != null && treeItem.isLoaded()) treeItem.reload();
    }

    /** Текст в дереве. */
    public abstract String title();

    /** Ключ для сохранения раскрытых узлов при обновлении (по умолчанию — заголовок). */
    public Object key() {
        return title();
    }

    /** Устойчивый ключ узла для запоминания раскрытых/выбранных узлов между запусками. */
    public String stateKey() {
        return String.valueOf(key());
    }

    /** Вид правой панели с полем поиска (Ctrl+F). */
    public interface Searchable {
        void focusFilter();

        /** Задать текст поиска в таблице. */
        default void setFilter(String text) {
        }
    }

    /** Узел продуктивного сервера — выделяется в дереве. */
    public boolean isProd() {
        return false;
    }

    /** Узел сервера, связь с которым сейчас проверяется. */
    public boolean isChecking() {
        return false;
    }

    /** Узел недоступного сервера — выделяется в дереве красным. */
    public boolean isUnavailable() {
        return false;
    }

    /** Иконка в дереве (null — без иконки). */
    public Node icon() {
        return null;
    }

    public boolean isLeaf() {
        return true;
    }

    /** Загрузить дочерние узлы (может быть асинхронно). При ошибке передать пустой список. */
    public void loadChildren(Consumer<List<ConsoleNode>> done) {
        done.accept(List.of());
    }

    /** Содержимое правой панели. */
    public Node createView() {
        return null;
    }

    /** Пункты контекстного меню. */
    public List<MenuItem> menu(NodeTreeItem item) {
        return List.of();
    }

    /** Освобождение ресурсов вида (таймеры автообновления). */
    public interface Disposable {
        void dispose();
    }

    public static MenuItem item(String text, Runnable action) {
        MenuItem m = new MenuItem(text);
        m.setOnAction(e -> action.run());
        return m;
    }
}
