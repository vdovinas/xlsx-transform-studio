package local.xlsx.model;

public class Condition {
    private String source = "";
    private ConditionOperator operator = ConditionOperator.CONTAINS;
    private String value = "";
    private boolean ignoreCase = true;

    public Condition() {}

    public Condition(String s, ConditionOperator o, String v) {
        source = s;
        operator = o;
        value = v;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public ConditionOperator getOperator() {
        return operator;
    }

    public void setOperator(ConditionOperator operator) {
        this.operator = operator;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    public void setIgnoreCase(boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
    }
}
