package local.xlsx.model;

import java.util.List;

public record CsvLayout(List<String> sheets, List<String> columns, String encoding, String delimiter) {}
