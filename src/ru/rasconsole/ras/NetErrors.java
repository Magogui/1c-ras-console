package ru.rasconsole.ras;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Понятное описание сетевых ошибок вместо цепочки «V8ConnectionException: java.net…». */
public final class NetErrors {

    /** «Different client and server versions (8.5.4.1683 - 8.3.27.1989)» / «Различаются версии клиента и сервера (…)». */
    private static final Pattern VERSIONS = Pattern.compile(
        "(?:different client and server versions|различаются версии клиента и сервера)\\s*\\(\\s*([\\d.]+)\\s*-\\s*([\\d.]+)\\s*\\)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Адрес, к которому не смог подключиться сам RAS: «server_addr=tcp://host:1540». */
    private static final Pattern SERVER_ADDR = Pattern.compile("server_addr=tcp://([^\\s]+)");

    private NetErrors() {
    }

    public static String describe(Throwable t) {
        // обёртки асинхронных вызовов в сообщении несут имя класса — пропускаем их
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
            && t.getCause() != null) {
            t = t.getCause();
        }
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() != null) all.append(c.getMessage()).append('\n');
        }
        String text = all.toString();
        String lower = text.toLowerCase();

        // RAS и агент сервера 1С разных версий — так 1С не работает, даже штатный rac выдаёт эту ошибку
        Matcher v = VERSIONS.matcher(text);
        if (v.find()) {
            return versionMismatch(v.group(1), v.group(2));
        }
        // RAS доступен, но сам не может подключиться к агенту / менеджеру кластера
        Matcher a = SERVER_ADDR.matcher(text);
        if (a.find() && (lower.contains("отверг запрос") || lower.contains("10061") || lower.contains("refused"))) {
            return "служба RAS работает, но не может подключиться к агенту сервера 1С " + a.group(1)
                + ": агент остановлен или RAS запущен с другим адресом агента";
        }

        String deepest = null;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            String cls = c.getClass().getName();
            String m = c.getMessage() == null ? "" : c.getMessage();
            String lm = m.toLowerCase();
            if (c instanceof java.nio.channels.UnresolvedAddressException || c instanceof java.net.UnknownHostException) {
                return "имя сервера не найдено (DNS)";
            }
            if (lm.contains("не ответил за")) {
                return m;
            }
            if (lm.contains("connection refused") || lm.contains("отверг запрос") || lm.contains("10061")) {
                return "подключение отклонено: служба RAS не запущена или порт закрыт";
            }
            if (lm.contains("timed out") || cls.endsWith("ConnectTimeoutException") || c instanceof java.net.SocketTimeoutException) {
                return "сервер не отвечает (истекло время ожидания)";
            }
            if (lm.contains("connection reset") || lm.contains("разорвал") || lm.contains("10054")) {
                return "соединение разорвано сервером";
            }
            if (!m.isBlank()) deepest = m;
        }
        return deepest == null ? t.getClass().getSimpleName() : deepest;
    }

    /**
     * Текст для несовпадения версий. В сообщении 1С первой идёт версия клиента — здесь это служба RAS
     * («клиентское приложение: Сервер администрирования»), второй — версия агента/менеджера кластера.
     */
    static String versionMismatch(String ras, String agent) {
        return "эта служба RAS (версия " + ras + ") подключена к агенту сервера 1С версии " + agent
            + ", а RAS работает только с агентом своей версии. Чаще всего ras запущен без адреса агента — тогда он"
            + " подключается к localhost:1540. Укажите адрес агента " + ras + " последним параметром:"
            + " «ras cluster --port=<порт этого RAS> <сервер>:<порт агента " + ras + ">»."
            + " Если нужен именно агент " + agent + " — подключайтесь к RAS версии " + agent
            + ". Консоль работает с RAS любой версии";
    }
}
