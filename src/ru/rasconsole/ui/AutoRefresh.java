package ru.rasconsole.ui;

import java.util.List;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import ru.rasconsole.config.UiState;

/**
 * Флажок «Автообновление» с выбором интервала (5, 10, 15, 30, 60 с). Интервал запоминается
 * между запусками — свой для списков и для сводки.
 */
public final class AutoRefresh {

    private static final List<Integer> CHOICES = List.of(5, 10, 15, 30, 60);

    private AutoRefresh() {
    }

    /**
     * @param timer   таймер вида (кадры задаются здесь)
     * @param action  что делать на каждом тике
     * @param key     ключ в ui.properties для интервала
     * @param def     интервал по умолчанию, с
     * @param on      включено ли сразу
     */
    public static HBox control(Timeline timer, Runnable action, String key, int def, boolean on) {
        int sec = (int) UiState.getDouble(key, def);
        if (!CHOICES.contains(sec)) sec = def;
        CheckBox auto = new CheckBox("Автообновление");
        ComboBox<Integer> interval = new ComboBox<>();
        interval.getItems().setAll(CHOICES);
        interval.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer s) {
                return s == null ? "" : s + " с";
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        interval.setValue(sec);
        interval.setTooltip(new javafx.scene.control.Tooltip("Как часто обновлять"));
        timer.setCycleCount(Timeline.INDEFINITE);
        setInterval(timer, action, sec);
        interval.valueProperty().addListener((o, was, now) -> {
            if (now == null) return;
            UiState.put(key, Integer.toString(now));
            boolean running = timer.getStatus() == javafx.animation.Animation.Status.RUNNING;
            timer.stop();
            setInterval(timer, action, now);
            if (running) timer.play();
        });
        auto.selectedProperty().addListener((o, was, now) -> {
            if (now) timer.play();
            else timer.stop();
        });
        interval.disableProperty().bind(auto.selectedProperty().not());
        auto.setSelected(on);
        HBox box = new HBox(6, auto, interval);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static void setInterval(Timeline timer, Runnable action, int sec) {
        timer.getKeyFrames().setAll(new KeyFrame(Duration.seconds(sec), e -> action.run()));
    }
}
