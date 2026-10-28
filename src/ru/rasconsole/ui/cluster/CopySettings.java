package ru.rasconsole.ui.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com._1c.v8.ibis.admin.IAgentAdminConnection;
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

import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.Scope;
import ru.rasconsole.ui.table.Col;

/**
 * Копирование настроек кластера в другой кластер (в том числе на другом сервере):
 * профили безопасности со всеми разрешениями, счётчики и ограничения потребления ресурсов.
 * Объекты с теми же именами в целевом кластере перезаписываются.
 */
final class CopySettings {

    private CopySettings() {
    }

    /** Все серверы консоли — чтобы предложить целевой кластер на любом из них. */
    static Supplier<List<RasConnection>> connections = List::of;

    private record Target(RasConnection conn, IClusterInfo cluster) {
        @Override
        public String toString() {
            return conn.config().display() + " / " + cluster.getName() + " (" + cluster.getHostName() + ":"
                + cluster.getMainPort() + ")";
        }
    }

    /** Что скопировать (читается из исходного кластера). */
    private static final class Snapshot {
        final List<ISecurityProfile> profiles = new ArrayList<>();
        final List<Object> permissions = new ArrayList<>();
        final List<IResourceConsumptionCounter> counters = new ArrayList<>();
        final List<IResourceConsumptionLimit> limits = new ArrayList<>();
    }

    private record Line(String what, String name, String result) {
    }

