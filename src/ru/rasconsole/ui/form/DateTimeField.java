package ru.rasconsole.ui.form;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;
import ru.rasconsole.ui.Fmt;

/**
 * Дата и время: календарь (DatePicker), поле времени чч:мм и кнопки «Сейчас», «+1 ч», «Очистить».
 * Текстовое значение — в формате {@link Fmt#DATE_PATTERN}, пустая строка — пустая дата.
 */
public final class DateTimeField extends HBox {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern(Fmt.DATE_PATTERN);

    private final DatePicker date = new DatePicker();
    private final TextField time = new TextField();

    public DateTimeField(Date initial) {
        super(6);
        setAlignment(Pos.CENTER_LEFT);
        date.setEditable(true);
        date.setPromptText("дд.мм.гггг");
        date.setPrefWidth(135);
        date.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate d) {
                return d == null ? "" : DAY.format(d);
            }

            @Override
            public LocalDate fromString(String s) {
                if (s == null || s.isBlank()) return null;
                try {
                    return LocalDate.parse(s.trim(), DAY);
                } catch (DateTimeParseException e) {
                    return null;
                }
            }
        });
        // выбор дня без времени — подставить 00:00
        date.valueProperty().addListener((o, was, now) -> {
            if (now != null && time.getText().isBlank()) time.setText("00:00");
        });
        time.setPromptText("чч:мм");
        time.setPrefColumnCount(4);
        time.setMaxWidth(70);
        time.setMinWidth(60);
        time.setTooltip(new Tooltip("Время: чч:мм или чч:мм:сс"));
        Button nowBtn = new Button("Сейчас");
        nowBtn.setOnAction(e -> set(LocalDateTime.now().withSecond(0).withNano(0)));
        Button plusHour = new Button("+1 ч");
        plusHour.setTooltip(new Tooltip("На час позже указанного (или текущего) времени"));
        plusHour.setOnAction(e -> {
            LocalDateTime cur = current();
            set((cur == null ? LocalDateTime.now().withSecond(0).withNano(0) : cur).plusHours(1));
        });
        Button clear = new Button("✕");
        clear.setTooltip(new Tooltip("Очистить дату"));
        clear.setOnAction(e -> {
            date.setValue(null);
            date.getEditor().clear();
            time.clear();
        });
        getChildren().addAll(date, time, nowBtn, plusHour, clear);
        for (Button b : new Button[] {nowBtn, plusHour, clear}) b.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        date.setMinWidth(120);
        HBox.setHgrow(date, Priority.NEVER);
        setDate(initial);
    }

    public void setPromptText(String text) {
        date.setPromptText(text);
        date.setPrefWidth(Math.max(150, text.length() * 7 + 40));
    }

    public void setDate(Date d) {
        if (d == null || Fmt.isEmptyDate(d)) {
            date.setValue(null);
            date.getEditor().clear();
            time.clear();
        } else {
            set(LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()));
        }
    }

    private void set(LocalDateTime t) {
        date.setValue(t.toLocalDate());
        date.getEditor().setText(DAY.format(t.toLocalDate()));
        time.setText(t.getSecond() == 0 ? t.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
            : t.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    /** Значение полей или null, если дата пуста или введена с ошибкой. */
    private LocalDateTime current() {
        LocalDate d = date.getConverter().fromString(date.getEditor().getText());
        if (d == null) return null;
        LocalTime t = parseTime(time.getText());
        return t == null ? null : LocalDateTime.of(d, t);
    }

    private static LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return LocalTime.MIDNIGHT;
        String v = s.trim();
        try {
            return LocalTime.parse(v.length() <= 5 ? (v.length() == 4 ? "0" + v : v) + ":00" : v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Текст в формате дд.мм.гггг чч:мм:сс; пустая строка — дата не задана.
     * При ошибке ввода возвращается введённый текст как есть — разбор в {@link Fmt#parseDate} сообщит об ошибке.
     */
    public String getText() {
        String day = date.getEditor().getText();
        if ((day == null || day.isBlank()) && time.getText().isBlank()) return "";
        LocalDateTime v = current();
        return v == null ? (day + " " + time.getText()).trim() + " ?" : FULL.format(v);
    }

    public void setText(String s) {
        try {
            setDate(Fmt.parseDate(s));
        } catch (java.text.ParseException e) {
            setDate(null);
        }
    }
}
