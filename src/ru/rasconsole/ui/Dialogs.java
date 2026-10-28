package ru.rasconsole.ui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import ru.rasconsole.config.ServerConfig;
import ru.rasconsole.ras.Credentials;
import ru.rasconsole.ui.form.BeanForm;
import ru.rasconsole.ui.table.Col;
import ru.rasconsole.ui.table.EntityTable;

/** Стандартные диалоги консоли. */
public final class Dialogs {

    private static Window owner;

    private Dialogs() {
    }

    public static void setOwner(Window w) {
        owner = w;
    }

    /** Подготовить окно: владелец — главное окно, заголовок, изменяемый размер. */
    public static <D extends Dialog<?>> D prepare(D d, String title) {
        return init(d, title);
    }

    private static <D extends Dialog<?>> D init(D d, String title) {
        if (owner != null) d.initOwner(owner);
        Theme.apply(d.getDialogPane());
        // кнопки диалога оформляются, когда содержимое уже собрано
        d.showingProperty().addListener((o, was, now) -> {
            if (now) Buttons.decorateAll(d.getDialogPane());
        });
        d.setTitle(title);
        d.setResizable(true);
        return d;
    }

    public static void error(String header, Throwable t) {
        Alert a = init(new Alert(Alert.AlertType.ERROR), "Ошибка");
        a.setHeaderText(header);
        a.setContentText(Fmt.rootMessage(t));
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        TextArea area = new TextArea(sw.toString());
        area.setEditable(false);
        area.setPrefRowCount(14);
        a.getDialogPane().setExpandableContent(area);
        a.showAndWait();
    }

    public static void info(String header, String text) {
        Alert a = init(new Alert(Alert.AlertType.INFORMATION), "Информация");
        a.setHeaderText(header);
        a.setContentText(text);
        a.showAndWait();
    }

