package ru.rasconsole.ui.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com._1c.v8.ibis.admin.IInfoBaseInfoShort;

import ru.rasconsole.ras.Platform1C;
import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fx;

/** Открытие ИБ в 1С, строка подключения и список баз (.v8i) для пользователей. */
final class OneC {

    private OneC() {
    }

    /** Адрес кластера для строки подключения: host или host:port (порт 1541 по умолчанию не пишется). */
    static String clusterAddress(ClusterCtx ctx) {
        return ctx.mainPort == 0 || ctx.mainPort == 1541 ? ctx.host : ctx.host + ":" + ctx.mainPort;
    }

    static String connectionString(ClusterCtx ctx, String ib) {
        return "Srvr=\"" + clusterAddress(ctx) + "\";Ref=\"" + ib + "\";";
    }

    /** Скопировать строку подключения в буфер обмена. */
    static void copyConnectionString(ClusterCtx ctx, String ib) {
        ClipboardContent cc = new ClipboardContent();
        String s = connectionString(ctx, ib);
        cc.putString(s);
        Clipboard.getSystemClipboard().setContent(cc);
        Fx.status("Скопировано: " + s);
    }

    /** Запустить тонкий клиент или Конфигуратор той же версии, что и сервер (если она установлена). */
    static void open(ClusterCtx ctx, String ib, boolean designer) {
        if (!safeArg(ib) || !safeArg(clusterAddress(ctx))) {
            Dialogs.warn("Имя базы или адрес кластера содержат недопустимые символы — запуск 1С отменён.");
            return;
        }
        String exe = designer ? "1cv8.exe" : "1cv8c.exe";
        Path p = Platform1C.find(exe, ctx.conn.agentVersion());
        if (p == null && !designer) p = Platform1C.find("1cv8.exe", ctx.conn.agentVersion());
        if (p == null) {
            Dialogs.warn("Не найдена платформа 1С на этом компьютере (" + exe + ").");
            return;
        }
        String version = Platform1C.version(p.toFile());
        try {
            new ProcessBuilder(p.toString(), designer ? "DESIGNER" : "ENTERPRISE", "/S", clusterAddress(ctx) + "\\" + ib)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            String warn = ctx.conn.agentVersion() != null && !ctx.conn.agentVersion().equals(version)
                ? " (версия клиента " + version + " отличается от сервера " + ctx.conn.agentVersion() + ")" : "";
            Fx.status((designer ? "Запущен Конфигуратор: " : "Запущена 1С: ") + ib + warn);
        } catch (IOException e) {
            Dialogs.error("Запуск 1С", e);
        }
    }

    /** Убрать переводы строк и управляющие символы: иначе значение с сервера добавило бы в файл свои параметры. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ");
    }

    /** Имя базы для командной строки 1С: без кавычек и управляющих символов. */
    private static boolean safeArg(String s) {
        return s != null && !s.isEmpty() && !s.contains("\"") && !s.matches("(?s).*\\p{Cntrl}.*");
    }

    /** Сохранить список выбранных баз в формате ibases.v8i — его можно раздать пользователям. */
    static void saveV8i(ClusterCtx ctx, List<IInfoBaseInfoShort> bases) {
        if (bases.isEmpty()) return;
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Список баз для 1С");
        fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Список информационных баз (*.v8i)", "*.v8i"));
        fc.setInitialFileName(ctx.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".v8i");
        java.io.File f = fc.showSaveDialog(null);
        if (f == null) return;
        StringBuilder sb = new StringBuilder("﻿");
        int order = 0;
        for (IInfoBaseInfoShort b : bases) {
            String title = oneLine(b.getDescr() == null || b.getDescr().isBlank() ? b.getName() : b.getDescr());
            sb.append('[').append(title.replace("[", "(").replace("]", ")")).append("]\r\n")
                .append("Connect=").append(oneLine(connectionString(ctx, b.getName()))).append("\r\n")
                .append("ID=").append(b.getInfoBaseId()).append("\r\n")
                .append("OrderInList=").append(order++).append("\r\n")
                .append("Folder=/").append(oneLine(ctx.name)).append("\r\n")
                .append("OrderInTree=0\r\n")
                .append("External=0\r\n")
                .append("ClientConnectionSpeed=Normal\r\n")
                .append("App=Auto\r\n")
                .append("WA=1\r\n")
                .append("Version=8.3\r\n");
        }
        try {
            Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
            Fx.status("Сохранено баз: " + bases.size() + " → " + f);
        } catch (IOException e) {
            Dialogs.error("Список баз", e);
        }
    }
}
