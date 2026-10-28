package ru.rasconsole.ui.form;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.util.StringConverter;
import ru.rasconsole.ui.Fmt;

/**
 * Форма свойств объекта API, построенная по его getter/setter.
 * Поддерживаются String, int, long, boolean, Date; прочие типы показываются только для чтения.
 */
public final class BeanForm {

    private final Object bean;
    private final List<Prop> props = new ArrayList<>();
    private final GridPane grid = new GridPane();

    private static final class Prop {
        String name;
        Method getter;
        Method setter;
        Class<?> type;
        Node control;
    }

    /**
     * @param bean     объект
     * @param type     интерфейс API, по которому ищутся свойства (например IInfoBaseInfo.class)
     * @param only     список свойств или null — все, в порядке из {@link Labels#order(Class)}
     * @param editable разрешить редактирование свойств, у которых есть setter
     */
    public BeanForm(Object bean, Class<?> type, List<String> only, boolean editable) {
        this.bean = bean;
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(180);
        c0.setPrefWidth(300);
        c0.setHalignment(HPos.LEFT);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(220);
        grid.getColumnConstraints().addAll(c0, c1);

        List<String> names = propertyNames(type, only);
        Map<String, List<String>> groups = Labels.groups(type);
        int row = 0;
        if (groups == null) {
            for (String name : names) row = addProp(type, name, editable, row);
            return;
        }
        // разделы по смыслу; свойства, не попавшие ни в один раздел, — в «Прочее»
        Set<String> rest = new LinkedHashSet<>(names);
        Map<String, List<String>> sections = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            List<String> in = new ArrayList<>();
            for (String n : g.getValue()) if (rest.remove(n)) in.add(n);
            if (!in.isEmpty()) sections.put(g.getKey(), in);
        }
        if (!rest.isEmpty()) sections.put("Прочее", new ArrayList<>(rest));
        boolean titles = sections.size() > 1;
        for (Map.Entry<String, List<String>> sec : sections.entrySet()) {
            if (titles) {
                Label h = new Label(sec.getKey());
                h.getStyleClass().add("form-section");
                h.setMaxWidth(Double.MAX_VALUE);
                if (row > 0) GridPane.setMargin(h, new Insets(10, 0, 0, 0));
                grid.add(h, 0, row++, 2, 1);
                String hint = Labels.groupHint(sec.getKey());
                if (hint != null) {
                    Label hl = new Label(hint);
                    hl.setWrapText(true);
                    hl.getStyleClass().add("hint-label");
                    grid.add(hl, 0, row++, 2, 1);
                }
            }
            for (String name : sec.getValue()) row = addProp(type, name, editable, row);
        }
    }

    private int addProp(Class<?> type, String name, boolean editable, int row) {
        Prop p = resolve(type, name);
        if (p == null) return row;
        Object value = invoke(p.getter, bean);
        boolean ro = !editable || p.setter == null;
        p.control = createControl(p, value, ro);
        props.add(p);
        Label label = new Label(Labels.label(name));
        label.setWrapText(true);
        GridPane.setMargin(label, new Insets(0, 0, 0, 8));
        grid.add(label, 0, row);
        grid.add(p.control, 1, row);
        return row + 1;
    }

    public Node node() {
        ScrollPane sp = new ScrollPane(grid);
        sp.setFitToWidth(true);
        return sp;
    }

    public GridPane grid() {
        return grid;
    }

    /** Записать значения из полей в объект. При ошибке ввода бросает IllegalArgumentException. */
    public void apply() {
        for (Prop p : props) {
            if (p.setter == null || p.control.isDisabled() || (p.control instanceof TextField tf && !tf.isEditable())) {
                continue;
            }
            Object v = read(p);
            try {
                p.setter.invoke(bean, v);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Не удалось установить «" + Labels.label(p.name) + "»: " + e, e);
            }
        }
    }

    /** Получить элемент управления свойства (для подписки на изменения). */
    public Node control(String name) {
        for (Prop p : props) {
            if (p.name.equals(name)) return p.control;
        }
        return null;
    }

    // ---- внутреннее ----

    private static List<String> propertyNames(Class<?> type, List<String> only) {
        if (only != null) return only;
        Set<String> names = new LinkedHashSet<>();
        List<String> order = Labels.order(type);
        if (order != null) names.addAll(order);
        List<Method> getters = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers()) && propName(m) != null) {
                getters.add(m);
            }
        }
        getters.sort(Comparator.comparing(Method::getName));
        for (Method m : getters) names.add(propName(m));
        return new ArrayList<>(names);
    }

    private static String propName(Method m) {
        String n = m.getName();
        String rest;
        if (n.startsWith("get") && n.length() > 3 && !n.equals("getClass")) rest = n.substring(3);
        else if (n.startsWith("is") && n.length() > 2 && (m.getReturnType() == boolean.class)) rest = n.substring(2);
        else return null;
        // getSPName -> SPName (как Introspector.decapitalize)
        if (rest.length() > 1 && Character.isUpperCase(rest.charAt(1))) return rest;
        return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    private static Prop resolve(Class<?> type, String name) {
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method g = find(type, "get" + cap);
        if (g == null) g = find(type, "is" + cap);
        if (g == null) return null;
        Prop p = new Prop();
        p.name = name;
        p.getter = g;
        p.type = g.getReturnType();
        try {
            p.setter = type.getMethod("set" + cap, p.type);
        } catch (NoSuchMethodException e) {
            p.setter = null;
        }
        return p;
    }

    private static Method find(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Method m, Object bean) {
        try {
            return m.invoke(bean);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Node createControl(Prop p, Object value, boolean ro) {
        Class<?> t = p.type;
        if (t == Date.class && !ro) return new DateTimeField((Date) value);
        if (t == boolean.class || t == Boolean.class) {
            CheckBox cb = new CheckBox();
            cb.setSelected(Boolean.TRUE.equals(value));
            cb.setDisable(ro);
            return cb;
        }
        Map<Integer, String> choices = Labels.choices(p.name);
        if ((t == int.class || t == Integer.class) && choices != null) {
            ComboBox<Integer> combo = new ComboBox<>();
            combo.getItems().addAll(choices.keySet());
            int v = value == null ? 0 : (Integer) value;
            if (!combo.getItems().contains(v)) combo.getItems().add(v);
            combo.setConverter(new StringConverter<>() {
                @Override
                public String toString(Integer i) {
                    return i == null ? "" : Labels.choiceText(p.name, i);
                }

                @Override
                public Integer fromString(String s) {
                    return null;
                }
            });
            combo.setValue(v);
            combo.setDisable(ro);
            combo.setMaxWidth(Double.MAX_VALUE);
            return combo;
        }
        List<String> strChoices = Labels.stringChoices(p.name);
        if (t == String.class && strChoices != null && !ro) {
            ComboBox<String> combo = new ComboBox<>();
            combo.setEditable(true);
            combo.getItems().addAll(strChoices);
            combo.setValue(value == null ? "" : (String) value);
            combo.setMaxWidth(Double.MAX_VALUE);
            return combo;
        }
        TextField tf;
        String lower = p.name.toLowerCase();
        if (t == String.class && (lower.contains("password") || lower.contains("pwd")) && !ro) {
            tf = new PasswordField();
        } else {
            tf = new TextField();
        }
        if (t == Date.class) {
            tf.setPromptText("дд.мм.гггг чч:мм:сс");
            tf.setText(Fmt.date((Date) value));
        } else {
            tf.setText(Fmt.format(value));
        }
        boolean supported = t == String.class || t == int.class || t == long.class || t == Date.class
            || t == Integer.class || t == Long.class;
        tf.setEditable(!ro && supported);
        if (!tf.isEditable()) tf.getStyleClass().add("readonly");
        return tf;
    }

    @SuppressWarnings("unchecked")
    private Object read(Prop p) {
        Class<?> t = p.type;
        Node c = p.control;
        String label = Labels.label(p.name);
        if (c instanceof CheckBox cb) return cb.isSelected();
        if (c instanceof DateTimeField df) {
            try {
                return Fmt.parseDate(df.getText());
            } catch (ParseException e) {
                throw new IllegalArgumentException("«" + label + "»: укажите дату в календаре и время в виде чч:мм");
            }
        }
        if (c instanceof ComboBox<?> combo) {
            if (t == String.class) {
                Object v = ((ComboBox<String>) combo).getEditor().getText();
                return v == null ? "" : v.toString().trim();
            }
            Integer v = (Integer) combo.getValue();
            return v == null ? 0 : v;
        }
        String s = ((TextField) c).getText();
        s = s == null ? "" : s;
        try {
            if (t == String.class) return s;
            if (t == int.class || t == Integer.class) return s.isBlank() ? 0 : Integer.parseInt(s.trim().replace(" ", ""));
            if (t == long.class || t == Long.class) return s.isBlank() ? 0L : Long.parseLong(s.trim().replace(" ", ""));
            if (t == Date.class) return Fmt.parseDate(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("«" + label + "»: ожидается целое число");
        } catch (ParseException e) {
            throw new IllegalArgumentException("«" + label + "»: дата в формате дд.мм.гггг чч:мм:сс");
        }
        throw new IllegalArgumentException("«" + label + "»: тип " + t.getSimpleName() + " не поддерживается");
    }
}
