package local.xlsx.model;

import java.util.ArrayList;
import java.util.List;

public class OutputField {
    private String name = "Новое поле";
    private String source = "";
    private String format = "";

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    private OutputType kind = OutputType.TEXT;
    private boolean required = false;
    private List<TransformationStep> steps = new ArrayList<>();

    public OutputField() {}

    public OutputField(String n, String s) {
        name = n;
        source = s;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public OutputType getKind() {
        return kind;
    }

    public void setKind(OutputType kind) {
        this.kind = kind;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public List<TransformationStep> getSteps() {
        return steps;
    }

    public void setSteps(List<TransformationStep> steps) {
        this.steps = steps;
    }
}
