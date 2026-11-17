package local.xlsx.service;

import local.xlsx.model.*;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

public final class XlsxConversionService {
    private XlsxConversionService() {
    }

    public record Layout(List<String> sheets, List<String> columns) {
    }

    public record Result(int read, int written, int errors) {
    }

    private static void xlsx(Path p) {
        if (!p.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new IllegalArgumentException("Выберите файл XLSX");
    }

    public static Layout inspect(Path input, int sheet, int header, BooleanSupplier cancelled) throws Exception {
        xlsx(input);
        List<String> names = new ArrayList<>(), columns = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(input.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            var sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            int i = 0;
            while (sheets.hasNext())
                try (InputStream stream = sheets.next()) {
                    names.add(sheets.getSheetName());
                    if (i++ == sheet)
                        parse(
                                reader,
                                stream,
                                (r, cells) -> {
                                    if (r == header - 1) columns.addAll(headers(cells));
                                },
                                cancelled,
                                header);
                }
        }
        if (names.isEmpty()) throw new IllegalArgumentException("В книге нет листов");
        return new Layout(names, columns);
    }

    static List<String> headers(Map<Integer, String> cells) {
        int max = cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        List<String> h = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i <= max; i++) {
            String value = cells.getOrDefault(i, "").strip();
            if (value.isBlank() || value.startsWith("@") || !used.add(value))
                value = "@" + CellReference.convertNumToColString(i);
            h.add(value);
        }
        return h;
    }

    public static Result convert(
            Path input, Path output, int sheetIndex, Profile profile, BooleanSupplier cancelled, IntConsumer progress)
            throws Exception {
        xlsx(input);
        xlsx(output);
        validate(profile);
        if (input.toRealPath().equals(output.toAbsolutePath().normalize())
                || (Files.exists(output) && Files.isSameFile(input, output)))
            throw new IllegalArgumentException("Результат нельзя записывать поверх исходного файла");
        Path temp = Files.createTempFile(output.toAbsolutePath().getParent(), "xlsx-result-", ".xlsx");
        int[] counts = new int[3];
        SXSSFWorkbook book = new SXSSFWorkbook(100);
        book.setCompressTempFiles(true);
        try (book;
             OPCPackage pkg = OPCPackage.open(input.toFile(), PackageAccess.READ)) {
            Sheet out = book.createSheet(profile.getSheetName());
            Row head = out.createRow(0);
            CellStyle heading = book.createCellStyle();
            Font font = book.createFont();
            font.setBold(true);
            heading.setFont(font);
            List<CellStyle> styles = new ArrayList<>(), errorStyles = new ArrayList<>();
            for (int i = 0; i < profile.getFields().size(); i++) {
                OutputField f = profile.getFields().get(i);
                Cell c = head.createCell(i);
                c.setCellValue(f.getName());
                c.setCellStyle(heading);
                out.setColumnWidth(i, Math.min(60, Math.max(18, f.getName().length() + 4)) * 256);
                CellStyle style = book.createCellStyle();
                String format = f.getFormat().isBlank()
                        ? (f.getKind() == OutputType.NUMBER
                        ? "0.00"
                        : f.getKind() == OutputType.DATE ? "dd.mm.yyyy" : "@")
                        : f.getFormat();
                style.setDataFormat(book.createDataFormat().getFormat(format));
                styles.add(style);
                CellStyle error = book.createCellStyle();
                error.cloneStyleFrom(style);
                error.setFillForegroundColor(IndexedColors.ROSE.getIndex());
                error.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                errorStyles.add(error);
            }
            Drawing<?> drawing = out.createDrawingPatriarch();
            CreationHelper helper = book.getCreationHelper();
            XSSFReader reader = new XSSFReader(pkg);
            var iterator = reader.getSheetsData();
            int sheet = 0;
            boolean found = false;
            while (iterator.hasNext())
                try (InputStream stream = iterator.next()) {
                    if (sheet++ != sheetIndex) continue;
                    found = true;
                    List<String> columns = new ArrayList<>();
                    parse(
                            reader,
                            stream,
                            (index, cells) -> {
                                if (index == profile.getHeaderRow() - 1) {
                                    columns.addAll(headers(cells));
                                    return;
                                }
                                if (index < profile.getHeaderRow()
                                        || cells.values().stream().allMatch(String::isBlank)) return;
                                if (++counts[0] > 100_000)
                                    throw new IllegalArgumentException("Превышен предел 100 000 строк данных");
                                Map<String, String> values = new HashMap<>();
                                for (int j = 0; j < columns.size(); j++)
                                    values.put(columns.get(j), cells.getOrDefault(j, ""));
                                for (int j = 0;
                                     j
                                             < Math.max(
                                             columns.size(),
                                             cells.keySet().stream()
                                                     .mapToInt(Integer::intValue)
                                                     .max()
                                                     .orElse(-1)
                                                     + 1);
                                     j++)
                                    values.put("@" + CellReference.convertNumToColString(j), cells.getOrDefault(j, ""));
                                String filterError = null;
                                try {
                                    if (!RuleService.matches(profile.getFilters(), profile.isMatchAll(), values))
                                        return;
                                    for (RuleBranch group : profile.getFilterGroups())
                                        if (!RuleService.matches(group.getConditions(), group.isMatchAll(), values))
                                            return;
                                } catch (RuntimeException e) {
                                    filterError = "Ошибка фильтра: " + e.getMessage();
                                }
                                Row target = out.createRow(++counts[1]);
                                for (int j = 0; j < profile.getFields().size(); j++) {
                                    OutputField field = profile.getFields().get(j);
                                    Cell cell = target.createCell(j);
                                    cell.setCellStyle(styles.get(j));
                                    String issue = filterError;
                                    try {
                                        String value = RuleService.apply(field, values);
                                        if (value.isBlank()) cell.setCellValue("");
                                        else
                                            switch (field.getKind()) {
                                                case TEXT -> cell.setCellValue(value);
                                                case NUMBER -> {
                                                    double n = RuleService.number(value)
                                                            .doubleValue();
                                                    if (!Double.isFinite(n))
                                                        throw new IllegalArgumentException("Число вне диапазона Excel");
                                                    cell.setCellValue(n);
                                                }
                                                case DATE -> cell.setCellValue(RuleService.date(value, "")
                                                        .atStartOfDay());
                                            }
                                    } catch (RuntimeException e) {
                                        cell.setCellValue(safe(values.getOrDefault(field.getSource(), "")));
                                        issue = (issue == null ? "" : issue + "; ") + e.getMessage();
                                    }
                                    if (issue != null) {
                                        counts[2]++;
                                        cell.setCellStyle(errorStyles.get(j));
                                        ClientAnchor anchor = helper.createClientAnchor();
                                        anchor.setCol1(j);
                                        anchor.setCol2(Math.min(j + 3, 16383));
                                        anchor.setRow1(target.getRowNum());
                                        anchor.setRow2(target.getRowNum() + 3);
                                        Comment comment = drawing.createCellComment(anchor);
                                        comment.setAuthor("XLSX Studio");
                                        comment.setString(helper.createRichTextString(
                                                safe("Исходная строка " + (index + 1) + ". " + issue)));
                                        cell.setCellComment(comment);
                                    }
                                }
                                if (counts[0] % 100 == 0) progress.accept(counts[0]);
                            },
                            cancelled,
                            Integer.MAX_VALUE);
                }
            if (!found) throw new IllegalArgumentException("Выбранный лист отсутствует");
            out.createFreezePane(0, 1);
            out.setAutoFilter(
                    new CellRangeAddress(0, counts[1], 0, profile.getFields().size() - 1));
            if (cancelled.getAsBoolean()) throw new CancellationException();
            try (OutputStream stream = Files.newOutputStream(temp)) {
                book.write(stream);
            }
            if (cancelled.getAsBoolean()) throw new CancellationException();
            try {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            }
            progress.accept(counts[0]);
            return new Result(counts[0], counts[1], counts[2]);
        } finally {
            book.dispose();
            Files.deleteIfExists(temp);
        }
    }

