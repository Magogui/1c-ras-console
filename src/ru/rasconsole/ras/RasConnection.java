package ru.rasconsole.ras;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import com._1c.v8.ibis.admin.AgentAdminAuthenticationException;
import com._1c.v8.ibis.admin.AgentAdminConnectionException;
import com._1c.v8.ibis.admin.AgentAdminException;
import com._1c.v8.ibis.admin.IAgentAdminConnection;
import com._1c.v8.ibis.admin.client.AgentAdminConnectorFactory;
import com._1c.v8.ibis.admin.client.IAgentAdminConnector;

import ru.rasconsole.config.ServerConfig;

/**
 * Соединение с одним сервером RAS.
 * <p>
 * Все обращения идут через собственный однопоточный executor: вызовы одного сервера не пересекаются,
 * а UI-поток не блокируется. Учётные данные кластеров и ИБ запоминаются до конца сеанса работы и
 * переприменяются после переподключения.
 * <p>
 * Защита от «зависания» на недоступном сервере:
 * <ul>
 *   <li>подключение (вместе с поиском имени в DNS) ограничено {@link #CONNECT_TIMEOUT_MS};</li>
 *   <li>после неудачного подключения следующие {@link #RETRY_DELAY_MS} операции сразу получают ту же
 *       ошибку, а не ждут каждая свою попытку; {@link #resetRetry()} (действие пользователя) сбрасывает паузу;</li>
 *   <li>обычные запросы ограничены таймаутом из настроек сервера, а долгие операции (создание и удаление ИБ,
 *       регистрация кластера) идут через отдельный канал с таймаутом {@link #SLOW_TIMEOUT_MS}.</li>
 * </ul>
 */
public final class RasConnection {

    /** CHECKING — идёт подключение (статус ещё неизвестен), ERROR — подтверждённая неудача. */
    public enum State { DISCONNECTED, CHECKING, CONNECTED, ERROR }

    /** Сколько ждать установки соединения (включая DNS). */
    public static final long CONNECT_TIMEOUT_MS = 5_000;
    /** Пауза перед новой попыткой подключения после неудачи. */
    public static final long RETRY_DELAY_MS = 15_000;
    /** Таймаут ответа для долгих операций: создание ИБ с базой данных может идти минутами. */
    public static final long SLOW_TIMEOUT_MS = 600_000;

