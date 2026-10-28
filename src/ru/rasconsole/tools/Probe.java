package ru.rasconsole.tools;

import java.util.List;

import com._1c.v8.ibis.admin.IAgentAdminConnection;
import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.IWorkingServerInfo;
import com._1c.v8.ibis.admin.client.AgentAdminConnectorFactory;
import com._1c.v8.ibis.admin.client.IAgentAdminConnector;

/**
 * Проверка связи с RAS из командной строки, без UI.
 * <pre>java -cp "ras-console.jar;lib/*" ru.rasconsole.tools.Probe host[:port] [clusterUser [clusterPassword]]</pre>
 */
public final class Probe {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Использование: Probe host[:port] [clusterUser [clusterPassword]]");
            System.exit(2);
        }
        String host = args[0];
        int port = 1545;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            port = Integer.parseInt(host.substring(colon + 1));
            host = host.substring(0, colon);
        }
        String user = args.length > 1 ? args[1] : "";
        String pwd = args.length > 2 ? args[2] : "";

        IAgentAdminConnector connector = new AgentAdminConnectorFactory().createConnector(10_000);
        try {
            System.out.println("Подключение к " + host + ":" + port + " ...");
            IAgentAdminConnection c = connector.connect(host, port);
            System.out.println("Версия RAS: " + safe(c::getAgentVersion));
            List<IClusterInfo> clusters = c.getClusters();
            System.out.println("Кластеров: " + clusters.size());
            for (IClusterInfo cl : clusters) {
                System.out.printf("%nКластер \"%s\" %s:%d  id=%s%n", cl.getName(), cl.getHostName(), cl.getMainPort(), cl.getClusterId());
                try {
                    c.authenticate(cl.getClusterId(), user, pwd);
                    List<IWorkingServerInfo> servers = c.getWorkingServers(cl.getClusterId());
                    for (IWorkingServerInfo s : servers) {
                        System.out.printf("  Рабочий сервер: %s (%s:%d)%n", s.getName(), s.getHostName(), s.getMainPort());
                    }
                    List<IInfoBaseInfoShort> ibs = c.getInfoBasesShort(cl.getClusterId());
                    System.out.println("  Информационных баз: " + ibs.size());
                    for (IInfoBaseInfoShort ib : ibs) {
                        System.out.println("    " + ib.getName() + (ib.getDescr() == null || ib.getDescr().isEmpty() ? "" : " — " + ib.getDescr()));
                    }
                    System.out.println("  Сеансов: " + c.getSessions(cl.getClusterId()).size());
                    System.out.println("  Рабочих процессов: " + c.getWorkingProcesses(cl.getClusterId()).size());
                } catch (RuntimeException e) {
                    System.out.println("  Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            System.out.println("Ошибка: " + ru.rasconsole.ras.NetErrors.describe(e));
            System.exit(1);
        } finally {
            connector.shutdown();
        }
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return "(недоступно: " + e.getMessage() + ")";
        }
    }
}
