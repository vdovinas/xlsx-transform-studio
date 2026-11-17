package local.xlsx.model;

import java.util.Set;

public record CsvOptions(String encoding, String delimiter) {
    public CsvOptions {
        if (encoding == null) encoding = "auto";
        if (delimiter == null) delimiter = "auto";
        if (!Set.of("auto", "UTF-8", "windows-1251").contains(encoding)) {
            throw new IllegalArgumentException("Допустимые кодировки CSV: UTF-8 и Windows-1251");
        }
        if (!Set.of("auto", ";", ",", "\t").contains(delimiter)) {
            throw new IllegalArgumentException("Допустимые разделители CSV: точка с запятой, запятая и табуляция");
        }
    }

    public CsvOptions() {
        this("auto", "auto");
    }
}
