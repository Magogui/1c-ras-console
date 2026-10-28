package ru.rasconsole.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import com._1c.v8.ibis.admin.ClusterInfo;
import com._1c.v8.ibis.admin.IClusterInfo;

import ru.rasconsole.config.CredentialStore;
import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.Credentials;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ras.RasFn;
import ru.rasconsole.ui.cluster.ClusterSections;
import ru.rasconsole.ui.form.Labels;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.tree.ConsoleNode;
import ru.rasconsole.ui.tree.ListNode;
import ru.rasconsole.ui.tree.ListNode.Sel;
import ru.rasconsole.ui.tree.NodeTreeItem;

/** Узел сервера RAS: кластеры и администраторы центрального сервера. */
public final class ServerNode extends ConsoleNode {

    /** Действия, которые выполняет главное окно (список серверов хранится там). */
    public interface Owner {
        void editServer(ServerNode node);

        void removeServer(ServerNode node);
    }

    private final RasConnection conn;
    private final Owner owner;

    public ServerNode(RasConnection conn, Owner owner) {
        this.conn = conn;
        this.owner = owner;
    }

    public RasConnection conn() {
        return conn;
    }

    private Scope scope(AuthKind kind) {
        return new Scope(conn, kind, null, conn.config().name());
    }

    @Override
    public String title() {
        var cfg = conn.config();
        String t = cfg.name().equals(cfg.host()) ? cfg.address() : cfg.name() + " (" + cfg.address() + ")";
        return switch (conn.state()) {
            case CONNECTED -> t;
            case CHECKING -> t + " — проверка связи…";
            case ERROR -> t + " — нет связи";
            case DISCONNECTED -> t + " — не подключён";
        };
    }

    @Override
    public Object key() {
        return conn;
    }

    @Override
    public String stateKey() {
        return "server:" + conn.config().address();
    }

    @Override
    public boolean isChecking() {
        return conn.state() == RasConnection.State.CHECKING;
    }

    @Override
    public boolean isUnavailable() {
        return conn.isUnavailable();
    }

    @Override
    public boolean isProd() {
        return conn.config().prod();
    }

    @Override
    public Node icon() {
        return Icons.server(conn.state() == RasConnection.State.CONNECTED, conn.state() == RasConnection.State.ERROR,
            conn.state() == RasConnection.State.CHECKING);
    }

    @Override
    public boolean isLeaf() {
        return false;
    }

    @Override
    public void loadChildren(Consumer<List<ConsoleNode>> done) {
        // раскрытие узла — действие пользователя: пробуем подключиться, не дожидаясь паузы после ошибки
        conn.resetRetry();
        Fx.call(scope(AuthKind.NONE), "Список кластеров " + conn.config().address(), c -> c.getClusters(), clusters -> {
            List<ConsoleNode> nodes = new ArrayList<>();
            for (IClusterInfo ci : clusters) nodes.add(ClusterSections.clusterNode(conn, ci, this::reloadTree));
            nodes.add(agentAdmins());
            done.accept(nodes);
        }, err -> done.accept(List.of()));
    }

    private ConsoleNode agentAdmins() {
        return ClusterSections.adminsNode("Администраторы центрального сервера", scope(AuthKind.AGENT),
            c -> c.getAgentAdmins(),
            u -> RasFn.run(c -> c.regAgentAdmin(u)),
            name -> RasFn.run(c -> c.unregAgentAdmin(name)))
            .icon(Icons.AGENT_ADMINS);
    }