    static void open(ClusterCtx src) {
        Fx.status("Поиск кластеров на серверах…");
        List<CompletableFuture<List<Target>>> fs = new ArrayList<>();
        for (RasConnection conn : connections.get()) {
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
            Platform.runLater(() -> ask(src, targets));
        });
    }

    private static void ask(ClusterCtx src, List<Target> targets) {
        Fx.status("Готово");
        if (targets.isEmpty()) {
            Dialogs.info("Копирование настроек", "Не найдено других кластеров. Добавьте сервер и проверьте связь с ним.");
            return;
        }
        Dialog<ButtonType> d = Dialogs.prepare(new Dialog<>(), "Копирование настроек кластера");
        d.setHeaderText("Из кластера «" + src.name + "» (" + src.conn.config().display() + ")");
        ComboBox<Target> target = new ComboBox<>();
        target.getItems().addAll(targets);
        target.getSelectionModel().select(0);
        target.setMaxWidth(Double.MAX_VALUE);
        CheckBox profiles = new CheckBox("Профили безопасности (со всеми разрешениями)");
        CheckBox counters = new CheckBox("Счётчики потребления ресурсов");
        CheckBox limits = new CheckBox("Ограничения потребления ресурсов");
        profiles.setSelected(true);
        counters.setSelected(true);
        limits.setSelected(true);
        VBox box = new VBox(8, new Label("Целевой кластер:"), target, profiles, counters, limits,
            new Label("Объекты с такими же именами в целевом кластере будут перезаписаны.\n"
                + "Требования назначения не копируются: они привязаны к рабочим серверам."));
        box.setPadding(new Insets(10));
        box.setPrefWidth(620);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        Target t = target.getValue();
        boolean p = profiles.isSelected();
        boolean c = counters.isSelected();
        boolean l = limits.isSelected();
        if (!p && !c && !l) return;
        if (!Dialogs.confirmDanger(t.conn().config(), "Копирование настроек", "Записать настройки в кластер «"
            + t.cluster().getName() + "» на " + t.conn().config().display() + "?")) {
            return;
        }
        // 1) прочитать источник
        Fx.call(src.cluster(), "Чтение настроек кластера «" + src.name + "»", api -> {
            Snapshot s = new Snapshot();
            if (p) {
                for (ISecurityProfile sp : api.getSecurityProfiles(src.clusterId)) {
                    s.profiles.add(sp);
                    String n = sp.getSPName();
                    s.permissions.addAll(api.getSecurityProfileVirtualDirectories(src.clusterId, n));
                    s.permissions.addAll(api.getSecurityProfileComClasses(src.clusterId, n));
                    s.permissions.addAll(api.getSecurityProfileAddIns(src.clusterId, n));
                    s.permissions.addAll(api.getSecurityProfileUnsafeExternalModules(src.clusterId, n));
                    s.permissions.addAll(api.getSecurityProfileApplications(src.clusterId, n));
                    s.permissions.addAll(api.getSecurityProfileInternetResources(src.clusterId, n));
                }
            }
            if (c) s.counters.addAll(api.getResourceConsumptionCounters(src.clusterId));
            if (l) s.limits.addAll(api.getResourceConsumptionLimits(src.clusterId));
            return s;
        }, snap -> write(src, t, snap));
    }

    /** 2) записать в целевой кластер (каждый объект отдельно, ошибки — в отчёт). */
    private static void write(ClusterCtx src, Target t, Snapshot snap) {
        UUID cid = t.cluster().getClusterId();
        Scope scope = new Scope(t.conn(), AuthKind.CLUSTER, cid, t.cluster().getName());
        Fx.call(scope, "Запись настроек в кластер «" + t.cluster().getName() + "»", api -> {
            List<Line> lines = new ArrayList<>();
            for (ISecurityProfile sp : snap.profiles) {
                lines.add(apply("Профиль безопасности", sp.getSPName(), () -> api.createSecurityProfile(cid, sp)));
            }
            for (Object o : snap.permissions) {
                if (o instanceof ISecurityProfileVirtualDirectory x) {
                    lines.add(apply("Виртуальный каталог", x.getSPName() + " / " + x.getAlias(),
                        () -> api.createSecurityProfileVirtualDirectory(cid, x)));
                } else if (o instanceof ISecurityProfileCOMClass x) {
                    lines.add(apply("COM-класс", x.getSPName() + " / " + x.getName(), () -> api.createSecurityProfileComClass(cid, x)));
                } else if (o instanceof ISecurityProfileAddIn x) {
                    lines.add(apply("Внешняя компонента", x.getSPName() + " / " + x.getName(), () -> api.createSecurityProfileAddIn(cid, x)));
                } else if (o instanceof ISecurityProfileExternalModule x) {
                    lines.add(apply("Внешний модуль", x.getSPName() + " / " + x.getName(),
                        () -> api.createSecurityProfileUnsafeExternalModule(cid, x)));
                } else if (o instanceof ISecurityProfileApplication x) {
                    lines.add(apply("Приложение", x.getSPName() + " / " + x.getName(), () -> api.createSecurityProfileApplication(cid, x)));
                } else if (o instanceof ISecurityProfileInternetResource x) {
                    lines.add(apply("Интернет-ресурс", x.getSPName() + " / " + x.getName(),
                        () -> api.createSecurityProfileInternetResource(cid, x)));
                }
            }
            // счётчики раньше ограничений: ограничение ссылается на счётчик
            for (IResourceConsumptionCounter x : snap.counters) {
                lines.add(apply("Счётчик ресурсов", x.getName(), () -> api.regResourceConsumptionCounter(cid, x)));
            }
            for (IResourceConsumptionLimit x : snap.limits) {
                lines.add(apply("Ограничение ресурсов", x.getName(), () -> api.regResourceConsumptionLimit(cid, x)));
            }
            return lines;
        }, lines -> {
            long errors = lines.stream().filter(x -> !x.result().equals("OK")).count();
            Fx.audit(scope, "Копирование настроек из «" + src.name + "» (" + src.conn.config().display() + ")",
                "объектов " + lines.size() + ", ошибок " + errors);
            Dialogs.table("Копирование настроек: «" + src.name + "» → «" + t.cluster().getName() + "»", lines, List.of(
                Col.<Line>of("Объект", 180, Line::what),
                Col.<Line>of("Имя", 260, Line::name),
                Col.<Line>of("Результат", 380, Line::result)));
        });
    }

    private static Line apply(String what, String name, Runnable r) {
        try {
            r.run();
            return new Line(what, name, "OK");
        } catch (RuntimeException e) {
            return new Line(what, name, "ошибка: " + Fmt.rootMessage(e));
        }
    }
}
