package local.xlsx.model;

public enum TransformationType {
    TRIM("Убрать пробелы"),
    LOWER("Нижний регистр"),
    UPPER("Верхний регистр"),
    REPLACE("Заменить текст"),
    EXTRACT("Извлечь по шаблону"),
    CONSTANT("Задать значение"),
    IF_EMPTY("Если пусто, взять значение"),
    IF_EMPTY_SOURCE("Если пусто, взять столбец"),
    CLASSIFY("Классификация: первое совпадение"),
    NUMBER("Преобразовать в число"),
    DATE("Преобразовать в дату");
    private final String title;

    TransformationType(String title) {
        this.title = title;
    }

    public String toString() {
        return title;
    }
}
