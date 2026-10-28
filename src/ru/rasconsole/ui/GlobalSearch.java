package ru.rasconsole.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.ISessionInfo;

import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.AuthRequiredException;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.table.EntityTable;

/**
 * Поиск по всем серверам: информационные базы (имя, описание) и сеансы (пользователь, компьютер, приложение).
 * Опрос идёт без диалогов авторизации; двойной щелчок открывает найденное в дереве.
 */
final class GlobalSearch {

    /** Переход в дереве: сервер → кластер → раздел (→ объект) и, при необходимости, поиск в таблице. */
    interface Navigator {
        void open(String serverAddress, UUID clusterId, String section, Object childKey, String filter);
    }

    record Hit(String kind, RasConnection conn, String server, String cluster, UUID clusterId, String base, UUID baseId,
               String user, String host, String app, Integer session, String note) {
    }

    private final Supplier<List<RasConnection>> connections;
    private final Navigator navigator;

    GlobalSearch(Supplier<List<RasConnection>> connections, Navigator navigator) {
        this.connections = connections;
        this.navigator = navigator;
    }

    void open(String initial) {
        Dialog<Void> d = Dialogs.prepare(new Dialog<>(), "Поиск по всем серверам");
        d.initModality(Modality.NONE);
        d.setHeaderText("Где сидит пользователь, на каком кластере база — по всем серверам из списка");
        TextField query = new TextField(initial == null ? "" : initial);
        query.setPromptText("Имя базы, пользователь, компьютер…");
        CheckBox bases = new CheckBox("Информационные базы");
        CheckBox sessions = new CheckBox("Сеансы");
        bases.setSelected(true);
        sessions.setSelected(true);
        Button find = new Button("Найти");
        find.setDefaultButton(true);
        Label status = new Label();
        EntityTable<Hit> table = new EntityTable<>(List.of(
            Col.<Hit>of("Что", 70, Hit::kind),
            Col.<Hit>of("Сервер", 180, Hit::server),
            Col.<Hit>of("Кластер", 150, Hit::cluster),
            Col.<Hit>of("ИБ", 140, Hit::base),
            Col.<Hit>of("Пользователь", 160, Hit::user),
            Col.<Hit>of("Компьютер", 130, Hit::host),
            Col.<Hit>of("Приложение", 120, Hit::app),
            Col.<Hit>of("Сеанс", 60, Hit::session),
            Col.<Hit>of("Примечание", 260, Hit::note)), null);
        table.bindState("Поиск по всем серверам");
        table.setPlaceholder("Введите текст и нажмите «Найти»");
        table.setRowStyle(h -> h.note() != null && !h.note().isEmpty() ? "row-warning" : null);
        table.setOnDoubleClick(h -> {
            if (h.clusterId() == null) return;
            if ("ИБ".equals(h.kind())) {
                navigator.open(h.conn().config().address(), h.clusterId(), "Информационные базы", h.baseId(), null);
            } else {
                navigator.open(h.conn().config().address(), h.clusterId(), "Сеансы", null,
                    h.user() == null || h.user().isEmpty() ? String.valueOf(h.session()) : h.user());
            }
        });
        find.setOnAction(e -> {
            String q = query.getText() == null ? "" : query.getText().trim();
            if (q.isEmpty()) return;
            status.setText("Поиск…");
            table.setItems(List.of());
            search(q, bases.isSelected(), sessions.isSelected(), hits -> {
                table.setItems(hits);
                long found = hits.stream().filter(h -> h.note() == null || h.note().isEmpty()).count();
                status.setText("Найдено: " + found + (found != hits.size() ? " (и серверов/кластеров, которые не удалось проверить: "
                    + (hits.size() - found) + ")" : ""));
                table.setPlaceholder("Ничего не найдено");
            });
        });
        HBox top = new HBox(8, query, bases, sessions, find);
        HBox.setHgrow(query, Priority.ALWAYS);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox box = new VBox(8, top, status, table.view(),
            new Label("Двойной щелчок открывает базу или сеансы пользователя в дереве."));
        VBox.setVgrow(table.view(), Priority.ALWAYS);
        box.setPadding(new Insets(4));
        box.setPrefSize(1150, 520);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(new ButtonType("Закрыть", ButtonBar.ButtonData.CANCEL_CLOSE));
        d.show();
        if (!query.getText().isBlank()) find.fire();
        Platform.runLater(query::requestFocus);
    }

