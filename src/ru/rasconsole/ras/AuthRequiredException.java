package ru.rasconsole.ras;

import java.util.UUID;

/** Сервер отклонил операцию из-за авторизации: нужно запросить учётные данные и повторить. */
public final class AuthRequiredException extends RuntimeException {

    private final AuthKind kind;
    private final UUID clusterId;

    public AuthRequiredException(AuthKind kind, UUID clusterId, Throwable cause) {
        super(cause == null || cause.getMessage() == null ? "Требуется авторизация" : cause.getMessage(), cause);
        this.kind = kind;
        this.clusterId = clusterId;
    }

    public AuthKind kind() {
        return kind;
    }

    public UUID clusterId() {
        return clusterId;
    }
}
