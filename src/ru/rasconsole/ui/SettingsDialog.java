package ru.rasconsole.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;

import ru.rasconsole.config.CredentialStore;
import ru.rasconsole.ras.Credentials;

/** Настройки консоли: наблюдение, оповещения, история и журналы, оформление. */
final class SettingsDialog {

    private SettingsDialog() {
    }

    private static final List<String> SECURITY = List.of("ssl", "starttls", "none");
    private static final List<String> SECURITY_TEXT = List.of("SSL/TLS (обычно порт 465)", "STARTTLS (обычно 587)",
        "без шифрования (25)");

    static void open(Monitor monitor) {
        Monitor.Settings st = monitor.settings();
        Notifier.Channels ch = st.channels;
        CredentialStore cs = Fx.credentialStore();
        boolean canSave = cs != null && cs.available();
        Dialog<ButtonType> d = Dialogs.prepare(new Dialog<>(), "Настройки");
        d.setHeaderText("Наблюдение, оповещения, история, журналы и оформление");

        // ---- наблюдение ----
        TextField mem = num(st.memoryMb);
        TextField call = num(st.callSec);
        TextField dbProc = num(st.dbProcSec);
        TextField lockWait = num(st.lockWaitSec);
        TextField sessionsMax = num(st.sessionsMax);
        TextField licensesMax = num(st.licensesMax);
        TextField interval = num(st.intervalSec);
        CheckBox notify = new CheckBox("Наблюдать в фоне и показывать уведомления (трей и внешние каналы)");
        notify.setSelected(st.notify);
        CheckBox recovered = new CheckBox("Сообщать, когда связь с сервером восстановилась");
        recovered.setSelected(st.notifyRecovered);
        GridPane mon = grid();
        int r = 0;
        mon.add(title("Пороги"), 0, r++, 2, 1);
        mon.addRow(r++, new Label("Память рабочего процесса больше, МБ:"), mem);
        mon.addRow(r++, new Label("Время вызова (текущее) больше, с:"), call);
        mon.addRow(r++, new Label("Захвачено СУБД (текущее) больше, с:"), dbProc);
        mon.addRow(r++, new Label("Ожидание на блокировке дольше, с:"), lockWait);
        mon.addRow(r++, new Label("Сеансов в кластере больше:"), sessionsMax);
        mon.addRow(r++, new Label("Занято лицензий в кластере больше:"), licensesMax);
        mon.add(hint("0 — не проверять (для блокировок 0 — любое ожидание). Потеря связи — проблема всегда."), 0, r++, 2, 1);
        mon.add(new Separator(), 0, r++, 2, 1);
        mon.add(title("Фоновое наблюдение"), 0, r++, 2, 1);
        mon.addRow(r++, new Label("Интервал проверки, с (не меньше 15):"), interval);
        mon.add(notify, 0, r++, 2, 1);
        mon.add(recovered, 0, r++, 2, 1);
        mon.add(hint("Уведомление приходит только о новой проблеме — повторно о той же не напоминает."), 0, r++, 2, 1);

        // ---- оповещения ----
        CheckBox tg = new CheckBox("Telegram");
        tg.setSelected(ch.telegram);
        boolean tgSaved = canSave && cs.secret(Notifier.TELEGRAM_SECRET) != null || ch.telegramToken != null;
        PasswordField tgToken = new PasswordField();
        tgToken.setPromptText(tgSaved ? "сохранён — введите, чтобы заменить" : "токен бота от @BotFather");
        TextField tgChat = new TextField(ch.telegramChat);
        tgChat.setPromptText("chat_id: число (у групп — отрицательное) или @канал");
        CheckBox wh = new CheckBox("Webhook (POST JSON с полем text — Slack, Mattermost, Rocket.Chat, свой обработчик)");
        wh.setSelected(ch.webhook);
        TextField whUrl = new TextField(ch.webhookUrl);
        whUrl.setPromptText("https://…");
        CheckBox mail = new CheckBox("Электронная почта (SMTP)");
        mail.setSelected(ch.email);
        TextField smtpHost = new TextField(ch.smtpHost);
        smtpHost.setPromptText("smtp.example.ru");
        TextField smtpPort = num(ch.smtpPort);
        ChoiceBox<String> security = new ChoiceBox<>();
        security.getItems().setAll(SECURITY_TEXT);
        security.getSelectionModel().select(Math.max(0, SECURITY.indexOf(ch.smtpSecurity)));
        TextField smtpUser = new TextField(ch.smtpUser);
        smtpUser.setPromptText("пусто — без авторизации");
        boolean smtpSaved = canSave && cs.secret(Notifier.SMTP_SECRET) != null || ch.smtpPassword != null;
        PasswordField smtpPwd = new PasswordField();
        smtpPwd.setPromptText(smtpSaved ? "сохранён — введите, чтобы заменить" : "пароль");
        TextField from = new TextField(ch.mailFrom);
        from.setPromptText("пусто — как пользователь SMTP");
        TextField to = new TextField(ch.mailTo);
        to.setPromptText("адреса через запятую");
        Label testResult = new Label();
        testResult.setWrapText(true);
        Button test = new Button("Отправить тестовое сообщение");
        GridPane nt = grid();
        r = 0;
        nt.add(tg, 0, r++, 2, 1);
        nt.addRow(r++, new Label("Токен бота:"), tgToken);
        nt.addRow(r++, new Label("Чат:"), tgChat);
        nt.add(new Separator(), 0, r++, 2, 1);
        nt.add(wh, 0, r++, 2, 1);
        nt.addRow(r++, new Label("Адрес:"), whUrl);
        nt.add(new Separator(), 0, r++, 2, 1);
        nt.add(mail, 0, r++, 2, 1);
        nt.addRow(r++, new Label("SMTP-сервер и порт:"), new HBox(6, smtpHost, smtpPort));
        HBox.setHgrow(smtpHost, Priority.ALWAYS);
        smtpPort.setPrefColumnCount(5);
        nt.addRow(r++, new Label("Шифрование:"), security);
        nt.addRow(r++, new Label("Пользователь:"), smtpUser);
        nt.addRow(r++, new Label("Пароль:"), smtpPwd);
        nt.addRow(r++, new Label("Отправитель:"), from);
        nt.addRow(r++, new Label("Получатели:"), to);
        nt.add(new Separator(), 0, r++, 2, 1);
        nt.add(new HBox(10, test), 0, r++, 2, 1);
        nt.add(testResult, 0, r++, 2, 1);
        nt.add(hint("Оповещения уходят при включённом фоновом наблюдении (вкладка «Наблюдение»): те же проблемы, что в трее,"
            + " восстановление связи и запланированные работы. "
            + (canSave ? "Токен и пароль хранятся зашифрованными вместе с паролями серверов."
                : "Сохранить токен и пароль нельзя (" + (cs == null ? "нет хранилища" : cs.lastError())
                    + ") — они действуют до закрытия программы.")), 0, r++, 2, 1);
        for (Node n : List.of(tgToken, tgChat)) n.disableProperty().bind(tg.selectedProperty().not());
        whUrl.disableProperty().bind(wh.selectedProperty().not());
        for (Node n : List.of(smtpHost, smtpPort, security, smtpUser, smtpPwd, from, to)) {
            n.disableProperty().bind(mail.selectedProperty().not());
        }

        // ---- история и журналы ----
        CheckBox history = new CheckBox("Записывать историю показателей (для графиков «История…»)");
        history.setSelected(st.history);
        TextField historyDir = new TextField(st.historyDir == null || st.historyDir.isBlank()
            ? monitor.defaultHistoryPath().toString() : st.historyDir);
        TextField historyDays = num(st.historyDays);
        HBox historyRow = folderRow(historyDir, monitor.defaultHistoryPath());
        TextField auditDir = new TextField(st.auditDir == null || st.auditDir.isBlank()
            ? monitor.defaultAuditDir().toString() : st.auditDir);
        HBox auditRow = folderRow(auditDir, monitor.defaultAuditDir());
        GridPane hs = grid();
        r = 0;
        hs.add(title("История показателей"), 0, r++, 2, 1);
        hs.add(history, 0, r++, 2, 1);
        hs.add(hint("Раз в минуту по каждому кластеру — пока открыта сводка по серверам или включено фоновое наблюдение."),
            0, r++, 2, 1);
        hs.addRow(r++, new Label("Папка истории:"), historyRow);
        hs.addRow(r++, new Label("Хранить, дней:"), historyDays);
        hs.add(hint("В этой же папке — журнал входов и выходов наблюдаемых баз (sessions-ГГГГ-ММ.tsv)."), 0, r++, 2, 1);
        hs.add(new Separator(), 0, r++, 2, 1);
        hs.add(title("Журнал действий администратора"), 0, r++, 2, 1);
        hs.addRow(r++, new Label("Папка журнала (audit.log):"), auditRow);
        hs.add(hint("Журнал ведётся всегда. Командная строка RasCli пишет в ту же папку."), 0, r++, 2, 1);
        historyDays.disableProperty().bind(history.selectedProperty().not());

        // ---- оформление ----
        ChoiceBox<String> theme = new ChoiceBox<>();
        theme.getItems().setAll("Светлая", "Тёмная");
        theme.getSelectionModel().select(Theme.DARK.equals(st.theme) ? 1 : 0);
        GridPane look = grid();
        look.addRow(0, new Label("Тема:"), theme);
        look.add(hint("Применяется сразу ко всем окнам."), 0, 1, 2, 1);

        TabPane tabs = new TabPane(
            tab("Наблюдение", mon), tab("Оповещения", nt), tab("История и журналы", hs), tab("Оформление", look));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefWidth(880);
        tabs.setPrefHeight(600);
        d.getDialogPane().setContent(tabs);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // каналы из полей диалога (для проверки и сохранения)
        java.util.function.Supplier<Notifier.Channels> fromForm = () -> {
            Notifier.Channels c = ch.copy();
            c.telegram = tg.isSelected();
            c.telegramChat = tgChat.getText().trim();
            c.webhook = wh.isSelected();
            c.webhookUrl = whUrl.getText().trim();
            c.email = mail.isSelected();
            c.smtpHost = smtpHost.getText().trim();
            c.smtpPort = parse(smtpPort.getText(), 465);
            c.smtpSecurity = SECURITY.get(Math.max(0, security.getSelectionModel().getSelectedIndex()));
            c.smtpUser = smtpUser.getText().trim();
            c.mailFrom = from.getText().trim();
            c.mailTo = to.getText().trim();
            if (!tgToken.getText().isBlank()) c.telegramToken = tgToken.getText().trim();
            if (!smtpPwd.getText().isEmpty()) c.smtpPassword = smtpPwd.getText();
            return c;
        };
        test.setOnAction(e -> {
            Notifier.Channels c = fromForm.get();
            if (!c.any()) {
                testResult.setText("Не включён ни один канал.");
                return;
            }
            test.setDisable(true);
            testResult.setText("Отправка…");
            Thread t = new Thread(() -> {
                List<String> errors = Notifier.send(c, "Консоль RAS: проверка оповещений",
                    "Тестовое сообщение консоли администрирования RAS. Если вы его видите — канал настроен.");
                javafx.application.Platform.runLater(() -> {
                    test.setDisable(false);
                    testResult.setText(errors.isEmpty() ? "Отправлено во все включённые каналы." : String.join("\n", errors));
                });
            }, "notify-test");
            t.setDaemon(true);
            t.start();
        });

        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            for (TextField f : List.of(mem, call, dbProc, lockWait, sessionsMax, licensesMax, interval, historyDays, smtpPort)) {
                if (parse(f.getText(), -1) < 0) {
                    Dialogs.warn("Пороги, интервал, срок хранения и порт — целые неотрицательные числа");
                    e.consume();
                    return;
                }
            }
            if (parse(historyDays.getText(), 0) < 1) {
                Dialogs.warn("Срок хранения истории — не меньше 1 дня");
                e.consume();
                return;
            }
            String err = history.isSelected() ? checkFolder(historyDir.getText()) : null;
            if (err == null) err = checkFolder(auditDir.getText());
            if (err != null) {
                Dialogs.warn(err);
                e.consume();
            }
        });
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;
        st.memoryMb = parse(mem.getText(), st.memoryMb);
        st.callSec = parse(call.getText(), st.callSec);
        st.dbProcSec = parse(dbProc.getText(), st.dbProcSec);
        st.lockWaitSec = parse(lockWait.getText(), 0);
        st.sessionsMax = parse(sessionsMax.getText(), 0);
        st.licensesMax = parse(licensesMax.getText(), 0);
        st.intervalSec = Math.max(15, parse(interval.getText(), 60));
        st.notify = notify.isSelected();
        st.notifyRecovered = recovered.isSelected();
        st.history = history.isSelected();
        st.historyDays = parse(historyDays.getText(), 30);
        // папка по умолчанию хранится пустой строкой — тогда она переедет вместе с профилем пользователя
        st.historyDir = sameDir(historyDir.getText(), monitor.defaultHistoryPath()) ? "" : historyDir.getText().trim();
        st.auditDir = sameDir(auditDir.getText(), monitor.defaultAuditDir()) ? "" : auditDir.getText().trim();
        st.theme = theme.getSelectionModel().getSelectedIndex() == 1 ? Theme.DARK : Theme.LIGHT;

        Notifier.Channels c = fromForm.get();
        ch.telegram = c.telegram;
        ch.telegramChat = c.telegramChat;
        ch.webhook = c.webhook;
        ch.webhookUrl = c.webhookUrl;
        ch.email = c.email;
        ch.smtpHost = c.smtpHost;
        ch.smtpPort = c.smtpPort;
        ch.smtpSecurity = c.smtpSecurity;
        ch.smtpUser = c.smtpUser;
        ch.mailFrom = c.mailFrom;
        ch.mailTo = c.mailTo;
        // секреты: в зашифрованное хранилище, а если его нет — только в памяти до выхода
        if (!tgToken.getText().isBlank()) {
            if (canSave) {
                cs.putSecret(Notifier.TELEGRAM_SECRET, new Credentials("", tgToken.getText().trim()));
                ch.telegramToken = null;
            } else {
                ch.telegramToken = tgToken.getText().trim();
            }
        }
        if (!smtpPwd.getText().isEmpty()) {
            if (canSave) {
                cs.putSecret(Notifier.SMTP_SECRET, new Credentials(c.smtpUser, smtpPwd.getText()));
                ch.smtpPassword = null;
            } else {
                ch.smtpPassword = smtpPwd.getText();
            }
        }
        Theme.set(st.theme);
        monitor.settingsChanged();
        Fx.status("Настройки сохранены");
    }

    private static Tab tab(String name, GridPane content) {
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(content);
        sp.setFitToWidth(true);
        return new Tab(name, sp);
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(12));
        javafx.scene.layout.ColumnConstraints c0 = new javafx.scene.layout.ColumnConstraints();
        c0.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(420);
        g.getColumnConstraints().addAll(c0, c1);
        return g;
    }

    private static TextField num(int v) {
        TextField f = new TextField(Integer.toString(v));
        f.setPrefColumnCount(8);
        f.setMaxWidth(120);
        return f;
    }

    private static int parse(String s, int def) {
        try {
            int v = Integer.parseInt(s.trim());
            return v < 0 ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Поле папки с кнопками «Обзор…», «По умолчанию», «Открыть». */
    private static HBox folderRow(TextField field, Path def) {
        Button browse = new Button("Обзор…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            File cur = new File(field.getText().trim());
            if (cur.isDirectory()) dc.setInitialDirectory(cur);
            File f = dc.showDialog(field.getScene() == null ? null : field.getScene().getWindow());
            if (f != null) field.setText(f.getAbsolutePath());
        });
        Button reset = new Button("По умолчанию");
        reset.setOnAction(e -> field.setText(def.toString()));
        Button open = new Button("Открыть");
        open.setOnAction(e -> {
            try {
                Path p = Path.of(field.getText().trim());
                Files.createDirectories(p);
                openFolder(p);
            } catch (IOException | RuntimeException ex) {
                Dialogs.warn("Не удалось открыть папку: " + ex.getMessage());
            }
        });
        HBox row = new HBox(6, field, browse, reset, open);
        HBox.setHgrow(field, Priority.ALWAYS);
        return row;
    }

    /** Открыть папку в проводнике (Windows) или файловом менеджере (Linux). */
    static void openFolder(Path p) throws IOException {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        new ProcessBuilder(win ? ru.rasconsole.config.CredentialStore.systemExe("explorer.exe") : "/usr/bin/xdg-open",
            p.toAbsolutePath().toString()).start();
    }

    /** Папка должна существовать (или создаваться) и быть доступна для записи. */
    private static String checkFolder(String text) {
        if (text == null || text.isBlank()) return "Не указана папка";
        try {
            Path p = Path.of(text.trim());
            Files.createDirectories(p);
            Path probe = Files.createTempFile(p, "rasconsole", ".tmp");
            Files.delete(probe);
            return null;
        } catch (IOException | RuntimeException e) {
            return "Нет доступа на запись в папку «" + text.trim() + "»: " + e.getMessage();
        }
    }

    private static boolean sameDir(String text, Path def) {
        try {
            return Path.of(text.trim()).toAbsolutePath().normalize().equals(def.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Label title(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static Label hint(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.getStyleClass().add("hint-label");
        return l;
    }
}