    private ListNode<IClusterInfo> clustersList() {
        List<Col<IClusterInfo>> cols = List.of(
            Col.<IClusterInfo>of("Имя", 220, IClusterInfo::getName),
            Col.of("Компьютер", 160, IClusterInfo::getHostName),
            Col.of("Порт", 70, IClusterInfo::getMainPort),
            Col.<IClusterInfo>of("Распределение нагрузки", 220, IClusterInfo::getLoadBalancingMode)
                .text(v -> Labels.choiceText("loadBalancingMode", (Integer) v)),
            Col.of("Уровень отказоустойчивости", IClusterInfo::getSessionFaultToleranceLevel),
            Col.of("ID", 260, IClusterInfo::getClusterId));
        return new ListNode<>("Кластеры", scope(AuthKind.NONE), cols, c -> c.getClusters())
            .key(IClusterInfo::getClusterId)
            .action("Новый кластер…", Sel.NONE, (sel, n) -> newCluster(n::refresh))
            .action("Удалить кластер…", Sel.ONE, (sel, n) -> {
                IClusterInfo ci = sel.get(0);
                if (Dialogs.confirmDanger(conn.config(), "Удаление кластера", "Удалить кластер «" + ci.getName() + "»?")) {
                    Scope s = new Scope(conn, AuthKind.CLUSTER, ci.getClusterId(), ci.getName());
                    Fx.change(s.asSlow(), "Удаление кластера", RasFn.run(c -> c.unregCluster(ci.getClusterId())), v -> {
                        n.refresh();
                        reloadTree();
                    });
                }
            });
    }

    private void newCluster(Runnable after) {
        ClusterInfo ci = new ClusterInfo();
        ci.setName("Новый кластер");
        ci.setHostName(conn.config().host());
        ci.setMainPort(1541);
        if (Dialogs.editBean("Новый кластер", ci, IClusterInfo.class, null, true, null)) {
            Fx.change(scope(AuthKind.AGENT).asSlow(), "Регистрация кластера", c -> c.regCluster(ci), id -> {
                after.run();
                reloadTree();
            });
        }
    }

    @Override
    public Node createView() {
        BorderPane root = new BorderPane();
        var cfg = conn.config();
        Label header = new Label("Сервер администрирования " + cfg.address());
        header.getStyleClass().add("view-title");
        Label info = new Label(stateText());
        info.setWrapText(true);
        Consumer<RasConnection> listener = c -> javafx.application.Platform.runLater(() -> info.setText(stateText()));
        conn.addListener(listener);
        root.sceneProperty().addListener((o, was, now) -> {
            if (now == null) conn.removeListener(listener);
        });
        javafx.scene.control.Button diag = new javafx.scene.control.Button("Диагностика сети…");
        diag.setOnAction(e -> NetDiag.open(conn.config()));
        VBox top = new VBox(4, header, info, diag);
        top.setPadding(new Insets(6, 8, 6, 8));
        root.setTop(top);
        root.setCenter(clustersList().createView());
        return root;
    }

    private void askAgentAdmin() {
        String address = conn.config().address();
        CredentialStore store = Fx.credentialStore();
        boolean can = store != null && store.available();
        Credentials saved = can ? store.agent(address) : null;
        String user = saved != null ? saved.user() : conn.config().agentUser();
        Dialogs.credentials("Авторизация", "Администратор центрального сервера " + address, user, can, saved != null)
            .ifPresent(login -> {
                conn.setAgentCredentials(login.credentials());
                if (login.remember()) store.putAgent(address, login.credentials());
            });
    }

    private String stateText() {
        String state = switch (conn.state()) {
            case CONNECTED -> "подключён";
            case CHECKING -> "проверка связи…";
            case ERROR -> "нет связи (" + conn.lastError() + ")";
            case DISCONNECTED -> "не подключён";
        };
        return "Состояние: " + state + (conn.agentVersion() != null ? "    Версия RAS: " + conn.agentVersion() : "");
    }

    @Override
    public List<MenuItem> menu(NodeTreeItem item) {
        return List.of(
            item("Подключиться / обновить", () -> {
                item.setExpanded(true);
                item.reload();
            }),
            item("Отключиться", () -> conn.disconnect().whenComplete((v, e) ->
                javafx.application.Platform.runLater(item::collapseAndReset))),
            new SeparatorMenuItem(),
            item("Новый кластер…", () -> newCluster(this::reloadTree)),
            item("Администратор агента…", this::askAgentAdmin),
            new SeparatorMenuItem(),
            item("Диагностика сети…", () -> NetDiag.open(conn.config())),
            item("Параметры подключения…", () -> owner.editServer(this)),
            item("Удалить из списка", () -> owner.removeServer(this)));
    }
}
