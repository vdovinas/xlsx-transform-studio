package local.xlsx.model;

import java.util.ArrayList;
import java.util.List;

public class Profile {
    private CsvOptions csv = new CsvOptions();

    public CsvOptions getCsv() {
        return csv;
    }

    public void setCsv(CsvOptions csv) {
        this.csv = csv == null ? new CsvOptions() : csv;
    }

    private String name = "Новый профиль";
    private int headerRow = 1;
    private String sheetName = "Результат";
    private boolean matchAll = true;
    private List<Condition> filters = new ArrayList<>();
    private List<RuleBranch> filterGroups = new ArrayList<>();
    private List<OutputField> fields = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getHeaderRow() {
        return headerRow;
    }

    public void setHeaderRow(int headerRow) {
        this.headerRow = headerRow;
    }

    public String getSheetName() {
        return sheetName;
    }

    public void setSheetName(String sheetName) {
        this.sheetName = sheetName;
    }

    public boolean isMatchAll() {
        return matchAll;
    }

    public void setMatchAll(boolean matchAll) {
        this.matchAll = matchAll;
    }

    public List<Condition> getFilters() {
        return filters;
    }

    public void setFilters(List<Condition> filters) {
        this.filters = filters;
    }

    public List<RuleBranch> getFilterGroups() {
        return filterGroups;
    }

    public void setFilterGroups(List<RuleBranch> filterGroups) {
        this.filterGroups = filterGroups;
    }

    public List<OutputField> getFields() {
        return fields;
    }

    public void setFields(List<OutputField> fields) {
        this.fields = fields;
    }
}
