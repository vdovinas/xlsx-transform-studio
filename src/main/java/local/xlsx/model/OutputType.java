package local.xlsx.model;

public enum OutputType {
    TEXT("Текст"),
    NUMBER("Число"),
    DATE("Дата");
    private final String title;

    OutputType(String title) {
        this.title = title;
    }

    public String toString() {
        return title;
    }
}
