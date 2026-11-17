package local.xlsx.model;

import java.util.ArrayList;
import java.util.List;

public class TransformationStep {
    private TransformationType action = TransformationType.TRIM;
    private String argument = "";
    private String replacement = "";

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }

    private boolean matchAll = true;
    private List<Condition> conditions = new ArrayList<>();
    private List<RuleBranch> cases = new ArrayList<>();

    public TransformationStep() {}

    public TransformationStep(TransformationType a, String arg, String repl) {
        action = a;
        argument = arg;
        replacement = repl;
    }

    public TransformationType getAction() {
        return action;
    }

    public void setAction(TransformationType action) {
        this.action = action;
    }

    public String getArgument() {
        return argument;
    }

    public void setArgument(String argument) {
        this.argument = argument;
    }

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

    public List<RuleBranch> getCases() {
        return cases;
    }

    public void setCases(List<RuleBranch> cases) {
        this.cases = cases;
    }
}
