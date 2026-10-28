package ru.rasconsole.ui.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import com._1c.v8.ibis.admin.AssignmentRuleInfo;
import com._1c.v8.ibis.admin.IAssignmentRuleInfo;
import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IClusterManagerInfo;
import com._1c.v8.ibis.admin.IClusterServiceInfo;
import com._1c.v8.ibis.admin.IInfoBaseConnectionShort;
import com._1c.v8.ibis.admin.IInfoBaseInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.IObjectLockInfo;
import com._1c.v8.ibis.admin.IPortRangeInfo;
import com._1c.v8.ibis.admin.IRegUserInfo;
import com._1c.v8.ibis.admin.IResourceConsumptionCounter;
import com._1c.v8.ibis.admin.IResourceConsumptionCounterValue;
import com._1c.v8.ibis.admin.IResourceConsumptionLimit;
import com._1c.v8.ibis.admin.ISecurityProfile;
import com._1c.v8.ibis.admin.ISecurityProfileAddIn;
import com._1c.v8.ibis.admin.ISecurityProfileApplication;
import com._1c.v8.ibis.admin.ISecurityProfileCOMClass;
import com._1c.v8.ibis.admin.ISecurityProfileExternalModule;
import com._1c.v8.ibis.admin.ISecurityProfileInternetResource;
import com._1c.v8.ibis.admin.ISecurityProfileVirtualDirectory;
import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;
import com._1c.v8.ibis.admin.IWorkingServerInfo;
import com._1c.v8.ibis.admin.InfoBaseInfo;
import com._1c.v8.ibis.admin.PortRangeInfo;
import com._1c.v8.ibis.admin.RegUserInfo;
import com._1c.v8.ibis.admin.ResourceConsumptionCounter;
import com._1c.v8.ibis.admin.ResourceConsumptionLimit;
import com._1c.v8.ibis.admin.SecurityProfile;
import com._1c.v8.ibis.admin.SecurityProfileAddIn;
import com._1c.v8.ibis.admin.SecurityProfileApplication;
import com._1c.v8.ibis.admin.SecurityProfileCOMClass;
import com._1c.v8.ibis.admin.SecurityProfileExternalModule;
import com._1c.v8.ibis.admin.SecurityProfileInternetResource;
import com._1c.v8.ibis.admin.SecurityProfileVirtualDirectory;
import com._1c.v8.ibis.admin.WorkingServerInfo;

import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ras.RasFn;
import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.Icons;
import ru.rasconsole.ui.Scope;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.tree.ConsoleNode;
import ru.rasconsole.ui.tree.ListNode;
import ru.rasconsole.ui.tree.ListNode.Sel;
import ru.rasconsole.ui.tree.ObjectNode;

/** Дерево разделов кластера: всё, что есть в стандартной консоли. */
public final class ClusterSections {

    private static final List<String> SESSION_LOCK_PROPS = List.of("sessionsDenied", "deniedFrom", "deniedTo",
        "deniedMessage", "permissionCode", "deniedParameter", "scheduledJobsDenied");

    private static final List<String> NEW_IB_PROPS = List.of("name", "descr", "dbServerName", "dbms", "dbName",
        "dbUser", "dbPassword", "dateOffset", "locale", "licenseDistributionAllowed", "scheduledJobsDenied");

    private final ClusterCtx ctx;
    private final UUID cid;

    private ClusterSections(ClusterCtx ctx) {
        this.ctx = ctx;
        this.cid = ctx.clusterId;
    }

    /** Источник списка всех соединений консоли (для копирования настроек между кластерами). */
    public static void setConnections(java.util.function.Supplier<List<RasConnection>> s) {
        CopySettings.connections = s;
    }

    /** Узел кластера. {@code onChanged} вызывается после удаления кластера (обновить список на сервере). */
    public static ObjectNode<IClusterInfo> clusterNode(RasConnection conn, IClusterInfo info, Runnable onChanged) {
        ClusterCtx ctx = new ClusterCtx(conn, info.getClusterId(), info.getName());
        ctx.host = info.getHostName();
        ctx.mainPort = info.getMainPort();
        ClusterSections s = new ClusterSections(ctx);
        UUID cid = info.getClusterId();
        return new ObjectNode<IClusterInfo>(ctx.cluster(), IClusterInfo.class, info, cid,
            ci -> "Кластер «" + ci.getName() + "» (" + ci.getHostName() + ":" + ci.getMainPort() + ")")
            .reloader(c -> {
                IClusterInfo ci = c.getClusterInfo(cid);
                ctx.name = ci.getName();
                return ci;
            })
            .saver("Свойства кластера", ci -> RasFn.run(c -> c.regCluster(ci)))
            .action("Регламентные работы…", n -> Maintenance.open(ctx, List.of(), () -> { }))
            .action("Копировать настройки…", n -> CopySettings.open(ctx))
            .action("Сравнить настройки…", n -> CompareSettings.open(ctx))
            .action("Применить требования (полностью)", n -> s.applyRules(1))
            .action("Применить требования (частично)", n -> s.applyRules(0))
            .action("Удалить кластер…", n -> {
                if (s.danger("Удаление кластера", "Удалить кластер «" + n.item().getName() + "»?\n"
                    + "Информационные базы и рабочие серверы будут удалены из кластера.")) {
                    Fx.change(ctx.cluster().asSlow(), "Удаление кластера", RasFn.run(c -> c.unregCluster(cid)), v -> onChanged.run());
                }
            })
            .icon(Icons.CLUSTER)
            .sections(n -> s.sections());
    }

    private List<ConsoleNode> sections() {
        Scope cs = ctx.cluster();
        List<ConsoleNode> list = new ArrayList<>();
        list.add(admins());
        list.add(new ListNode<>("Менеджеры кластера", cs, Columns.managers(), c -> c.getClusterManagers(cid))
            .icon(Icons.MANAGERS)
            .defaultAction("Свойства", (sel, n) -> show("Менеджер кластера", sel.get(0), IClusterManagerInfo.class)));
        list.add(new ListNode<>("Сервисы", cs, Columns.services(), c -> c.getClusterServices(cid)).icon(Icons.SERVICES));
        list.add(workingServers());
        list.add(infoBases());
        list.add(processes("Рабочие процессы", c -> c.getWorkingProcesses(cid)));
        list.add(sessions("Сеансы", cs, c -> c.getSessions(cid)));
        list.add(heavySessions());
        list.add(new SessionGroups(ctx));
        list.add(new LockChains(ctx));
        list.add(locks("Блокировки", c -> c.getLocks(cid)));
        list.add(connections("Соединения", cs, c -> c.getConnectionsShort(cid)));
        list.add(Licenses.node(ctx));
        list.add(securityProfiles());
        list.add(counters());
        list.add(limits());
        return list;
    }

    private void applyRules(int full) {
        Fx.change(ctx.cluster(), "Применение требований назначения", RasFn.run(c -> c.applyAssignmentRules(cid, full)),
            v -> Dialogs.info("Требования назначения", "Требования назначения функциональности применены."));
    }