    static String safe(String text) {
        return text.length() > 32767 ? text.substring(0, 32767) : text;
    }

    public static void validate(Profile p) {
        if (p.getHeaderRow() < 1 || p.getHeaderRow() > 1048576)
            throw new IllegalArgumentException("Некорректная строка заголовков");
        WorkbookUtil.validateSheetName(p.getSheetName());
        if (p.getFields().isEmpty() || p.getFields().size() > 16384)
            throw new IllegalArgumentException("Добавьте от 1 до 16384 выходных столбцов");
        Set<String> names = new HashSet<>();
        for (OutputField f : p.getFields()) {
            if (f.getName().isBlank() || !names.add(f.getName()))
                throw new IllegalArgumentException("Названия выходных полей должны быть непустыми и уникальными");
            for (TransformationStep s : f.getSteps())
                if (s.getAction() == TransformationType.EXTRACT) java.util.regex.Pattern.compile(s.getArgument());
        }
    }

    private static class Stop extends RuntimeException {
    }

    private static void parse(
            XSSFReader reader,
            InputStream stream,
            BiConsumer<Integer, Map<Integer, String>> consumer,
            BooleanSupplier cancel,
            int stopAfter)
            throws Exception {
        var parser = XMLHelper.newXMLReader();
        boolean[] date1904 = {false};
        parser.setContentHandler(new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                if ("workbookPr".equals(localName)) {
                    String value = attributes.getValue("date1904");
                    date1904[0] = "1".equals(value) || "true".equals(value);
                }
            }
        });
        try (InputStream workbook = reader.getWorkbookData()) {
            parser.parse(new InputSource(workbook));
        }
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("ru-RU")) {
            @Override
            public String formatRawCellContents(double value, int idx, String format, boolean use1904) {
                if (DateUtil.isADateFormat(idx, format))
                    return DateUtil.getLocalDateTime(value, date1904[0])
                            .toLocalDate()
                            .toString();
                // Rules use stored numbers independently of display rounding or percent formats.
                return NumberToTextConverter.toText(value);
            }
        };
        var handler = new XSSFSheetXMLHandler.SheetContentsHandler() {
            Map<Integer, String> cells;

            public void startRow(int row) {
                if (cancel.getAsBoolean()) throw new CancellationException();
                if (row >= stopAfter) throw new Stop();
                cells = new HashMap<>();
            }

            public void endRow(int row) {
                consumer.accept(row, cells);
            }

            public void cell(String reference, String value, XSSFComment comment) {
                cells.put((int) new CellReference(reference).getCol(), value == null ? "" : value);
            }
        };
        parser.setContentHandler(new XSSFSheetXMLHandler(
                reader.getStylesTable(), null, reader.getSharedStringsTable(), handler, formatter, false));
        try {
            parser.parse(new InputSource(stream));
        } catch (Stop ignored) {
        }
    }
}
