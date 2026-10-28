package ru.rasconsole.ui.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import com._1c.v8.ibis.admin.IInfoBaseInfo;
import com._1c.v8.ibis.admin.IInfoBaseInfoShort;

import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;
import ru.rasconsole.ui.table.Col;

/**
 * Изменение одних и тех же параметров сразу у нескольких информационных баз: блокировка регламентных заданий,
 * выдача лицензий, профили безопасности, параметры СУБД. Меняются только отмеченные параметры.
 */
final class BulkEdit {

    private BulkEdit() {
    }

    /** Параметр: флажок «изменить», поле значения и как применить его к базе. */
    private record Param(CheckBox change, Node field, Consumer<IInfoBaseInfo> apply, java.util.function.Supplier<String> text) {
    }

    record Result(String base, String result) {
    }

    static void open(ClusterCtx ctx, List<IInfoBaseInfoShort> bases, Runnable after) {
        if (bases.isEmpty()) return;
        Dialog<ButtonType> d = Dialogs.prepare(new Dialog<>(), "Изменение параметров нескольких баз");
        d.setHeaderText("Базы (" + bases.size() + "): " + names(bases)
            + "\nОтметьте параметры, которые нужно изменить; остальные останутся как есть.");

        CheckBox jobs = new CheckBox("запрещены");
        ChoiceBox<String> lic = new ChoiceBox<>();
        lic.getItems().setAll("Запрещена", "Разрешена");
        lic.getSelectionModel().select(1);
        TextField sp = new TextField();
        sp.setPromptText("пусто — без профиля");
        TextField safeSp = new TextField();
        safeSp.setPromptText("пусто — без профиля");
        CheckBox reserve = new CheckBox("резервировать");
        TextField dbServer = new TextField();
        TextField dbUser = new TextField();
        PasswordField dbPwd = new PasswordField();
        TextField offset = new TextField("0");
        offset.setPromptText("0 или 2000");

        List<Param> params = new ArrayList<>();
        params.add(param("Блокировка регламентных заданий", jobs, i -> i.setScheduledJobsDenied(jobs.isSelected()),
            () -> "регл. задания " + (jobs.isSelected() ? "запрещены" : "разрешены")));
        params.add(param("Выдача лицензий сервером 1С", lic, i -> i.setLicenseDistributionAllowed(lic.getSelectionModel().getSelectedIndex()),
            () -> "выдача лицензий: " + lic.getValue()));
        params.add(param("Профиль безопасности", sp, i -> i.setSecurityProfileName(sp.getText().trim()),
            () -> "профиль «" + sp.getText().trim() + "»"));
        params.add(param("Профиль безопасного режима", safeSp, i -> i.setSafeModeSecurityProfileName(safeSp.getText().trim()),
            () -> "профиль безопасного режима «" + safeSp.getText().trim() + "»"));
        params.add(param("Резервирование рабочих процессов", reserve, i -> i.setReserveWorkingProcesses(reserve.isSelected()),
            () -> "резервирование " + (reserve.isSelected() ? "вкл." : "выкл.")));
        params.add(param("Сервер баз данных", dbServer, i -> i.setDbServerName(dbServer.getText().trim()),
            () -> "сервер СУБД " + dbServer.getText().trim()));
        params.add(param("Пользователь СУБД", dbUser, i -> i.setDbUser(dbUser.getText().trim()),
            () -> "пользователь СУБД " + dbUser.getText().trim()));
        params.add(param("Пароль пользователя СУБД", dbPwd, i -> i.setDbPassword(dbPwd.getText()), () -> "пароль СУБД"));
        params.add(param("Смещение дат", offset, i -> i.setDateOffset(Integer.parseInt(offset.getText().trim())),
            () -> "смещение дат " + offset.getText().trim()));

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        int r = 0;
        for (Param p : params) {
            p.field().disableProperty().bind(p.change().selectedProperty().not());
            g.addRow(r++, p.change(), p.field());
        }
        Label warn = new Label("Для изменения нужны права администратора каждой базы — пароль спросят, если он не сохранён.");
        warn.getStyleClass().add("hint-label");
        warn.setWrapText(true);
        VBox box = new VBox(10, g, warn);
        box.setPadding(new Insets(10));
        box.setPrefWidth(640);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        javafx.scene.control.Button ok = (javafx.scene.control.Button) d.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (params.stream().noneMatch(p -> p.change().isSelected())) {
                Dialogs.warn("Не отмечен ни один параметр");
                e.consume();
                return;
            }
            if (params.get(8).change().isSelected()) {
                try {
                    Integer.parseInt(offset.getText().trim());
                } catch (NumberFormatException ex) {
                    Dialogs.warn("Смещение дат — целое число (0 или 2000)");
                    e.consume();
                }
            }
        });
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) return;

        List<Param> chosen = new ArrayList<>();
        for (Param p : params) if (p.change().isSelected()) chosen.add(p);
        StringBuilder what = new StringBuilder();
        for (Param p : chosen) {
            if (what.length() > 0) what.append(", ");
            what.append(p.text().get());
        }
        if (!Dialogs.confirmDanger(ctx.conn.config(), "Изменение параметров баз",
            "Изменить у баз (" + bases.size() + "): " + what + "?")) {
            return;
        }
        apply(ctx, bases, chosen, what.toString(), 0, new ArrayList<>(), after);
    }

    private static Param param(String title, Node field, Consumer<IInfoBaseInfo> apply, java.util.function.Supplier<String> text) {
        CheckBox cb = new CheckBox(title);
        cb.setMnemonicParsing(false);
        // текст для подтверждения вычисляется при применении — по значению поля на тот момент
        return new Param(cb, field, apply, text);
    }

    /** Последовательно: авторизация в ИБ может спрашиваться для каждой базы. */
    private static void apply(ClusterCtx ctx, List<IInfoBaseInfoShort> bases, List<Param> params, String what, int i,
                              List<Result> results, Runnable after) {
        if (i >= bases.size()) {
            Dialogs.table("Изменение параметров баз — результат", results, List.of(
                Col.<Result>of("Информационная база", 240, Result::base),
                Col.<Result>of("Результат", 480, Result::result)));
            after.run();
            return;
        }
        IInfoBaseInfoShort b = bases.get(i);
        String action = "Изменение параметров «" + b.getName() + "»: " + what;
        Fx.call(ctx.infoBase(b.getName()), action, c -> {
            IInfoBaseInfo info = c.getInfoBaseInfo(ctx.clusterId, b.getInfoBaseId());
            for (Param p : params) p.apply().accept(info);
            c.updateInfoBase(ctx.clusterId, info);
            return null;
        }, v -> {
            results.add(new Result(b.getName(), "OK"));
            Fx.audit(ctx.infoBase(b.getName()), action, "OK");
            apply(ctx, bases, params, what, i + 1, results, after);
        }, err -> {
            String res = Fx.isAuthCancel(err) ? "пропущено: нет авторизации" : "ошибка: " + Fmt.rootMessage(err);
            results.add(new Result(b.getName(), res));
            Fx.audit(ctx.infoBase(b.getName()), action, res);
            apply(ctx, bases, params, what, i + 1, results, after);
        });
    }

    private static String names(List<IInfoBaseInfoShort> bases) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bases.size() && i < 8; i++) {
            if (i > 0) sb.append(", ");
            sb.append(bases.get(i).getName());
        }
        if (bases.size() > 8) sb.append(" и ещё ").append(bases.size() - 8);
        return sb.toString();
    }
}
