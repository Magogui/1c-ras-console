package ru.rasconsole.ras;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import ru.rasconsole.config.ServerConfig;

/**
 * Вызов утилиты {@code rac.exe} из платформы 1С для команд, которых нет в Java API 1.6.7.
 * <p>
 * Сейчас это «Выключить рабочий процесс» ({@code rac process turn-off}): кластер сам выключает rphost,
 * переносит соединения и запускает новый процесс. Команда выполняется через RAS, поэтому работает и
 * для серверов 1С на Linux, и без прав администратора Windows на сервере.
 */
public final class Rac {

    /** Путь, заданный пользователем (или через -Drasconsole.rac); null — искать в каталогах платформы. */
    private static volatile Path configured = System.getProperty("rasconsole.rac") == null ? null
        : Path.of(System.getProperty("rasconsole.rac"));

    private Rac() {
    }

    public static void setPath(Path p) {
        configured = p;
    }

    /** Найти rac.exe: заданный путь, иначе самая новая платформа 8.3 (затем любая) в Program Files. */
    public static Path find() {
        return find(null);
    }

    /** Найти rac.exe, по возможности той же версии, что и служба RAS ({@code preferVersion}). */
    public static Path find(String preferVersion) {
        Path c = configured;
        if (c != null && Files.isRegularFile(c)) return c;
        return Platform1C.find("rac.exe", preferVersion);
    }

    /** Результат вызова. */
    public record Result(boolean ok, String text) {
    }

    /** Выключить рабочий процесс (аналог «Выключить» в стандартной консоли). */
    public static Result turnOffProcess(ServerConfig server, UUID clusterId, UUID processId, Credentials cluster) {
        return turnOffProcess(server, clusterId, processId, cluster, null);
    }

    /** @param rasVersion версия службы RAS — выбирается rac той же версии, если установлен */
    public static Result turnOffProcess(ServerConfig server, UUID clusterId, UUID processId, Credentials cluster,
                                        String rasVersion) {
        Path rac = find(rasVersion);
        if (rac == null) return new Result(false, "не найдена утилита rac.exe (установите платформу 1С или укажите путь)");
        if (!ServerConfig.validHost(server.host())) return new Result(false, "недопустимый адрес сервера: " + server.host());
        List<String> cmd = new ArrayList<>(List.of(rac.toString(), server.address(), "process", "turn-off",
            "--cluster=" + clusterId, "--process=" + processId));
        if (cluster != null && !cluster.user().isEmpty()) {
            cmd.add("--cluster-user=" + cluster.user());
            cmd.add("--cluster-pwd=" + cluster.password());
        }
        return run(cmd);
    }

    private static Result run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            p.getInputStream().transferTo(out);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(false, "rac не ответил за 60 с");
            }
            String text = out.toString(oem()).trim();
            return p.exitValue() == 0 ? new Result(true, text.isEmpty() ? "OK" : text)
                : new Result(false, text.isEmpty() ? "код завершения " + p.exitValue() : text);
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    /** rac пишет в OEM-кодировке консоли (cp866 для русской Windows). */
    private static Charset oem() {
        try {
            return Charset.forName("IBM866");
        } catch (Exception e) {
            return Charset.defaultCharset();
        }
    }
}
