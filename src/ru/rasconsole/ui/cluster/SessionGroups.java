package ru.rasconsole.ui.cluster;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com._1c.v8.ibis.admin.ISessionInfo;

import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.Icons;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.table.EntityTable;
import ru.rasconsole.ui.tree.ConsoleNode;
import ru.rasconsole.ui.tree.NodeTreeItem;

/**
 * Раздел «Сеансы по группам»: сеансы кластера, сгруппированные по пользователю, компьютеру, приложению,
 * базе или рабочему процессу, с итогами. Сразу видно, что один пользователь занял несколько лицензий.
 */
final class SessionGroups extends ConsoleNode {

    /** Способ группировки. */
    private record Mode(String title, Function<ISessionInfo, String> key) {
    }

    /** Строка-группа. */
    record Group(String key, List<ISessionInfo> sessions, int active, int hibernate, int licenses, String infoBases,
                 String hosts, String apps, long cpu5min, long memory, Date oldest) {
    }

    private final ClusterCtx ctx;
    private final List<Mode> modes;

    SessionGroups(ClusterCtx ctx) {
        this.ctx = ctx;
        this.modes = List.of(
            new Mode("Пользователь", s -> nz(s.getUserName())),
            new Mode("Компьютер", s -> nz(s.getHost())),
            new Mode("Пользователь и компьютер", s -> nz(s.getUserName()) + " @ " + nz(s.getHost())),
            new Mode("Приложение", s -> Fmt.app(s.getAppId())),
            new Mode("Информационная база", s -> ctx.ibName(s.getInfoBaseId())),
            new Mode("Рабочий процесс", s -> ctx.processName(s.getWorkingProcessId())));
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "(не указан)" : s;
    }

    @Override
    public String title() {
        return "Сеансы по группам";
    }

    @Override
    public Node icon() {
        return Icons.get(Icons.SESSIONS);
    }

    @Override
    public List<MenuItem> menu(NodeTreeItem item) {
        return List.of();
    }

    @Override
    public Node createView() {
        return new View().root;
    }

    private final class View implements Searchable {
        final BorderPane root = new BorderPane();
        final ChoiceBox<String> by = new ChoiceBox<>();
        final Label count = new Label();
        final EntityTable<Group> table;
        final TextField filter;
        List<ISessionInfo> sessions = List.of();

        View() {
            table = new EntityTable<>(List.of(
                Col.<Group>of("Группа", 260, Group::key),
                Col.<Group>of("Сеансов", 70, g -> g.sessions().size()),
                Col.<Group>of("Лицензий", 70, Group::licenses),
                Col.<Group>of("Активных", 70, Group::active),
                Col.<Group>of("Спящих", 70, Group::hibernate),
                Col.<Group>of("CPU (5 мин), мс", 110, Group::cpu5min),
                Col.<Group>bytes("Память (тек.)", Group::memory),
                Col.<Group>of("Самый ранний вход", 140, Group::oldest),
                Col.<Group>of("Базы", 240, Group::infoBases),
                Col.<Group>of("Компьютеры", 200, Group::hosts),
                Col.<Group>of("Приложения", 200, Group::apps)), Group::key);
            table.setRowStyle(g -> g.licenses() > 1 && !by.getValue().equals("Информационная база")
                && !by.getValue().equals("Рабочий процесс") && !by.getValue().equals("Приложение") ? "row-warning" : null);
            table.setOnDoubleClick(this::showGroup);
            table.bindState("Сеансы по группам");
            for (Mode m : modes) by.getItems().add(m.title());
            String saved = ru.rasconsole.config.UiState.get("sessionGroups.by");
            by.setValue(saved != null && by.getItems().contains(saved) ? saved : modes.get(0).title());
            by.valueProperty().addListener((o, was, now) -> {
                ru.rasconsole.config.UiState.put("sessionGroups.by", now);
                regroup();
            });
            Button refresh = new Button("Обновить");
            refresh.setOnAction(e -> reload());
            Button open = new Button("Сеансы группы");
            open.setOnAction(e -> {
                if (table.selected().size() == 1) showGroup(table.selected().get(0));
            });
            Button kill = new Button("Завершить сеансы группы…");
            kill.setOnAction(e -> {
                List<ISessionInfo> all = new ArrayList<>();
                for (Group g : table.selected()) all.addAll(g.sessions());
                if (all.isEmpty()) return;
                SessionKill.terminate(ctx, all, "Завершить сеансы выбранных групп (" + all.size() + ")", this::reload);
            });
            filter = table.filterField();
            filter.textProperty().addListener((o, was, now) -> updateCount());
            Pane spacer = new Pane();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            ToolBar bar = new ToolBar(new Label("Группировать по:"), by, refresh, open, kill);
            ToolBar bar2 = new ToolBar(filter, spacer, count);
            Label header = new Label("Сеансы по группам — кластер «" + ctx.name + "»");
            header.getStyleClass().add("view-title");
            header.setPadding(new Insets(6, 8, 2, 8));
            Label hint = new Label("Жёлтым выделены группы, занявшие больше одной лицензии. Двойной щелчок — сеансы группы.");
            hint.getStyleClass().add("hint-label");
            hint.setPadding(new Insets(0, 8, 2, 8));
            root.setTop(new VBox(header, hint, bar, bar2));
            root.setCenter(table.view());
            root.setUserData(this);
            reload();
        }

