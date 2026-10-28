package ru.rasconsole.ui;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/** Форматирование значений API для таблиц и форм. */
public final class Fmt {

    public static final String DATE_PATTERN = "dd.MM.yyyy HH:mm:ss";

    /** Пустая дата 1С: библиотека кодирует {@code getTime() == 0} как 0 тиков. */
    public static final Date EMPTY_DATE = new Date(0);

    private static final Map<String, String> APPS = Map.ofEntries(
        Map.entry("1CV8", "Толстый клиент"),
        Map.entry("1CV8C", "Тонкий клиент"),
        Map.entry("WebClient", "Веб-клиент"),
        Map.entry("Designer", "Конфигуратор"),
        Map.entry("COMConnection", "COM-соединение"),
        Map.entry("COMConsole", "COM-администратор"),
        Map.entry("SrvrConsole", "Консоль кластера"),
        Map.entry("WSConnection", "Web-сервис"),
        Map.entry("HTTPServiceConnection", "HTTP-сервис"),
        Map.entry("BackgroundJob", "Фоновое задание"),
        Map.entry("JobScheduler", "Планировщик заданий"),
        Map.entry("RAS", "Сервер администрирования"),
        Map.entry("ODataConnection", "Стандартный OData-интерфейс"),
        Map.entry("MobileClient", "Мобильный клиент"));

    private Fmt() {
    }

    public static String app(String appId) {
        if (appId == null) return "";
        return APPS.getOrDefault(appId, appId);
    }

    public static boolean isEmptyDate(Date d) {
        return d == null || d.getTime() == 0;
    }

    public static String date(Date d) {
        return isEmptyDate(d) ? "" : new SimpleDateFormat(DATE_PATTERN).format(d);
    }

    /** Разбор даты; пустая строка — пустая дата 1С. */
    public static Date parseDate(String s) throws ParseException {
        if (s == null || s.isBlank()) return EMPTY_DATE;
        SimpleDateFormat f = new SimpleDateFormat(DATE_PATTERN);
        f.setLenient(false);
        String t = s.trim();
        if (t.length() == 10) t += " 00:00:00";
        else if (t.length() == 16) t += ":00";
        return f.parse(t);
    }

    public static String format(Object v) {
        if (v == null) return "";
        if (v instanceof Date d) return date(d);
        if (v instanceof Boolean b) return b ? "Да" : "Нет";
        if (v instanceof Double d) return String.format("%.2f", d);
        if (v instanceof Float d) return String.format("%.2f", d);
        if (v instanceof UUID u) return u.getMostSignificantBits() == 0 && u.getLeastSignificantBits() == 0 ? "" : u.toString();
        if (v instanceof Collection<?> c) {
            StringBuilder sb = new StringBuilder();
            for (Object o : c) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(o);
            }
            return sb.toString();
        }
        return v.toString();
    }

    /** Байты в человекочитаемый вид. */
    public static String bytes(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024 * 1024) return String.format("%.1f КБ", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f МБ", b / 1024.0 / 1024);
        return String.format("%.2f ГБ", b / 1024.0 / 1024 / 1024);
    }

    /** Понятный текст ошибки: сообщение сервера 1С или причина сетевой ошибки. */
    public static String rootMessage(Throwable t) {
        boolean connection = false;
        Throwable deepest = t;
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof com._1c.v8.ibis.admin.AgentAdminConnectionException) connection = true;
            deepest = c;
        }
        Throwable top = t;
        while ((top instanceof java.util.concurrent.CompletionException || top instanceof java.util.concurrent.ExecutionException)
            && top.getCause() != null) {
            top = top.getCause();
        }
        Throwable src = connection ? deepest : top;
        String msg = src.getMessage();
        if ((msg == null || msg.isBlank()) && src instanceof com._1c.v8.ibis.admin.AgentAdminException) {
            // так бывает, пока рабочий процесс кластера ещё запускается
            return "Сервер вернул ошибку без описания. Возможно, рабочий процесс кластера ещё запускается —"
                + " повторите через несколько секунд.";
        }
        if (msg == null || msg.isBlank()) msg = src.getClass().getSimpleName();
        return connection ? "Нет связи с сервером администрирования: " + ru.rasconsole.ras.NetErrors.describe(t) : msg;
    }
}
