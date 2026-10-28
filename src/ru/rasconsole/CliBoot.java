package ru.rasconsole;

/**
 * Точка входа командной строки (RasCli). Сначала — команда {@code install-api}, ей библиотека 1С не нужна;
 * остальные команды запускаются, когда библиотека найдена (см. {@link Boot}).
 */
public final class CliBoot {

    private CliBoot() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("install-api")) {
            System.exit(Boot.installCommand(args));
        }
        Boot.run("ru.rasconsole.Cli", args, false);
    }
}
