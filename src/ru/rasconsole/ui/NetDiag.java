package ru.rasconsole.ui;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com._1c.v8.ibis.admin.IAgentAdminConnection;
import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.client.AgentAdminConnectorFactory;
import com._1c.v8.ibis.admin.client.IAgentAdminConnector;

import ru.rasconsole.config.ServerConfig;
import ru.rasconsole.ras.CodecFix;
import ru.rasconsole.ras.NetErrors;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.table.EntityTable;

/**
 * Диагностика сети до сервера 1С: имя в DNS → ping → порт RAS → ответ RAS (и его связь с агентом)
 * → менеджеры кластеров и порт агента. По каждому шагу — время и подсказка. Отличает «порт закрыт»
 * (компьютер ответил отказом — служба не запущена) от «нет ответа» (файрвол или потеря сети).
 */
public final class NetDiag {

    private static final int TCP_TIMEOUT_MS = 3000;
    private static final int PING_TIMEOUT_MS = 2000;

    public static final String OK = "OK";
    public static final String FAIL = "ошибка";
    public static final String WARN = "внимание";
    public static final String SKIP = "не проверено";

    /** Строка отчёта. */
    public record Step(String check, String status, String ms, String result, String hint) {
    }

    private NetDiag() {
    }

    /** Открыть окно диагностики и сразу запустить проверку. */
    public static void open(ServerConfig cfg) {
        Dialog<Void> d = Dialogs.prepare(new Dialog<>(), "Диагностика сети — " + cfg.display());
        d.setHeaderText("Проверка связи с сервером администрирования " + cfg.address());
        ObservableList<Step> rows = FXCollections.observableArrayList();
        EntityTable<Step> table = new EntityTable<>(List.of(
            Col.<Step>of("Проверка", 300, Step::check),
            Col.<Step>of("Состояние", 100, Step::status),
            Col.<Step>of("Время, мс", 80, Step::ms),
            Col.<Step>of("Результат", 330, Step::result),
            Col.<Step>of("Что делать", 420, Step::hint)), null);
        table.setRowStyle(s -> FAIL.equals(s.status()) ? "row-blocked" : WARN.equals(s.status()) ? "row-warning" : null);
        table.view().setPrefSize(1180, 360);
        table.setPlaceholder("Проверка…");
        Label state = new Label();
        Button again = new Button("Повторить");
        Button ports = new Button("Проверить порты 1С (1540, 1541, 1545)");
        Button copy = new Button("Копировать отчёт");
        Runnable[] run = new Runnable[1];
        run[0] = () -> {
            rows.clear();
            table.setItems(rows);
            again.setDisable(true);
            ports.setDisable(true);
            state.setText("Проверка…");
            runAsync(() -> check(cfg, s -> Platform.runLater(() -> {
                rows.add(s);
                table.setItems(new ArrayList<>(rows));
            })), () -> {
                again.setDisable(false);
                ports.setDisable(false);
                state.setText(summary(rows));
            });
        };
        again.setOnAction(e -> run[0].run());
        ports.setOnAction(e -> {
            ports.setDisable(true);
            runAsync(() -> {
                for (int p : new int[] {1540, 1541, 1545}) {
                    Step s = tcpStep("Порт " + p + " на " + cfg.host() + " (" + portRole(p) + ")", cfg.host(), p, false);
                    Platform.runLater(() -> {
                        rows.add(s);
                        table.setItems(new ArrayList<>(rows));
                    });
                }
            }, () -> {
                ports.setDisable(false);
                state.setText(summary(rows));
            });
        });
        copy.setOnAction(e -> {
            StringBuilder sb = new StringBuilder("Диагностика сети — " + cfg.display() + "\n");
            for (Step s : rows) {
                sb.append(s.status()).append('\t').append(s.check()).append('\t').append(s.ms()).append(" мс\t")
                    .append(s.result()).append(s.hint().isEmpty() ? "" : "\t" + s.hint()).append('\n');
            }
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(sb.toString());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            Fx.status("Отчёт диагностики скопирован");
        });
        HBox bar = new HBox(8, again, ports, copy, state);
        HBox.setHgrow(state, Priority.ALWAYS);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox box = new VBox(8, bar, table.view());
        VBox.setVgrow(table.view(), Priority.ALWAYS);
        box.setPadding(new Insets(6));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(new ButtonType("Закрыть", ButtonBar.ButtonData.CANCEL_CLOSE));
        Platform.runLater(run[0]);
        d.showAndWait();
    }

    private static String summary(List<Step> rows) {
        long fail = rows.stream().filter(s -> FAIL.equals(s.status())).count();
        long warn = rows.stream().filter(s -> WARN.equals(s.status())).count();
        return fail > 0 ? "Найдены ошибки: " + fail + (warn > 0 ? ", замечаний: " + warn : "")
            : warn > 0 ? "Связь есть, замечаний: " + warn : "Всё в порядке";
    }

