package ru.rasconsole.ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;

/** Оформление: светлая (стандартная) или тёмная тема. Переключается сразу во всех открытых окнах. */
public final class Theme {

    public static final String LIGHT = "light";
    public static final String DARK = "dark";

    private static final String BASE = Theme.class.getResource("/ru/rasconsole/ui/console.css").toExternalForm();
    private static final String DARK_CSS = Theme.class.getResource("/ru/rasconsole/ui/dark.css").toExternalForm();

    private static String current = LIGHT;
    private static final List<WeakReference<ObservableList<String>>> targets = new ArrayList<>();

    private Theme() {
    }

    public static String current() {
        return current;
    }

    public static boolean isDark() {
        return DARK.equals(current);
    }

    /** Сменить тему во всех зарегистрированных окнах. */
    public static void set(String theme) {
        current = DARK.equals(theme) ? DARK : LIGHT;
        targets.removeIf(r -> r.get() == null);
        for (WeakReference<ObservableList<String>> r : targets) {
            ObservableList<String> s = r.get();
            if (s != null) update(s);
        }
    }

    /** Подключить стили к окну (главное окно, отдельные окна). */
    public static void apply(Scene scene) {
        register(scene.getStylesheets());
    }

    /** Подключить стили к диалогу. */
    public static void apply(DialogPane pane) {
        register(pane.getStylesheets());
    }

    private static void register(ObservableList<String> sheets) {
        targets.removeIf(r -> r.get() == null);
        boolean known = false;
        for (WeakReference<ObservableList<String>> r : targets) if (r.get() == sheets) known = true;
        if (!known) targets.add(new WeakReference<>(sheets));
        update(sheets);
    }

    private static void update(ObservableList<String> sheets) {
        if (!sheets.contains(BASE)) sheets.add(BASE);
        if (isDark()) {
            if (!sheets.contains(DARK_CSS)) sheets.add(DARK_CSS);
        } else {
            sheets.remove(DARK_CSS);
        }
    }
}