    /** Подтверждение опасного действия (с учётом пометки «продуктивный сервер»). */
    private boolean danger(String header, String text) {
        return Dialogs.confirmDanger(ctx.conn.config(), header, text);
    }

    private static void show(String title, Object bean, Class<?> type) {
        Dialogs.editBean(title, bean, type, null, false, null);
    }

    /** Выполнить изменение и обновить список. */
    private static <T> void change(ListNode<T> node, String what, RasFn<?> fn) {
        Fx.change(node.scope(), what, fn, v -> node.refresh());
    }

    private static <T> void change(ListNode<T> node, Scope scope, String what, RasFn<?> fn) {
        Fx.change(scope, what, fn, v -> node.refresh());
    }

    // ---------------- администраторы ----------------

    private ConsoleNode admins() {
        return adminsNode("Администраторы", ctx.cluster(), c -> c.getClusterAdmins(cid),
            u -> RasFn.run(c -> c.regClusterAdmin(cid, u)),
            name -> RasFn.run(c -> c.unregClusterAdmin(cid, name)));
    }

    /** Список администраторов (кластера или центрального сервера). */
    public static ListNode<IRegUserInfo> adminsNode(String title, Scope scope, RasFn<List<IRegUserInfo>> loader,
                                                   Function<IRegUserInfo, RasFn<?>> reg, Function<String, RasFn<?>> unreg) {
        return new ListNode<>(title, scope, Columns.admins(), loader)
            .key(IRegUserInfo::getName)
            .icon(Icons.ADMINS)
            .action("Добавить…", Sel.NONE, (sel, n) -> {
                RegUserInfo u = new RegUserInfo("", "", "", true, false, "");
                if (Dialogs.editBean("Новый администратор", u, IRegUserInfo.class, null, true, null)) {
                    change(n, "Регистрация администратора", reg.apply(u));
                }
            })
            .defaultAction("Изменить…", (sel, n) -> {
                IRegUserInfo src = sel.get(0);
                RegUserInfo u = new RegUserInfo(src.getName(), src.getDescr(), "", src.isPasswordAuthAllowed(),
                    src.isSysAuthAllowed(), src.getSysUserName());
                if (Dialogs.editBean("Администратор «" + src.getName() + "»", u, IRegUserInfo.class, null, true, null)) {
                    change(n, "Изменение администратора", reg.apply(u));
                }
            })
            .action("Удалить", Sel.MANY, (sel, n) -> {
                if (!Dialogs.confirmDanger(n.scope().conn().config(), "Удаление администраторов", "Удалить выбранных администраторов (" + sel.size() + ")?")) return;
                for (IRegUserInfo u : sel) change(n, "Удаление администратора " + u.getName(), unreg.apply(u.getName()));
            });
    }

    // ---------------- рабочие серверы ----------------

    private ConsoleNode workingServers() {
        return new ListNode<>("Рабочие серверы", ctx.cluster(), Columns.servers(), c -> c.getWorkingServers(cid))
            .key(IWorkingServerInfo::getWorkingServerId)
            .icon(Icons.WORKING_SERVERS)
            .children(this::serverNode)
            .action("Добавить…", Sel.NONE, (sel, n) -> {
                WorkingServerInfo ws = new WorkingServerInfo();
                ws.setMainPort(1540);
                TextField ranges = new TextField("1560:1591");
                if (Dialogs.editBean("Новый рабочий сервер", ws, IWorkingServerInfo.class, null, true, rangesBox(ranges))) {
                    if (applyRanges(ws, ranges.getText())) {
                        change(n, ctx.cluster().asSlow(), "Регистрация рабочего сервера", RasFn.run(c -> c.regWorkingServer(cid, ws)));
                    }
                }
            })
            .defaultAction("Свойства…", (sel, n) -> editServer(sel.get(0), n::refresh))
            .action("Перезапустить все рабочие процессы…", Sel.ONE, (sel, n) -> restartServerProcesses(sel.get(0), n::refresh))
            .action("Удалить…", Sel.ONE, (sel, n) -> {
                IWorkingServerInfo ws = sel.get(0);
                if (danger("Удаление рабочего сервера", "Удалить рабочий сервер «" + ws.getName() + "» из кластера?")) {
                    change(n, ctx.cluster().asSlow(), "Удаление рабочего сервера", RasFn.run(c -> c.unregWorkingServer(cid, ws.getWorkingServerId())));
                }
            });
    }

    /** Перезапустить все рабочие процессы рабочего сервера. */
    private void restartServerProcesses(IWorkingServerInfo ws, Runnable after) {
        Fx.call(ctx.cluster(), "Рабочие процессы сервера " + ws.getName(),
            c -> c.getServerWorkingProcesses(cid, ws.getWorkingServerId()), list -> {
                if (list.isEmpty()) {
                    Dialogs.info("Перезапуск рабочих процессов", "У сервера «" + ws.getName() + "» нет рабочих процессов.");
                    return;
                }
                ProcessRestart.restart(ctx, list, () -> later(after));
            });
    }

    private void editServer(IWorkingServerInfo ws, Runnable after) {
        TextField ranges = new TextField(portRangesText(ws.getPortRanges()));
        if (Dialogs.editBean("Рабочий сервер «" + ws.getName() + "»", ws, IWorkingServerInfo.class, null, true, rangesBox(ranges))
            && applyRanges(ws, ranges.getText())) {
            Fx.change(ctx.cluster().asSlow(), "Изменение рабочего сервера", RasFn.run(c -> c.regWorkingServer(cid, ws)), v -> after.run());
        }
    }

    private static HBox rangesBox(TextField ranges) {
        ranges.setPromptText("1560:1591, 1600:1610");
        HBox box = new HBox(10, new Label("Диапазоны IP-портов:"), ranges);
        HBox.setHgrow(ranges, javafx.scene.layout.Priority.ALWAYS);
        return box;
    }

