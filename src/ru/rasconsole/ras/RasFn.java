package ru.rasconsole.ras;

import com._1c.v8.ibis.admin.IAgentAdminConnection;

/** Операция над соединением с RAS. Выполняется в потоке соединения. */
@FunctionalInterface
public interface RasFn<T> {
    T apply(IAgentAdminConnection c) throws Exception;

    /** Операция без результата. */
    static RasFn<Void> run(RasAction a) {
        return c -> {
            a.run(c);
            return null;
        };
    }

    @FunctionalInterface
    interface RasAction {
        void run(IAgentAdminConnection c) throws Exception;
    }
}
