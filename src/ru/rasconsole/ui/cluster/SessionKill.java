package ru.rasconsole.ui.cluster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import com._1c.v8.ibis.admin.ISessionInfo;

import ru.rasconsole.ui.Dialogs;
import ru.rasconsole.ui.Fmt;
import ru.rasconsole.ui.Fx;

/** Завершение группы сеансов с исключениями по типу приложения и сообщением пользователям. */
final class SessionKill {

    private SessionKill() {
    }

    /** Типы приложений, которые можно исключить из завершения. */
    static final Map<String, String> EXCLUDABLE = new LinkedHashMap<>();

    static {
        EXCLUDABLE.put("Designer", "Конфигуратор");
        EXCLUDABLE.put("BackgroundJob", "Фоновые задания");
        EXCLUDABLE.put("COMConnection", "COM-соединения");
        EXCLUDABLE.put("SrvrConsole", "Консоль кластера");
        EXCLUDABLE.put("WSConnection", "Web-сервисы");
        EXCLUDABLE.put("HTTPServiceConnection", "HTTP-сервисы");
    }

    /** Параметры завершения. */
    record Options(String message, Set<String> excludedApps) {
        boolean excluded(ISessionInfo s) {
            return s.getAppId() != null && excludedApps.contains(s.getAppId());
        }
    }

    /** Диалог параметров; {@code defaultExcluded} — какие типы исключены по умолчанию. */
    static Optional<Options> ask(String header, List<ISessionInfo> sessions, ClusterCtx ctx, Set<String> defaultExcluded,
                                 String defaultMessage) {
        Dialog<Options> d = Dialogs.prepare(new Dialog<>(), "Завершение сеансов");
        d.setHeaderText(header);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField msg = new TextField(defaultMessage == null ? "" : defaultMessage);
        msg.setPromptText("Сообщение пользователям (необязательно)");
        FlowPane ex = new FlowPane(12, 6);
        Map<String, CheckBox> boxes = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : EXCLUDABLE.entrySet()) {
            CheckBox cb = new CheckBox(e.getValue());
            cb.setSelected(defaultExcluded.contains(e.getKey()));
            boxes.put(e.getKey(), cb);
            ex.getChildren().add(cb);
        }
        TextArea preview = new TextArea();
        preview.setEditable(false);
        preview.setPrefRowCount(10);
        Label count = new Label();
        Runnable update = () -> {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (ISessionInfo s : sessions) {
                String app = s.getAppId();
                if (app != null && boxes.containsKey(app) && boxes.get(app).isSelected()) continue;
                n++;
                if (n <= 200) {
                    sb.append(String.format("%-6d %-24s %-18s %-16s %s%n", s.getSessionId(), s.getUserName(),
                        Fmt.app(app), s.getHost(), ctx.ibName(s.getInfoBaseId())));
                }
            }
            preview.setText(sb.toString());
            count.setText("Будет завершено сеансов: " + n + " из " + sessions.size());
        };
        boxes.values().forEach(cb -> cb.selectedProperty().addListener((o, w, now) -> update.run()));
        update.run();
        preview.setStyle("-fx-font-family: 'Consolas', monospace;");
        VBox box = new VBox(8, new Label("Не завершать:"), ex, new Label("Сообщение пользователям:"), msg, count, preview);
        box.setPadding(new Insets(10));
        box.setPrefWidth(760);
        d.getDialogPane().setContent(box);
        d.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            Set<String> excluded = new java.util.HashSet<>();
            boxes.forEach((k, cb) -> {
                if (cb.isSelected()) excluded.add(k);
            });
            return new Options(msg.getText().trim(), excluded);
        });
        return d.showAndWait();
    }

    /** Спросить параметры, подтвердить и завершить. {@code after} — после завершения (FX-поток). */
    static void terminate(ClusterCtx ctx, List<ISessionInfo> sessions, String header, Runnable after) {
        if (sessions.isEmpty()) {
            Dialogs.info("Завершение сеансов", "Нет сеансов для завершения.");
            return;
        }
        ask(header, sessions, ctx, Set.of(), "").ifPresent(opt -> {
            List<ISessionInfo> todo = new ArrayList<>();
            for (ISessionInfo s : sessions) if (!opt.excluded(s)) todo.add(s);
            if (todo.isEmpty()) return;
            if (!Dialogs.confirmDanger(ctx.conn.config(), "Завершение сеансов",
                "Завершить сеансы: " + todo.size() + "?")) {
                return;
            }
            run(ctx, todo, opt.message(), after);
        });
    }

    /** Завершить без вопросов; ошибки по отдельным сеансам собираются в отчёт. */
    static void run(ClusterCtx ctx, List<ISessionInfo> todo, String message, Runnable after) {
        Fx.change(ctx.cluster(), "Завершение сеансов: " + todo.size(), c -> {
            List<String> errors = new ArrayList<>();
            for (ISessionInfo s : todo) {
                try {
                    if (message == null || message.isBlank()) c.terminateSession(ctx.clusterId, s.getSid());
                    else c.terminateSession(ctx.clusterId, s.getSid(), message);
                } catch (RuntimeException e) {
                    // сеанс мог завершиться сам — это не ошибка операции в целом
                    errors.add(s.getSessionId() + " " + s.getUserName() + ": " + e.getMessage());
                }
            }
            return errors;
        }, errors -> {
            if (!errors.isEmpty()) {
                Dialogs.info("Завершение сеансов", "Не удалось завершить " + errors.size() + " из " + todo.size()
                    + ":\n" + String.join("\n", errors.subList(0, Math.min(30, errors.size()))));
            }
            after.run();
        });
    }
}
