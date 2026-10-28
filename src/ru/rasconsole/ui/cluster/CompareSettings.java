package ru.rasconsole.ui.cluster;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.scene.control.ChoiceDialog;

import com._1c.v8.ibis.admin.IAgentAdminConnection;
import com._1c.v8.ibis.admin.IAssignmentRuleInfo;
import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IResourceConsumptionCounter;
import com._1c.v8.ibis.admin.IResourceConsumptionLimit;
import com._1c.v8.ibis.admin.ISecurityProfile;
import com._1c.v8.ibis.admin.ISecurityProfileAddIn;
import com._1c.v8.ibis.admin.ISecurityProfileApplication;
import com._1c.v8.ibis.admin.ISecurityProfileCOMClass;
import com._1c.v8.ibis.admin.ISecurityProfileExternalModule;
import com._1c.v8.ibis.admin.ISecurityProfileInternetResource;
import com._1c.v8.ibis.admin.ISecurityProfileVirtualDirectory;
import com._1c.v8.ibis.admin.IWorkingServerInfo;

import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ras.RasFn;
import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.Scope;
import ru.rasconsole.ui.form.Labels;
import ru.rasconsole.ui.table.Col;

/**
 * Сравнение настроек двух кластеров: параметры кластера, рабочие серверы, требования назначения,
 * профили безопасности с разрешениями, счётчики и ограничения ресурсов.
 */
final class CompareSettings {

    private CompareSettings() {
    }

    private record Target(RasConnection conn, IClusterInfo cluster) {
        @Override
        public String toString() {
            return conn.config().display() + " / " + cluster.getName() + " (" + cluster.getHostName() + ":" + cluster.getMainPort() + ")";
        }
    }

    /** Строка результата. */
    record Diff(String section, String object, String what, String a, String b) {
    }

    /** Раздел → объект → свойство → значение. */
    private static final class Snapshot extends TreeMap<String, Map<String, Map<String, String>>> {
        void add(String section, String object, Object bean, Class<?> type) {
            computeIfAbsent(section, k -> new TreeMap<>()).put(object, props(bean, type));
        }
    }

