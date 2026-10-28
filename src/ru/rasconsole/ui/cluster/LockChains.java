package ru.rasconsole.ui.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com._1c.v8.ibis.admin.ISessionInfo;

import ru.rasconsole.ras.RasFn;
import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.Icons;
import ru.rasconsole.ui.tree.ConsoleNode;

/**
 * «Ожидания на блокировках»: дерево «кто кого держит».
 * <p>
 * У сеанса поля «Заблокировано упр.» (blockedByLs) и «Заблокировано СУБД» (blockedByDbms) содержат
 * номер сеанса той же ИБ, который удерживает блокировку. Корни дерева — сеансы-виновники, которые
 * держат других, но сами ничего не ждут; ниже — ожидающие их сеансы.
 */
final class LockChains extends ConsoleNode {

    /** Ключ сеанса: номера сеансов уникальны только внутри ИБ. */
    static String key(UUID ib, int number) {
        return ib + "#" + number;
    }

    /** Номер сеанса, которого ждёт данный (0 — ничего не ждёт). */
    static int waitsFor(ISessionInfo s) {
        return s.getBlockedByLs() != 0 ? s.getBlockedByLs() : s.getBlockedByDbms();
    }

    /** Множество ключей сеансов, которые кого-то блокируют. */
    static Set<String> blockers(List<ISessionInfo> sessions) {
        Set<String> r = new HashSet<>();
        for (ISessionInfo s : sessions) {
            int b = waitsFor(s);
            if (b != 0) r.add(key(s.getInfoBaseId(), b));
        }
        return r;
    }

    private final ClusterCtx ctx;

    LockChains(ClusterCtx ctx) {
        this.ctx = ctx;
    }

    @Override
    public String title() {
        return "Ожидания на блокировках";
    }

    @Override
    public Node icon() {
        return Icons.get(Icons.LOCKS);
    }

    @Override
    public Node createView() {
        return new View().root;
    }

    /** Строка дерева: сеанс или служебная строка «нет данных». */
    private record Row(ISessionInfo s, String waitKind, int waiting) {
    }

