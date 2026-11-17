package local.xlsx.model;

import java.util.ArrayList;
import java.util.List;

public class RuleBranch {
    private boolean matchAll = true;
    private List<Condition> conditions = new ArrayList<>();
    private String value = "";

    public boolean isMatchAll() {
        return matchAll;
    }

    public void setMatchAll(boolean matchAll) {
        this.matchAll = matchAll;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
