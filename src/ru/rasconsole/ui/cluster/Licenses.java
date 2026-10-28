package ru.rasconsole.ui.cluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com._1c.v8.ibis.admin.ILicenseInfo;
import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;

import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Icons;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.tree.ListNode;
import ru.rasconsole.ui.tree.ListNode.Sel;

/** Раздел «Лицензии»: какие лицензии заняты сеансами и рабочими процессами кластера. */
final class Licenses {

    private Licenses() {
    }

    /** Одна выданная лицензия: кому (сеанс или рабочий процесс) и какая. */
    record Row(String owner, ISessionInfo session, IWorkingProcessInfo process, ILicenseInfo license) {
    }

    /** Сводка по ключу/серии. */
    record Summary(String series, String presentation, boolean net, int used, int maxUsers) {
    }

    static ListNode<Row> node(ClusterCtx ctx) {
        List<Col<Row>> cols = List.of(
            Col.<Row>of("Кому выдана", 150, Row::owner),
            Col.<Row>of("ИБ", 140, r -> r.session() == null ? "" : ctx.ibName(r.session().getInfoBaseId())),
            Col.<Row>of("Пользователь", 160, r -> r.session() == null ? "" : r.session().getUserName()),
            Col.<Row>of("Компьютер", 130, r -> r.session() != null ? r.session().getHost() : r.process().getHostName()),
            Col.<Row>of("Приложение", 130, r -> r.session() == null ? "" : Fmt.app(r.session().getAppId())),
            Col.<Row>of("Лицензия", 260, r -> r.license().getShortPresentation()),
            Col.<Row>of("Серия ключа / номер комплекта", 200, r -> r.license().getSeries()),
            Col.<Row>of("Выдана сервером", r -> r.license().isIssuedByServer()),
            Col.<Row>of("Сетевой ключ", r -> r.license().isNet()),
            Col.<Row>of("Макс. пользователей", r -> r.license().getMaxUsersCur()),
            Col.<Row>of("Менеджер лицензий", 160, r -> r.license().getRmngrAddress() == null ? ""
                : r.license().getRmngrAddress() + ":" + r.license().getRmngrPort()),
            Col.<Row>of("Полное описание", 360, r -> r.license().getFullPresentation()));
        ListNode<Row> node = new ListNode<>("Лицензии", ctx.cluster(), cols, c -> {
            List<Row> rows = new ArrayList<>();
            for (ISessionInfo s : c.getSessions(ctx.clusterId)) {
                ctx.ensureInfoBase(c, s.getInfoBaseId());
                if (s.getLicenses() == null) continue;
                for (ILicenseInfo l : s.getLicenses()) rows.add(new Row("Сеанс " + s.getSessionId(), s, null, l));
            }
            for (IWorkingProcessInfo p : c.getWorkingProcesses(ctx.clusterId)) {
                if (p.getLicense() == null) continue;
                for (ILicenseInfo l : p.getLicense()) {
                    rows.add(new Row("Процесс " + p.getHostName() + ":" + p.getMainPort(), null, p, l));
                }
            }
            return rows;
        });
        return node
            .icon(Icons.LICENSES)
            .action("Сводка по ключам", Sel.NONE, (sel, n) -> {
                List<Row> rows = n.visible();
                Map<String, Summary> m = new LinkedHashMap<>();
                for (Row r : rows) {
                    ILicenseInfo l = r.license();
                    String k = l.getSeries() + "|" + l.getShortPresentation();
                    Summary prev = m.get(k);
                    m.put(k, new Summary(l.getSeries(), l.getShortPresentation(), l.isNet(),
                        (prev == null ? 0 : prev.used()) + 1, Math.max(prev == null ? 0 : prev.maxUsers(), l.getMaxUsersCur())));
                }
                Dialogs.table("Сводка по лицензиям — кластер «" + ctx.name + "»", new ArrayList<>(m.values()), List.of(
                    Col.<Summary>of("Серия / номер комплекта", 220, Summary::series),
                    Col.<Summary>of("Лицензия", 300, Summary::presentation),
                    Col.<Summary>of("Сетевой ключ", Summary::net),
                    Col.<Summary>of("Занято", Summary::used),
                    Col.<Summary>of("Максимум по ключу", Summary::maxUsers)));
            })
            .defaultAction("Свойства сеанса", (sel, n) -> {
                Row r = sel.get(0);
                if (r.session() != null) {
                    Dialogs.editBean("Сеанс " + r.session().getSessionId(), r.session(), ISessionInfo.class, null, false, null);
                } else {
                    Dialogs.editBean("Рабочий процесс", r.process(), IWorkingProcessInfo.class, null, false, null);
                }
            });
    }
}
