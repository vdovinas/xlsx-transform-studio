package local.xlsx.model;

public enum ConditionOperator {
    EQUALS("Равно"),
    NOT_EQUALS("Не равно"),
    CONTAINS("Содержит"),
    NOT_CONTAINS("Не содержит"),
    STARTS_WITH("Начинается с"),
    EMPTY("Пусто"),
    NOT_EMPTY("Не пусто"),
    GREATER("Больше"),
    LESS("Меньше"),
    DATE_BEFORE("Дата раньше"),
    DATE_AFTER("Дата позже");
    private final String title;

    ConditionOperator(String title) {
        this.title = title;
    }

    public String toString() {
        return title;
    }
}