        void reload() {
            table.setPlaceholder("Загрузка…");
            Fx.call(ctx.cluster(), "Сеансы кластера «" + ctx.name + "»", c -> {
                List<ISessionInfo> list = c.getSessions(ctx.clusterId);
                for (ISessionInfo s : list) {
                    ctx.sessionNumbers.put(s.getSid(), s.getSessionId());
                    ctx.ensureInfoBase(c, s.getInfoBaseId());
                    ctx.ensureProcess(c, s.getWorkingProcessId());
                }
                return list;
            }, list -> {
                sessions = list;
                regroup();
                table.setPlaceholder("Нет сеансов");
            }, err -> table.setPlaceholder(Fx.isAuthCancel(err) ? "Требуется авторизация. Нажмите «Обновить»."
                : "Ошибка: " + Fmt.rootMessage(err)));
        }

        void regroup() {
            Mode mode = modes.get(Math.max(0, by.getItems().indexOf(by.getValue())));
            Map<String, List<ISessionInfo>> m = new LinkedHashMap<>();
            for (ISessionInfo s : sessions) m.computeIfAbsent(mode.key().apply(s), k -> new ArrayList<>()).add(s);
            List<Group> groups = new ArrayList<>();
            for (Map.Entry<String, List<ISessionInfo>> e : m.entrySet()) groups.add(group(e.getKey(), e.getValue()));
            // по умолчанию — сначала группы с наибольшим числом лицензий и сеансов
            groups.sort((a, b) -> a.licenses() != b.licenses() ? Integer.compare(b.licenses(), a.licenses())
                : Integer.compare(b.sessions().size(), a.sessions().size()));
            table.setItems(groups);
            updateCount();
        }

        Group group(String key, List<ISessionInfo> list) {
            int active = 0;
            int hib = 0;
            int lic = 0;
            long cpu = 0;
            long mem = 0;
            Date oldest = null;
            TreeSet<String> ibs = new TreeSet<>();
            TreeSet<String> hosts = new TreeSet<>();
            TreeSet<String> apps = new TreeSet<>();
            for (ISessionInfo s : list) {
                if (s.getDurationCurrent() > 0) active++;
                if (s.getHibernate()) hib++;
                if (s.getLicenses() != null) lic += s.getLicenses().size();
                cpu += s.getCpuTimeLast5Min();
                mem += s.getMemoryCurrent();
                if (s.getStartedAt() != null && (oldest == null || s.getStartedAt().before(oldest))) oldest = s.getStartedAt();
                ibs.add(ctx.ibName(s.getInfoBaseId()));
                if (s.getHost() != null && !s.getHost().isBlank()) hosts.add(s.getHost());
                apps.add(Fmt.app(s.getAppId()));
            }
            return new Group(key, list, active, hib, lic, String.join(", ", ibs), String.join(", ", hosts),
                String.join(", ", apps), cpu, mem, oldest);
        }

        void showGroup(Group g) {
            Dialogs.table("Сеансы: " + g.key() + " (" + g.sessions().size() + ")", g.sessions(), Columns.sessions(ctx));
        }

        void updateCount() {
            int s = 0;
            for (Group g : table.visible()) s += g.sessions().size();
            count.setText("Групп: " + table.visible().size() + ", сеансов в них: " + s);
        }

        @Override
        public void focusFilter() {
            filter.requestFocus();
        }

        @Override
        public void setFilter(String text) {
            filter.setText(text);
        }
    }
}
