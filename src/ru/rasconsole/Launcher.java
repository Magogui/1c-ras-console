package ru.rasconsole;

import javafx.application.Application;

/**
 * Точка входа. Отдельный класс (не наследник Application) нужен, чтобы приложение
 * запускалось через {@code java -jar} без проверки модулей JavaFX.
 */
public final class Launcher {

    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