    /** Опросить все серверы и все кластеры параллельно (каждый сервер — в своём потоке соединения). */
    private void search(String q, boolean bases, boolean sessions, java.util.function.Consumer<List<Hit>> done) {
        String ql = q.toLowerCase(Locale.ROOT);
        List<CompletableFuture<List<Hit>>> fs = new ArrayList<>();
        for (RasConnection conn : connections.get()) {
            String server = conn.config().display();
            fs.add(conn.submit(AuthKind.NONE, null, c -> c.getClusters()).thenCompose(clusters -> {
                List<CompletableFuture<List<Hit>>> per = new ArrayList<>();
                for (IClusterInfo ci : clusters) per.add(searchCluster(conn, server, ci, ql, bases, sessions));
                return CompletableFuture.allOf(per.toArray(new CompletableFuture[0])).thenApply(v -> {
                    List<Hit> r = new ArrayList<>();
                    for (CompletableFuture<List<Hit>> f : per) r.addAll(f.join());
                    return r;
                });
            }).exceptionally(e -> List.of(new Hit("—", conn, server, "", null, "", null, "", "", "", null,
                "нет связи: " + ru.rasconsole.ras.NetErrors.describe(e)))));
        }
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).whenComplete((v, e) -> {
            List<Hit> all = new ArrayList<>();
            for (CompletableFuture<List<Hit>> f : fs) all.addAll(f.getNow(List.of()));
            Platform.runLater(() -> done.accept(all));
        });
    }

    private CompletableFuture<List<Hit>> searchCluster(RasConnection conn, String server, IClusterInfo ci, String ql,
                                                      boolean bases, boolean sessions) {
        UUID cid = ci.getClusterId();
        String cluster = ci.getName();
        return conn.submit(AuthKind.CLUSTER, cid, c -> {
            List<Hit> r = new ArrayList<>();
            Map<UUID, String> names = new HashMap<>();
            for (IInfoBaseInfoShort b : c.getInfoBasesShort(cid)) {
                names.put(b.getInfoBaseId(), b.getName());
                if (bases && (contains(b.getName(), ql) || contains(b.getDescr(), ql))) {
                    r.add(new Hit("ИБ", conn, server, cluster, cid, b.getName(), b.getInfoBaseId(), "", "", "", null, ""));
                }
            }
            if (sessions) {
                for (ISessionInfo s : c.getSessions(cid)) {
                    String base = names.getOrDefault(s.getInfoBaseId(), "");
                    if (contains(s.getUserName(), ql) || contains(s.getHost(), ql) || contains(Fmt.app(s.getAppId()), ql)
                        || contains(base, ql) || contains(s.getClientIPAddress(), ql)) {
                        r.add(new Hit("Сеанс", conn, server, cluster, cid, base, s.getInfoBaseId(), s.getUserName(), s.getHost(),
                            Fmt.app(s.getAppId()), s.getSessionId(), ""));
                    }
                }
            }
            return r;
        }).exceptionally(e -> {
            Throwable t = Fx.unwrap(e);
            String note = t instanceof AuthRequiredException ? "нужна авторизация: откройте кластер и войдите (или сохраните пароль)"
                : "ошибка: " + Fmt.rootMessage(t);
            return List.of(new Hit("—", conn, server, cluster, cid, "", null, "", "", "", null, note));
        });
    }

    private static boolean contains(String s, String ql) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(ql);
    }
}
