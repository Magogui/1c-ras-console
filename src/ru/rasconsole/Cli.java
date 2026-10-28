package ru.rasconsole;

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import com._1c.v8.ibis.admin.IClusterInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;
import com._1c.v8.ibis.admin.ISessionInfo;
import com._1c.v8.ibis.admin.IWorkingProcessInfo;
import com._1c.v8.ibis.admin.IWorkingServerInfo;

import ru.rasconsole.config.AuditLog;
import ru.rasconsole.config.CredentialStore;
import ru.rasconsole.config.ServerConfig;
import ru.rasconsole.config.ServerStore;
import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.AuthRequiredException;
import ru.rasconsole.ras.Credentials;
import ru.rasconsole.ras.NetErrors;
import ru.rasconsole.ras.Rac;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ras.RasFn;

/**
 * Режим командной строки — для Планировщика Windows и скриптов.
 * Использует тот же список серверов и сохранённые пароли, что и консоль; действия пишутся в журнал.
 * Коды завершения: 0 — успешно, 1 — ошибка, 2 — неверные параметры, 3 — нужна авторизация.
 */
public final class Cli {

    private static PrintStream out;
    private final Map<String, String> opt = new HashMap<>();
    private final List<String> args = new ArrayList<>();
    private RasConnection conn;
    private ServerConfig server;

    public static void main(String[] argv) {
        // служебные сообщения библиотеки 1С («INFO: Trying to connect…») в выводе утилиты не нужны
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        root.setLevel(java.util.logging.Level.WARNING);
        for (java.util.logging.Handler h : root.getHandlers()) h.setLevel(java.util.logging.Level.WARNING);
        Charset cs = System.console() != null ? System.console().charset() : StandardCharsets.UTF_8;
        out = new PrintStream(System.out, true, cs);
        int code;
        try {
            code = new Cli().run(argv);
        } catch (Usage e) {
            out.println("Ошибка: " + e.getMessage());
            out.println("Справка: RasCli help");
            code = 2;
        } catch (AuthRequiredException e) {
            out.println("Нужна авторизация (" + e.kind() + "): " + e.getMessage());
            out.println("Укажите --cluster-user/--cluster-pwd (--ib-user/--ib-pwd, --agent-user/--agent-pwd)"
                + " или сохраните пароль в консоли («Запомнить пароль»).");
            code = 3;
        } catch (Exception e) {
            out.println("Ошибка: " + message(e));
            code = 1;
        }
        System.exit(code);
    }

    private static final class Usage extends RuntimeException {
        Usage(String m) {
            super(m);
        }
    }

