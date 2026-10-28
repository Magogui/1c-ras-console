package ru.rasconsole.ui;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import javafx.application.Platform;
import ru.rasconsole.config.CredentialStore;
import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.AuthRequiredException;
import ru.rasconsole.ras.Credentials;
import ru.rasconsole.ras.RasConnection;
import ru.rasconsole.ras.RasFn;

/**
 * Асинхронный вызов RAS из UI: операция выполняется в потоке соединения, результат возвращается
 * в FX-поток. При отказе в авторизации спрашивает учётные данные и повторяет операцию.
 */
public final class Fx {

    private static final int MAX_AUTH_ATTEMPTS = 5;
    private static Consumer<String> status = s -> { };

    private Fx() {
    }

    public static void setStatusSink(Consumer<String> sink) {
        status = sink;
    }

    public static void status(String s) {
        status.accept(s);
    }

    /**
     * Изменяющая операция: как {@link #call}, но результат (успех или ошибка) пишется в журнал действий.
     */
    public static <T> void change(Scope scope, String what, RasFn<T> fn, Consumer<T> ok) {
        call(scope, what, fn, r -> {
            audit(scope, what, "OK");
            ok.accept(r);
        }, err -> {
            if (isAuthCancel(err)) return;
            audit(scope, what, "ошибка: " + Fmt.rootMessage(err));
            Dialogs.error(what, err);
        });
    }

    /** Записать действие в журнал (сервер и объект берутся из scope). */
    public static void audit(Scope scope, String what, String result) {
        ru.rasconsole.config.AuditLog.write(scope.conn().config().display(), scope.target(), what, result);
    }

    /** Вызов с показом ошибки в диалоге. */
    public static <T> void call(Scope scope, String what, RasFn<T> fn, Consumer<T> ok) {
        call(scope, what, fn, ok, null);
    }

    /**
     * @param what описание операции для строки состояния и заголовка ошибки
     * @param fail обработчик ошибки; null — показать диалог ошибки. Отмена авторизации ошибкой не считается
     *             и приходит в fail как {@link AuthRequiredException}
     */
    public static <T> void call(Scope scope, String what, RasFn<T> fn, Consumer<T> ok, Consumer<Throwable> fail) {
        attempt(scope, what, fn, ok, fail, 0, null);
    }

    /**
     * @param save сохранить введённые учётные данные — выполняется, только если сервер их принял
     */
    private static <T> void attempt(Scope scope, String what, RasFn<T> fn, Consumer<T> ok, Consumer<Throwable> fail,
                                    int n, Runnable save) {
        RasConnection conn = scope.conn();
        status(what + "…");
        (scope.slow() ? conn.submitSlow(scope.auth(), scope.clusterId(), fn) : conn.submit(scope.auth(), scope.clusterId(), fn))
            .whenComplete((r, ex) -> Platform.runLater(() -> {
            Throwable t = ex == null ? null : unwrap(ex);
            // операция прошла — значит, введённый пароль сервер принял
            if (save != null && ex == null) save.run();
            if (ex == null) {
                status("Готово: " + what);
                ok.accept(r);
                return;
            }
            if (t instanceof AuthRequiredException a && n < MAX_AUTH_ATTEMPTS) {
                Optional<Dialogs.Login> login = askCredentials(scope, a, n > 0);
                if (login.isPresent()) {
                    Credentials cred = login.get().credentials();
                    String address = conn.config().address();
                    UUID cid = a.clusterId();
                    Runnable remember = null;
                    switch (a.kind()) {
                        case AGENT -> {
                            conn.setAgentCredentials(cred);
                            remember = () -> credentialStore.putAgent(address, cred);
                        }
                        case CLUSTER -> {
                            conn.setClusterCredentials(cid, cred);
                            remember = () -> credentialStore.putCluster(address, cid, cred);
                        }
                        case INFOBASE -> {
                            conn.addInfoBaseCredentials(cid, cred);
                            remember = () -> credentialStore.putInfoBase(address, cid, cred);
                        }
                        default -> { }
                    }
                    boolean keep = login.get().remember() && credentialStore != null;
                    attempt(scope, what, fn, ok, fail, n + 1, keep ? remember : null);
                    return;
                }
                status("Отменено: " + what);
                if (fail != null) fail.accept(t);
                return;
            }
            status("Ошибка: " + what + " — " + Fmt.rootMessage(t));
            if (fail != null) {
                fail.accept(t);
            } else {
                Dialogs.error(what, t);
            }
        }));
    }

    private static Optional<Dialogs.Login> askCredentials(Scope scope, AuthRequiredException a, boolean retry) {
        RasConnection conn = scope.conn();
        String server = conn.config().address();
        String target = scope.target() == null ? "" : "«" + scope.target() + "»";
        String header = switch (a.kind()) {
            case AGENT -> "Администратор центрального сервера " + server;
            case CLUSTER -> "Администратор кластера " + target + " на " + server;
            case INFOBASE -> "Пользователь информационной базы " + target
                + "\n(администратор ИБ, если в базе есть пользователи)";
            default -> "Авторизация";
        };
        if (retry || a.getMessage() != null) {
            header += "\n\nСервер: " + a.getMessage();
        }
        header += "\nЧерез RAS работает только вход по имени и паролю (аутентификация ОС не поддерживается).";
        String user = "";
        boolean saved = false;
        String address = conn.config().address();
        if (a.kind() == AuthKind.AGENT) {
            Credentials c = conn.agentCredentials();
            user = c != null ? c.user() : conn.config().agentUser();
            saved = credentialStore != null && credentialStore.agent(address) != null;
        } else if (a.kind() == AuthKind.CLUSTER && a.clusterId() != null) {
            Credentials c = conn.clusterCredentials(a.clusterId());
            if (c != null) user = c.user();
            saved = credentialStore != null && credentialStore.hasCluster(address, a.clusterId());
        }
        if (saved) header += "\nСохранённый пароль не подошёл — введите новый.";
        boolean canRemember = credentialStore != null && credentialStore.available();
        return Dialogs.credentials("Авторизация", header, user, canRemember, saved);
    }

    private static CredentialStore credentialStore;

    /** Хранилище паролей для флажка «Запомнить пароль» (null — сохранение отключено). */
    public static void setCredentialStore(CredentialStore store) {
        credentialStore = store;
    }

    public static CredentialStore credentialStore() {
        return credentialStore;
    }

    public static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
            && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static boolean isAuthCancel(Throwable t) {
        return unwrap(t) instanceof AuthRequiredException;
    }
}
