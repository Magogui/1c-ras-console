package ru.rasconsole.ui.cluster;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com._1c.v8.ibis.admin.IAgentAdminConnection;
import com._1c.v8.ibis.admin.IInfoBaseConnectionShort;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;

import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ui.Scope;

/**
 * Контекст кластера: соединение, ID и справочники «ID → имя» для таблиц
 * (имя ИБ у сеанса, адрес процесса у соединения, номер сеанса у блокировки…).
 * Справочники дочитываются в потоке соединения, только если встретился неизвестный ID.
 */
public final class ClusterCtx {

    public static final UUID ZERO = new UUID(0, 0);

    final RasConnection conn;
    final UUID clusterId;
    volatile String name;
    /** Адрес кластера (менеджера) для строки подключения клиентов: host и порт, обычно 1541. */
    volatile String host = "";
    volatile int mainPort;

    final Map<UUID, String> ibNames = new ConcurrentHashMap<>();
    final Map<UUID, String> processNames = new ConcurrentHashMap<>();
    final Map<UUID, Integer> connNumbers = new ConcurrentHashMap<>();
    final Map<UUID, Integer> sessionNumbers = new ConcurrentHashMap<>();

    ClusterCtx(RasConnection conn, UUID clusterId, String name) {
        this.conn = conn;
        this.clusterId = clusterId;
        this.name = name;
    }

    Scope cluster() {
        return new Scope(conn, AuthKind.CLUSTER, clusterId, name);
    }

    Scope infoBase(String ibName) {
        return new Scope(conn, AuthKind.INFOBASE, clusterId, ibName);
    }

    // ---- справочники (вызываются в потоке соединения) ----

    void rememberInfoBases(Collection<IInfoBaseInfoShort> list) {
        for (IInfoBaseInfoShort ib : list) ibNames.put(ib.getInfoBaseId(), ib.getName());
    }

    void rememberProcesses(Collection<IWorkingProcessInfo> list) {
        for (IWorkingProcessInfo p : list) processNames.put(p.getWorkingProcessId(), p.getHostName() + ":" + p.getMainPort());
    }

    void ensureInfoBase(IAgentAdminConnection c, UUID id) {
        if (isSet(id) && !ibNames.containsKey(id)) rememberInfoBases(c.getInfoBasesShort(clusterId));
    }

    void ensureProcess(IAgentAdminConnection c, UUID id) {
        if (isSet(id) && !processNames.containsKey(id)) rememberProcesses(c.getWorkingProcesses(clusterId));
    }

    void ensureConnection(IAgentAdminConnection c, UUID id) {
        if (isSet(id) && !connNumbers.containsKey(id)) {
            for (IInfoBaseConnectionShort x : c.getConnectionsShort(clusterId)) {
                connNumbers.put(x.getInfoBaseConnectionId(), x.getConnId());
            }
        }
    }

    void ensureSession(IAgentAdminConnection c, UUID id) {
        if (isSet(id) && !sessionNumbers.containsKey(id)) {
            for (ISessionInfo s : c.getSessions(clusterId)) sessionNumbers.put(s.getSid(), s.getSessionId());
        }
    }

    // ---- отображение (FX-поток) ----

    String ibName(UUID id) {
        if (!isSet(id)) return "";
        return ibNames.getOrDefault(id, id.toString());
    }

    String processName(UUID id) {
        if (!isSet(id)) return "";
        return processNames.getOrDefault(id, id.toString());
    }

    Object connNumber(UUID id) {
        if (!isSet(id)) return null;
        Integer n = connNumbers.get(id);
        return n != null ? n : id.toString();
    }

    Object sessionNumber(UUID id) {
        if (!isSet(id)) return null;
        Integer n = sessionNumbers.get(id);
        return n != null ? n : id.toString();
    }

    static boolean isSet(UUID id) {
        return id != null && !ZERO.equals(id);
    }
}
