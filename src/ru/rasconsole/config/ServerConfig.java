package ru.rasconsole.config;

/**
 * Параметры подключения к серверу администрирования (RAS).
 * @param group группа в дереве («Прод», «Тест»…); пустая — без группы
 * @param prod  продуктивный сервер: опасные действия требуют дополнительного подтверждения
 */
public record ServerConfig(String name, String host, int port, int timeoutSec, String agentUser, String group, boolean prod) {

    public static final int DEFAULT_PORT = 1545;
    public static final int DEFAULT_TIMEOUT_SEC = 10;

    public ServerConfig {
        host = host == null ? "" : host.trim();
        name = name == null || name.isBlank() ? host : name.trim();
        agentUser = agentUser == null ? "" : agentUser;
        group = group == null ? "" : group.trim();
        if (port <= 0) port = DEFAULT_PORT;
        if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SEC;
    }

    public ServerConfig(String name, String host, int port, int timeoutSec, String agentUser) {
        this(name, host, port, timeoutSec, agentUser, "", false);
    }

    /** Допустимое имя или IP-адрес компьютера: буквы, цифры, «.», «-», «_», IPv6 в скобках; не начинается с «-». */
    public static boolean validHost(String h) {
        return h != null && !h.isEmpty() && h.length() <= 253 && !h.startsWith("-")
            && h.matches("[\\p{L}\\p{N}._\\-]+|\\[[0-9A-Fa-f:.]+\\]");
    }

    public static boolean validPort(int p) {
        return p > 0 && p <= 65535;
    }

    public String address() {
        return host + ":" + port;
    }

    /** Отображаемое имя: «имя (host:port)» или просто адрес. */
    public String display() {
        return name.equals(host) ? address() : name + " (" + address() + ")";
    }
}