    private static String message(Throwable e) {
        while (e instanceof ExecutionException && e.getCause() != null) e = e.getCause();
        if (e instanceof com._1c.v8.ibis.admin.AgentAdminConnectionException) return "нет связи: " + NetErrors.describe(e);
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return "сервер вернул ошибку без описания (возможно, рабочий процесс кластера ещё запускается — повторите позже)";
        }
        String d = NetErrors.describe(e);
        return d.equals(m) || m.length() < 200 ? m : d;
    }

    static void help() {
        out.println("""
            Консоль администрирования RAS — командная строка, версия {VERSION}.

            RasCli <команда> <сервер> [параметры]
              <сервер> — имя или адрес из списка серверов консоли, либо host[:port].

            Команды:
              servers                               список серверов консоли
              infobases <сервер>                    информационные базы
              sessions  <сервер> [--base b1,b2]     сеансы
              lock      <сервер> --base b1,b2 [--message текст] [--code КОД] [--from дата] [--to дата]
                                 [--no-jobs] [--terminate] [--exclude Designer,BackgroundJob]
                                                    установить блокировку сеансов (и регламентных заданий)
              unlock    <сервер> --base b1,b2 [--keep-jobs]   снять блокировку
              terminate <сервер> --base b1,b2 [--exclude Designer,...] [--message текст]
                                                    завершить сеансы баз
              restart-processes <сервер> [--worker имя]       выключить (перезапустить) рабочие процессы через rac
              report    <файл.xlsx|файл.html> [--servers a,b]  отчёт по всем (или выбранным) серверам консоли
              install-api <архив.zip>               установить библиотеку 1С из архива с ИТС (для публичной сборки)

            Общие параметры:
              --cluster имя|uuid   кластер (если на сервере их несколько)
              --cluster-user, --cluster-pwd, --ib-user, --ib-pwd, --agent-user, --agent-pwd
              Без них используются пароли, сохранённые в консоли.
              Даты: дд.мм.гггг чч:мм[:сс]. «--base *» — все базы кластера.

            Пример для Планировщика Windows (ночной регламент):
              RasCli lock prod --base buh,zup --message "Регламентные работы" --code 777 --terminate --exclude Designer
              RasCli unlock prod --base buh,zup
            """.replace("{VERSION}", Version.VERSION));
    }

    // ---- разбор параметров ----

    private int run(String[] argv) throws Exception {
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String k = a.substring(2);
                int eq = k.indexOf('=');
                if (eq > 0) {
                    opt.put(k.substring(0, eq), k.substring(eq + 1));
                } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")
                    && !Set.of("no-jobs", "terminate", "keep-jobs", "help").contains(k)) {
                    opt.put(k, argv[++i]);
                } else {
                    opt.put(k, "true");
                }
            } else {
                args.add(a);
            }
        }
        if (args.isEmpty() || opt.containsKey("help") || args.get(0).equals("help")) {
            help();
            return args.isEmpty() ? 2 : 0;
        }
        ServerStore store = new ServerStore();
        AuditLog.init(store.dir());
        String cmd = args.get(0);
        if (cmd.equals("servers")) {
            for (ServerConfig s : store.load()) {
                out.printf("%-30s %-28s %s%s%n", s.name(), s.address(), s.group(), s.prod() ? " [прод]" : "");
            }
            return 0;
        }
        if (cmd.equals("report")) return report(store);
        if (args.size() < 2) throw new Usage("не указан сервер");
        server = resolveServer(store, args.get(1));
        conn = new RasConnection(server);
        CredentialStore creds = new CredentialStore(store.dir());
        creds.load();
        creds.applyTo(conn);
        if (opt.containsKey("agent-user")) conn.setAgentCredentials(new Credentials(opt.get("agent-user"), opt.getOrDefault("agent-pwd", "")));
        try {
            return switch (cmd) {
                case "infobases" -> infobases();
                case "sessions" -> sessions();
                case "lock" -> lock(true);
                case "unlock" -> lock(false);
                case "terminate" -> terminate();
                case "restart-processes" -> restartProcesses();
                default -> throw new Usage("неизвестная команда «" + cmd + "»");
            };
        } finally {
            conn.shutdown();
        }
    }

    /** Отчёт по серверам в .xlsx или .html: все серверы консоли или выбранные (--servers a,b). */
    private int report(ServerStore store) throws Exception {
        if (args.size() < 2) throw new Usage("не указан файл отчёта (.xlsx или .html)");
        java.nio.file.Path file = java.nio.file.Path.of(args.get(1));
        List<ServerConfig> list = new ArrayList<>();
        String want = opt.get("servers");
        if (want == null || want.equals("*")) {
            list.addAll(store.load());
        } else {
            for (String w : want.split(",")) if (!w.isBlank()) list.add(resolveServer(store, w.trim()));
        }
        if (list.isEmpty()) throw new Usage("список серверов пуст");
        CredentialStore creds = new CredentialStore(store.dir());
        creds.load();
        List<RasConnection> conns = new ArrayList<>();
        for (ServerConfig cfg : list) {
            RasConnection c = new RasConnection(cfg);
            creds.applyTo(c);
            conns.add(c);
        }
        try {
            ru.rasconsole.ras.ServerReport r = ru.rasconsole.ras.ServerReport.collect(conns).get();
            r.write(file);
            for (ru.rasconsole.config.Xlsx.Sheet sh : r.sheets()) out.printf("%-22s %d%n", sh.name(), sh.rows().size());
            out.println("Отчёт сохранён: " + file.toAbsolutePath());
            return 0;
        } finally {
            for (RasConnection c : conns) c.shutdown();
        }
    }

    private static ServerConfig resolveServer(ServerStore store, String s) {
        for (ServerConfig c : store.load()) {
            if (c.name().equalsIgnoreCase(s) || c.address().equalsIgnoreCase(s) || c.host().equalsIgnoreCase(s)) return c;
        }
        String host = s;
        int port = ServerConfig.DEFAULT_PORT;
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            host = s.substring(0, colon);
            port = Integer.parseInt(s.substring(colon + 1));
        }
        return new ServerConfig(host, host, port, ServerConfig.DEFAULT_TIMEOUT_SEC, "");
    }

    /** Выбранный кластер: --cluster или единственный на сервере. */
    private IClusterInfo cluster() throws Exception {
        List<IClusterInfo> list = conn.submit(AuthKind.NONE, null, c -> c.getClusters()).get();
        String want = opt.get("cluster");
        IClusterInfo found = null;
        for (IClusterInfo ci : list) {
            if (want == null || ci.getName().equalsIgnoreCase(want) || ci.getClusterId().toString().equalsIgnoreCase(want)) {
                if (found != null && want == null) {
                    throw new Usage("на сервере несколько кластеров — укажите --cluster (" + names(list) + ")");
                }
                found = ci;
            }
        }
        if (found == null) throw new Usage("кластер «" + want + "» не найден (" + names(list) + ")");
        if (opt.containsKey("cluster-user")) {
            conn.setClusterCredentials(found.getClusterId(), new Credentials(opt.get("cluster-user"), opt.getOrDefault("cluster-pwd", "")));
        }
        if (opt.containsKey("ib-user")) {
            conn.addInfoBaseCredentials(found.getClusterId(), new Credentials(opt.get("ib-user"), opt.getOrDefault("ib-pwd", "")));
        }
        return found;
    }

    private static String names(List<IClusterInfo> list) {
        List<String> n = new ArrayList<>();
        for (IClusterInfo ci : list) n.add(ci.getName());
        return String.join(", ", n);
    }

    private <T> T call(AuthKind auth, UUID cid, RasFn<T> fn) throws Exception {
        try {
            return conn.submit(auth, cid, fn).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) throw x;
            throw e;
        }
    }

    /** Базы из --base (через запятую, «*» — все). */
    private List<IInfoBaseInfoShort> bases(UUID cid, boolean required) throws Exception {
        List<IInfoBaseInfoShort> all = call(AuthKind.CLUSTER, cid, c -> c.getInfoBasesShort(cid));
        String b = opt.get("base");
        if (b == null) {
            if (required) throw new Usage("не указаны базы: --base b1,b2 или --base *");
            return all;
        }
        if (b.equals("*")) return all;
        Map<String, IInfoBaseInfoShort> byName = new LinkedHashMap<>();
        for (IInfoBaseInfoShort ib : all) byName.put(ib.getName().toLowerCase(Locale.ROOT), ib);
        List<IInfoBaseInfoShort> r = new ArrayList<>();
        for (String n : b.split(",")) {
            IInfoBaseInfoShort ib = byName.get(n.trim().toLowerCase(Locale.ROOT));
            if (ib == null) throw new Usage("база «" + n.trim() + "» не найдена в кластере");
            r.add(ib);
        }
        return r;
    }

    private void audit(String target, String action, String result) {
        AuditLog.write(server.display() + " (командная строка)", target, action, result);
    }

    // ---- команды ----

    private int infobases() throws Exception {
        IClusterInfo ci = cluster();
        UUID cid = ci.getClusterId();
        for (IInfoBaseInfoShort ib : call(AuthKind.CLUSTER, cid, c -> c.getInfoBasesShort(cid))) {
            out.printf("%-30s %s%n", ib.getName(), ib.getDescr() == null ? "" : ib.getDescr());
        }
        return 0;
    }

    private int sessions() throws Exception {
        IClusterInfo ci = cluster();
        UUID cid = ci.getClusterId();
        Map<UUID, String> names = new HashMap<>();
        Set<UUID> filter = new HashSet<>();
        for (IInfoBaseInfoShort ib : bases(cid, false)) {
            names.put(ib.getInfoBaseId(), ib.getName());
            filter.add(ib.getInfoBaseId());
        }
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        out.printf("%-6s %-20s %-24s %-16s %-20s %s%n", "Сеанс", "ИБ", "Пользователь", "Компьютер", "Приложение", "Начало");
        for (ISessionInfo s : call(AuthKind.CLUSTER, cid, c -> c.getSessions(cid))) {
            if (!filter.contains(s.getInfoBaseId())) continue;
            out.printf("%-6d %-20s %-24s %-16s %-20s %s%n", s.getSessionId(), names.getOrDefault(s.getInfoBaseId(), ""),
                s.getUserName(), s.getHost(), ru.rasconsole.ui.Fmt.app(s.getAppId()), f.format(s.getStartedAt()));
        }
        return 0;
    }

    private static Date date(String s) throws Usage {
        if (s == null || s.isBlank()) return new Date(0);
        for (String p : new String[] {"dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd.MM.yyyy"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p);
                f.setLenient(false);
                return f.parse(s.trim());
            } catch (ParseException ignored) {
                // следующий формат
            }
        }
        throw new Usage("дата «" + s + "»: ожидается дд.мм.гггг чч:мм[:сс]");
    }

    private int lock(boolean lock) throws Exception {
        IClusterInfo ci = cluster();
        UUID cid = ci.getClusterId();
        List<IInfoBaseInfoShort> bases = bases(cid, true);
        Date from = lock ? (opt.containsKey("from") ? date(opt.get("from")) : new Date()) : new Date(0);
        Date to = lock ? date(opt.get("to")) : new Date(0);
        boolean jobs = lock ? !opt.containsKey("no-jobs") : !opt.containsKey("keep-jobs");
        int errors = 0;
        for (IInfoBaseInfoShort b : bases) {
            String action = lock ? "Блокировка (командная строка)" : "Снятие блокировки (командная строка)";
            try {
                call(AuthKind.INFOBASE, cid, c -> {
                    IInfoBaseInfo info = c.getInfoBaseInfo(cid, b.getInfoBaseId());
                    info.setSessionsDenied(lock);
                    info.setDeniedFrom(from);
                    info.setDeniedTo(to);
                    if (lock) {
                        info.setDeniedMessage(opt.getOrDefault("message", "Выполняются регламентные работы"));
                        info.setPermissionCode(opt.getOrDefault("code", ""));
                    }
                    if (jobs) info.setScheduledJobsDenied(lock);
                    c.updateInfoBase(cid, info);
                    return null;
                });
                out.println(b.getName() + ": " + (lock ? "заблокирована" : "блокировка снята"));
                audit(b.getName(), action, "OK");
            } catch (AuthRequiredException e) {
                throw e;
            } catch (Exception e) {
                errors++;
                out.println(b.getName() + ": ошибка — " + message(e));
                audit(b.getName(), action, "ошибка: " + message(e));
            }
        }
        if (lock && opt.containsKey("terminate")) errors += terminate(cid, bases);
        return errors == 0 ? 0 : 1;
    }

    private int terminate() throws Exception {
        IClusterInfo ci = cluster();
        UUID cid = ci.getClusterId();
        return terminate(cid, bases(cid, true)) == 0 ? 0 : 1;
    }

    private int terminate(UUID cid, List<IInfoBaseInfoShort> bases) throws Exception {
        Set<UUID> ids = new HashSet<>();
        for (IInfoBaseInfoShort b : bases) ids.add(b.getInfoBaseId());
        Set<String> exclude = new HashSet<>(Arrays.asList(opt.getOrDefault("exclude", "").split(",")));
        String msg = opt.getOrDefault("message", "Сеанс завершён администратором");
        int done = 0;
        int skipped = 0;
        int errors = 0;
        for (ISessionInfo s : call(AuthKind.CLUSTER, cid, c -> c.getSessions(cid))) {
            if (!ids.contains(s.getInfoBaseId())) continue;
            if (s.getAppId() != null && exclude.contains(s.getAppId())) {
                skipped++;
                continue;
            }
            try {
                call(AuthKind.CLUSTER, cid, RasFn.run(c -> c.terminateSession(cid, s.getSid(), msg)));
                done++;
            } catch (AuthRequiredException e) {
                throw e;
            } catch (Exception e) {
                errors++;
                out.println("Сеанс " + s.getSessionId() + " (" + s.getUserName() + "): ошибка — " + message(e));
            }
        }
        String r = "завершено " + done + (skipped > 0 ? ", оставлено (исключения) " + skipped : "") + (errors > 0 ? ", ошибок " + errors : "");
        out.println("Сеансы: " + r);
        audit("базы: " + bases.size(), "Завершение сеансов (командная строка)", r);
        return errors;
    }

    private int restartProcesses() throws Exception {
        IClusterInfo ci = cluster();
        UUID cid = ci.getClusterId();
        String worker = opt.get("worker");
        List<IWorkingProcessInfo> procs = new ArrayList<>();
        if (worker == null) {
            procs.addAll(call(AuthKind.CLUSTER, cid, c -> c.getWorkingProcesses(cid)));
        } else {
            for (IWorkingServerInfo ws : call(AuthKind.CLUSTER, cid, c -> c.getWorkingServers(cid))) {
                if (ws.getName().equalsIgnoreCase(worker) || ws.getHostName().equalsIgnoreCase(worker)) {
                    procs.addAll(call(AuthKind.CLUSTER, cid, c -> c.getServerWorkingProcesses(cid, ws.getWorkingServerId())));
                }
            }
            if (procs.isEmpty()) throw new Usage("рабочий сервер «" + worker + "» не найден или у него нет процессов");
        }
        int errors = 0;
        for (IWorkingProcessInfo p : procs) {
            Rac.Result r = Rac.turnOffProcess(server, cid, p.getWorkingProcessId(), conn.clusterCredentials(cid),
                conn.agentVersion());
            String what = p.getHostName() + ":" + p.getMainPort() + " PID " + p.getPid();
            out.println(what + ": " + (r.ok() ? "выключен, кластер запустит новый" : "ошибка — " + r.text()));
            audit(ci.getName(), "Выключение rphost " + what + " (командная строка)", r.ok() ? "OK" : "ошибка: " + r.text());
            if (!r.ok()) errors++;
        }
        return errors == 0 ? 0 : 1;
    }
}
