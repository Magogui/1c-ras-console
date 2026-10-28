package ru.rasconsole.ui.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.TreeItem;

/** Элемент дерева с ленивой загрузкой дочерних узлов. */
public final class NodeTreeItem extends TreeItem<ConsoleNode> {

    private static final ConsoleNode LOADING = new ConsoleNode() {
        @Override
        public String title() {
            return "Загрузка…";
        }
    };

    private boolean loaded;
    private boolean loading;

    public NodeTreeItem(ConsoleNode node) {
        super(node);
        node.treeItem = this;
        valueProperty().addListener((o, was, now) -> {
            if (now != null) now.treeItem = this;
        });
        resetPlaceholder();
        expandedProperty().addListener((o, was, now) -> {
            if (now && !loaded) reload();
        });
    }

    private void resetPlaceholder() {
        if (!getValue().isLeaf()) {
            getChildren().setAll(List.of(new TreeItem<>(LOADING)));
        } else {
            getChildren().clear();
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public boolean isLeaf() {
        return getValue() == null || getValue().isLeaf();
    }

    /** Перезагрузить дочерние узлы; раскрытые узлы с тем же ключом сохраняются. */
    public void reload() {
        if (loading || isLeaf()) return;
        loading = true;
        getValue().loadChildren(nodes -> {
            loading = false;
            loaded = true;
            Map<Object, NodeTreeItem> old = new HashMap<>();
            for (TreeItem<ConsoleNode> c : getChildren()) {
                if (c instanceof NodeTreeItem n) old.put(n.getValue().key(), n);
            }
            List<TreeItem<ConsoleNode>> items = new ArrayList<>();
            for (ConsoleNode n : nodes) {
                NodeTreeItem prev = old.get(n.key());
                if (prev != null) {
                    prev.setValue(n);
                    if (prev.isExpanded()) {
                        prev.reload();
                    } else {
                        prev.loaded = false;
                        prev.resetPlaceholder();
                    }
                    items.add(prev);
                } else {
                    items.add(new NodeTreeItem(n));
                }
            }
            getChildren().setAll(items);
        });
    }

    /** Обновить только заголовок (например, при смене состояния соединения). */
    public void refreshTitle() {
        ConsoleNode n = getValue();
        setValue(null);
        setValue(n);
    }

    /** Сбросить загруженных детей и свернуть (например, при отключении). */
    public void collapseAndReset() {
        setExpanded(false);
        loaded = false;
        resetPlaceholder();
    }
}
