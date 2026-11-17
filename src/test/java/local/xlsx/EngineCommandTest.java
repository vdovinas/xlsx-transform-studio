package local.xlsx;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import local.xlsx.model.OutputField;
import local.xlsx.model.OutputType;
import local.xlsx.model.Profile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineCommandTest {
    @TempDir
    Path directory;

    @Test
    @DisplayName("JSON-мост сохраняет исходные значения и пояснения ошибок")
    void jsonBridgeExportsOriginalValuesAndComments() throws Exception {
        Path source = Path.of("target/electron-fixture.xlsx").toAbsolutePath();
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Операции");
            String[] headers = {"Дата", "Номер", "Контрагент", "Назначение", "Сумма"};
            var heading = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) heading.createCell(i).setCellValue(headers[i]);
            String[][] values = {
                {"16.11.2026", "001", "Поставщик", "Оплата за товар", "1 234,56"},
                {"17.11.2026", "002", "Банк", "Комиссия", "ошибка суммы"}
            };
            for (int i = 0; i < values.length; i++) {
                var row = sheet.createRow(i + 1);
                for (int j = 0; j < values[i].length; j++) row.createCell(j).setCellValue(values[i][j]);
            }
            try (var stream = Files.newOutputStream(source)) {
                book.write(stream);
            }
        }
        Profile profile = new Profile();
        OutputField field = new OutputField("Расходы", "Сумма");
        field.setKind(OutputType.NUMBER);
        profile.getFields().add(field);
        Path output = directory.resolve("result.xlsx");
        Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "local.xlsx.cli.EngineCommand")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try (var writer = new PrintWriter(child.getOutputStream(), true, StandardCharsets.UTF_8)) {
            writer.println(new ObjectMapper()
                    .writeValueAsString(Map.of(
                            "operation",
                            "convert",
                            "input",
                            source.toString(),
                            "output",
                            output.toString(),
                            "sheet",
                            0,
                            "profile",
                            profile)));
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
        String message = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), message);
        assertTrue(message.contains("\"errors\":1"), message);
        try (var book = new XSSFWorkbook(output.toFile())) {
            var cell = book.getSheetAt(0).getRow(2).getCell(0);
            assertEquals("ошибка суммы", cell.getStringCellValue());
            assertNotNull(cell.getCellComment());
        }
    }
}
