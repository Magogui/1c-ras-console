package ru.rasconsole;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ru.rasconsole.config.ServerStore;
import ru.rasconsole.ui.MainWindow;

public final class App extends Application {

    private MainWindow window;

    @Override
    public void start(Stage stage) {
        window = new MainWindow(new ServerStore());
        Scene scene = new Scene(window.root(),
            ru.rasconsole.config.UiState.getDouble("window.w", 1280), ru.rasconsole.config.UiState.getDouble("window.h", 800));
        ru.rasconsole.ui.Theme.apply(scene);
        stage.setTitle("Консоль администрирования серверов 1С:Предприятия (RAS)");
        // иконка окна и панели задач: все размеры, Windows выберет подходящий
        for (int s : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            var url = App.class.getResource("/ru/rasconsole/ui/icons/app-" + s + ".png");
            if (url != null) stage.getIcons().add(new javafx.scene.image.Image(url.toExternalForm()));
        }
        stage.setScene(scene);
        double x = ru.rasconsole.config.UiState.getDouble("window.x", Double.NaN);
        double y = ru.rasconsole.config.UiState.getDouble("window.y", Double.NaN);
        // позиция восстанавливается, только если она видна на одном из мониторов
        if (!Double.isNaN(x) && !javafx.stage.Screen.getScreensForRectangle(x, y, 100, 100).isEmpty()) {
            stage.setX(x);
            stage.setY(y);
        }
        stage.setMaximized(ru.rasconsole.config.UiState.getBool("window.maximized", false));
        this.stage = stage;
        stage.show();
    }

    private Stage stage;

    @Override
    public void stop() {
        if (stage != null) {
            ru.rasconsole.config.UiState.put("window.maximized", Boolean.toString(stage.isMaximized()));
            if (!stage.isMaximized() && !stage.isIconified()) {
                ru.rasconsole.config.UiState.put("window.x", Double.toString(stage.getX()));
                ru.rasconsole.config.UiState.put("window.y", Double.toString(stage.getY()));
                ru.rasconsole.config.UiState.put("window.w", Double.toString(stage.getScene().getWidth()));
                ru.rasconsole.config.UiState.put("window.h", Double.toString(stage.getScene().getHeight()));
            }
        }
        if (window != null) {
            window.shutdown();
        }
        // значок в трее держит поток AWT — завершаем процесс явно
        System.exit(0);
    }
}