    private static void runAsync(Runnable work, Runnable done) {
        Thread t = new Thread(() -> {
            try {
                work.run();
            } finally {
                Platform.runLater(done);
            }
        }, "net-diag");
        t.setDaemon(true);
        t.start();
    }

    private static String portRole(int p) {
        return switch (p) {
            case 1540 -> "агент сервера ragent";
            case 1541 -> "менеджер кластера rmngr";
            case 1545 -> "сервер администрирования ras";
            default -> "";
        };
    }

    // ---- проверки (фоновый поток) ----

    /** Все шаги по порядку; каждый готовый шаг сразу передаётся в {@code out}. */
    public static void check(ServerConfig cfg, java.util.function.Consumer<Step> out) {
        String host = cfg.host();
        // 1. DNS
        long t0 = System.nanoTime();
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
            out.accept(new Step("Имя «" + host + "» в DNS", OK, ms(t0), addresses(addrs), ""));
        } catch (UnknownHostException e) {
            out.accept(new Step("Имя «" + host + "» в DNS", FAIL, ms(t0), "имя не найдено",
                "Проверьте написание имени, DNS-суффикс домена, файл hosts. Попробуйте указать IP-адрес сервера."));
            out.accept(new Step("Остальные проверки", SKIP, "", "без адреса проверять нечего", ""));
            return;
        }
        // 2. ping
        t0 = System.nanoTime();
        try {
            boolean ok = addrs[0].isReachable(PING_TIMEOUT_MS);
            out.accept(ok ? new Step("Ping " + addrs[0].getHostAddress(), OK, ms(t0), "отвечает", "")
                : new Step("Ping " + addrs[0].getHostAddress(), WARN, ms(t0), "нет ответа за " + PING_TIMEOUT_MS / 1000 + " с",
                    "ICMP часто закрыт файрволом — это не ошибка, если порты ниже доступны."));
        } catch (IOException e) {
            out.accept(new Step("Ping " + addrs[0].getHostAddress(), SKIP, "", "не проверено: " + e.getMessage(),
                "Нет прав на ICMP (в Linux без root) — ориентируйтесь на проверку портов."));
        }
        // 3. порт RAS
        Step ras = tcpStep("Порт RAS " + cfg.address(), host, cfg.port(), true);
        out.accept(ras);
        if (!OK.equals(ras.status())) {
            out.accept(new Step("Ответ сервера администрирования", SKIP, "", "порт RAS недоступен", ""));
            return;
        }
        // 4. протокол RAS и связь RAS → агент
        t0 = System.nanoTime();
        List<IClusterInfo> clusters;
        String version;
        IAgentAdminConnector connector = new AgentAdminConnectorFactory().createConnector(10_000);
        CodecFix.patch(connector);
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "net-diag-ras");
            th.setDaemon(true);
            return th;
        });
        IAgentAdminConnection conn = null;
        try {
            Future<IAgentAdminConnection> f = ex.submit(() -> connector.connect(host, cfg.port()));
            conn = f.get(10, TimeUnit.SECONDS);
            IAgentAdminConnection c = conn;
            version = ex.submit(c::getAgentVersion).get(10, TimeUnit.SECONDS);
            clusters = ex.submit(c::getClusters).get(10, TimeUnit.SECONDS);
            out.accept(new Step("Ответ сервера администрирования (RAS)", OK, ms(t0),
                "версия RAS " + version + ", кластеров: " + clusters.size(), ""));
        } catch (TimeoutException e) {
            out.accept(new Step("Ответ сервера администрирования (RAS)", FAIL, ms(t0), "не ответил за 10 с",
                "Порт открыт, но RAS не отвечает: перегружен или на этом порту другая программа."));
            close(connector, conn, ex);
            return;
        } catch (Exception e) {
            String why = NetErrors.describe(e);
            out.accept(new Step("Ответ сервера администрирования (RAS)", FAIL, ms(t0), why, hintFor(why)));
            close(connector, conn, ex);
            return;
        }
        close(connector, conn, ex);
        // 5. менеджеры кластеров: нужны для запуска 1С с этого компьютера, но не для консоли
        for (IClusterInfo ci : clusters) {
            String ch = ci.getHostName();
            if (!ch.equalsIgnoreCase(host)) {
                long t1 = System.nanoTime();
                try {
                    InetAddress[] a = InetAddress.getAllByName(ch);
                    out.accept(new Step("Имя кластера «" + ci.getName() + "»: " + ch, OK, ms(t1), addresses(a), ""));
                } catch (UnknownHostException e) {
                    out.accept(new Step("Имя кластера «" + ci.getName() + "»: " + ch, WARN, ms(t1), "имя не найдено с этого компьютера",
                        "Кластер зарегистрирован под этим именем. Консоли через RAS это не мешает, но 1С и Конфигуратор"
                            + " с этого компьютера не подключатся: добавьте имя в DNS или hosts."));
                    continue;
                }
            }
            Step s = tcpStep("Менеджер кластера «" + ci.getName() + "» " + ch + ":" + ci.getMainPort(), ch, ci.getMainPort(), false);
            if (!OK.equals(s.status())) {
                s = new Step(s.check(), WARN, s.ms(), s.result(),
                    "Нужен для запуска 1С и Конфигуратора с этого компьютера, консоли через RAS не нужен."
                        + " Также откройте диапазон портов рабочих процессов (обычно 1560–1591).");
            }
            out.accept(s);
        }
        // 6. порт агента
        Step agent = tcpStep("Порт агента " + host + ":1540", host, 1540, false);
        if (!OK.equals(agent.status())) {
            agent = new Step(agent.check(), WARN, agent.ms(), agent.result(),
                "Нужен стандартной консоли (MMC) и COM-подключению. Консоли через RAS не нужен;"
                    + " если агент на другом порту — это нормально.");
        }
        out.accept(agent);
    }

    private static void close(IAgentAdminConnector connector, IAgentAdminConnection conn, ExecutorService ex) {
        ex.submit(() -> {
            try {
                connector.shutdown();
            } catch (RuntimeException ignored) {
                // соединение уже разорвано
            }
        });
        ex.shutdown();
    }

    private static String hintFor(String why) {
        String w = why.toLowerCase();
        if (w.contains("верси")) return "Служба RAS подключена к агенту другой версии: запустите RAS той же версии с явным адресом своего агента.";
        if (w.contains("агент")) return "RAS работает, но не достучался до агента: проверьте, что ragent запущен, и адрес агента в параметрах ras.";
        if (w.contains("авториз") || w.contains("аутентиф")) return "Связь есть, нужны имя и пароль администратора.";
        return "Проверьте, что на этом порту запущена служба ras нужной версии.";
    }

    /** TCP-подключение с различением «закрыт» и «нет ответа». */
    static Step tcpStep(String check, String host, int port, boolean ras) {
        long t0 = System.nanoTime();
        InetSocketAddress target = new InetSocketAddress(host, port);
        // к какому адресу шло подключение: у имени их может быть несколько, Java берёт первый
        String via = target.isUnresolved() || target.getAddress().getHostAddress().equals(host) ? ""
            : " (адрес " + target.getAddress().getHostAddress() + ")";
        try (Socket s = new Socket()) {
            if (target.isUnresolved()) throw new UnknownHostException(host);
            s.connect(target, TCP_TIMEOUT_MS);
            return new Step(check, OK, ms(t0), "порт открыт" + via, "");
        } catch (UnknownHostException e) {
            return new Step(check, FAIL, ms(t0), "имя не найдено", "Проверьте DNS или hosts.");
        } catch (ConnectException e) {
            boolean refused = e.getMessage() != null && e.getMessage().toLowerCase().contains("refused");
            return refused
                ? new Step(check, FAIL, ms(t0), "порт закрыт: компьютер ответил отказом" + via,
                    ras ? "Компьютер доступен, но служба ras на этом порту не запущена. Запустите:"
                        + " ras cluster --service --port=" + port + " localhost:1540 (или проверьте номер порта)."
                        : "Компьютер доступен, но на этом порту никто не слушает: служба не запущена или работает на другом порту.")
                : new Step(check, FAIL, ms(t0), "не удалось подключиться: " + e.getMessage(), "Проверьте сеть и файрвол.");
        } catch (SocketTimeoutException e) {
            return new Step(check, FAIL, ms(t0), "нет ответа за " + TCP_TIMEOUT_MS / 1000 + " с" + via,
                "Пакеты не доходят: порт закрыт файрволом (на сервере или по пути) или нет маршрута. Откройте TCP " + port + "."
                    + (via.isEmpty() ? "" : " Если у имени несколько адресов, проверьте, что первый из них — рабочий."));
        } catch (NoRouteToHostException e) {
            return new Step(check, FAIL, ms(t0), "нет маршрута до компьютера", "Проверьте VPN, маршрутизацию, что сервер включён.");
        } catch (IOException e) {
            return new Step(check, FAIL, ms(t0), e.getMessage() == null ? e.toString() : e.getMessage(), "");
        }
    }

    private static String addresses(InetAddress[] a) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < a.length && i < 3; i++) r.add(a[i].getHostAddress());
        return String.join(", ", r) + (a.length > 3 ? " и ещё " + (a.length - 3) : "");
    }

    private static String ms(long t0) {
        return Long.toString(Math.max(0, (System.nanoTime() - t0) / 1_000_000));
    }
}
