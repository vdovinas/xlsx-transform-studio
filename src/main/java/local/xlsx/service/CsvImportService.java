package local.xlsx.service;

import local.xlsx.model.CsvLayout;
import local.xlsx.model.CsvOptions;
import local.xlsx.model.Profile;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.*;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

public final class CsvImportService {
    private CsvImportService() {
    }

    public static CsvLayout inspect(Path input, int headerRow, CsvOptions options, BooleanSupplier cancelled)
            throws IOException {
        CsvOptions detected = detect(input, headerRow, options, cancelled);
        try (Reader reader = reader(input, detected.encoding());
             CSVParser parser = format(detected).parse(reader)) {
            for (CSVRecord record : parser) {
                checkCancelled(cancelled);
                if (record.getRecordNumber() == headerRow) {
                    Map<Integer, String> cells = new HashMap<>();
                    for (int i = 0; i < record.size(); i++) cells.put(i, record.get(i));
                    return new CsvLayout(
                            List.of("CSV"),
                            XlsxConversionService.headers(cells),
                            detected.encoding(),
                            detected.delimiter());
                }
            }
        }
        throw new IllegalArgumentException("В CSV нет выбранной строки заголовков");
    }

    public static XlsxConversionService.Result convert(
            Path input, Path output, Profile profile, BooleanSupplier cancelled, IntConsumer progress)
            throws Exception {
        XlsxConversionService.validate(profile);
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())
                || (Files.exists(output) && Files.isSameFile(input, output))) {
            throw new IllegalArgumentException("Результат нельзя записывать поверх исходного файла");
        }
        CsvOptions options = detect(input, profile.getHeaderRow(), profile.getCsv(), cancelled);
        Path staged = Files.createTempFile("xlsx-studio-csv-", ".xlsx");
        // Keep one rule engine and one XLSX writer for both input formats.
        try {
            try (var book = new SXSSFWorkbook(100);
                 Reader reader = reader(input, options.encoding());
                 CSVParser parser = format(options).parse(reader)) {
                book.setCompressTempFiles(true);
                var sheet = book.createSheet("CSV");
                int dataRows = 0;
                for (CSVRecord record : parser) {
                    checkCancelled(cancelled);
                    if (record.getRecordNumber() > 1048576 || record.size() > 16384) {
                        throw new IllegalArgumentException("CSV превышает допустимый размер таблицы Excel");
                    }
                    if (record.getRecordNumber() > profile.getHeaderRow()
                            && record.stream().anyMatch(value -> !value.isBlank())
                            && ++dataRows > 100_000) {
                        throw new IllegalArgumentException("Превышен предел 100 000 строк данных");
                    }
                    var row = sheet.createRow((int) record.getRecordNumber() - 1);
                    for (int i = 0; i < record.size(); i++) {
                        if (record.get(i).length() > 32767) {
                            throw new IllegalArgumentException("Запись CSV " + record.getRecordNumber()
                                    + ": значение длиннее предела Excel (32767 символов)");
                        }
                        row.createCell(i).setCellValue(record.get(i));
                    }
                }
                if (sheet.getLastRowNum() < profile.getHeaderRow() - 1) {
                    throw new IllegalArgumentException("В CSV нет выбранной строки заголовков");
                }
                checkCancelled(cancelled);
                try (var stream = Files.newOutputStream(staged)) {
                    book.write(stream);
                }
            }
            return XlsxConversionService.convert(staged, output, 0, profile, cancelled, progress);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static CSVFormat format(CsvOptions options) {
        return CSVFormat.RFC4180
                .builder()
                .setDelimiter(options.delimiter().charAt(0))
                .setIgnoreEmptyLines(false)
                .get();
    }

    private static CsvOptions detect(Path input, int headerRow, CsvOptions options, BooleanSupplier cancelled)
            throws IOException {
        if (headerRow < 1 || headerRow > 1048576) throw new IllegalArgumentException("Проверьте строку заголовков");
        String encoding = options.encoding();
        boolean utf8Bom;
        try (var stream = Files.newInputStream(input)) {
            byte[] prefix = stream.readNBytes(4);
            utf8Bom = prefix.length >= 3
                    && prefix[0] == (byte) 0xef
                    && prefix[1] == (byte) 0xbb
                    && prefix[2] == (byte) 0xbf;
            if (prefix.length >= 2
                    && ((prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xfe)
                    || (prefix[0] == (byte) 0xfe && prefix[1] == (byte) 0xff))) {
                throw new IllegalArgumentException("Сохраните CSV в UTF-8 или Windows-1251: UTF-16 не поддерживается");
            }
        }
        if (encoding.equals("auto")) {
            encoding = "UTF-8";
            try (Reader reader = reader(input, encoding)) {
                char[] buffer = new char[8192];
                while (reader.read(buffer) != -1) checkCancelled(cancelled);
            } catch (CharacterCodingException e) {
                if (utf8Bom) throw new IOException("CSV с меткой UTF-8 содержит некорректные символы", e);
                encoding = "windows-1251";
            }
        }
        String delimiter = options.delimiter();
        if (delimiter.equals("auto")) delimiter = detectDelimiter(input, encoding, headerRow, cancelled);
        return new CsvOptions(encoding, delimiter);
    }

    private static String detectDelimiter(Path input, String encoding, int headerRow, BooleanSupplier cancelled)
            throws IOException {
        char[] candidates = {';', ',', '\t'};
        int[] counts = new int[3];
        boolean quoted = false, previousCr = false;
        int record = 1;
        try (Reader reader = reader(input, encoding)) {
            int value;
            while ((value = reader.read()) != -1) {
                checkCancelled(cancelled);
                char character = (char) value;
                if (character == '"') quoted = !quoted;
                if (!quoted && (character == '\r' || character == '\n')) {
                    if (character == '\n' && previousCr) {
                        previousCr = false;
                        continue;
                    }
                    if (record++ == headerRow) break;
                    counts = new int[3];
                } else if (!quoted && record == headerRow) {
                    for (int i = 0; i < candidates.length; i++) if (character == candidates[i]) counts[i]++;
                }
                previousCr = !quoted && character == '\r';
            }
        }
        int best = 0;
        for (int i = 1; i < counts.length; i++) if (counts[i] > counts[best]) best = i;
        return String.valueOf(candidates[best]);
    }

    private static Reader reader(Path input, String encoding) throws IOException {
        Charset charset = Charset.forName(encoding);
        var decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader =
                new PushbackReader(new BufferedReader(new InputStreamReader(Files.newInputStream(input), decoder)), 1);
        try {
            int first = reader.read();
            if (first != -1 && first != '\ufeff') reader.unread(first);
            return reader;
        } catch (IOException e) {
            reader.close();
            throw e;
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
    }
}