    public static boolean confirm(String header, String text) {
        Alert a = init(new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO), "Подтверждение");
        a.setHeaderText(header);
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    /**
     * Подтверждение опасного действия. Для продуктивного сервера — с предупреждением и флажком,
     * без которого кнопка «Да» недоступна.
     */
    public static boolean confirmDanger(ServerConfig server, String header, String text) {
        if (server == null || !server.prod()) return confirm(header, text);
        Alert a = init(new Alert(Alert.AlertType.WARNING, null, ButtonType.YES, ButtonType.NO), "Продуктивный сервер");
        a.setHeaderText(header);
        Label warn = new Label("ПРОДУКТИВНЫЙ СЕРВЕР: " + server.display());
        warn.getStyleClass().add("prod-warning");
        Label body = new Label(text);
        body.setWrapText(true);
        CheckBox sure = new CheckBox("Я понимаю, что действие выполняется на продуктивном сервере");
        a.getDialogPane().setContent(new VBox(10, warn, body, sure));
        Button yes = (Button) a.getDialogPane().lookupButton(ButtonType.YES);
        yes.disableProperty().bind(sure.selectedProperty().not());
        ((Button) a.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
        yes.setDefaultButton(false);
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }

    public static Optional<String> text(String title, String header, String initial) {
        TextInputDialog d = init(new TextInputDialog(initial == null ? "" : initial), title);
        d.setHeaderText(header);
        return d.showAndWait();
    }

    /** Введённые имя и пароль и флажок «Запомнить пароль». */
    public record Login(Credentials credentials, boolean remember) {
    }

    /**
     * Запрос имени и пароля.
     * @param canRemember показать флажок «Запомнить пароль»
     * @param remember    начальное состояние флажка
     */
    public static Optional<Login> credentials(String title, String header, String user, boolean canRemember, boolean remember) {
        Dialog<Login> d = init(new Dialog<>(), title);
        d.setHeaderText(header);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField u = new TextField(user == null ? "" : user);
        PasswordField p = new PasswordField();
        CheckBox keep = new CheckBox("Запомнить пароль (зашифрован ключом вашей учётной записи Windows)");
        keep.setSelected(remember);
        GridPane g = grid();
        g.addRow(0, new Label("Имя:"), u);
        g.addRow(1, new Label("Пароль:"), p);
        if (canRemember) g.add(keep, 1, 2);
        d.getDialogPane().setContent(g);
        Platform.runLater(() -> (u.getText().isEmpty() ? u : p).requestFocus());
        d.setResultConverter(b -> b == ButtonType.OK
            ? new Login(new Credentials(u.getText().trim(), p.getText()), canRemember && keep.isSelected()) : null);
        return d.showAndWait();
    }

    /**
     * Выбор серверов из списка флажками (для экспорта и импорта).
     * @param checked какие отмечены изначально
     * @param note    пометка справа от сервера (может вернуть null)
     */
    public static Optional<List<ServerConfig>> chooseServers(String title, String header, List<ServerConfig> servers,
                                                             java.util.function.Predicate<ServerConfig> checked,
                                                             java.util.function.Function<ServerConfig, String> note) {
        Dialog<List<ServerConfig>> d = init(new Dialog<>(), title);
        d.setHeaderText(header);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        List<CheckBox> boxes = new java.util.ArrayList<>();
        VBox list = new VBox(6);
        list.setPadding(new Insets(8));
        for (ServerConfig s : servers) {
            String text = (s.name().equals(s.host()) ? s.address() : s.name() + " (" + s.address() + ")");
            String extra = note == null ? null : note.apply(s);
            CheckBox cb = new CheckBox(extra == null ? text : text + " — " + extra);
            cb.setSelected(checked.test(s));
            cb.setMnemonicParsing(false);
            cb.setUserData(s);
            boxes.add(cb);
            list.getChildren().add(cb);
        }
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(list);
        sp.setFitToWidth(true);
        sp.setPrefSize(520, Math.min(60 + servers.size() * 26, 420));
        Button all = new Button("Выбрать все");
        all.setOnAction(e -> boxes.forEach(b -> b.setSelected(true)));
        Button none = new Button("Снять все");
        none.setOnAction(e -> boxes.forEach(b -> b.setSelected(false)));
        Label count = new Label();
        Runnable recount = () -> count.setText("Выбрано: " + boxes.stream().filter(CheckBox::isSelected).count()
            + " из " + boxes.size());
        boxes.forEach(b -> b.selectedProperty().addListener((o, was, now) -> recount.run()));
        recount.run();
        javafx.scene.layout.HBox buttons = new javafx.scene.layout.HBox(8, all, none, count);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        d.getDialogPane().setContent(new VBox(8, buttons, sp));
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            if (boxes.stream().noneMatch(CheckBox::isSelected)) {
                warn("Не выбран ни один сервер");
                e.consume();
            }
        });
        d.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            List<ServerConfig> r = new java.util.ArrayList<>();
            for (CheckBox cb : boxes) if (cb.isSelected()) r.add((ServerConfig) cb.getUserData());
            return r;
        });
        return d.showAndWait();
    }

    /** Окно выбора файла списка серверов. */
    public static java.io.File chooseServerFile(boolean save) {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle(save ? "Экспорт списка серверов" : "Импорт списка серверов");
        fc.getExtensionFilters().addAll(
            new javafx.stage.FileChooser.ExtensionFilter("Список серверов RAS (*.rasservers)", "*.rasservers"),
            new javafx.stage.FileChooser.ExtensionFilter("Все файлы", "*.*"));
        if (save) fc.setInitialFileName("servers.rasservers");
        return save ? fc.showSaveDialog(owner) : fc.showOpenDialog(owner);
    }

    /** Параметры сервера RAS. */
    public static Optional<ServerConfig> server(ServerConfig initial, boolean isNew, java.util.Collection<String> groups) {
        Dialog<ServerConfig> d = init(new Dialog<>(), isNew ? "Новый сервер RAS" : "Параметры сервера RAS");
        d.setHeaderText("Сервер администрирования (ras) кластера 1С:Предприятия");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField host = new TextField(initial == null ? "localhost" : initial.host());
        TextField port = new TextField(Integer.toString(initial == null ? ServerConfig.DEFAULT_PORT : initial.port()));
        TextField name = new TextField(initial == null ? "" : initial.name());
        name.setPromptText("по умолчанию — имя компьютера");
        TextField timeout = new TextField(Integer.toString(initial == null ? ServerConfig.DEFAULT_TIMEOUT_SEC : initial.timeoutSec()));
        TextField agentUser = new TextField(initial == null ? "" : initial.agentUser());
        agentUser.setPromptText("если заданы администраторы центрального сервера");
        javafx.scene.control.ComboBox<String> group = new javafx.scene.control.ComboBox<>();
        group.setEditable(true);
        group.getItems().addAll(new java.util.TreeSet<>(groups));
        group.getEditor().setText(initial == null ? "" : initial.group());
        group.setPromptText("например: Прод, Тест (пусто — без группы)");
        group.setMaxWidth(Double.MAX_VALUE);
        CheckBox prod = new CheckBox("Продуктивный сервер (опасные действия — с дополнительным подтверждением)");
        prod.setSelected(initial != null && initial.prod());
        GridPane g = grid();
        g.addRow(0, new Label("Компьютер:"), host);
        g.addRow(1, new Label("IP-порт RAS:"), port);
        g.addRow(2, new Label("Отображаемое имя:"), name);
        g.addRow(3, new Label("Группа:"), group);
        g.addRow(4, new Label("Таймаут ответа, с:"), timeout);
        timeout.setTooltip(new javafx.scene.control.Tooltip("Сколько ждать ответа на обычный запрос. Подключение ограничено 5 с,"
            + "\nдолгие операции (создание и удаление ИБ, регистрация кластера) ждут до 10 минут."));
        g.addRow(5, new Label("Администратор агента:"), agentUser);
        g.add(prod, 1, 6);
        d.getDialogPane().setContent(g);
        Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            try {
                if (host.getText().isBlank()) throw new IllegalArgumentException("Укажите компьютер");
                if (!ServerConfig.validHost(host.getText().trim())) {
                    throw new IllegalArgumentException("Компьютер — имя или IP-адрес: буквы, цифры, точка, дефис"
                        + " (без пробелов, без «-» в начале); IPv6 — в квадратных скобках");
                }
                if (!ServerConfig.validPort(Integer.parseInt(port.getText().trim()))) {
                    throw new IllegalArgumentException("Порт — от 1 до 65535");
                }
                Integer.parseInt(timeout.getText().trim());
            } catch (IllegalArgumentException ex) {
                warn(ex instanceof NumberFormatException ? "Порт и таймаут — целые числа" : ex.getMessage());
                e.consume();
            }
        });
        Platform.runLater(host::requestFocus);
        d.setResultConverter(b -> b != ButtonType.OK ? null : new ServerConfig(
            name.getText(), host.getText(), Integer.parseInt(port.getText().trim()),
            Integer.parseInt(timeout.getText().trim()), agentUser.getText().trim(),
            group.getEditor().getText(), prod.isSelected()));
        return d.showAndWait();
    }

    /**
     * Диалог свойств объекта API.
     * @param extra дополнительный элемент под формой (может быть null)
     * @return true, если нажата OK и значения записаны в объект
     */
    public static boolean editBean(String title, Object bean, Class<?> type, List<String> only, boolean editable, Node extra) {
        Dialog<ButtonType> d = init(new Dialog<>(), title);
        BeanForm form = new BeanForm(bean, type, only, editable);
        VBox box = new VBox(8, form.node());
        VBox.setVgrow(box.getChildren().get(0), Priority.ALWAYS);
        if (extra != null) {
            box.getChildren().add(extra);
            VBox.setMargin(extra, new Insets(0, 10, 10, 10));
        }
        box.setPrefSize(760, Math.min(160 + form.grid().getChildren().size() / 2 * 32, 760));
        d.getDialogPane().setContent(box);
        if (editable) {
            d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
            ok.addEventFilter(ActionEvent.ACTION, e -> {
                try {
                    form.apply();
                } catch (IllegalArgumentException ex) {
                    warn(ex.getMessage());
                    e.consume();
                }
            });
        } else {
            d.getDialogPane().getButtonTypes().add(new ButtonType("Закрыть", ButtonBar.ButtonData.CANCEL_CLOSE));
        }
        return d.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /** Режим удаления ИБ: 0 — не удалять БД, 1 — удалить БД, 2 — очистить БД. */
    public static Optional<Integer> dropInfoBaseMode(String ibName) {
        Dialog<Integer> d = init(new Dialog<>(), "Удаление информационной базы");
        d.setHeaderText("Удалить информационную базу «" + ibName + "» из кластера?");
        ToggleGroup g = new ToggleGroup();
        RadioButton keep = new RadioButton("Оставить базу данных без изменений");
        RadioButton drop = new RadioButton("Удалить базу данных");
        RadioButton clear = new RadioButton("Очистить базу данных");
        for (RadioButton r : List.of(keep, drop, clear)) r.setToggleGroup(g);
        keep.setSelected(true);
        d.getDialogPane().setContent(new VBox(8, keep, drop, clear));
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(b -> b != ButtonType.OK ? null : drop.isSelected() ? 1 : clear.isSelected() ? 2 : 0);
        return d.showAndWait();
    }

    /** Флажок «создать базу данных» для диалога создания ИБ. */
    public static CheckBox createDbCheckBox() {
        CheckBox cb = new CheckBox("Создать базу данных в случае её отсутствия");
        cb.setSelected(true);
        return cb;
    }

    /** Показать таблицу (например, значения счётчика ресурсов). */
    public static <T> void table(String title, List<T> rows, List<Col<T>> cols) {
        Dialog<Void> d = init(new Dialog<>(), title);
        EntityTable<T> t = new EntityTable<>(cols, null);
        t.setItems(rows);
        t.view().setPrefSize(1000, 450);
        Button csv = new Button("Экспорт в CSV…");
        csv.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv"));
            fc.setInitialFileName("export.csv");
            java.io.File f = fc.showSaveDialog(d.getDialogPane().getScene().getWindow());
            if (f == null) return;
            try {
                t.exportCsv(f.toPath());
            } catch (java.io.IOException ex) {
                error("Экспорт в CSV", ex);
            }
        });
        javafx.scene.layout.HBox top = new javafx.scene.layout.HBox(8, t.filterField(), csv,
            new Label("Строк: " + rows.size()));
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox box = new VBox(8, top, t.view());
        VBox.setVgrow(t.view(), Priority.ALWAYS);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(new ButtonType("Закрыть", ButtonBar.ButtonData.CANCEL_CLOSE));
        d.showAndWait();
    }

    public static void warn(String text) {
        Alert a = init(new Alert(Alert.AlertType.WARNING, text, ButtonType.OK), "Проверка");
        a.setHeaderText(null);
        a.showAndWait();
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(10));
        g.setMinWidth(420);
        return g;
    }
}