    static String portRangesText(List<IPortRangeInfo> ranges) {
        if (ranges == null) return "";
        StringBuilder sb = new StringBuilder();
        for (IPortRangeInfo r : ranges) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.getLowBound()).append(':').append(r.getHighBound());
        }
        return sb.toString();
    }

    private static boolean applyRanges(IWorkingServerInfo ws, String text) {
        List<IPortRangeInfo> list = new ArrayList<>();
        try {
            for (String part : text.split("[,;\\s]+")) {
                if (part.isBlank()) continue;
                String[] lh = part.split("[:\\-]");
                int low = Integer.parseInt(lh[0].trim());
                int high = lh.length > 1 ? Integer.parseInt(lh[1].trim()) : low;
                PortRangeInfo r = new PortRangeInfo(high, low);
                r.setLowBound(Math.min(low, high));
                r.setHighBound(Math.max(low, high));
                list.add(r);
            }
        } catch (RuntimeException e) {
            Dialogs.warn("Диапазоны портов: укажите через запятую в виде 1560:1591");
            return false;
        }
        ws.setPortRanges(list);
        return true;
    }

    private ConsoleNode serverNode(IWorkingServerInfo info) {
        UUID sid = info.getWorkingServerId();
        return new ObjectNode<IWorkingServerInfo>(ctx.cluster(), IWorkingServerInfo.class, info, sid,
            ws -> ws.getName() + " (" + ws.getHostName() + ":" + ws.getMainPort() + ")")
            .reloader(c -> c.getWorkingServerInfo(cid, sid))
            .action("Свойства…", n -> editServer(n.item(), n::reload))
            .action("Перезапустить все рабочие процессы…", n -> restartServerProcesses(n.item(), () -> {
                if (n.treeItem() != null && n.treeItem().isLoaded()) n.treeItem().reload();
            }))
            .icon(Icons.COMPUTER)
            .sections(n -> List.of(
                processes("Рабочие процессы", c -> c.getServerWorkingProcesses(cid, sid)),
                assignmentRules(sid)));
    }

    // ---------------- требования назначения ----------------

    private ConsoleNode assignmentRules(UUID serverId) {
        return new ListNode<>("Требования назначения функциональности", ctx.cluster(), Columns.assignmentRules(),
            c -> c.getAssignmentRules(cid, serverId))
            .key(IAssignmentRuleInfo::getAssignmentRuleId)
            .icon(Icons.RULES)
            .action("Добавить…", Sel.NONE, (sel, n) -> {
                AssignmentRuleInfo r = new AssignmentRuleInfo();
                r.setRuleType(2);
                if (Dialogs.editBean("Новое требование назначения", r, IAssignmentRuleInfo.class, null, true, null)) {
                    change(n, "Добавление требования", RasFn.run(c ->
                        c.regAssignmentRule(cid, serverId, r, c.getAssignmentRules(cid, serverId).size())));
                }
            })
            .defaultAction("Изменить…", (sel, n) -> {
                IAssignmentRuleInfo r = sel.get(0);
                if (Dialogs.editBean("Требование назначения", r, IAssignmentRuleInfo.class, null, true, null)) {
                    change(n, "Изменение требования", RasFn.run(c -> {
                        int pos = indexOfRule(c.getAssignmentRules(cid, serverId), r.getAssignmentRuleId());
                        c.regAssignmentRule(cid, serverId, r, Math.max(pos, 0));
                    }));
                }
            })
            .action("Выше", Sel.ONE, (sel, n) -> moveRule(n, serverId, sel.get(0), -1))
            .action("Ниже", Sel.ONE, (sel, n) -> moveRule(n, serverId, sel.get(0), 1))
            .action("Удалить", Sel.MANY, (sel, n) -> {
                if (!danger("Удаление требований", "Удалить выбранные требования (" + sel.size() + ")?")) return;
                for (IAssignmentRuleInfo r : sel) {
                    change(n, "Удаление требования", RasFn.run(c -> c.unregAssignmentRule(cid, serverId, r.getAssignmentRuleId())));
                }
            })
            .action("Применить полностью", Sel.NONE, (sel, n) -> applyRules(1));
    }

    private void moveRule(ListNode<IAssignmentRuleInfo> n, UUID serverId, IAssignmentRuleInfo r, int delta) {
        change(n, "Перемещение требования", RasFn.run(c -> {
            List<IAssignmentRuleInfo> all = c.getAssignmentRules(cid, serverId);
            int pos = indexOfRule(all, r.getAssignmentRuleId());
            int to = pos + delta;
            if (pos >= 0 && to >= 0 && to < all.size()) c.regAssignmentRule(cid, serverId, r, to);
        }));
    }

    private static int indexOfRule(List<IAssignmentRuleInfo> rules, UUID id) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).getAssignmentRuleId().equals(id)) return i;
        }
        return -1;
    }

    // ---------------- рабочие процессы ----------------

    private ConsoleNode processes(String title, RasFn<List<IWorkingProcessInfo>> loader) {
        return new ListNode<>(title, ctx.cluster(), Columns.processes(), c -> {
            List<IWorkingProcessInfo> list = loader.apply(c);
            ctx.rememberProcesses(list);
            return list;
        })
            .key(IWorkingProcessInfo::getWorkingProcessId)
            .icon(Icons.PROCESSES)
            .children(this::processNode)
            .defaultAction("Свойства", (sel, n) -> show("Рабочий процесс", sel.get(0), IWorkingProcessInfo.class))
            .action("Перезапустить…", Sel.MANY, (sel, n) -> ProcessRestart.restart(ctx, sel, () -> later(n::refresh)))
            .action("Перезапустить все…", Sel.NONE, (sel, n) ->
                Fx.call(n.scope(), title, loader, all -> ProcessRestart.restart(ctx, all, () -> later(n::refresh))));
    }

    /** Обновить через несколько секунд: менеджеру кластера нужно время, чтобы поднять новый rphost. */
    private static void later(Runnable r) {
        for (int sec : new int[] {4, 12}) {
            javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(sec));
            p.setOnFinished(e -> r.run());
            p.play();
        }
    }

    private ConsoleNode processNode(IWorkingProcessInfo info) {
        UUID pid = info.getWorkingProcessId();
        return new ObjectNode<IWorkingProcessInfo>(ctx.cluster(), IWorkingProcessInfo.class, info, pid,
            p -> "Процесс " + p.getHostName() + ":" + p.getMainPort() + " (PID " + p.getPid() + ")")
            .reloader(c -> c.getWorkingProcessInfo(cid, pid))
            .icon(Icons.PROCESS)
            .action("Перезапустить…", n -> ProcessRestart.restart(ctx, List.of(n.item()), () -> later(() -> {
                // процесс заменён новым: обновить список процессов, в котором находится узел
                if (n.treeItem() != null && n.treeItem().getParent() instanceof ru.rasconsole.ui.tree.NodeTreeItem p) {
                    p.reload();
                }
            })))
            .sections(n -> List.of(
                connections("Соединения", ctx.cluster(), c -> filter(c.getConnectionsShort(cid),
                    x -> pid.equals(x.getWorkingProcessId()))),
                sessions("Сеансы", ctx.cluster(), c -> filter(c.getSessions(cid),
                    x -> pid.equals(x.getWorkingProcessId())))));
    }

    private static <T> List<T> filter(List<T> list, java.util.function.Predicate<T> p) {
        List<T> r = new ArrayList<>();
        for (T t : list) if (p.test(t)) r.add(t);
        return r;
    }

    // ---------------- информационные базы ----------------

    private ConsoleNode infoBases() {
        return new ListNode<>("Информационные базы", ctx.cluster(), Columns.infoBases(), c -> {
            List<IInfoBaseInfoShort> list = c.getInfoBasesShort(cid);
            ctx.rememberInfoBases(list);
            return list;
        })
            .key(IInfoBaseInfoShort::getInfoBaseId)
            .icon(Icons.INFOBASES)
            .children(ib -> infoBaseNode(ib, IInfoBaseInfoShort::getName))
            .action("Создать…", Sel.NONE, (sel, n) -> createInfoBase(n::refresh))
            .action("Открыть в 1С", Sel.ONE, (sel, n) -> OneC.open(ctx, sel.get(0).getName(), false))
            .action("Конфигуратор", Sel.ONE, (sel, n) -> OneC.open(ctx, sel.get(0).getName(), true))
            .action("Строка подключения", Sel.ONE, (sel, n) -> OneC.copyConnectionString(ctx, sel.get(0).getName()))
            .action("Список баз (.v8i)…", Sel.MANY, (sel, n) -> OneC.saveV8i(ctx, sel))
            .action("Регламентные работы…", Sel.NONE, (sel, n) -> Maintenance.open(ctx, sel, n::refresh))
            .action("Изменить у нескольких…", Sel.MANY, (sel, n) -> BulkEdit.open(ctx, sel, n::refresh))
            .action("В избранное", Sel.MANY, (sel, n) -> {
                for (IInfoBaseInfoShort b : sel) addFavorite(b);
                Fx.status("Добавлено в избранное: " + sel.size());
            })
            .action("Наблюдать за входами", Sel.MANY, (sel, n) -> {
                for (IInfoBaseInfoShort b : sel) setWatched(b, true);
            })
            .action("Журнал входов", Sel.NONE, (sel, n) -> watchLog("Журнал входов и выходов — кластер «" + ctx.name + "»",
                e -> e.server().equals(ctx.conn.config().display()) && e.cluster().equals(ctx.name)))
            .defaultAction("Свойства…", (sel, n) -> editInfoBase(sel.get(0).getInfoBaseId(), sel.get(0).getName(), null, n::refresh))
            .action("Блокировка сеансов…", Sel.ONE, (sel, n) ->
                editInfoBase(sel.get(0).getInfoBaseId(), sel.get(0).getName(), SESSION_LOCK_PROPS, n::refresh))
            .action("Удалить…", Sel.ONE, (sel, n) -> dropInfoBase(sel.get(0), n::refresh));
    }

    private ObjectNode<IInfoBaseInfoShort> infoBaseNode(IInfoBaseInfoShort info, Function<IInfoBaseInfoShort, String> titleFn) {
        UUID ibid = info.getInfoBaseId();
        String ibName = info.getName();
        Scope ibs = ctx.infoBase(ibName);
        String address = ctx.conn.config().address();
        return new ObjectNode<IInfoBaseInfoShort>(ctx.cluster(), IInfoBaseInfoShort.class, info, ibid, titleFn)
            .reloader(c -> c.getInfoBaseShortInfo(cid, ibid))
            .action("Открыть в 1С", n -> OneC.open(ctx, ibName, false))
            .action("Конфигуратор", n -> OneC.open(ctx, ibName, true))
            .action("Строка подключения", n -> OneC.copyConnectionString(ctx, ibName))
            .action("Свойства…", n -> editInfoBase(ibid, ibName, null, n::reload))
            .action("Блокировка сеансов…", n -> editInfoBase(ibid, ibName, SESSION_LOCK_PROPS, n::reload))
            .action(() -> favorites != null && favorites.contains(address, cid, ibid) ? "Убрать из избранного" : "В избранное", n -> {
                if (favorites == null) return;
                if (favorites.contains(address, cid, ibid)) favorites.remove(address, cid, ibid);
                else addFavorite(n.item());
            })
            .action(() -> monitor != null && monitor.watch().contains(address, cid, ibid) ? "Не наблюдать" : "Наблюдать за входами",
                n -> setWatched(n.item(), monitor == null || !monitor.watch().contains(address, cid, ibid)))
            .action("Журнал входов", n -> watchLog("Журнал входов и выходов — «" + ibName + "»",
                e -> e.server().equals(ctx.conn.config().display()) && e.ib().equals(ibName)))
            .action("Удалить…", n -> dropInfoBase(n.item(), () -> {
                if (n.treeItem() != null && n.treeItem().getParent() instanceof ru.rasconsole.ui.tree.NodeTreeItem p) p.reload();
            }))
            .icon(Icons.INFOBASE)
            .sections(n -> List.of(
                sessions("Сеансы", ctx.cluster(), c -> c.getInfoBaseSessions(cid, ibid)),
                connections("Соединения", ibs, c -> c.getInfoBaseConnectionsShort(cid, ibid)),
                locks("Блокировки", c -> c.getInfoBaseLocks(cid, ibid))));
    }

    /** Шаблоны новых ИБ (задаются главным окном). */
    private static ru.rasconsole.config.IbTemplates templates;

    public static void setTemplates(ru.rasconsole.config.IbTemplates t) {
        templates = t;
    }

    private void createInfoBase(Runnable after) {
        InfoBaseInfo ib = new InfoBaseInfo(0);
        ib.setDbms("MSSQLServer");
        ib.setLocale("ru");
        ib.setDbServerName("localhost");
        // шаблон: СУБД, сервер, пользователь и пароль СУБД, язык, смещение дат
        if (templates != null && !templates.all().isEmpty()) {
            List<String> names = new ArrayList<>();
            names.add("(без шаблона)");
            names.addAll(templates.all().keySet());
            javafx.scene.control.ChoiceDialog<String> cd = Dialogs.prepare(new javafx.scene.control.ChoiceDialog<>(names.get(1), names),
                "Новая информационная база");
            cd.setHeaderText("Заполнить параметры СУБД из шаблона?");
            java.util.Optional<String> choice = cd.showAndWait();
            if (choice.isEmpty()) return;
            ru.rasconsole.config.IbTemplates.Template t = templates.all().get(choice.get());
            if (t != null) {
                ib.setDbms(t.dbms());
                ib.setDbServerName(t.dbServer());
                ib.setDbUser(t.dbUser());
                ib.setLocale(t.locale());
                ib.setDateOffset(t.dateOffset());
                ib.setLicenseDistributionAllowed(t.licenseDistribution());
                ib.setScheduledJobsDenied(t.jobsDenied());
                ru.rasconsole.ras.Credentials pwd = Fx.credentialStore() == null ? null : Fx.credentialStore().secret("ibtemplate|" + t.name());
                if (pwd != null) ib.setDbPassword(pwd.password());
            }
        }
        javafx.scene.control.CheckBox createDb = Dialogs.createDbCheckBox();
        javafx.scene.control.CheckBox saveTpl = new javafx.scene.control.CheckBox("Сохранить параметры СУБД как шаблон:");
        javafx.scene.control.TextField tplName = new javafx.scene.control.TextField();
        tplName.setPromptText("имя шаблона, например «PostgreSQL прод»");
        tplName.disableProperty().bind(saveTpl.selectedProperty().not());
        javafx.scene.layout.HBox tplRow = new javafx.scene.layout.HBox(8, saveTpl, tplName);
        javafx.scene.layout.HBox.setHgrow(tplName, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.VBox extra = new javafx.scene.layout.VBox(8, createDb, tplRow);
        if (Dialogs.editBean("Новая информационная база", ib, IInfoBaseInfo.class, NEW_IB_PROPS, true, extra)) {
            if (ib.getName() == null || ib.getName().isBlank()) {
                Dialogs.warn("Не указано имя информационной базы");
                return;
            }
            if (ib.getDbName() == null || ib.getDbName().isBlank()) ib.setDbName(ib.getName());
            if (saveTpl.isSelected() && !tplName.getText().isBlank() && templates != null) {
                String tn = tplName.getText().trim();
                templates.put(new ru.rasconsole.config.IbTemplates.Template(tn, ib.getDbms(), ib.getDbServerName(), ib.getDbUser(),
                    ib.getLocale(), ib.getDateOffset(), ib.getLicenseDistributionAllowed(), ib.isScheduledJobsDenied()));
                if (Fx.credentialStore() != null && Fx.credentialStore().available()) {
                    Fx.credentialStore().putSecret("ibtemplate|" + tn, new ru.rasconsole.ras.Credentials(ib.getDbUser(), ib.getDbPassword()));
                }
            }
            int mode = createDb.isSelected() ? 1 : 0;
            Fx.change(ctx.cluster().asSlow(), "Создание информационной базы «" + ib.getName() + "»",
                c -> c.createInfoBase(cid, ib, mode), id -> after.run());
        }
    }

    /** Загрузить полные свойства ИБ (нужна авторизация в ИБ), показать форму, сохранить. */
    private void editInfoBase(UUID ibid, String ibName, List<String> only, Runnable after) {
        Scope ibs = ctx.infoBase(ibName);
        Fx.call(ibs, "Свойства ИБ «" + ibName + "»", c -> c.getInfoBaseInfo(cid, ibid), info -> {
            String title = only == null ? "Информационная база «" + ibName + "»" : "Блокировка сеансов «" + ibName + "»";
            if (Dialogs.editBean(title, info, IInfoBaseInfo.class, only, true, null)) {
                Fx.change(ibs, "Сохранение ИБ «" + ibName + "»", RasFn.run(c -> c.updateInfoBase(cid, info)), v -> after.run());
            }
        });
    }

    private void dropInfoBase(IInfoBaseInfoShort ib, Runnable after) {
        Dialogs.dropInfoBaseMode(ib.getName()).ifPresent(mode -> {
            String what = mode == 1 ? "\nБаза данных на сервере СУБД будет УДАЛЕНА." : mode == 2 ? "\nБаза данных будет ОЧИЩЕНА." : "";
            if (danger("Удаление информационной базы", "Удалить «" + ib.getName() + "» из кластера?" + what)) {
                Fx.change(ctx.infoBase(ib.getName()).asSlow(), "Удаление ИБ «" + ib.getName() + "»",
                    RasFn.run(c -> c.dropInfoBase(cid, ib.getInfoBaseId(), mode)), v -> after.run());
            }
        });
    }

    // ---------------- избранное и наблюдение ----------------

    private static ru.rasconsole.config.Favorites favorites;
    private static ru.rasconsole.ui.Monitor monitor;

    /** Избранные базы (задаются главным окном). */
    public static void setFavorites(ru.rasconsole.config.Favorites f) {
        favorites = f;
    }

    /** Наблюдение: журнал входов, порог долгого вызова, уведомления о запланированных работах. */
    public static void setMonitor(ru.rasconsole.ui.Monitor m) {
        monitor = m;
        callLimitSec = () -> m.settings().callSec;
        dbProcLimitSec = () -> m.settings().dbProcSec;
        Scheduler.setNotifier(m::event);
    }

    private void addFavorite(IInfoBaseInfoShort b) {
        if (favorites == null) return;
        favorites.add(new ru.rasconsole.config.Favorites.Entry(ctx.conn.config().address(), cid, ctx.name,
            b.getInfoBaseId(), b.getName()));
    }

    private void setWatched(IInfoBaseInfoShort b, boolean on) {
        if (monitor == null) return;
        String address = ctx.conn.config().address();
        if (on) {
            monitor.watch().add(new ru.rasconsole.config.IbWatch.Entry(address, cid, ctx.name, b.getInfoBaseId(), b.getName()));
            Fx.status("Наблюдение за входами в «" + b.getName() + "» включено. Журнал: " + monitor.watch().logDir());
        } else {
            monitor.watch().remove(address, cid, b.getInfoBaseId());
            Fx.status("Наблюдение за «" + b.getName() + "» выключено");
        }
        monitor.watchChanged();
    }

    /** Журнал входов и выходов наблюдаемых баз. */
    public static void watchLog(String title, java.util.function.Predicate<ru.rasconsole.config.IbWatch.Event> filter) {
        if (monitor == null) return;
        List<ru.rasconsole.config.IbWatch.Event> rows = new ArrayList<>();
        for (ru.rasconsole.config.IbWatch.Event e : monitor.watch().read(20000)) if (filter.test(e)) rows.add(e);
        if (rows.isEmpty() && monitor.watch().isEmpty()) {
            Dialogs.info("Журнал входов", "Нет наблюдаемых баз. Включите наблюдение в контекстном меню базы: «Наблюдать за входами».\n"
                + "Сеансы проверяются при каждом фоновом опросе (интервал — в Настройках), журнал хранится в папке истории.");
            return;
        }
        Dialogs.table(title, rows, watchColumns());
    }

    private static List<Col<ru.rasconsole.config.IbWatch.Event>> watchColumns() {
        return List.of(
            Col.<ru.rasconsole.config.IbWatch.Event>of("Время", 140, ru.rasconsole.config.IbWatch.Event::time),
            Col.<ru.rasconsole.config.IbWatch.Event>of("Событие", 70, ru.rasconsole.config.IbWatch.Event::event),
            Col.<ru.rasconsole.config.IbWatch.Event>of("ИБ", 140, ru.rasconsole.config.IbWatch.Event::ib),
            Col.<ru.rasconsole.config.IbWatch.Event>of("Пользователь", 160, ru.rasconsole.config.IbWatch.Event::user),
            Col.<ru.rasconsole.config.IbWatch.Event>of("Компьютер", 130, ru.rasconsole.config.IbWatch.Event::host),
            Col.<ru.rasconsole.config.IbWatch.Event>of("Приложение", 130, ru.rasconsole.config.IbWatch.Event::app),
            Col.<ru.rasconsole.config.IbWatch.Event>of("Сеанс", 60, ru.rasconsole.config.IbWatch.Event::session),
            Col.<ru.rasconsole.config.IbWatch.Event>of("Начало сеанса", 140, ru.rasconsole.config.IbWatch.Event::startedAt),
            Col.<ru.rasconsole.config.IbWatch.Event>of("Сервер", 160, ru.rasconsole.config.IbWatch.Event::server),
            Col.<ru.rasconsole.config.IbWatch.Event>of("Кластер", 140, ru.rasconsole.config.IbWatch.Event::cluster));
    }

    /**
     * Узел избранной базы: та же база, что в дереве кластера, но в разделе «Избранное».
     * Адрес кластера для запуска 1С уточняется в фоне (до этого — адрес сервера RAS и порт 1541).
     */
    public static ConsoleNode favoriteNode(RasConnection conn, ru.rasconsole.config.Favorites.Entry e) {
        ClusterCtx ctx = new ClusterCtx(conn, e.clusterId(), e.clusterName());
        ctx.host = conn.config().host();
        ctx.mainPort = 1541;
        conn.submit(ru.rasconsole.ras.AuthKind.NONE, null, c -> c.getClusters()).thenAccept(list -> {
            for (IClusterInfo ci : list) {
                if (ci.getClusterId().equals(e.clusterId())) {
                    ctx.host = ci.getHostName();
                    ctx.mainPort = ci.getMainPort();
                    ctx.name = ci.getName();
                }
            }
        }).exceptionally(x -> null);
        com._1c.v8.ibis.admin.InfoBaseInfoShort info = new com._1c.v8.ibis.admin.InfoBaseInfoShort(e.ibId());
        info.setName(e.ibName());
        String where = conn.config().name() + " / " + e.clusterName();
        return new ClusterSections(ctx).infoBaseNode(info, ib -> ib.getName() + "  (" + where + ")")
            .action("Выше в избранном", n -> favorites.move(e, -1))
            .action("Ниже в избранном", n -> favorites.move(e, 1));
    }

    // ---------------- сеансы, соединения, блокировки ----------------

    private ListNode<ISessionInfo> sessions(String title, Scope scope, RasFn<List<ISessionInfo>> loader) {
        return sessions(title, scope, loader, Columns.sessions(ctx), null);
    }

    /** Порог «долгого вызова» для подсветки тяжёлых сеансов (задаётся из настроек наблюдения). */
    private static java.util.function.IntSupplier callLimitSec = () -> 60;

    public static void setCallLimit(java.util.function.IntSupplier sec) {
        callLimitSec = sec;
    }

    /** Порог «Захвачено СУБД (текущее)» для подсветки, с. */
    private static java.util.function.IntSupplier dbProcLimitSec = () -> 60;

    /** Превышен порог времени вызова или захвата СУБД. */
    private static boolean overLimit(ISessionInfo s) {
        int call = callLimitSec.getAsInt();
        int db = dbProcLimitSec.getAsInt();
        return call > 0 && s.getDurationCurrent() >= call * 1000L || db > 0 && s.getDbProcTook() >= db * 1000L;
    }

    /** Есть ли у сеанса нагрузка сейчас или за последние 5 минут. */
    private static boolean busy(ISessionInfo s) {
        return s.getDurationCurrent() > 0 || s.getCpuTimeCurrent() > 0 || s.getCpuTimeLast5Min() > 0
            || s.getDurationLast5Min() > 0 || s.getDbmsBytesLast5Min() > 0 || s.getDurationCurrentDbms() > 0
            || s.getDbProcTook() > 0;
    }

    /** Тяжёлые сеансы: только с нагрузкой, самые нагружающие — сверху. */
    private ConsoleNode heavySessions() {
        return sessions("Тяжёлые сеансы", ctx.cluster(), c -> {
            List<ISessionInfo> list = new ArrayList<>();
            for (ISessionInfo s : c.getSessions(cid)) if (busy(s)) list.add(s);
            // процессор за 5 минут плюс текущий вызов: и «давно грузит», и «завис прямо сейчас»
            list.sort(java.util.Comparator.comparingLong((ISessionInfo s) -> s.getCpuTimeLast5Min() + s.getDurationCurrent()
                + s.getDbProcTook()).reversed());
            return list;
        }, Columns.heavySessions(ctx), s -> LockChains.waitsFor(s) != 0 ? "row-blocked"
            : overLimit(s) ? "row-warning" : null)
            .autoRefresh(true)
            .icon(Icons.COUNTERS);
    }

    private ListNode<ISessionInfo> sessions(String title, Scope scope, RasFn<List<ISessionInfo>> loader,
                                            List<Col<ISessionInfo>> cols, Function<ISessionInfo, String> style) {
        // ключи сеансов-виновников блокировок из последней загрузки — для подсветки строк
        java.util.Set<String> blockers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ListNode<ISessionInfo> node = new ListNode<>(title, scope, cols, c -> {
            List<ISessionInfo> list = loader.apply(c);
            for (ISessionInfo s : list) {
                ctx.sessionNumbers.put(s.getSid(), s.getSessionId());
                ctx.ensureInfoBase(c, s.getInfoBaseId());
                ctx.ensureProcess(c, s.getWorkingProcessId());
                ctx.ensureConnection(c, s.getConnectionId());
            }
            java.util.Set<String> b = LockChains.blockers(list);
            blockers.retainAll(b);
            blockers.addAll(b);
            return list;
        })
            .key(ISessionInfo::getSid)
            .icon(Icons.SESSIONS)
            .rowStyle(style != null ? style : s -> LockChains.waitsFor(s) != 0 ? "row-blocked"
                : blockers.contains(LockChains.key(s.getInfoBaseId(), s.getSessionId())) ? "row-blocker" : null)
            .action("Завершить сеанс…", Sel.MANY, (sel, n) ->
                SessionKill.terminate(ctx, sel, "Завершить выбранные сеансы (" + sel.size() + ")", n::refresh))
            .action("Завершить отфильтрованные…", Sel.NONE, (sel, n) -> {
                List<ISessionInfo> vis = n.visible();
                String header = n.isFiltered()
                    ? "Завершить сеансы, найденные поиском (" + vis.size() + ")"
                    : "Поиск не задан: будут завершены ВСЕ сеансы списка (" + vis.size() + ")";
                SessionKill.terminate(ctx, vis, header, n::refresh);
            })
            .action("Прервать серверный вызов…", Sel.MANY, (sel, n) ->
                Dialogs.text("Прерывание серверного вызова", "Сообщение пользователю:", "Вызов прерван администратором")
                    .ifPresent(msg -> {
                        for (ISessionInfo s : sel) {
                            change(n, "Прерывание вызова сеанса " + s.getSessionId(),
                                RasFn.run(c -> c.interruptCurrentServerCall(cid, s.getSid(), msg)));
                        }
                    }))
            .action("Блокировки сеанса", Sel.ONE, (sel, n) -> {
                ISessionInfo s = sel.get(0);
                Fx.call(scope, "Блокировки сеанса", c -> {
                    List<IObjectLockInfo> list = c.getSessionLocks(cid, s.getInfoBaseId(), s.getSid());
                    for (IObjectLockInfo l : list) ctx.ensureConnection(c, l.getConnectionId());
                    return list;
                }, list -> Dialogs.table("Блокировки сеанса " + s.getSessionId(), list, Columns.locks(ctx)));
            })
            .defaultAction("Свойства", (sel, n) -> show("Сеанс " + sel.get(0).getSessionId(), sel.get(0), ISessionInfo.class));
        // «Сеансы (N)» в дереве — для обычных списков сеансов (у кластера, базы, рабочего процесса)
        return style == null ? node.countInTitle() : node;
    }

    private ConsoleNode connections(String title, Scope scope, RasFn<List<IInfoBaseConnectionShort>> loader) {
        return new ListNode<>(title, scope, Columns.connections(ctx), c -> {
            List<IInfoBaseConnectionShort> list = loader.apply(c);
            for (IInfoBaseConnectionShort x : list) {
                ctx.connNumbers.put(x.getInfoBaseConnectionId(), x.getConnId());
                ctx.ensureInfoBase(c, x.getInfoBaseId());
                ctx.ensureProcess(c, x.getWorkingProcessId());
            }
            return list;
        })
            .key(IInfoBaseConnectionShort::getInfoBaseConnectionId)
            .icon(Icons.CONNECTIONS)
            .action("Отключить…", Sel.MANY, (sel, n) -> {
                if (!danger("Отключение соединений", "Разорвать выбранные соединения (" + sel.size() + ")?")) return;
                for (IInfoBaseConnectionShort x : sel) {
                    change(n, ctx.infoBase(ctx.ibName(x.getInfoBaseId())), "Отключение соединения " + x.getConnId(),
                        RasFn.run(c -> c.disconnect(cid, x.getWorkingProcessId(), x.getInfoBaseConnectionId())));
                }
            })
            .action("Блокировки соединения", Sel.ONE, (sel, n) -> {
                IInfoBaseConnectionShort x = sel.get(0);
                Fx.call(scope, "Блокировки соединения", c -> c.getConnectionLocks(cid, x.getInfoBaseConnectionId()),
                    list -> Dialogs.table("Блокировки соединения " + x.getConnId(), list, Columns.locks(ctx)));
            })
            .defaultAction("Свойства", (sel, n) -> show("Соединение " + sel.get(0).getConnId(), sel.get(0), IInfoBaseConnectionShort.class));
    }

    private ConsoleNode locks(String title, RasFn<List<IObjectLockInfo>> loader) {
        return new ListNode<>(title, ctx.cluster(), Columns.locks(ctx), c -> {
            List<IObjectLockInfo> list = loader.apply(c);
            for (IObjectLockInfo l : list) {
                ctx.ensureSession(c, l.getSid());
                ctx.ensureConnection(c, l.getConnectionId());
            }
            return list;
        }).icon(Icons.LOCKS);
    }

    // ---------------- профили безопасности ----------------

    private ConsoleNode securityProfiles() {
        return new ListNode<>("Профили безопасности", ctx.cluster(), Columns.securityProfiles(), c -> c.getSecurityProfiles(cid))
            .key(ISecurityProfile::getSPName)
            .icon(Icons.PROFILES)
            .children(this::profileNode)
            .action("Создать…", Sel.NONE, (sel, n) -> {
                SecurityProfile p = new SecurityProfile();
                if (Dialogs.editBean("Новый профиль безопасности", p, ISecurityProfile.class, null, true, null)) {
                    change(n, "Создание профиля безопасности", RasFn.run(c -> c.createSecurityProfile(cid, p)));
                }
            })
            .defaultAction("Свойства…", (sel, n) -> {
                ISecurityProfile p = sel.get(0);
                if (Dialogs.editBean("Профиль безопасности «" + p.getSPName() + "»", p, ISecurityProfile.class, null, true, null)) {
                    change(n, "Изменение профиля безопасности", RasFn.run(c -> c.createSecurityProfile(cid, p)));
                }
            })
            .action("Удалить…", Sel.ONE, (sel, n) -> {
                String name = sel.get(0).getSPName();
                if (danger("Удаление профиля безопасности", "Удалить профиль «" + name + "»?")) {
                    change(n, "Удаление профиля безопасности", RasFn.run(c -> c.dropSecurityProfile(cid, name)));
                }
            });
    }

    private ConsoleNode profileNode(ISecurityProfile info) {
        String sp = info.getSPName();
        return new ObjectNode<ISecurityProfile>(ctx.cluster(), ISecurityProfile.class, info, sp, ISecurityProfile::getSPName)
            .reloader(c -> {
                for (ISecurityProfile p : c.getSecurityProfiles(cid)) if (sp.equals(p.getSPName())) return p;
                return null;
            })
            .saver("Профиль безопасности «" + sp + "»", p -> RasFn.run(c -> c.createSecurityProfile(cid, p)))
            .icon(Icons.PROFILE)
            .sections(n -> List.of(
                spList("Виртуальные каталоги", ISecurityProfileVirtualDirectory.class, Columns.virtualDirectories(),
                    () -> {
                        SecurityProfileVirtualDirectory v = new SecurityProfileVirtualDirectory();
                        v.setSPName(sp);
                        v.setAllowedRead(true);
                        return v;
                    },
                    c -> c.getSecurityProfileVirtualDirectories(cid, sp),
                    v -> RasFn.run(c -> c.createSecurityProfileVirtualDirectory(cid, v)),
                    v -> RasFn.run(c -> c.dropSecurityProfileVirtualDirectory(cid, sp, v.getAlias())),
                    ISecurityProfileVirtualDirectory::getAlias),
                spList("Разрешённые COM-классы", ISecurityProfileCOMClass.class, Columns.comClasses(),
                    () -> {
                        SecurityProfileCOMClass v = new SecurityProfileCOMClass();
                        v.setSPName(sp);
                        return v;
                    },
                    c -> c.getSecurityProfileComClasses(cid, sp),
                    v -> RasFn.run(c -> c.createSecurityProfileComClass(cid, v)),
                    v -> RasFn.run(c -> c.dropSecurityProfileComClass(cid, sp, v.getName())),
                    ISecurityProfileCOMClass::getName),
                spList("Внешние компоненты", ISecurityProfileAddIn.class, Columns.addIns(),
                    () -> {
                        SecurityProfileAddIn v = new SecurityProfileAddIn();
                        v.setSPName(sp);
                        return v;
                    },
                    c -> c.getSecurityProfileAddIns(cid, sp),
                    v -> RasFn.run(c -> c.createSecurityProfileAddIn(cid, v)),
                    v -> RasFn.run(c -> c.dropSecurityProfileAddIn(cid, sp, v.getName())),
                    ISecurityProfileAddIn::getName),
                spList("Внешние модули", ISecurityProfileExternalModule.class, Columns.externalModules(),
                    () -> {
                        SecurityProfileExternalModule v = new SecurityProfileExternalModule();
                        v.setSPName(sp);
                        return v;
                    },
                    c -> c.getSecurityProfileUnsafeExternalModules(cid, sp),
                    v -> RasFn.run(c -> c.createSecurityProfileUnsafeExternalModule(cid, v)),
                    v -> RasFn.run(c -> c.dropSecurityProfileUnsafeExternalModule(cid, sp, v.getName())),
                    ISecurityProfileExternalModule::getName),
                spList("Разрешённые приложения", ISecurityProfileApplication.class, Columns.applications(),
                    () -> {
                        SecurityProfileApplication v = new SecurityProfileApplication();
                        v.setSPName(sp);
                        return v;
                    },
                    c -> c.getSecurityProfileApplications(cid, sp),
                    v -> RasFn.run(c -> c.createSecurityProfileApplication(cid, v)),
                    v -> RasFn.run(c -> c.dropSecurityProfileApplication(cid, sp, v.getName())),
                    ISecurityProfileApplication::getName),
                spList("Интернет-ресурсы", ISecurityProfileInternetResource.class, Columns.internetResources(),
                    () -> {
                        SecurityProfileInternetResource v = new SecurityProfileInternetResource();
                        v.setSPName(sp);
                        v.setProtocol("HTTPS");
                        v.setPort(443);
                        return v;
                    },
                    c -> c.getSecurityProfileInternetResources(cid, sp),
                    v -> RasFn.run(c -> c.createSecurityProfileInternetResource(cid, v)),
                    v -> RasFn.run(c -> c.dropSecurityProfileInternetResource(cid, sp, v.getName())),
                    ISecurityProfileInternetResource::getName)));
    }

    /** Список разрешений внутри профиля безопасности (одинаковая схема для всех шести видов). */
    private <T> ConsoleNode spList(String title, Class<T> type, List<Col<T>> cols, java.util.function.Supplier<T> factory,
                                   RasFn<List<T>> loader, Function<T, RasFn<?>> create, Function<T, RasFn<?>> drop,
                                   Function<T, Object> key) {
        return new ListNode<>(title, ctx.cluster(), cols, loader)
            .key(key)
            .icon(Icons.FOLDER)
            .action("Добавить…", Sel.NONE, (sel, n) -> {
                T v = factory.get();
                if (Dialogs.editBean(title + ": новый элемент", v, type, null, true, null)) {
                    change(n, title + ": добавление", create.apply(v));
                }
            })
            .defaultAction("Изменить…", (sel, n) -> {
                T v = sel.get(0);
                if (Dialogs.editBean(title, v, type, null, true, null)) {
                    change(n, title + ": изменение", create.apply(v));
                }
            })
            .action("Удалить", Sel.MANY, (sel, n) -> {
                if (!danger(title, "Удалить выбранные элементы (" + sel.size() + ")?")) return;
                for (T v : sel) change(n, title + ": удаление", drop.apply(v));
            });
    }

    // ---------------- счётчики и ограничения ресурсов ----------------

    private ConsoleNode counters() {
        return new ListNode<>("Счётчики потребления ресурсов", ctx.cluster(), Columns.counters(),
            c -> c.getResourceConsumptionCounters(cid))
            .key(IResourceConsumptionCounter::getName)
            .icon(Icons.COUNTERS)
            .action("Добавить…", Sel.NONE, (sel, n) -> {
                ResourceConsumptionCounter rc = new ResourceConsumptionCounter();
                rc.setFilterType(2);
                if (Dialogs.editBean("Новый счётчик потребления ресурсов", rc, IResourceConsumptionCounter.class, null, true, null)) {
                    change(n, "Создание счётчика", RasFn.run(c -> c.regResourceConsumptionCounter(cid, rc)));
                }
            })
            .defaultAction("Свойства…", (sel, n) -> {
                IResourceConsumptionCounter rc = sel.get(0);
                if (Dialogs.editBean("Счётчик «" + rc.getName() + "»", rc, IResourceConsumptionCounter.class, null, true, null)) {
                    change(n, "Изменение счётчика", RasFn.run(c -> c.regResourceConsumptionCounter(cid, rc)));
                }
            })
            .action("Текущие значения", Sel.ONE, (sel, n) -> counterValues(sel.get(0).getName(), false))
            .action("Накопленные значения", Sel.ONE, (sel, n) -> counterValues(sel.get(0).getName(), true))
            .action("Сбросить накопленные", Sel.ONE, (sel, n) -> {
                String name = sel.get(0).getName();
                if (danger("Счётчик «" + name + "»", "Сбросить накопленные значения?")) {
                    change(n, "Сброс накопленных значений",
                        RasFn.run(c -> c.clearResourceConsumptionCounterAccumulatedValues(cid, name, "")));
                }
            })
            .action("Удалить", Sel.MANY, (sel, n) -> {
                if (!danger("Удаление счётчиков", "Удалить выбранные счётчики (" + sel.size() + ")?")) return;
                for (IResourceConsumptionCounter rc : sel) {
                    change(n, "Удаление счётчика", RasFn.run(c -> c.unregResourceConsumptionCounter(cid, rc.getName())));
                }
            });
    }

    private void counterValues(String name, boolean accumulated) {
        RasFn<List<IResourceConsumptionCounterValue>> fn = accumulated
            ? c -> c.getResourceConsumptionCounterAccumulatedValues(cid, name, "")
            : c -> c.getResourceConsumptionCounterValues(cid, name, "");
        String title = (accumulated ? "Накопленные значения" : "Текущие значения") + " счётчика «" + name + "»";
        Fx.call(ctx.cluster(), title, fn, list -> Dialogs.table(title, list, Columns.counterValues()));
    }

    private ConsoleNode limits() {
        return new ListNode<>("Ограничения потребления ресурсов", ctx.cluster(), Columns.limits(),
            c -> c.getResourceConsumptionLimits(cid))
            .key(IResourceConsumptionLimit::getName)
            .icon(Icons.LIMITS)
            .action("Добавить…", Sel.NONE, (sel, n) -> {
                ResourceConsumptionLimit l = new ResourceConsumptionLimit();
                if (Dialogs.editBean("Новое ограничение потребления ресурсов", l, IResourceConsumptionLimit.class, null, true, null)) {
                    change(n, "Создание ограничения", RasFn.run(c -> c.regResourceConsumptionLimit(cid, l)));
                }
            })
            .defaultAction("Свойства…", (sel, n) -> {
                IResourceConsumptionLimit l = sel.get(0);
                if (Dialogs.editBean("Ограничение «" + l.getName() + "»", l, IResourceConsumptionLimit.class, null, true, null)) {
                    change(n, "Изменение ограничения", RasFn.run(c -> c.regResourceConsumptionLimit(cid, l)));
                }
            })
            .action("Удалить", Sel.MANY, (sel, n) -> {
                if (!danger("Удаление ограничений", "Удалить выбранные ограничения (" + sel.size() + ")?")) return;
                for (IResourceConsumptionLimit l : sel) {
                    change(n, "Удаление ограничения", RasFn.run(c -> c.unregResourceConsumptionLimit(cid, l.getName())));
                }
            });
    }
}
