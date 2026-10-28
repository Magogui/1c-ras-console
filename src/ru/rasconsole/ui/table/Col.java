package ru.rasconsole.ui.table;

import java.util.function.Function;

import ru.rasconsole.ui.Fmt;

/**
 * Колонка таблицы: значение (по нему же сортировка) и способ отображения.
 */
public record Col<T>(String title, Function<T, Object> value, Function<Object, String> text, double width) {

    public static <T> Col<T> of(String title, Function<T, Object> value) {
        return new Col<>(title, value, Fmt::format, 0);
    }

    public static <T> Col<T> of(String title, double width, Function<T, Object> value) {
        return new Col<>(title, value, Fmt::format, width);
    }

    /** Колонка с объёмом в байтах. */
    public static <T> Col<T> bytes(String title, Function<T, Object> value) {
        return new Col<>(title, value, v -> v instanceof Number n ? Fmt.bytes(n.longValue()) : Fmt.format(v), 0);
    }

    public Col<T> width(double w) {
        return new Col<>(title, value, text, w);
    }

    public Col<T> text(Function<Object, String> f) {
        return new Col<>(title, value, f, width);
    }
}