    static void open(ClusterCtx src) {
        Fx.status("Поиск кластеров на серверах…");
        List<CompletableFuture<List<Target>>> fs = new ArrayList<>();
        for (RasConnection conn : CopySettings.connections.get()) {
            fs.add(conn.submit(AuthKind.NONE, null, IAgentAdminConnection::getClusters).thenApply(list -> {
                List<Target> r = new ArrayList<>();
                for (IClusterInfo ci : list) {
                    if (!(conn == src.conn && ci.getClusterId().equals(src.clusterId))) r.add(new Target(conn, ci));
                }
                return r;
            }).exceptionally(e -> List.of()));
        }
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).whenComplete((v, e) -> {
            List<Target> targets = new ArrayList<>();
            for (CompletableFuture<List<Target>> f : fs) targets.addAll(f.getNow(List.of()));
            Platform.runLater(() -> choose(src, targets));
        });
    }

    private static void choose(ClusterCtx src, List<Target> targets) {
        Fx.status("Готово");
        if (targets.isEmpty()) {
            Dialogs.info("Сравнение настроек", "Не найдено других кластеров для сравнения.");
            return;
        }
        ChoiceDialog<Target> d = Dialogs.prepare(new ChoiceDialog<>(targets.get(0), targets), "Сравнение настроек");
        d.setHeaderText("Сравнить кластер «" + src.name + "» с:");
        d.showAndWait().ifPresent(t -> {
            Scope a = src.cluster();
            Scope b = new Scope(t.conn(), AuthKind.CLUSTER, t.cluster().getClusterId(), t.cluster().getName());
            Fx.call(a, "Чтение настроек «" + src.name + "»", read(src.clusterId), snapA ->
                Fx.call(b, "Чтение настроек «" + t.cluster().getName() + "»", read(t.cluster().getClusterId()), snapB ->
                    show(src.name + " (" + src.conn.config().display() + ")",
                        t.cluster().getName() + " (" + t.conn().config().display() + ")", snapA, snapB)));
        });
    }

    private static RasFn<Snapshot> read(UUID cid) {
        return c -> {
            Snapshot s = new Snapshot();
            s.add("Параметры кластера", "кластер", c.getClusterInfo(cid), IClusterInfo.class);
            for (IWorkingServerInfo ws : c.getWorkingServers(cid)) {
                s.add("Рабочий сервер", ws.getName(), ws, IWorkingServerInfo.class);
                List<IAssignmentRuleInfo> rules = c.getAssignmentRules(cid, ws.getWorkingServerId());
                for (int i = 0; i < rules.size(); i++) {
                    IAssignmentRuleInfo r = rules.get(i);
                    s.add("Требование назначения", ws.getName() + " / " + Fmt.format(r.getObjectType()) + " / "
                        + Fmt.format(r.getInfoBaseName()) + " / " + Fmt.format(r.getApplicationExt()), r, IAssignmentRuleInfo.class);
                    s.get("Требование назначения").get(ws.getName() + " / " + Fmt.format(r.getObjectType()) + " / "
                        + Fmt.format(r.getInfoBaseName()) + " / " + Fmt.format(r.getApplicationExt())).put("Позиция", Integer.toString(i + 1));
                }
            }
            for (ISecurityProfile p : c.getSecurityProfiles(cid)) {
                String n = p.getSPName();
                s.add("Профиль безопасности", n, p, ISecurityProfile.class);
                for (ISecurityProfileVirtualDirectory x : c.getSecurityProfileVirtualDirectories(cid, n)) {
                    s.add("Профиль: виртуальный каталог", n + " / " + x.getAlias(), x, ISecurityProfileVirtualDirectory.class);
                }
                for (ISecurityProfileCOMClass x : c.getSecurityProfileComClasses(cid, n)) {
                    s.add("Профиль: COM-класс", n + " / " + x.getName(), x, ISecurityProfileCOMClass.class);
                }
                for (ISecurityProfileAddIn x : c.getSecurityProfileAddIns(cid, n)) {
                    s.add("Профиль: внешняя компонента", n + " / " + x.getName(), x, ISecurityProfileAddIn.class);
                }
                for (ISecurityProfileExternalModule x : c.getSecurityProfileUnsafeExternalModules(cid, n)) {
                    s.add("Профиль: внешний модуль", n + " / " + x.getName(), x, ISecurityProfileExternalModule.class);
                }
                for (ISecurityProfileApplication x : c.getSecurityProfileApplications(cid, n)) {
                    s.add("Профиль: приложение", n + " / " + x.getName(), x, ISecurityProfileApplication.class);
                }
                for (ISecurityProfileInternetResource x : c.getSecurityProfileInternetResources(cid, n)) {
                    s.add("Профиль: интернет-ресурс", n + " / " + x.getName(), x, ISecurityProfileInternetResource.class);
                }
            }
            for (IResourceConsumptionCounter x : c.getResourceConsumptionCounters(cid)) {
                s.add("Счётчик ресурсов", x.getName(), x, IResourceConsumptionCounter.class);
            }
            for (IResourceConsumptionLimit x : c.getResourceConsumptionLimits(cid)) {
                s.add("Ограничение ресурсов", x.getName(), x, IResourceConsumptionLimit.class);
            }
            return s;
        };
    }

    /** Свойства объекта по getter'ам интерфейса (идентификаторы и адреса не сравниваются). */
    private static Map<String, String> props(Object bean, Class<?> type) {
        Map<String, String> r = new TreeMap<>();
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() == UUID.class) continue;
            String n = m.getName();
            String p;
            if (n.startsWith("get") && n.length() > 3) p = n.substring(3);
            else if (n.startsWith("is") && n.length() > 2) p = n.substring(2);
            else continue;
            String prop = p.length() > 1 && Character.isUpperCase(p.charAt(1)) ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
            // не сравниваем то, что естественно различается между кластерами
            if (prop.equals("hostName") || prop.equals("mainPort") || prop.equals("clusterMainPort") || prop.equals("name")
                && type == IClusterInfo.class) {
                continue;
            }
            try {
                Object v = m.invoke(bean);
                r.put(Labels.label(prop), Fmt.format(v));
            } catch (ReflectiveOperationException e) {
                r.put(Labels.label(prop), "?");
            }
        }
        return r;
    }

    private static void show(String nameA, String nameB, Snapshot a, Snapshot b) {
        List<Diff> diffs = new ArrayList<>();
        java.util.Set<String> sections = new java.util.TreeSet<>(a.keySet());
        sections.addAll(b.keySet());
        for (String sec : sections) {
            Map<String, Map<String, String>> oa = a.getOrDefault(sec, Map.of());
            Map<String, Map<String, String>> ob = b.getOrDefault(sec, Map.of());
            java.util.Set<String> objects = new java.util.TreeSet<>(oa.keySet());
            objects.addAll(ob.keySet());
            for (String o : objects) {
                Map<String, String> pa = oa.get(o);
                Map<String, String> pb = ob.get(o);
                if (pa == null) {
                    diffs.add(new Diff(sec, o, "только во втором", "—", "есть"));
                } else if (pb == null) {
                    diffs.add(new Diff(sec, o, "только в первом", "есть", "—"));
                } else {
                    java.util.Set<String> keys = new java.util.TreeSet<>(pa.keySet());
                    keys.addAll(pb.keySet());
                    for (String k : keys) {
                        String va = pa.getOrDefault(k, "");
                        String vb = pb.getOrDefault(k, "");
                        if (!va.equals(vb)) diffs.add(new Diff(sec, o, k, va, vb));
                    }
                }
            }
        }
        if (diffs.isEmpty()) {
            Dialogs.info("Сравнение настроек", "Настройки кластеров совпадают:\n" + nameA + "\n" + nameB);
            return;
        }
        Dialogs.table("Различия: 1) " + nameA + "   2) " + nameB, diffs, List.of(
            Col.<Diff>of("Раздел", 200, Diff::section),
            Col.<Diff>of("Объект", 240, Diff::object),
            Col.<Diff>of("Свойство / различие", 260, Diff::what),
            Col.<Diff>of("В первом", 200, Diff::a),
            Col.<Diff>of("Во втором", 200, Diff::b)));
    }
}