    /** Потоки для ожидания подключения с ограничением по времени (зависшее подключение не держит executor). */
    private static final ExecutorService CONNECT_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ras-connect");
        t.setDaemon(true);
        return t;
    });

    private static final Object AGENT_CONTEXT = new Object();

    private volatile ServerConfig config;
    private final ExecutorService executor;
    private final List<Consumer<RasConnection>> listeners = new CopyOnWriteArrayList<>();
    private final Channel normal = new Channel(false);
    private final Channel slow = new Channel(true);

    private volatile State state = State.DISCONNECTED;
    private volatile String lastError;
    private volatile String agentVersion;
    private volatile long lastFailureAt;

    private volatile Credentials agentCredentials;
    private final Map<UUID, Credentials> clusterCredentials = new ConcurrentHashMap<>();
    private final Map<UUID, List<Credentials>> infoBaseCredentials = new ConcurrentHashMap<>();

    /** Как открыть соединение; по умолчанию — через AgentAdminConnectorFactory. Подменяется в тестах. */
    private final Function<ServerConfig, IAgentAdminConnection> opener;

    public RasConnection(ServerConfig config) {
        this(config, null);
    }

    public RasConnection(ServerConfig config, Function<ServerConfig, IAgentAdminConnection> opener) {
        this.config = config;
        this.opener = opener;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ras-" + config.address());
            t.setDaemon(true);
            return t;
        });
    }

    public ServerConfig config() {
        return config;
    }

    /** Меняет параметры подключения; текущее соединение закрывается. */
    public CompletableFuture<Void> reconfigure(ServerConfig newConfig) {
        return CompletableFuture.runAsync(() -> {
            closeAll();
            config = newConfig;
            lastFailureAt = 0;
            setState(State.DISCONNECTED, null);
        }, executor);
    }

    /** Изменить параметры, не влияющие на подключение (имя, группа, пометка «продуктивный»). */
    public void updateConfig(ServerConfig newConfig) {
        if (!newConfig.address().equals(config.address()) || newConfig.timeoutSec() != config.timeoutSec()) {
            throw new IllegalArgumentException("Адрес или таймаут изменились — используйте reconfigure");
        }
        config = newConfig;
    }

    public State state() {
        return state;
    }

    /** Сервер недоступен (последняя попытка подключения или запрос завершились сетевой ошибкой). */
    public boolean isUnavailable() {
        return state == State.ERROR;
    }

    public String lastError() {
        return lastError;
    }

    public String agentVersion() {
        return agentVersion;
    }

    public void addListener(Consumer<RasConnection> l) {
        listeners.add(l);
    }

    public void removeListener(Consumer<RasConnection> l) {
        listeners.remove(l);
    }

    /** Разрешить новую попытку подключения, не дожидаясь паузы (явное действие пользователя). */
    public void resetRetry() {
        lastFailureAt = 0;
    }

    /** Подключиться сейчас (сбрасывает паузу после неудачной попытки). */
    public CompletableFuture<Void> connect() {
        resetRetry();
        return CompletableFuture.runAsync(normal::ensureConnected, executor);
    }

    public CompletableFuture<Void> disconnect() {
        return CompletableFuture.runAsync(() -> {
            closeAll();
            lastFailureAt = 0;
            setState(State.DISCONNECTED, null);
        }, executor);
    }

    /** Закрыть соединение и остановить поток (при выходе из программы). */
    public void shutdown() {
        try {
            executor.submit(this::closeAll).get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // выходим в любом случае
        }
        executor.shutdownNow();
    }

    // ---- учётные данные ----

    public void setAgentCredentials(Credentials c) {
        agentCredentials = c;
        resetAuth();
    }

    public Credentials agentCredentials() {
        return agentCredentials;
    }

    public void setClusterCredentials(UUID clusterId, Credentials c) {
        clusterCredentials.put(clusterId, c);
        resetAuth();
    }

    public Credentials clusterCredentials(UUID clusterId) {
        return clusterCredentials.get(clusterId);
    }

    public void addInfoBaseCredentials(UUID clusterId, Credentials c) {
        infoBaseCredentials.computeIfAbsent(clusterId, k -> new CopyOnWriteArrayList<>()).add(c);
        // При следующем обращении authenticate + addAuthentication будут выполнены заново
        resetAuth();
    }

    private void resetAuth() {
        normal.authContext = null;
        slow.authContext = null;
    }

    // ---- выполнение операций ----

    /**
     * Выполнить операцию в потоке соединения.
     * Если соединения нет — подключается. Ошибки авторизации превращаются в {@link AuthRequiredException}.
     */
    public <T> CompletableFuture<T> submit(AuthKind auth, UUID clusterId, RasFn<T> fn) {
        return submit(normal, auth, clusterId, fn);
    }

    /** Долгая операция (создание/удаление ИБ, регистрация кластера): отдельный канал с большим таймаутом. */
    public <T> CompletableFuture<T> submitSlow(AuthKind auth, UUID clusterId, RasFn<T> fn) {
        return submit(slow, auth, clusterId, fn);
    }

    private <T> CompletableFuture<T> submit(Channel ch, AuthKind auth, UUID clusterId, RasFn<T> fn) {
        CompletableFuture<T> f = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                f.complete(ch.execute(auth, clusterId, fn));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /**
     * Сервер сообщает об отказе в правах по-разному: AgentAdminAuthenticationException или обычным
     * AgentAdminException с текстом «… не аутентифицирован» (так делает 8.3.27). Различаем по тексту.
     */
    static boolean isAuthError(AgentAdminException e) {
        if (e instanceof AgentAdminAuthenticationException) return true;
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("не аутентифицирован") || m.contains("not authenticated")
            || m.contains("недостаточно прав") || m.contains("insufficient")
            || m.contains("неправильное имя") || m.contains("неверн") || m.contains("invalid user")
            || m.contains("authentication");
    }

    /** Чья авторизация нужна: по тексту ошибки, иначе — по виду операции. */
    static AuthKind classify(String message, AuthKind scope, UUID clusterId) {
        String m = message == null ? "" : message.toLowerCase();
        if (m.contains("центрального сервера") || m.contains("central server") || m.contains("агент")) {
            return AuthKind.AGENT;
        }
        if (clusterId != null && (m.contains("администратор кластера") || m.contains("управление кластером")
            || m.contains("cluster administrator") || m.contains("cluster management"))) {
            return AuthKind.CLUSTER;
        }
        if (clusterId != null && (m.contains("информационн") || m.contains("infobase"))) {
            return AuthKind.INFOBASE;
        }
        if (scope == AuthKind.NONE || clusterId == null) return AuthKind.AGENT;
        return scope;
    }

    private void closeAll() {
        normal.close();
        slow.close();
    }

    private void setState(State s, String error) {
        state = s;
        lastError = error;
        for (Consumer<RasConnection> l : listeners) {
            try {
                l.accept(this);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Одно соединение с RAS со своей авторизацией.
     * Текущая авторизация: AGENT_CONTEXT, ID кластера или null. RAS хранит на соединении только последнюю
     * авторизацию: authenticateAgent сбрасывает авторизацию кластера и наоборот (проверено на 8.3.27),
     * поэтому при смене контекста авторизуемся заново. Поля меняются только в потоке executor.
     */
    private final class Channel {
        private final boolean slowChannel;
        private IAgentAdminConnector connector;
        private IAgentAdminConnection connection;
        private volatile Object authContext;
        /** Были ли уже попытка подключения и успешное подключение (для однократного повтора первой проверки). */
        private boolean attempted;
        private boolean everConnected;

        Channel(boolean slowChannel) {
            this.slowChannel = slowChannel;
        }

        <T> T execute(AuthKind auth, UUID clusterId, RasFn<T> fn) throws Exception {
            ensureConnected();
            if (auth == AuthKind.AGENT || (clusterId == null && auth != AuthKind.NONE)) {
                ensureAgent();
            } else if (auth == AuthKind.CLUSTER || auth == AuthKind.INFOBASE) {
                ensureCluster(clusterId);
            }
            try {
                return fn.apply(connection);
            } catch (AgentAdminConnectionException e) {
                failed(e);
                throw e;
            } catch (AgentAdminException e) {
                if (!isAuthError(e)) throw e;
                AuthKind kind = classify(e.getMessage(), auth, clusterId);
                if (kind == AuthKind.AGENT && authContext != AGENT_CONTEXT) {
                    // Операция кластера, которой нужен агент (например, regCluster для изменения свойств):
                    // переключаемся на авторизацию агента и повторяем
                    try {
                        ensureAgent();
                        return fn.apply(connection);
                    } catch (AgentAdminConnectionException e2) {
                        failed(e2);
                        throw e2;
                    } catch (AgentAdminException e2) {
                        if (!isAuthError(e2)) throw e2;
                        e = e2;
                        kind = classify(e2.getMessage(), auth, clusterId);
                    }
                }
                authContext = null;
                throw new AuthRequiredException(kind, kind == AuthKind.AGENT ? null : clusterId, e);
            }
        }

        /** Сетевая ошибка: закрыть соединение, пометить сервер недоступным, включить паузу перед повтором. */
        private void failed(RuntimeException e) {
            close();
            lastFailureAt = System.currentTimeMillis();
            setState(State.ERROR, NetErrors.describe(e));
        }

        void ensureConnected() {
            if (connection != null) {
                return;
            }
            long since = System.currentTimeMillis() - lastFailureAt;
            if (lastFailureAt != 0 && since < RETRY_DELAY_MS) {
                // недавно не смогли подключиться — не ждём заново, сразу сообщаем ту же причину
                throw new AgentAdminConnectionException((lastError == null ? "Нет связи с сервером" : lastError)
                    + " (повторная попытка через " + ((RETRY_DELAY_MS - since) / 1000 + 1) + " с)");
            }
            ServerConfig cfg = config;
            // пока идёт подключение, сервер показывается как «проверка связи», а не «нет связи»
            // (уже подтверждённо недоступный сервер при повторных опросах остаётся красным — без мигания)
            if (!slowChannel && state == State.DISCONNECTED) setState(State.CHECKING, null);
            try {
                try {
                    open(cfg);
                } catch (RuntimeException first) {
                    // первую неудачу не считаем окончательной: холодный старт, DNS или VPN могут задержать
                    // самое первое подключение — повторяем один раз, и только потом сервер «краснеет»
                    if (everConnected || attempted) throw first;
                    attempted = true;
                    close();
                    open(cfg);
                }
                attempted = true;
                everConnected = true;
                authContext = null;
                if (!slowChannel) {
                    try {
                        agentVersion = connection.getAgentVersion();
                    } catch (RuntimeException e) {
                        agentVersion = null;
                    }
                }
                lastFailureAt = 0;
                if (state != State.CONNECTED) setState(State.CONNECTED, null);
            } catch (RuntimeException e) {
                failed(e);
                throw e;
            }
        }

        /** Открыть соединение (или тестовое — через opener). */
        private void open(ServerConfig cfg) {
            if (opener != null) {
                connection = opener.apply(cfg);
            } else {
                long requestTimeout = slowChannel ? SLOW_TIMEOUT_MS : cfg.timeoutSec() * 1000L;
                connector = new AgentAdminConnectorFactory().createConnector(requestTimeout);
                // ошибка библиотеки 1С: общий буфер кодека портит данные при одновременной работе соединений
                CodecFix.patch(connector);
                connection = connectWithDeadline(connector, cfg, Math.min(CONNECT_TIMEOUT_MS, cfg.timeoutSec() * 1000L));
            }
        }

        /** connector.connect с ограничением по времени: зависшая попытка бросается и закрывается в фоне. */
        private IAgentAdminConnection connectWithDeadline(IAgentAdminConnector c, ServerConfig cfg, long timeoutMs) {
            Future<IAgentAdminConnection> f = CONNECT_POOL.submit(() -> c.connect(cfg.host(), cfg.port()));
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                CONNECT_POOL.execute(() -> {
                    try {
                        c.shutdown();
                    } catch (RuntimeException ignored) {
                        // попытка подключения уже брошена
                    }
                });
                throw new AgentAdminConnectionException("Сервер " + cfg.address() + " не ответил за "
                    + timeoutMs / 1000 + " с");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new AgentAdminConnectionException(String.valueOf(cause), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentAdminConnectionException("Подключение прервано");
            }
        }

        /** Авторизация агента; без заданных учётных данных — пустая (агент без администраторов её принимает). */
        private void ensureAgent() {
            if (authContext == AGENT_CONTEXT) {
                return;
            }
            authContext = null;
            Credentials c = agentCredentials != null ? agentCredentials : Credentials.EMPTY;
            try {
                connection.authenticateAgent(c.user(), c.password());
                authContext = AGENT_CONTEXT;
            } catch (AgentAdminConnectionException e) {
                failed(e);
                throw e;
            } catch (AgentAdminException e) {
                if (!isAuthError(e)) throw e;
                throw new AuthRequiredException(AuthKind.AGENT, null, e);
            }
        }

        private void ensureCluster(UUID clusterId) {
            if (clusterId == null || clusterId.equals(authContext)) {
                return;
            }
            authContext = null;
            Credentials c = clusterCredentials.getOrDefault(clusterId, Credentials.EMPTY);
            try {
                connection.authenticate(clusterId, c.user(), c.password());
                for (Credentials ib : infoBaseCredentials.getOrDefault(clusterId, List.of())) {
                    connection.addAuthentication(clusterId, ib.user(), ib.password());
                }
            } catch (AgentAdminConnectionException e) {
                failed(e);
                throw e;
            } catch (AgentAdminException e) {
                if (!isAuthError(e)) throw e;
                throw new AuthRequiredException(AuthKind.CLUSTER, clusterId, e);
            }
            authContext = clusterId;
        }

        void close() {
            IAgentAdminConnector c = connector;
            connector = null;
            connection = null;
            authContext = null;
            if (c != null) {
                try {
                    c.shutdown();
                } catch (RuntimeException ignored) {
                    // соединение уже разорвано
                }
            }
        }
    }
}
