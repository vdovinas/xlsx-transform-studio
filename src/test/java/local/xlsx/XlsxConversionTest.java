package local.xlsx;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import local.xlsx.model.Condition;
import local.xlsx.model.ConditionOperator;
import local.xlsx.model.OutputField;
import local.xlsx.model.OutputType;
import local.xlsx.model.Profile;
import local.xlsx.repository.ProfileRepository;
import local.xlsx.service.RuleService;
import local.xlsx.service.XlsxConversionService;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XlsxConversionTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("Числовые форматы не округляют значения и результаты формул")
    void preservesStoredNumbers() throws Exception {
        Path input = directory.resolve("precision.xlsx");
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Число");
            String[] formats = {"0.00", "0%", "0.00E+00", "General"};
            for (int i = 0; i < formats.length; i++) {
                var cell = sheet.createRow(i + 1).createCell(0);
                cell.setCellValue(1234.567);
                var style = book.createCellStyle();
                style.setDataFormat(book.createDataFormat().getFormat(formats[i]));
                cell.setCellStyle(style);
            }
            var formula = sheet.createRow(5).createCell(0);
            formula.setCellFormula("A2/10");
            formula.setCellStyle(sheet.getRow(1).getCell(0).getCellStyle());
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (var stream = Files.newOutputStream(input)) {
                book.write(stream);
            }
        }
        Profile profile = new Profile();
        OutputField field = new OutputField("Число", "Число");
        field.setKind(OutputType.NUMBER);
        profile.getFields().add(field);
        Path output = directory.resolve("precision-out.xlsx");
        var result = XlsxConversionService.convert(input, output, 0, profile, () -> false, n -> {});
        assertEquals(0, result.errors());
        try (var book = new XSSFWorkbook(output.toFile())) {
            for (int i = 1; i <= 4; i++)
                assertEquals(1234.567, book.getSheetAt(0).getRow(i).getCell(0).getNumericCellValue());
            assertEquals(123.4567, book.getSheetAt(0).getRow(5).getCell(0).getNumericCellValue(), 1e-10);
        }
    }

    @Test
    @DisplayName("Учитывает системы дат 1900 и 1904, включая формулы")
    void respectsWorkbookDateSystem() throws Exception {
        for (boolean mac : new boolean[] {false, true}) {
            Path input = directory.resolve("dates-" + mac + ".xlsx");
            var expected = java.time.LocalDate.of(2026, 11, 16);
            try (var book = new XSSFWorkbook()) {
                book.getCTWorkbook().getWorkbookPr().setDate1904(mac);
                var sheet = book.createSheet();
                sheet.createRow(0).createCell(0).setCellValue("Дата");
                var cell = sheet.createRow(1).createCell(0);
                cell.setCellValue(org.apache.poi.ss.usermodel.DateUtil.getExcelDate(expected, mac));
                var style = book.createCellStyle();
                style.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"));
                cell.setCellStyle(style);
                var formula = sheet.createRow(2).createCell(0);
                formula.setCellFormula("A2+1");
                formula.setCellStyle(style);
                book.getCreationHelper().createFormulaEvaluator().evaluateAll();
                try (var stream = Files.newOutputStream(input)) {
                    book.write(stream);
                }
            }
            Profile profile = new Profile();
            OutputField field = new OutputField("Дата", "Дата");
            field.setKind(OutputType.DATE);
            profile.getFields().add(field);
            Path output = directory.resolve("date-out-" + mac + ".xlsx");
            var result = XlsxConversionService.convert(input, output, 0, profile, () -> false, n -> {});
            assertEquals(0, result.errors());
            try (var book = new XSSFWorkbook(output.toFile())) {
                assertEquals(
                        expected,
                        book.getSheetAt(0)
                                .getRow(1)
                                .getCell(0)
                                .getLocalDateTimeCellValue()
                                .toLocalDate());
                assertEquals(
                        expected.plusDays(1),
                        book.getSheetAt(0)
                                .getRow(2)
                                .getCell(0)
                                .getLocalDateTimeCellValue()
                                .toLocalDate());
            }
        }
    }

    @Test
    @DisplayName("Читает даты и сохранённые формулы, оставляя текст безопасным")
    void readsExcelDatesAndCachedFormulasWithoutExecutingOutputText() throws Exception {
        Path input = directory.resolve("dates.xlsx");
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Дата");
            sheet.getRow(0).createCell(1).setCellValue("Текст");
            sheet.getRow(0).createCell(2).setCellValue("Сумма");
            var row = sheet.createRow(1);
            var date = row.createCell(0);
            date.setCellValue(java.time.LocalDate.of(2026, 11, 16));
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy"));
            date.setCellStyle(style);
            row.createCell(1).setCellValue("=1+1");
            row.createCell(2).setCellFormula("2+3");
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            try (var stream = Files.newOutputStream(input)) {
                book.write(stream);
            }
        }
        Profile profile = new Profile();
        OutputField date = new OutputField("Дата", "Дата");
        date.setKind(OutputType.DATE);
        profile.getFields().add(date);
        profile.getFields().add(new OutputField("Текст", "Текст"));
        OutputField sum = new OutputField("Сумма", "Сумма");
        sum.setKind(OutputType.NUMBER);
        profile.getFields().add(sum);
        Path output = directory.resolve("result.xlsx");
        var result = XlsxConversionService.convert(input, output, 0, profile, () -> false, n -> {});
        assertEquals(0, result.errors());
        try (var book = new XSSFWorkbook(output.toFile())) {
            var row = book.getSheetAt(0).getRow(1);
            assertEquals(
                    java.time.LocalDate.of(2026, 11, 16),
                    row.getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(
                    org.apache.poi.ss.usermodel.CellType.STRING, row.getCell(1).getCellType());
            assertEquals("=1+1", row.getCell(1).getStringCellValue());
            assertEquals(5, row.getCell(2).getNumericCellValue());
        }
    }

    @Test
    @DisplayName("Профиль Точки фильтрует операции и извлекает комиссию")
    void dotProfileFiltersDirectionAndExtractsCommission() throws Exception {
        Path input = input(
                new String[] {
                    "Дата документа",
                    "Номер документа",
                    "Направление",
                    "Сумма операции",
                    "Назначение платежа",
                    "Наименование получателя"
                },
                new String[][] {
                    {"16.11.2026", "001", "Исходящий", "100", "Оплата за товар", "Поставщик"},
                    {"16.11.2026", "002", "Входящий", "1000", "Зачисление, сумма комиссии 12,50", "Клиент"},
                    {"16.11.2026", "003", "Входящий", "200", "Пополнение", "Клиент"},
                    {"16.11.2026", "004", "Исходящий", "20", "Пени", "Банк"}
                });
        Profile profile = ProfileRepository.builtins().stream()
                .filter(p -> p.getName().equals("Точка"))
                .findFirst()
                .orElseThrow();
        Path output = directory.resolve("dot.xlsx");
        var result = XlsxConversionService.convert(input, output, 0, profile, () -> false, n -> {});
        assertEquals(2, result.written());
        assertEquals(0, result.errors());
        try (var book = new XSSFWorkbook(output.toFile())) {
            var row = book.getSheetAt(0).getRow(2);
            assertEquals("002", row.getCell(1).getStringCellValue());
            assertEquals("Комиссия по эквайрингу", row.getCell(3).getStringCellValue());
            assertEquals(12.50, row.getCell(5).getNumericCellValue());
        }
    }

    private Path input(String[] headers, String[][] data) throws Exception {
        Path path = directory.resolve("input.xlsx");
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Вход");
            var row = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) row.createCell(i).setCellValue(headers[i]);
            for (int j = 0; j < data.length; j++) {
                row = sheet.createRow(j + 1);
                for (int i = 0; i < data[j].length; i++) row.createCell(i).setCellValue(data[j][i]);
            }
            try (var stream = Files.newOutputStream(path)) {
                book.write(stream);
            }
        }
        return path;
    }

    @Test
    @DisplayName("Подсвечивает ошибочную сумму и сохраняет исходное значение с пояснением")
    void highlightsOriginalValueAndWritesComment() throws Exception {
        Path in = input(new String[] {"Сумма"}, new String[][] {{"1 234,56"}, {"ошибка"}, {""}});
        Profile p = new Profile();
        OutputField f = new OutputField("Итого", "Сумма");
        f.setKind(OutputType.NUMBER);
        f.setRequired(true);
        p.getFields().add(f);
        Path out = directory.resolve("out.xlsx");
        var result = XlsxConversionService.convert(in, out, 0, p, () -> false, n -> {});
        assertEquals(2, result.written());
        assertEquals(1, result.errors());
        try (var book = new XSSFWorkbook(out.toFile())) {
            var sheet = book.getSheetAt(0);
            assertEquals(1234.56, sheet.getRow(1).getCell(0).getNumericCellValue());
            var bad = sheet.getRow(2).getCell(0);
            assertEquals("ошибка", bad.getStringCellValue());
            assertNotNull(bad.getCellComment());
            assertEquals(IndexedColors.ROSE.getIndex(), bad.getCellStyle().getFillForegroundColor());
        }
    }

    @Test
    @DisplayName("Сохраняет строку при ошибке фильтра или отсутствии столбца")
    void missingSourceAndInvalidFilterRetainRow() throws Exception {
        Path in = input(new String[] {"Дата"}, new String[][] {{"31.02.2026"}});
        Profile p = new Profile();
        OutputField f = new OutputField("Дата", "Дата");
        f.setKind(OutputType.DATE);
        p.getFields().add(f);
        p.getFields().add(new OutputField("Нет", "Нет"));
        p.getFilters().add(new Condition("Дата", ConditionOperator.GREATER, "0"));
        Path out = directory.resolve("out.xlsx");
        var result = XlsxConversionService.convert(in, out, 0, p, () -> false, n -> {});
        assertEquals(1, result.written());
        assertEquals(2, result.errors());
    }

    @Test
    @DisplayName("Отмена сохраняет существующий результат и защищает исходный файл")
    void cancellationPreservesExistingOutput() throws Exception {
        Path in = input(new String[] {"A"}, new String[][] {{"1"}}), out = directory.resolve("out.xlsx");
        Files.writeString(out, "existing");
        Profile p = new Profile();
        p.getFields().add(new OutputField("A", "A"));
        assertThrows(
                CancellationException.class, () -> XlsxConversionService.convert(in, out, 0, p, () -> true, n -> {}));
        assertEquals("existing", Files.readString(out));
        assertThrows(
                IllegalArgumentException.class,
                () -> XlsxConversionService.convert(in, in, 0, p, () -> false, n -> {}));
    }

    @Test
    @DisplayName("Профили сохраняются без потерь и выполняют банковские правила")
    void allProfilesRoundTripAndExistingBankRulesWork() throws Exception {
        for (Profile p : ProfileRepository.builtins()) {
            Path saved = directory.resolve("profile.json");
            ProfileRepository.save(saved, p);
            assertEquals(p.getName(), ProfileRepository.load(saved).getName());
        }
        Profile sb = ProfileRepository.builtins().stream()
                .filter(p -> p.getName().equals("Сбербанк"))
                .findFirst()
                .orElseThrow();
        String purpose = "Операции по счету: Назначение платежа", sum = "Операции по счету: Сумма платежа по дебету";
        Map<String, String> row = new HashMap<>();
        row.put(purpose, "Комиссия: 123,45");
        row.put(sum, "");
        assertEquals("123,45", RuleService.apply(sb.getFields().get(5), row));
        assertEquals("Комиссия по эквайрингу", RuleService.apply(sb.getFields().get(3), row));
        row.put(sum, "10");
        row.put(purpose, "Оплата за товар по счёту");
        assertEquals("10", RuleService.apply(sb.getFields().get(5), row));
        assertEquals("Оплата за товар", RuleService.apply(sb.getFields().get(3), row));
    }

    @Test
    @DisplayName("Различает повторяющиеся заголовки и проверяет корректность дат")
    void duplicateHeadersAndNumericDates() throws Exception {
        Path in = input(new String[] {"Повтор", "Повтор", ""}, new String[][] {{"1", "2", "3"}});
        var layout = XlsxConversionService.inspect(in, 0, 1, () -> false);
        assertEquals(List.of("Повтор", "@B", "@C"), layout.columns());
        assertThrows(IllegalArgumentException.class, () -> RuleService.date("31.02.2026", ""));
        assertEquals("2026-11-16", RuleService.date("16.11.2026", "").toString());
    }

    @Test
    @DisplayName("Обрабатывает сто тысяч строк без ошибок")
    void processesOneHundredThousandRows() throws Exception {
        Path in = directory.resolve("large.xlsx");
        var book = new SXSSFWorkbook(100);
        try (book) {
            var sheet = book.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Значение");
            for (int i = 1; i <= 100000; i++) sheet.createRow(i).createCell(0).setCellValue(i);
            try (var out = Files.newOutputStream(in)) {
                book.write(out);
            }
        } finally {
            book.dispose();
        }
        Profile p = new Profile();
        OutputField f = new OutputField("Значение", "Значение");
        f.setKind(OutputType.NUMBER);
        p.getFields().add(f);
        var result = XlsxConversionService.convert(in, directory.resolve("large-out.xlsx"), 0, p, () -> false, n -> {});
        assertEquals(100000, result.written());
        assertEquals(0, result.errors());
    }
}
