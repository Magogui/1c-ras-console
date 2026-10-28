package ru.rasconsole.ras;

/** Имя и пароль администратора (агента, кластера или информационной базы). */
public record Credentials(String user, String password) {

    public static final Credentials EMPTY = new Credentials("", "");

    public Credentials {
        user = user == null ? "" : user;
        password = password == null ? "" : password;
    }

    /** Пароль не выводится, чтобы не попасть в логи и сообщения об ошибках. */
    @Override
    public String toString() {
        return "Credentials[user=" + user + ", password=" + (password.isEmpty() ? "" : "***") + "]";
    }
}