    private final class View implements Disposable {
        final BorderPane root = new BorderPane();
        final TreeTableView<Row> tree = new TreeTableView<>();
        final Label summary = new Label();
        final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(5), e -> reload()));
        boolean loading;

        View() {
            tree.setShowRoot(false);
            tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            tree.setColumnResizePolicy(TreeTableView.UNCONSTRAINED_RESIZE_POLICY);
            col("Сеанс", 110, r -> r.s().getSessionId());
            col("Ждут его", 80, r -> r.waiting() == 0 ? null : r.waiting());
            col("Ожидание", 110, Row::waitKind);
            col("ИБ", 140, r -> ctx.ibName(r.s().getInfoBaseId()));
            col("Пользователь", 170, r -> r.s().getUserName());
            col("Приложение", 140, r -> Fmt.app(r.s().getAppId()));
            col("Компьютер", 130, r -> r.s().getHost());
            col("Вызов (тек.), мс", 110, r -> r.s().getDurationCurrent());
            col("Вызов СУБД (тек.), мс", 130, r -> r.s().getDurationCurrentDbms());
            col("Последняя активность", 140, r -> Fmt.date(r.s().getLastActiveAt()));
            col("Соединение с СУБД", 120, r -> r.s().getDbProcInfo());
            tree.setRowFactory(tv -> new TreeTableRow<>() {
                @Override
                protected void updateItem(Row item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("row-blocked", "row-blocker");
                    if (!empty && item != null) {
                        getStyleClass().add(getTreeItem() != null && getTreeItem().getParent() == tree.getRoot()
                            ? "row-blocker" : "row-blocked");
                    }
                }
            });
            tree.setPlaceholder(new Label("Ожиданий на блокировках нет"));

            Button refresh = new Button("Обновить");
            refresh.setOnAction(e -> reload());
            Button terminate = new Button("Завершить сеанс…");
            terminate.setOnAction(e -> terminateSelected());
            Button props = new Button("Свойства");
            props.setOnAction(e -> {
                TreeItem<Row> it = tree.getSelectionModel().getSelectedItem();
                if (it != null) Dialogs.editBean("Сеанс " + it.getValue().s().getSessionId(), it.getValue().s(),
                    ISessionInfo.class, null, false, null);
            });
            CheckBox auto = new CheckBox("Автообновление (5 с)");
            timer.setCycleCount(Timeline.INDEFINITE);
            auto.selectedProperty().addListener((o, was, now) -> {
                if (now) timer.play();
                else timer.stop();
            });
            Pane spacer = new Pane();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            ToolBar bar = new ToolBar(refresh, terminate, props, spacer, summary, auto);
            Label header = new Label("Ожидания на блокировках — кластер «" + ctx.name + "»");
            header.getStyleClass().add("view-title");
            header.setPadding(new Insets(6, 8, 2, 8));
            Label hint = new Label("Сверху — сеансы-виновники (держат блокировку, сами ничего не ждут), ниже — ожидающие их сеансы."
                + " Обычно достаточно завершить виновника.");
            hint.setPadding(new Insets(0, 8, 4, 8));
            hint.setWrapText(true);
            root.setTop(new VBox(header, hint, bar));
            root.setCenter(tree);
            root.setUserData(this);
            root.sceneProperty().addListener((o, was, now) -> {
                if (now == null) dispose();
            });
            reload();
        }

        void col(String title, double width, java.util.function.Function<Row, Object> f) {
            TreeTableColumn<Row, Object> c = new TreeTableColumn<>(title);
            c.setPrefWidth(width);
            c.setCellValueFactory(cd -> {
                Row r = cd.getValue().getValue();
                Object v;
                try {
                    v = r == null ? null : f.apply(r);
                } catch (RuntimeException e) {
                    v = null;
                }
                return new ReadOnlyObjectWrapper<>(v);
            });
            tree.getColumns().add(c);
        }

        void reload() {
            if (loading) return;
            loading = true;
            Fx.call(ctx.cluster(), "Ожидания на блокировках", c -> {
                List<ISessionInfo> list = c.getSessions(ctx.clusterId);
                for (ISessionInfo s : list) ctx.ensureInfoBase(c, s.getInfoBaseId());
                return list;
            }, list -> {
                loading = false;
                show(list);
            }, err -> {
                loading = false;
                timer.stop();
                tree.setRoot(null);
                tree.setPlaceholder(new Label(Fx.isAuthCancel(err) ? "Требуется авторизация. Нажмите «Обновить»."
                    : "Ошибка: " + Fmt.rootMessage(err)));
            });
        }

        void show(List<ISessionInfo> sessions) {
            Map<String, ISessionInfo> byKey = new HashMap<>();
            for (ISessionInfo s : sessions) byKey.put(key(s.getInfoBaseId(), s.getSessionId()), s);
            // кто кого ждёт: блокирующий -> список ожидающих
            Map<String, List<ISessionInfo>> waiters = new LinkedHashMap<>();
            int waitingTotal = 0;
            for (ISessionInfo s : sessions) {
                int b = waitsFor(s);
                if (b == 0) continue;
                waitingTotal++;
                waiters.computeIfAbsent(key(s.getInfoBaseId(), b), k -> new ArrayList<>()).add(s);
            }
            TreeItem<Row> top = new TreeItem<>();
            Set<String> placed = new HashSet<>();
            for (String blockerKey : waiters.keySet()) {
                ISessionInfo blocker = byKey.get(blockerKey);
                // виновник — тот, кто держит других и сам ничего не ждёт (или его нет в списке)
                if (blocker != null && waitsFor(blocker) != 0 && byKey.containsKey(
                    key(blocker.getInfoBaseId(), waitsFor(blocker)))) {
                    continue;
                }
                if (blocker == null) continue;
                top.getChildren().add(build(blocker, null, waiters, placed));
            }
            // циклические ожидания (взаимные блокировки): показываем их корнем первый непоказанный сеанс
            for (String blockerKey : waiters.keySet()) {
                ISessionInfo blocker = byKey.get(blockerKey);
                if (blocker != null && !placed.contains(blockerKey)) {
                    top.getChildren().add(build(blocker, "взаимная блокировка", waiters, placed));
                }
            }
            tree.setRoot(top);
            expandAll(top);
            summary.setText(waitingTotal == 0 ? "Ожиданий нет"
                : "Ждут: " + waitingTotal + ", виновников: " + top.getChildren().size());
        }

        TreeItem<Row> build(ISessionInfo s, String kindOverride, Map<String, List<ISessionInfo>> waiters, Set<String> placed) {
            String k = key(s.getInfoBaseId(), s.getSessionId());
            placed.add(k);
            String kind = kindOverride != null ? kindOverride
                : s.getBlockedByLs() != 0 ? "упр. блокировка" : s.getBlockedByDbms() != 0 ? "СУБД" : "держит";
            List<TreeItem<Row>> kids = new ArrayList<>();
            int total = 0;
            for (ISessionInfo w : waiters.getOrDefault(k, List.of())) {
                if (placed.contains(key(w.getInfoBaseId(), w.getSessionId()))) continue;
                TreeItem<Row> child = build(w, null, waiters, placed);
                total += 1 + child.getValue().waiting();
                kids.add(child);
            }
            TreeItem<Row> item = new TreeItem<>(new Row(s, kind, total));
            item.getChildren().addAll(kids);
            return item;
        }

        void expandAll(TreeItem<Row> it) {
            it.setExpanded(true);
            for (TreeItem<Row> c : it.getChildren()) expandAll(c);
        }

        void terminateSelected() {
            List<ISessionInfo> sel = new ArrayList<>();
            for (TreeItem<Row> it : tree.getSelectionModel().getSelectedItems()) {
                if (it != null && it.getValue() != null) sel.add(it.getValue().s());
            }
            if (sel.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            for (ISessionInfo s : sel) {
                sb.append("  ").append(s.getSessionId()).append("  ").append(s.getUserName())
                    .append("  ").append(ctx.ibName(s.getInfoBaseId())).append('\n');
            }
            if (!Dialogs.confirmDanger(ctx.conn.config(), "Завершение сеансов", "Завершить сеансы?\n" + sb)) return;
            for (ISessionInfo s : sel) {
                Fx.change(ctx.cluster(), "Завершение сеанса " + s.getSessionId() + " (" + s.getUserName() + ", "
                    + ctx.ibName(s.getInfoBaseId()) + ")", RasFn.run(c -> c.terminateSession(ctx.clusterId, s.getSid(),
                    "Сеанс завершён администратором: удерживал блокировку")), v -> reload());
            }
        }

        @Override
        public void dispose() {
            timer.stop();
        }
    }
}
