package local.xlsx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import local.xlsx.model.CsvOptions;
import local.xlsx.model.OutputField;
import local.xlsx.model.OutputType;
import local.xlsx.model.Profile;
import local.xlsx.repository.ProfileRepository;
import local.xlsx.service.CsvImportService;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvImportTest {
    @TempDir
    Path directory;

    private Profile profile() {
        Profile profile = new Profile();
        profile.getFields().add(new OutputField("Название", "Название"));
        OutputField amount = new OutputField("Сумма", "Сумма");
        amount.setKind(OutputType.NUMBER);
        profile.getFields().add(amount);
        return profile;
    }

    @Test
    @DisplayName("CSV UTF-8 с BOM сохраняет кавычки, переносы строк и исходные ошибочные значения")
    void importsQuotedMultilineAndHighlightsErrors() throws Exception {
        Path source = directory.resolve("quoted.csv");
        Files.writeString(
                source,
                "\ufeffНазвание;Сумма;Хвост\r\n\"Поставка; \"\"Хлеб\"\"\nза ноябрь\";\"1 234,56\";\r\n=1+1;ошибка;\r\n");
        var layout = CsvImportService.inspect(source, 1, new CsvOptions(), () -> false);
        assertEquals("UTF-8", layout.encoding());
        assertEquals(";", layout.delimiter());
        assertEquals(List.of("Название", "Сумма", "Хвост"), layout.columns());
        Path output = directory.resolve("result.xlsx");
        var result = CsvImportService.convert(source, output, profile(), () -> false, count -> {});
        assertEquals(2, result.written());
        assertEquals(1, result.errors());
        try (var book = new XSSFWorkbook(output.toFile())) {
            var sheet = book.getSheetAt(0);
            assertEquals(
                    "Поставка; \"Хлеб\"\nза ноябрь", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals(1234.56, sheet.getRow(1).getCell(1).getNumericCellValue());
            assertEquals("=1+1", sheet.getRow(2).getCell(0).getStringCellValue());
            var cell = sheet.getRow(2).getCell(1);
            assertEquals("ошибка", cell.getStringCellValue());
            assertEquals(IndexedColors.ROSE.getIndex(), cell.getCellStyle().getFillForegroundColor());
            assertTrue(cell.getCellComment().getString().getString().contains("Исходная строка 3"));
        }
    }

    @Test
    @DisplayName("Автоматически определяет Windows-1251 и применяет выбранный профиль Точки")
    void importsTochkaWindows1251() throws Exception {
        String data =
                "Дата документа;Номер документа;Направление;Сумма операции;Назначение платежа;Наименование получателя\r\n"
                        + "16.11.2026;001;Исходящий;100;Оплата за товар;Поставщик\r\n"
                        + "16.11.2026;002;Входящий;1000;Зачисление, сумма комиссии 12,50;Клиент\r\n"
                        + "16.11.2026;003;Входящий;200;Пополнение;Клиент\r\n"
                        + "16.11.2026;004;Исходящий;20;Пени;Банк\r\n";
        Path source = directory.resolve("tochka.csv");
        Files.writeString(source, data, Charset.forName("windows-1251"));
        var layout = CsvImportService.inspect(source, 1, new CsvOptions(), () -> false);
        assertEquals("windows-1251", layout.encoding());
        Profile profile = ProfileRepository.builtins().stream()
                .filter(p -> p.getName().equals("Точка"))
                .findFirst()
                .orElseThrow();
        Path output = directory.resolve("tochka.xlsx");
        var result = CsvImportService.convert(source, output, profile, () -> false, count -> {});
        assertEquals(2, result.written());
        assertEquals(0, result.errors());
        try (var book = new XSSFWorkbook(output.toFile())) {
            var row = book.getSheetAt(0).getRow(2);
            assertEquals("002", row.getCell(1).getStringCellValue());
            assertEquals("Комиссия по эквайрингу", row.getCell(3).getStringCellValue());
            assertEquals(12.5, row.getCell(5).getNumericCellValue());
        }
    }

    @Test
    @DisplayName("Определяет запятую и табуляцию, не считая разделители внутри кавычек")
    void detectsCommaAndTab() throws Exception {
        for (String delimiter : List.of(",", "\t")) {
            Path source = directory.resolve("separated.csv");
            Files.writeString(
                    source,
                    "\"Название; поля\"" + delimiter + "Сумма\n\"Текст, с; разделителями\"" + delimiter + "10\n");
            var layout = CsvImportService.inspect(source, 1, new CsvOptions(), () -> false);
            assertEquals(delimiter, layout.delimiter());
            assertEquals(List.of("Название; поля", "Сумма"), layout.columns());
        }
    }

    @Test
    @DisplayName("Ручные настройки CSV и строка заголовков сохраняются в профиле")
    void supportsManualOptionsAndDuplicateHeaders() throws Exception {
        Path source = directory.resolve("manual.csv");
        Files.writeString(
                source, "Отчёт\r\nНазвание\tНазвание\t\r\nПервое\tВторое\t\r\n", Charset.forName("windows-1251"));
        var options = new CsvOptions("windows-1251", "\t");
        var layout = CsvImportService.inspect(source, 2, options, () -> false);
        assertEquals(List.of("Название", "@B", "@C"), layout.columns());
        Profile profile = new Profile();
        profile.setCsv(options);
        profile.setHeaderRow(2);
        profile.getFields().add(new OutputField("Текст", "@B"));
        Path saved = directory.resolve("profile.json");
        ProfileRepository.save(saved, profile);
        assertEquals(options, ProfileRepository.load(saved).getCsv());
        Path output = directory.resolve("manual.xlsx");
        CsvImportService.convert(source, output, profile, () -> false, count -> {});
        try (var book = new XSSFWorkbook(output.toFile())) {
            assertEquals("Второе", book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("Повреждённый CSV не перезаписывает существующий результат")
    void malformedCsvPreservesOutput() throws Exception {
        Path source = directory.resolve("broken.csv"), output = directory.resolve("result.xlsx");
        Files.writeString(source, "Название;Сумма\n\"незакрытая кавычка;10");
        Files.writeString(output, "existing");
        assertThrows(
                Exception.class, () -> CsvImportService.convert(source, output, profile(), () -> false, count -> {}));
        assertEquals("existing", Files.readString(output));
        assertThrows(
                IllegalArgumentException.class,
                () -> CsvImportService.convert(source, source, profile(), () -> false, count -> {}));
    }

    @Test
    @DisplayName("Отмена CSV сохраняет существующий файл результата")
    void cancellationPreservesOutput() throws Exception {
        Path source = directory.resolve("input.csv"), output = directory.resolve("result.xlsx");
        Files.writeString(source, "Название;Сумма\nТекст;10");
        Files.writeString(output, "existing");
        assertThrows(
                CancellationException.class,
                () -> CsvImportService.convert(source, output, profile(), () -> true, count -> {}));
        assertEquals("existing", Files.readString(output));
    }

    @Test
    @DisplayName("Отклоняет неподдерживаемые кодировки и повреждённый UTF-8 с BOM")
    void rejectsUnsupportedAndCorruptEncoding() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new CsvOptions("UTF-16", ";"));
        assertThrows(IllegalArgumentException.class, () -> new CsvOptions("UTF-8", "|"));
        Path source = directory.resolve("encoding.csv");
        Files.writeString(source, "Название;Сумма\nТекст;10", StandardCharsets.UTF_16);
        assertThrows(
                IllegalArgumentException.class,
                () -> CsvImportService.inspect(source, 1, new CsvOptions(), () -> false));
        Files.write(source, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xff});
        assertThrows(IOException.class, () -> CsvImportService.inspect(source, 1, new CsvOptions(), () -> false));
    }

    @Test
    @DisplayName("Пустой CSV и отсутствующие заголовки отклоняются без потери результата")
    void rejectsMissingHeader() throws Exception {
        Path source = directory.resolve("empty.csv"), output = directory.resolve("result.xlsx");
        Files.writeString(source, "");
        Files.writeString(output, "existing");
        assertThrows(
                IllegalArgumentException.class,
                () -> CsvImportService.inspect(source, 1, new CsvOptions(), () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () -> CsvImportService.convert(source, output, profile(), () -> false, count -> {}));
        assertEquals("existing", Files.readString(output));
    }

    @Test
    @DisplayName("Обрабатывает сто тысяч CSV-записей и отклоняет превышение лимита")
    void enforcesRowLimit() throws Exception {
        Path source = directory.resolve("large.csv"), output = directory.resolve("large.xlsx");
        String data = "Название;Сумма\n" + "Текст;10\n".repeat(100000);
        Files.writeString(source, data);
        assertEquals(
                100000,
                CsvImportService.convert(source, output, profile(), () -> false, count -> {})
                        .written());
        Files.writeString(source, data + "Лишняя;20\n");
        long size = Files.size(output);
        assertThrows(
                IllegalArgumentException.class,
                () -> CsvImportService.convert(source, output, profile(), () -> false, count -> {}));
        assertEquals(size, Files.size(output));
    }
}
