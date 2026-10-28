package ru.rasconsole.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.control.Button;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.Priority;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import ru.rasconsole.config.CredentialStore;
import ru.rasconsole.config.ServerConfig;
import ru.rasconsole.config.ServerStore;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ui.tree.ConsoleNode;
import ru.rasconsole.ui.tree.NodeTreeItem;

/** Главное окно: дерево серверов слева, содержимое выбранного узла справа. */
public final class MainWindow implements ServerNode.Owner {

    private final ServerStore store;
    private final CredentialStore credentials;
    private final Monitor monitor;
    private final Function<ServerConfig, RasConnection> connections;
    private final BorderPane root = new BorderPane();
    private final BorderPane detail = new BorderPane();
    private final Label status = new Label("Готово");
    private final List<ServerNode> servers = new ArrayList<>();
    private final NodeTreeItem rootItem;
    private final TreeView<ConsoleNode> tree;

    public MainWindow(ServerStore store) {
        this(store, RasConnection::new);
    }

    /** @param connections как создавать соединение для сервера (подменяется в тестах) */
    public MainWindow(ServerStore store, Function<ServerConfig, RasConnection> connections) {
        this.store = store;
        this.connections = connections;
        // Сохранённые пароли: читаем до создания соединений, чтобы они сразу их получили
        ru.rasconsole.config.AuditLog.init(store.dir());
        ru.rasconsole.config.UiState.init(store.dir());
        this.credentials = new CredentialStore(store.dir());
        credentials.load();
        Fx.setCredentialStore(credentials);
        for (ServerConfig cfg : store.load()) servers.add(newServerNode(cfg));
        monitor = new Monitor(() -> servers.stream().map(ServerNode::conn).toList(), store.dir());
        monitor.setOnTrayClick(this::bringToFront);
        Theme.set(monitor.settings().theme);
        ru.rasconsole.ui.cluster.ClusterSections.setConnections(() -> servers.stream().map(ServerNode::conn).toList());
        ru.rasconsole.ui.cluster.ClusterSections.setTemplates(new ru.rasconsole.config.IbTemplates(store.dir()));
        favorites = new ru.rasconsole.config.Favorites(store.dir());
        ru.rasconsole.ui.cluster.ClusterSections.setFavorites(favorites);
        ru.rasconsole.ui.cluster.ClusterSections.setMonitor(monitor);
        ru.rasconsole.ui.cluster.Scheduler.init(store.dir(), () -> servers.stream().map(ServerNode::conn).toList());

        rootItem = new NodeTreeItem(new RootNode());
        tree = new TreeView<>(rootItem);
        tree.setShowRoot(true);
        tree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(ConsoleNode node, boolean empty) {
                super.updateItem(node, empty);
                if (empty || node == null) {
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    getStyleClass().removeAll("prod-node", "down-node", "checking-node");
                    return;
                }
                setText(node.title());
                setGraphic(node.icon());
                getStyleClass().removeAll("prod-node", "down-node", "checking-node");
                if (node.isProd()) getStyleClass().add("prod-node");
                if (node.isUnavailable()) getStyleClass().add("down-node");
                if (node.isChecking()) getStyleClass().add("checking-node");
                ContextMenu cm = new ContextMenu();
                cm.setOnShowing(e -> {
                    if (getTreeItem() instanceof NodeTreeItem ti) {
                        cm.getItems().setAll(node.menu(ti));
                        if (!cm.getItems().isEmpty()) cm.getItems().add(new SeparatorMenuItem());
                        MenuItem tab = ConsoleNode.item("Открыть в новой вкладке", () -> openInTab(ti));
                        tab.setAccelerator(new KeyCodeCombination(KeyCode.T, javafx.scene.input.KeyCombination.SHORTCUT_DOWN));
                        cm.getItems().add(tab);
                        Buttons.decorateMenu(cm.getItems());
                    }
                });
                cm.getItems().add(new MenuItem("…"));
                setContextMenu(cm);
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            tabs.getSelectionModel().select(mainTab);
            showDetail(now);
            rememberRecent(now);
        });
        // средняя кнопка мыши по узлу — открыть в новой вкладке
        tree.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.MIDDLE) return;
            Node n = e.getPickResult().getIntersectedNode();
            while (n != null && !(n instanceof TreeCell)) n = n.getParent();
            if (n instanceof TreeCell<?> cell && cell.getTreeItem() instanceof NodeTreeItem ti) openInTab(ti);
        });
        rootItem.setExpanded(true);

        Button add = new Button("Добавить");
        add.setTooltip(new javafx.scene.control.Tooltip("Добавить сервер RAS"));
        add.setOnAction(e -> addServer(""));
        Button imp = new Button("Импорт…");
        imp.setTooltip(new javafx.scene.control.Tooltip("Импорт списка серверов из файла"));
        imp.setOnAction(e -> importServers());
        Button exp = new Button("Экспорт…");
        exp.setTooltip(new javafx.scene.control.Tooltip("Экспорт выбранных серверов в файл"));
        exp.setOnAction(e -> exportServers());
        treeSearch.setPromptText("Найти в дереве (Enter — следующий)");
        treeSearch.setOnAction(e -> findInTree(treeSearch.getText()));
        Button everywhere = new Button("Везде…");
        everywhere.setTooltip(new javafx.scene.control.Tooltip("Поиск баз и пользователей на всех серверах (Ctrl+Shift+F)"));
        everywhere.setOnAction(e -> globalSearch(treeSearch.getText()));
        HBox searchRow = new HBox(4, treeSearch, everywhere);
        HBox.setHgrow(treeSearch, Priority.ALWAYS);
        searchRow.setPadding(new Insets(4));
        VBox left = new VBox(new ToolBar(add, imp, exp), searchRow, tree);
        Buttons.decorateAll(left);
        VBox.setVgrow(tree, Priority.ALWAYS);

        mainTab.setContent(detail);
        mainTab.setClosable(false);
        tabs.getTabs().add(mainTab);
        tabs.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.ALL_TABS);
        SplitPane split = new SplitPane(left, tabs);
        split.setDividerPositions(ru.rasconsole.config.UiState.getDouble("split", 0.28));
        split.getDividers().get(0).positionProperty().addListener((o, was, now) ->
            ru.rasconsole.config.UiState.put("split", String.format(java.util.Locale.ROOT, "%.3f", now.doubleValue())));
        SplitPane.setResizableWithParent(left, false);
        root.setTop(menuBar());
        root.setCenter(split);
        status.setPadding(new Insets(3, 8, 3, 8));
        root.setBottom(status);

        Fx.setStatusSink(status::setText);
        root.sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                scene.windowProperty().addListener((o2, w0, w) -> Dialogs.setOwner(w));
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::refreshSelected);
                // закладки: Ctrl+1…9 — перейти, Ctrl+Shift+1…9 — поставить; Ctrl+E — недавние
                for (int i = 1; i <= 9; i++) {
                    int n = i;
                    KeyCode k = KeyCode.valueOf("DIGIT" + i);
                    scene.getAccelerators().put(new KeyCodeCombination(k, javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                        () -> goBookmark(n));
                    scene.getAccelerators().put(new KeyCodeCombination(k, javafx.scene.input.KeyCombination.SHORTCUT_DOWN,
                        javafx.scene.input.KeyCombination.SHIFT_DOWN), () -> setBookmark(n));
                }
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.E, javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                    this::showRecentPopup);
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.T, javafx.scene.input.KeyCombination.SHORTCUT_DOWN), () -> {
                    if (tree.getSelectionModel().getSelectedItem() instanceof NodeTreeItem ti) openInTab(ti);
                });
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.W, javafx.scene.input.KeyCombination.SHORTCUT_DOWN), () -> {
                    javafx.scene.control.Tab t = tabs.getSelectionModel().getSelectedItem();
                    if (t != null && t != mainTab) tabs.getTabs().remove(t);
                });
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                    this::focusSearch);
                scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, javafx.scene.input.KeyCombination.SHORTCUT_DOWN,
                    javafx.scene.input.KeyCombination.SHIFT_DOWN), () -> globalSearch(treeSearch.getText()));
            }
        });
        detail.setCenter(hint());
        tree.getSelectionModel().select(rootItem);
        restoreTreeState();
        checkAllServers();
    }

    /** При запуске проверить связь со всеми серверами в фоне: пока идёт проверка, они показаны «проверка связи…». */
    private void checkAllServers() {
        for (ServerNode s : servers) {
            s.conn().resetRetry();
            s.conn().submit(ru.rasconsole.ras.AuthKind.NONE, null, c -> c.getClusters());
        }
    }

    private final javafx.scene.control.TextField treeSearch = new javafx.scene.control.TextField();
    private final javafx.scene.control.TabPane tabs = new javafx.scene.control.TabPane();
    private final javafx.scene.control.Tab mainTab = new javafx.scene.control.Tab();
    private ru.rasconsole.config.Favorites favorites;
    private FavoritesNode favoritesNode;

    /** Содержимое активной вкладки: основная (по выбору в дереве) или открытая отдельно. */
    private Node currentView() {
        javafx.scene.control.Tab t = tabs.getSelectionModel().getSelectedItem();
        return t == null || t == mainTab ? detail.getCenter() : t.getContent();
    }

    /** Открыть узел в отдельной вкладке: она не меняется при выборе других узлов дерева. */
    private void openInTab(NodeTreeItem item) {
        ConsoleNode node = item.getValue();
        if (node == null) return;
        Node view = node instanceof RootNode ? new Dashboard(monitor, this::openCluster).node() : node.createView();
        if (view == null) {
            Fx.status("У узла «" + node.title() + "» нет содержимого для вкладки");
            return;
        }
        String server = null;
        for (TreeItem<ConsoleNode> p = item.getParent(); p != null; p = p.getParent()) {
            if (p.getValue() instanceof ServerNode sn) server = sn.conn().config().name();
        }
        // у разделов («Сеансы», «Блокировки») добавляем, к какой базе или кластеру они относятся
        String owner = item.getParent() != null && item.getParent().getValue() != null
            && !(item.getParent().getValue() instanceof ServerNode) && !(item.getParent().getValue() instanceof RootNode)
            && !(item.getParent().getValue() instanceof GroupNode) && !(item.getParent().getValue() instanceof FavoritesNode)
            ? item.getParent().getValue().title() : null;
        String title = node.title() + (owner != null && node.isLeaf() ? " — " + shorten(owner) : "");
        Buttons.decorateAll(view);
        javafx.scene.control.Tab t = new javafx.scene.control.Tab(shorten(title), view);
        t.setTooltip(new javafx.scene.control.Tooltip(title + (server != null ? "\nСервер: " + server : "")));
        t.setGraphic(node.icon());
        t.setOnClosed(e -> {
            if (view.getUserData() instanceof ConsoleNode.Disposable d) d.dispose();
        });
        tabs.getTabs().add(t);
        tabs.getSelectionModel().select(t);
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    // ---- поиск и состояние дерева ----

    /** Ctrl+F: поле поиска правой панели, если оно есть, иначе поиск по дереву. */
    private void focusSearch() {
        Node c = currentView();
        if (c != null && c.getUserData() instanceof ConsoleNode.Searchable s) s.focusFilter();
        else treeSearch.requestFocus();
    }

    /** Найти следующий загруженный узел дерева, заголовок которого содержит текст. */
    private void findInTree(String text) {
        if (text == null || text.isBlank()) return;
        String q = text.trim().toLowerCase(java.util.Locale.ROOT);
        List<TreeItem<ConsoleNode>> all = new ArrayList<>();
        collect(rootItem, all);
        int start = all.indexOf(tree.getSelectionModel().getSelectedItem());
        for (int i = 1; i <= all.size(); i++) {
            TreeItem<ConsoleNode> it = all.get((start + i) % all.size());
            if (it.getValue() != null && it.getValue().title().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                for (TreeItem<ConsoleNode> p = it.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
                tree.getSelectionModel().select(it);
                tree.scrollTo(Math.max(0, tree.getRow(it) - 3));
                Fx.status("Найдено: " + it.getValue().title());
                return;
            }
        }
        Fx.status("«" + text + "» не найдено среди загруженных узлов. Поиск по всем серверам — кнопка «Везде…» (Ctrl+Shift+F)");
    }

    private static void collect(TreeItem<ConsoleNode> it, List<TreeItem<ConsoleNode>> out) {
        out.add(it);
        if (it instanceof NodeTreeItem n && !n.isLoaded() && it.getParent() != null) return;
        for (TreeItem<ConsoleNode> c : it.getChildren()) {
            if (c instanceof NodeTreeItem) collect(c, out);
        }
    }

    private void globalSearch(String text) {
        new GlobalSearch(() -> servers.stream().map(ServerNode::conn).toList(), this::openClusterChild).open(text);
    }

    /** Путь узла из устойчивых ключей (для запоминания раскрытых узлов). */
    private static String pathOf(TreeItem<ConsoleNode> it) {
        List<String> keys = new ArrayList<>();
        for (TreeItem<ConsoleNode> p = it; p != null; p = p.getParent()) {
            if (p.getValue() != null) keys.add(0, p.getValue().stateKey());
        }
        return String.join("\t", keys);
    }

    /** Запомнить раскрытые и выбранный узлы (при выходе). */
    private void saveTreeState() {
        List<String> expanded = new ArrayList<>();
        List<TreeItem<ConsoleNode>> all = new ArrayList<>();
        collect(rootItem, all);
        for (TreeItem<ConsoleNode> it : all) {
            if (it != rootItem && it.isExpanded() && !it.isLeaf()) expanded.add(pathOf(it));
        }
        ru.rasconsole.config.UiState.putList("tree.expanded", expanded);
        TreeItem<ConsoleNode> sel = tree.getSelectionModel().getSelectedItem();
        ru.rasconsole.config.UiState.put("tree.selected", sel == null ? null : pathOf(sel));
    }

    /** Раскрыть узлы, раскрытые при прошлом запуске, и выбрать прежний узел (узлы грузятся асинхронно). */
    private void restoreTreeState() {
        List<String> expanded = new ArrayList<>(ru.rasconsole.config.UiState.getList("tree.expanded"));
        expanded.sort(java.util.Comparator.comparingInt(String::length));
        for (String p : expanded) walk(rootItem, List.of(p.split("\t")), 1, it -> it.setExpanded(true));
        String sel = ru.rasconsole.config.UiState.get("tree.selected");
        if (sel != null && !sel.isEmpty()) {
            walk(rootItem, List.of(sel.split("\t")), 1, it -> {
                tree.getSelectionModel().select(it);
                tree.scrollTo(Math.max(0, tree.getRow(it) - 3));
            });
        }
    }

    /** Пройти по пути ключей, раскрывая промежуточные узлы и дожидаясь загрузки детей. */
    private void walk(NodeTreeItem from, List<String> keys, int i, Consumer<NodeTreeItem> then) {
        if (i >= keys.size()) {
            then.accept(from);
            return;
        }
        from.setExpanded(true);
        String k = keys.get(i);
        waitFor(() -> {
            for (TreeItem<ConsoleNode> c : from.getChildren()) {
                if (c instanceof NodeTreeItem n && n.getValue() != null && k.equals(n.getValue().stateKey())) return n;
            }
            return null;
        }, next -> walk(next, keys, i + 1, then), 60);
    }

    public Parent root() {
        return root;
    }

    public void shutdown() {
        saveTreeState();
        ru.rasconsole.config.UiState.save();
        monitor.shutdown();
        ru.rasconsole.ui.cluster.Scheduler.shutdown();
        for (ServerNode s : servers) s.conn().shutdown();
        credentials.flush();
    }

    private MenuBar menuBar() {
        Menu file = new Menu("Файл");
        file.getItems().addAll(
            ConsoleNode.item("Добавить сервер RAS…", () -> addServer("")),
            ConsoleNode.item("Импорт списка серверов…", this::importServers),
            ConsoleNode.item("Экспорт списка серверов…", this::exportServers),
            new SeparatorMenuItem(),
            ConsoleNode.item("Настройки…", () -> SettingsDialog.open(monitor)),
            ConsoleNode.item("Забыть сохранённые пароли…", this::forgetPasswords),
            new SeparatorMenuItem(),
            ConsoleNode.item("Выход", Platform::exit));
        Menu action = new Menu("Действия");
        MenuItem refresh = ConsoleNode.item("Обновить", this::refreshSelected);
        refresh.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        MenuItem newTab = ConsoleNode.item("Открыть в новой вкладке", () -> {
            if (tree.getSelectionModel().getSelectedItem() instanceof NodeTreeItem ti) openInTab(ti);
        });
        newTab.setAccelerator(new KeyCodeCombination(KeyCode.T, javafx.scene.input.KeyCombination.SHORTCUT_DOWN));
        action.getItems().addAll(refresh, newTab, new SeparatorMenuItem(),
            ConsoleNode.item("Отчёт по серверам (Excel/HTML)…", this::report),
            ConsoleNode.item("Диагностика сети…", this::netDiag),
            ConsoleNode.item("Запланированные работы…", ru.rasconsole.ui.cluster.Scheduler::showTasks),
            ConsoleNode.item("Журнал входов в наблюдаемые базы…", () ->
                ru.rasconsole.ui.cluster.ClusterSections.watchLog("Журнал входов и выходов — все наблюдаемые базы", e -> true)),
            new SeparatorMenuItem(),
            ConsoleNode.item("Журнал действий администратора…", this::showAudit));
        Menu help = new Menu("Справка");
        help.getItems().add(ConsoleNode.item("О программе", () -> Dialogs.info(
            "Консоль администрирования RAS, версия " + ru.rasconsole.Version.VERSION,
            "Консоль администрирования кластеров 1С:Предприятия через сервер администрирования (ras).\n"
                + "Используется программный Java-интерфейс 1С (com._1c.v8.ibis.admin).\n\n"
                + "Список серверов: " + store.file() + "\n"
                + "Библиотека 1С (Java API сервера администрирования): " + ru.rasconsole.Boot.apiLocation())));
        rebuildGoMenu();
        Buttons.decorateMenu(List.of(file, action, help));
        return new MenuBar(file, action, goMenu, help);
    }

    /** Диагностика сети: выбранный в дереве сервер (или его потомок), иначе спросить адрес. */
    private void netDiag() {
        for (TreeItem<ConsoleNode> p = tree.getSelectionModel().getSelectedItem(); p != null; p = p.getParent()) {
            if (p.getValue() instanceof ServerNode sn) {
                NetDiag.open(sn.conn().config());
                return;
            }
        }
        Dialogs.text("Диагностика сети", "Компьютер и порт RAS (например, srv-1c:1545):", "").ifPresent(s -> {
            String t = s.trim();
            if (t.isEmpty()) return;
            String host = t;
            int port = ServerConfig.DEFAULT_PORT;
            int colon = t.lastIndexOf(':');
            if (colon > 0 && !t.endsWith("]")) {
                host = t.substring(0, colon);
                try {
                    port = Integer.parseInt(t.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    Dialogs.warn("Порт — число, например srv-1c:1545");
                    return;
                }
            }
            if (!ServerConfig.validHost(host) || !ServerConfig.validPort(port)) {
                Dialogs.warn("Недопустимый адрес: " + t);
                return;
            }
            NetDiag.open(new ServerConfig(host, host, port, ServerConfig.DEFAULT_TIMEOUT_SEC, ""));
        });
    }

    /** Отчёт по всем серверам: файл .xlsx или .html. */
    private void report() {
        if (servers.isEmpty()) {
            Dialogs.info("Отчёт по серверам", "Список серверов пуст.");
            return;
        }
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Отчёт по серверам");
        fc.getExtensionFilters().addAll(
            new javafx.stage.FileChooser.ExtensionFilter("Книга Excel (*.xlsx)", "*.xlsx"),
            new javafx.stage.FileChooser.ExtensionFilter("Страница HTML (*.html)", "*.html"));
        fc.setInitialFileName("Отчёт по серверам " + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date()) + ".xlsx");
        java.io.File f = fc.showSaveDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (f == null) return;
        java.io.File file = !f.getName().contains(".") ? new java.io.File(f.getPath() + ".xlsx") : f;
        Fx.status("Отчёт по серверам: опрос " + servers.size() + " серверов…");
        ru.rasconsole.ras.ServerReport.collect(servers.stream().map(ServerNode::conn).toList()).whenComplete((r, e) -> {
            String err = null;
            if (e == null) {
                try {
                    r.write(file.toPath());
                } catch (java.io.IOException ex) {
                    err = ex.getMessage();
                }
            } else {
                err = Fmt.rootMessage(Fx.unwrap(e));
            }
            String error = err;
            Platform.runLater(() -> {
                if (error != null) {
                    Dialogs.warn("Не удалось сформировать отчёт: " + error);
                    return;
                }
                Fx.status("Отчёт сохранён: " + file);
                if (Dialogs.confirm("Отчёт по серверам", "Отчёт сохранён:\n" + file + "\n\nОткрыть его?")) {
                    try {
                        java.awt.Desktop.getDesktop().open(file);
                    } catch (Exception ex) {
                        Dialogs.warn("Не удалось открыть файл: " + ex.getMessage());
                    }
                }
            });
        });
    }

    // ---- закладки и недавние ----

    private static final int RECENT_MAX = 15;
    private static final String SEP = "\u0001";
    private final Menu goMenu = new Menu("Переход");

    /** Подпись узла для меню: «Сеансы — buh · Прод» (без счётчиков в скобках). */
    private String labelOf(TreeItem<ConsoleNode> it) {
        String own = clean(it.getValue().title());
        String parent = null;
        String server = null;
        for (TreeItem<ConsoleNode> p = it.getParent(); p != null; p = p.getParent()) {
            ConsoleNode v = p.getValue();
            if (v instanceof ServerNode sn) {
                server = sn.conn().config().name();
                break;
            }
            if (parent == null && !(v instanceof RootNode) && !(v instanceof GroupNode) && !(v instanceof FavoritesNode)) {
                parent = clean(v.title());
            }
        }
        if (it.getValue() instanceof ServerNode sn) return sn.conn().config().display();
        return own + (parent != null ? " — " + parent : "") + (server != null ? " · " + server : "");
    }

    /** Без «(12)» в конце и без адреса кластера в скобках. */
    private static String clean(String t) {
        String s = t.replaceAll("\\s*\\(\\d+\\)$", "");
        if (s.startsWith("Кластер «") && s.contains("» (")) s = s.substring(0, s.indexOf("» (") + 1);
        return s;
    }

    /** Узлы, которые не стоит запоминать: корень, группы, раздел избранного. */
    private static boolean worthRemembering(TreeItem<ConsoleNode> it) {
        return it != null && it.getValue() != null && !(it.getValue() instanceof RootNode)
            && !(it.getValue() instanceof GroupNode) && !(it.getValue() instanceof FavoritesNode);
    }

    private void rememberRecent(TreeItem<ConsoleNode> it) {
        if (!worthRemembering(it)) return;
        String path = pathOf(it);
        List<String> list = new ArrayList<>(ru.rasconsole.config.UiState.getList("recent"));
        list.removeIf(e -> e.startsWith(path + SEP));
        list.add(0, path + SEP + labelOf(it));
        while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
        ru.rasconsole.config.UiState.putList("recent", list);
        rebuildGoMenu();
    }

    private void setBookmark(int n) {
        TreeItem<ConsoleNode> it = tree.getSelectionModel().getSelectedItem();
        if (!worthRemembering(it)) {
            Fx.status("Выберите в дереве сервер, базу или раздел — на него и встанет закладка " + n);
            return;
        }
        String label = labelOf(it);
        ru.rasconsole.config.UiState.put("bookmark." + n, pathOf(it) + SEP + label);
        rebuildGoMenu();
        Fx.status("Закладка " + n + ": " + label + " (переход — Ctrl+" + n + ")");
    }

    private void goBookmark(int n) {
        String v = ru.rasconsole.config.UiState.get("bookmark." + n);
        if (v == null || v.isEmpty()) {
            Fx.status("Закладка " + n + " не задана. Поставить на выбранный узел — Ctrl+Shift+" + n);
            return;
        }
        goTo(v);
    }

    /** Перейти к узлу по сохранённому пути (узлы по дороге раскрываются и загружаются). */
    private void goTo(String entry) {
        int i = entry.indexOf(SEP);
        String path = i < 0 ? entry : entry.substring(0, i);
        String label = i < 0 ? "" : entry.substring(i + SEP.length());
        Fx.status("Переход: " + label + "…");
        walk(rootItem, List.of(path.split("\t")), 1, it -> {
            tree.getSelectionModel().select(it);
            tabs.getSelectionModel().select(mainTab);
            tree.scrollTo(Math.max(0, tree.getRow(it) - 3));
            Fx.status("Готово: " + label);
        });
    }

    /** Меню «Переход»: недавние, закладки, постановка и удаление закладок. */
    private void rebuildGoMenu() {
        List<MenuItem> items = new ArrayList<>();
        Menu recent = new Menu("Недавние");
        for (String e : ru.rasconsole.config.UiState.getList("recent")) {
            int i = e.indexOf(SEP);
            if (i < 0) continue;
            MenuItem mi = new MenuItem(e.substring(i + SEP.length()));
            mi.setMnemonicParsing(false);
            mi.setOnAction(x -> goTo(e));
            recent.getItems().add(mi);
        }
        if (recent.getItems().isEmpty()) {
            MenuItem none = new MenuItem("(пусто)");
            none.setDisable(true);
            recent.getItems().add(none);
        } else {
            recent.getItems().addAll(new SeparatorMenuItem(), ConsoleNode.item("Очистить список недавних", () -> {
                ru.rasconsole.config.UiState.putList("recent", List.of());
                rebuildGoMenu();
            }));
        }
        MenuItem recentPopup = ConsoleNode.item("Недавние (окно)…", this::showRecentPopup);
        recentPopup.setAccelerator(new KeyCodeCombination(KeyCode.E, javafx.scene.input.KeyCombination.SHORTCUT_DOWN));
        items.add(recent);
        items.add(recentPopup);
        items.add(new SeparatorMenuItem());
        Menu set = new Menu("Поставить закладку на выбранный узел");
        Menu del = new Menu("Удалить закладку");
        boolean any = false;
        for (int n = 1; n <= 9; n++) {
            int k = n;
            String v = ru.rasconsole.config.UiState.get("bookmark." + n);
            String label = v == null || v.indexOf(SEP) < 0 ? null : v.substring(v.indexOf(SEP) + SEP.length());
            KeyCode code = KeyCode.valueOf("DIGIT" + n);
            if (label != null) {
                any = true;
                MenuItem go = new MenuItem(n + ". " + label);
                go.setMnemonicParsing(false);
                go.setAccelerator(new KeyCodeCombination(code, javafx.scene.input.KeyCombination.SHORTCUT_DOWN));
                go.setOnAction(x -> goBookmark(k));
                items.add(go);
                MenuItem d = new MenuItem(n + ". " + label);
                d.setMnemonicParsing(false);
                d.setOnAction(x -> {
                    ru.rasconsole.config.UiState.put("bookmark." + k, null);
                    rebuildGoMenu();
                });
                del.getItems().add(d);
            }
            MenuItem s = new MenuItem(n + (label != null ? " (сейчас: " + label + ")" : ""));
            s.setMnemonicParsing(false);
            s.setAccelerator(new KeyCodeCombination(code, javafx.scene.input.KeyCombination.SHORTCUT_DOWN,
                javafx.scene.input.KeyCombination.SHIFT_DOWN));
            s.setOnAction(x -> setBookmark(k));
            set.getItems().add(s);
        }
        if (!any) {
            MenuItem hint = new MenuItem("Закладок нет: выберите узел и нажмите Ctrl+Shift+1…9");
            hint.setDisable(true);
            items.add(hint);
        }
        items.add(new SeparatorMenuItem());
        items.add(set);
        if (any) items.add(del);
        goMenu.getItems().setAll(items);
    }

    /** Ctrl+E: список недавних узлов рядом с деревом. */
    private void showRecentPopup() {
        ContextMenu cm = new ContextMenu();
        for (String e : ru.rasconsole.config.UiState.getList("recent")) {
            int i = e.indexOf(SEP);
            if (i < 0) continue;
            MenuItem mi = new MenuItem(e.substring(i + SEP.length()));
            mi.setMnemonicParsing(false);
            mi.setOnAction(x -> goTo(e));
            cm.getItems().add(mi);
        }
        if (cm.getItems().isEmpty()) {
            Fx.status("Недавних узлов пока нет");
            return;
        }
        cm.show(tree, javafx.geometry.Side.TOP, 20, 40);
        Platform.runLater(() -> {
            if (cm.getSkin() != null && cm.getSkin().getNode() != null) cm.getSkin().getNode().requestFocus();
        });
    }

    private Node hint() {
        Label l = new Label("Добавьте сервер администрирования: Файл → Добавить сервер RAS…\n"
            + "или контекстное меню узла «Серверы RAS».\n\n"
            + "На сервере 1С должна работать служба ras, например:\n"
            + "  ras cluster --service --port=1545 localhost:1540");
        l.setPadding(new Insets(20));
        StackPane p = new StackPane(l);
        StackPane.setAlignment(l, javafx.geometry.Pos.TOP_LEFT);
        return p;
    }

    private void showDetail(TreeItem<ConsoleNode> item) {
        Node old = detail.getCenter();
        if (old != null && old.getUserData() instanceof ConsoleNode.Disposable d) d.dispose();
        Node view = null;
        if (item != null && item.getValue() != null) {
            if (item.getValue() instanceof RootNode) {
                view = servers.isEmpty() ? hint() : new Dashboard(monitor, this::openCluster).node();
            } else {
                view = item.getValue().createView();
            }
        }
        Buttons.decorateAll(view);
        detail.setCenter(view != null ? view : new StackPane(new Label("")));
        mainTab.setText(item != null && item.getValue() != null ? shorten(item.getValue().title()) : "");
        mainTab.setGraphic(item != null && item.getValue() != null ? item.getValue().icon() : null);
    }

    /** Щелчок по уведомлению в трее: показать окно и сводку. */
    private void bringToFront() {
        if (root.getScene() != null && root.getScene().getWindow() instanceof javafx.stage.Stage st) {
            st.setIconified(false);
            st.toFront();
            tree.getSelectionModel().select(rootItem);
        }
    }

    /** Открыть в дереве кластер из сводной панели. */
    private void openCluster(Monitor.Stats s) {
        openClusterChild(s.server().address(), s.clusterId(), null, null, null);
    }

    /**
     * Открыть в дереве: сервер → кластер → раздел (по заголовку) → объект (по ключу),
     * затем при необходимости задать поиск в таблице раздела.
     */
    private void openClusterChild(String address, java.util.UUID clusterId, String section, Object childKey, String filter) {
        ServerNode server = null;
        for (ServerNode n : servers) if (n.conn().config().address().equals(address)) server = n;
        if (server == null) return;
        if (!server.conn().config().group().isEmpty()) {
            for (TreeItem<ConsoleNode> c : rootItem.getChildren()) {
                if (c.getValue() instanceof GroupNode g && g.name.equals(server.conn().config().group())) c.setExpanded(true);
            }
        }
        ServerNode target = server;
        // узлы грузятся асинхронно: ждём появления сервера, затем кластера, раздела и объекта
        waitFor(() -> target.treeItem(), item -> {
            item.setExpanded(true);
            waitFor(() -> child(item, n -> clusterId.equals(n.getValue().key())), cl -> {
                cl.setExpanded(true);
                if (section == null) {
                    select(cl, null);
                    return;
                }
                waitFor(() -> child(cl, n -> section.equals(n.getValue().title())
                    || section.equals(String.valueOf(n.getValue().key()))), sec -> {
                    if (childKey == null) {
                        select(sec, filter);
                        return;
                    }
                    sec.setExpanded(true);
                    waitFor(() -> child(sec, n -> childKey.equals(n.getValue().key())), obj -> select(obj, filter), 40);
                }, 40);
            }, 40);
        }, 40);
    }

    private static NodeTreeItem child(TreeItem<ConsoleNode> parent, java.util.function.Predicate<NodeTreeItem> p) {
        for (TreeItem<ConsoleNode> c : parent.getChildren()) {
            if (c instanceof NodeTreeItem n && n.getValue() != null && p.test(n)) return n;
        }
        return null;
    }

    private void select(NodeTreeItem it, String filter) {
        tree.getSelectionModel().select(it);
        tabs.getSelectionModel().select(mainTab);
        tree.scrollTo(Math.max(0, tree.getRow(it) - 3));
        if (filter != null && detail.getCenter() != null && detail.getCenter().getUserData() instanceof ConsoleNode.Searchable s) {
            s.setFilter(filter);
        }
    }

    private static void waitFor(java.util.function.Supplier<NodeTreeItem> find, Consumer<NodeTreeItem> then, int tries) {
        NodeTreeItem it = find.get();
        if (it != null) {
            then.accept(it);
        } else if (tries > 0) {
            javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
            p.setOnFinished(e -> waitFor(find, then, tries - 1));
            p.play();
        }
    }

    private void refreshSelected() {
        TreeItem<ConsoleNode> item = tree.getSelectionModel().getSelectedItem();
        if (item instanceof NodeTreeItem n) {
            if (n.isLoaded()) n.reload();
            showDetail(n);
        }
    }

    // ---- список серверов ----

    private ServerNode newServerNode(ServerConfig cfg) {
        RasConnection conn = connections.apply(cfg);
        credentials.applyTo(conn);
        ServerNode node = new ServerNode(conn, this);
        conn.addListener(c -> Platform.runLater(() -> {
            if (node.treeItem() != null) {
                node.treeItem().refreshTitle();
                // группа тоже подсвечивается, если в ней есть недоступный сервер
                if (node.treeItem().getParent() instanceof NodeTreeItem p && p.getValue() instanceof GroupNode) p.refreshTitle();
            }
        }));
        return node;
    }

    private java.util.Set<String> groups() {
        java.util.Set<String> g = new java.util.TreeSet<>();
        for (ServerNode s : servers) if (!s.conn().config().group().isEmpty()) g.add(s.conn().config().group());
        return g;
    }

    private void addServer(String group) {
        ServerConfig preset = new ServerConfig("", "localhost", ServerConfig.DEFAULT_PORT, ServerConfig.DEFAULT_TIMEOUT_SEC,
            "", group, false);
        Dialogs.server(preset, true, groups()).ifPresent(cfg -> {
            ServerNode node = newServerNode(cfg);
            servers.add(node);
            save();
            rootItem.reload();
            Platform.runLater(() -> {
                // раскрыть группу нового сервера и выделить его
                for (TreeItem<ConsoleNode> c : rootItem.getChildren()) {
                    if (c.getValue() instanceof GroupNode gn && gn.name.equals(cfg.group())) c.setExpanded(true);
                }
                Platform.runLater(() -> {
                    if (node.treeItem() != null) {
                        node.treeItem().setExpanded(true);
                        tree.getSelectionModel().select(node.treeItem());
                    }
                });
            });
        });
    }

    private void showAudit() {
        List<String[]> rows = ru.rasconsole.config.AuditLog.tail(2000);
        List<ru.rasconsole.ui.table.Col<String[]>> cols = List.of(
            ru.rasconsole.ui.table.Col.<String[]>of("Время", 140, r -> r[0]),
            ru.rasconsole.ui.table.Col.<String[]>of("Пользователь", 160, r -> r[1]),
            ru.rasconsole.ui.table.Col.<String[]>of("Сервер", 180, r -> r[2]),
            ru.rasconsole.ui.table.Col.<String[]>of("Объект", 160, r -> r[3]),
            ru.rasconsole.ui.table.Col.<String[]>of("Действие", 280, r -> r[4]),
            ru.rasconsole.ui.table.Col.<String[]>of("Результат", 200, r -> r[5]));
        Dialogs.table("Журнал действий администратора — " + ru.rasconsole.config.AuditLog.file(), rows, cols);
    }

    @Override
    public void editServer(ServerNode node) {
        ServerConfig old = node.conn().config();
        Dialogs.server(old, false, groups()).ifPresent(cfg -> {
            boolean sameConnection = old.address().equals(cfg.address()) && old.timeoutSec() == cfg.timeoutSec();
            if (sameConnection) {
                // имя, группа, пометка «продуктивный» — без переподключения
                node.conn().updateConfig(cfg);
                save();
                if (!old.group().equals(cfg.group())) rootItem.reload();
                else if (node.treeItem() != null) node.treeItem().refreshTitle();
                return;
            }
            node.conn().reconfigure(cfg).whenComplete((v, e) -> Platform.runLater(() -> {
                credentials.renameServer(old.address(), cfg.address());
                save();
                if (!old.group().equals(cfg.group())) {
                    rootItem.reload();
                } else if (node.treeItem() != null) {
                    node.treeItem().collapseAndReset();
                    node.treeItem().refreshTitle();
                }
            }));
        });
    }

    @Override
    public void removeServer(ServerNode node) {
        if (!Dialogs.confirm("Удаление сервера", "Удалить «" + node.conn().config().address() + "» из списка?")) return;
        servers.remove(node);
        node.conn().shutdown();
        String address = node.conn().config().address();
        if (servers.stream().noneMatch(s -> s.conn().config().address().equalsIgnoreCase(address))) {
            credentials.removeServer(address);
        }
        save();
        rootItem.reload();
    }

    private void forgetPasswords() {
        int n = credentials.size();
        if (n == 0) {
            Dialogs.info("Сохранённые пароли", "Сохранённых паролей нет.");
            return;
        }
        if (Dialogs.confirm("Сохранённые пароли", "Удалить сохранённые пароли (" + n + ")?\n"
            + "Пароли, уже введённые в этом сеансе работы, останутся в памяти до закрытия программы.")) {
            credentials.clear();
            Fx.status("Сохранённые пароли удалены");
        }
    }

    // ---- импорт / экспорт ----

    private static String key(ServerConfig s) {
        return s.address().toLowerCase(java.util.Locale.ROOT);
    }

    private void exportServers() {
        List<ServerConfig> all = new ArrayList<>();
        for (ServerNode s : servers) all.add(s.conn().config());
        if (all.isEmpty()) {
            Dialogs.info("Экспорт", "Список серверов пуст.");
            return;
        }
        Dialogs.chooseServers("Экспорт списка серверов",
            "Отметьте серверы для экспорта. Пароли в файл не попадают.", all, s -> true, null)
            .ifPresent(selected -> {
                java.io.File f = Dialogs.chooseServerFile(true);
                if (f == null) return;
                try {
                    ServerStore.write(f.toPath(), selected);
                    Fx.status("Экспортировано серверов: " + selected.size() + " → " + f);
                    Dialogs.info("Экспорт", "Экспортировано серверов: " + selected.size() + "\n" + f);
                } catch (java.io.IOException ex) {
                    Dialogs.error("Экспорт списка серверов", ex);
                }
            });
    }

    private void importServers() {
        java.io.File f = Dialogs.chooseServerFile(false);
        if (f == null) return;
        List<ServerConfig> found;
        try {
            found = ServerStore.read(f.toPath());
        } catch (java.io.IOException ex) {
            Dialogs.error("Импорт списка серверов", ex);
            return;
        }
        if (found.isEmpty()) {
            Dialogs.warn("В файле нет серверов:\n" + f);
            return;
        }
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (ServerNode s : servers) existing.add(key(s.conn().config()));
        Dialogs.chooseServers("Импорт списка серверов",
            "Отметьте серверы для добавления. Серверы, которые уже есть в списке, будут пропущены.",
            found, s -> !existing.contains(key(s)), s -> existing.contains(key(s)) ? "уже в списке" : null)
            .ifPresent(selected -> {
                int added = 0;
                int skipped = 0;
                for (ServerConfig cfg : selected) {
                    if (!existing.add(key(cfg))) {
                        skipped++;
                        continue;
                    }
                    servers.add(newServerNode(cfg));
                    added++;
                }
                save();
                rootItem.reload();
                String msg = "Добавлено серверов: " + added + (skipped > 0 ? ", пропущено (уже в списке): " + skipped : "");
                Fx.status(msg);
                Dialogs.info("Импорт", msg);
            });
    }

    private void save() {
        List<ServerConfig> list = new ArrayList<>();
        for (ServerNode s : servers) list.add(s.conn().config());
        store.save(list);
    }

    /** Корневой узел «Серверы RAS». */
    private final class RootNode extends ConsoleNode {
        @Override
        public String title() {
            return "Серверы RAS";
        }

        @Override
        public Node icon() {
            return Icons.get(Icons.SERVERS);
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public void loadChildren(Consumer<List<ConsoleNode>> done) {
            // сначала группы (по алфавиту), затем серверы без группы
            List<ConsoleNode> nodes = new ArrayList<>();
            if (favoritesNode == null) {
                favoritesNode = new FavoritesNode();
                favorites.addListener(() -> Platform.runLater(() -> favoritesNode.reloadTree()));
            }
            nodes.add(favoritesNode);
            for (String g : groups()) nodes.add(new GroupNode(g));
            for (ServerNode s : servers) if (s.conn().config().group().isEmpty()) nodes.add(s);
            done.accept(nodes);
        }

        @Override
        public List<MenuItem> menu(NodeTreeItem item) {
            return List.of(item("Добавить сервер RAS…", () -> addServer("")));
        }
    }

    /** «Избранные базы»: базы с разных серверов, отмеченные пользователем. */
    private final class FavoritesNode extends ConsoleNode {
        @Override
        public String title() {
            return "Избранные базы";
        }

        @Override
        public Object key() {
            return "favorites";
        }

        @Override
        public Node icon() {
            return Icons.get(Icons.FAVORITES);
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public void loadChildren(Consumer<List<ConsoleNode>> done) {
            List<ConsoleNode> nodes = new ArrayList<>();
            for (ru.rasconsole.config.Favorites.Entry e : favorites.all()) {
                ServerNode server = null;
                for (ServerNode s : servers) if (s.conn().config().address().equals(e.address())) server = s;
                nodes.add(server != null ? ru.rasconsole.ui.cluster.ClusterSections.favoriteNode(server.conn(), e) : new MissingFavorite(e));
            }
            done.accept(nodes);
        }

        @Override
        public Node createView() {
            Label l = new Label("Избранные информационные базы со всех серверов.\n\n"
                + "Добавить базу: контекстное меню базы → «В избранное» (или кнопка «В избранное» в списке баз).\n"
                + "Убрать: то же меню → «Убрать из избранного». Порядок — «Выше в избранном» / «Ниже в избранном».\n\n"
                + "Баз в избранном: " + favorites.all().size());
            l.setPadding(new Insets(20));
            StackPane p = new StackPane(l);
            StackPane.setAlignment(l, javafx.geometry.Pos.TOP_LEFT);
            return p;
        }
    }

    /** Избранная база, сервер которой удалён из списка. */
    private final class MissingFavorite extends ConsoleNode {
        private final ru.rasconsole.config.Favorites.Entry e;

        MissingFavorite(ru.rasconsole.config.Favorites.Entry e) {
            this.e = e;
        }

        @Override
        public String title() {
            return e.ibName() + "  (сервер " + e.address() + " не в списке)";
        }

        @Override
        public Object key() {
            return e.ibId();
        }

        @Override
        public boolean isUnavailable() {
            return true;
        }

        @Override
        public Node icon() {
            return Icons.get(Icons.INFOBASE);
        }

        @Override
        public List<MenuItem> menu(NodeTreeItem item) {
            return List.of(item("Убрать из избранного", () -> favorites.remove(e.address(), e.clusterId(), e.ibId())));
        }
    }

    /** Группа серверов в дереве. */
    private final class GroupNode extends ConsoleNode {
        final String name;

        GroupNode(String name) {
            this.name = name;
        }

        @Override
        public String title() {
            return name;
        }

        @Override
        public Object key() {
            return "group:" + name;
        }

        @Override
        public Node icon() {
            return Icons.get(Icons.FOLDER);
        }

        @Override
        public boolean isProd() {
            return servers.stream().anyMatch(s -> s.conn().config().group().equals(name) && s.conn().config().prod());
        }

        @Override
        public boolean isUnavailable() {
            return servers.stream().anyMatch(s -> s.conn().config().group().equals(name) && s.conn().isUnavailable());
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public void loadChildren(Consumer<List<ConsoleNode>> done) {
            List<ConsoleNode> nodes = new ArrayList<>();
            for (ServerNode s : servers) if (s.conn().config().group().equals(name)) nodes.add(s);
            done.accept(nodes);
        }

        @Override
        public List<MenuItem> menu(NodeTreeItem item) {
            return List.of(item("Добавить сервер в группу…", () -> addServer(name)));
        }
    }
}
