package ru.rasconsole.ui;

import java.util.UUID;

import ru.rasconsole.ras.AuthKind;
import ru.rasconsole.ras.RasConnection;

/**
 * Где и с какой авторизацией выполняется операция.
 * @param target имя кластера или ИБ — для заголовка диалога авторизации
 * @param slow   долгая операция (создание/удаление ИБ, регистрация кластера) — идёт с большим таймаутом
 */
public record Scope(RasConnection conn, AuthKind auth, UUID clusterId, String target, boolean slow) {

    public Scope(RasConnection conn, AuthKind auth, UUID clusterId, String target) {
        this(conn, auth, clusterId, target, false);
    }

    public Scope with(AuthKind newAuth, String newTarget) {
        return new Scope(conn, newAuth, clusterId, newTarget, slow);
    }

    /** Та же область для долгой операции. */
    public Scope asSlow() {
        return new Scope(conn, auth, clusterId, target, true);
    }
}
