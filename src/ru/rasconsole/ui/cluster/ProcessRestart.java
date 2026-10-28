package ru.rasconsole.ui.cluster;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;

import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;

import ru.rasconsole.ras.Credentials;
import ru.rasconsole.ras.Rac;
import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fx;

/**
 * Перезапуск рабочих процессов (rphost) — только средствами кластера: «Выключить», как в стандартной консоли
 * ({@code rac process turn-off}). Кластер выключает процесс, переносит соединения и запускает новый rphost.
 * В Java API 1.6.7 этой команды нет, поэтому вызывается rac.exe из платформы 1С на этом компьютере.
 */
final class ProcessRestart {

    private ProcessRestart() {
    }

    /** Подтвердить и выключить процессы; {@code after} вызывается в FX-потоке по окончании. */
    static void restart(ClusterCtx ctx, List<IWorkingProcessInfo> processes, Runnable after) {
        if (processes.isEmpty()) return;
        Path rac = Rac.find(ctx.conn.agentVersion());
        if (rac == null) {
            Dialogs.warn("Не найдена утилита rac.exe.\n\nРабочие процессы выключаются средствами кластера через rac.exe"
                + " из платформы 1С. Установите платформу 1С (компонент «Сервер 1С:Предприятия» или «Администрирование"
                + " сервера») на этот компьютер.");
            return;
        }
        Fx.call(ctx.cluster(), "Сеансы рабочих процессов", c -> {
            int n = 0;
            for (ISessionInfo s : c.getSessions(ctx.clusterId)) {
                for (IWorkingProcessInfo p : processes) {
                    if (p.getWorkingProcessId().equals(s.getWorkingProcessId())) n++;
                }
            }
            return n;
        }, sessions -> confirmAndTurnOff(ctx, processes, sessions, rac, after),
            err -> confirmAndTurnOff(ctx, processes, -1, rac, after));
    }

    private static void confirmAndTurnOff(ClusterCtx ctx, List<IWorkingProcessInfo> processes, int sessions, Path rac,
                                          Runnable after) {
        StringBuilder sb = new StringBuilder();
        for (IWorkingProcessInfo p : processes) {
            sb.append("  ").append(p.getHostName()).append(':').append(p.getMainPort())
                .append("  PID ").append(p.getPid()).append('\n');
        }
        String warn = sessions > 0
            ? "На этих процессах сеансов: " + sessions + ". Кластер перенесёт соединения на другие процессы."
            : sessions < 0 ? "Не удалось получить число сеансов на процессах." : "Сеансов на этих процессах нет.";
        if (!Dialogs.confirmDanger(ctx.conn.config(), "Перезапуск рабочих процессов",
            "Выключить рабочие процессы (" + processes.size() + ")? Кластер запустит новые автоматически.\n\n"
                + sb + "\n" + warn + "\n\nВыполняется средствами кластера через " + rac)) {
            return;
        }
        Credentials creds = ctx.conn.clusterCredentials(ctx.clusterId);
        Fx.status("Выключение рабочих процессов…");
        Thread t = new Thread(() -> {
            List<String> report = new ArrayList<>();
            boolean allOk = true;
            for (IWorkingProcessInfo p : processes) {
                Rac.Result res = Rac.turnOffProcess(ctx.conn.config(), ctx.clusterId, p.getWorkingProcessId(), creds,
                    ctx.conn.agentVersion());
                String r = res.ok() ? "OK — выключен, кластер запустит новый" : "ошибка: " + res.text();
                allOk &= res.ok();
                report.add(p.getHostName() + ":" + p.getMainPort() + " PID " + p.getPid() + ": " + r);
                Fx.audit(ctx.cluster(), "Выключение rphost " + p.getHostName() + ":" + p.getMainPort() + " PID " + p.getPid(),
                    res.ok() ? "OK" : r);
            }
            boolean ok = allOk;
            Platform.runLater(() -> {
                Fx.status(ok ? "Рабочие процессы выключены, кластер запускает новые" : "Перезапуск: есть ошибки");
                Dialogs.info("Перезапуск рабочих процессов", String.join("\n", report)
                    + (ok ? "\n\nНовые процессы появятся в списке через несколько секунд." : ""));
                after.run();
            });
        }, "rphost-turn-off");
        t.setDaemon(true);
        t.start();
    }
}
