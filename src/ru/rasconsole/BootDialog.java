package ru.rasconsole;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Окно первого запуска публичной сборки: где взять библиотеку 1С и выбор скачанного архива.
 * Swing, а не JavaFX: окно показывается до запуска приложения JavaFX.
 */
final class BootDialog {

    private BootDialog() {
    }

    private static void lookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // стандартное оформление Swing тоже подойдёт
        }
    }

    /** Спросить архив Java API; null — пользователь выбрал «Выход». */
    static Path askArchive(Path api) {
        lookAndFeel();
        String text = "<html><body style='width:460px'>"
            + "<b>Нужна библиотека 1С для сервера администрирования</b><br><br>"
            + "Консоль работает через «Программный Java-интерфейс для административного сервера» фирмы «1С». "
            + "Пользоваться им могут только пользователи «1С:Предприятия 8», поэтому в публичную сборку он не входит.<br><br>"
            + "1. Скачайте архив с ИТС (вариант для версий 8.3.11 и старше) — кнопка «Открыть страницу ИТС».<br>"
            + "2. Нажмите «Выбрать архив…» и укажите скачанный файл .zip.<br><br>"
            + "Консоль извлечёт библиотеку в<br><code>" + api + "</code><br>— это нужно сделать один раз."
            + "</body></html>";
        String[] options = {"Выбрать архив…", "Открыть страницу ИТС", "Выход"};
        while (true) {
            int r = JOptionPane.showOptionDialog(null, text, "Консоль администрирования RAS — первый запуск",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
            if (r == 1) {
                try {
                    Desktop.getDesktop().browse(URI.create(Boot.ITS_URL));
                } catch (Exception e) {
                    error("Не удалось открыть браузер. Адрес страницы:\n" + Boot.ITS_URL);
                }
                continue;
            }
            if (r != 0) return null;
            JFileChooser fc = new JFileChooser(new File(System.getProperty("user.home"), "Downloads"));
            fc.setDialogTitle("Архив Java API с ИТС");
            fc.setFileFilter(new FileNameExtensionFilter("Архив Java API (*.zip)", "zip"));
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) return fc.getSelectedFile().toPath();
        }
    }

    static void error(String text) {
        lookAndFeel();
        JOptionPane.showMessageDialog(null, text, "Консоль администрирования RAS", JOptionPane.ERROR_MESSAGE);
    }
}
