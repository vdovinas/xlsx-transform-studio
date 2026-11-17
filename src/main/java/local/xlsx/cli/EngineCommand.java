package local.xlsx.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import local.xlsx.model.CsvOptions;
import local.xlsx.model.Profile;
import local.xlsx.service.CsvImportService;
import local.xlsx.service.XlsxConversionService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local JSON protocol used by the desktop main process; never exposed over the network.
 */
public final class EngineCommand {
    private static final ObjectMapper JSON = new ObjectMapper();

    private EngineCommand() {
    }

    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        JsonNode request = JSON.readTree(input.readLine());
        var cancelled = new AtomicBoolean();
        Thread cancellationReader = new Thread(
                () -> {
                    try {
                        String command = input.readLine();
                        if (command == null || "cancel".equals(command)) cancelled.set(true);
                    } catch (Exception ignored) {
                        cancelled.set(true);
                    }
                },
                "cancellation-reader");
        cancellationReader.setDaemon(true);
        cancellationReader.start();
        try {
            Path source = Path.of(request.required("input").asText());
            int sheet = request.path("sheet").asInt(0);
            boolean csv = source.toString().toLowerCase(Locale.ROOT).endsWith(".csv");
            Object result;
            switch (request.required("operation").asText()) {
                case "inspect" -> {
                    CsvOptions options = request.hasNonNull("csv")
                            ? JSON.treeToValue(request.get("csv"), CsvOptions.class)
                            : new CsvOptions();
                    result = csv
                            ? CsvImportService.inspect(
                            source, request.path("headerRow").asInt(1), options, cancelled::get)
                            : XlsxConversionService.inspect(
                            source, sheet, request.path("headerRow").asInt(1), cancelled::get);
                }
                case "convert" -> {
                    Profile profile = JSON.treeToValue(request.required("profile"), Profile.class);
                    result = csv
                            ? CsvImportService.convert(
                            source,
                            Path.of(request.required("output").asText()),
                            profile,
                            cancelled::get,
                            count -> emit(Map.of("type", "progress", "count", count)))
                            : XlsxConversionService.convert(
                            source,
                            Path.of(request.required("output").asText()),
                            sheet,
                            profile,
                            cancelled::get,
                            count -> emit(Map.of("type", "progress", "count", count)));
                }
                default -> throw new IllegalArgumentException("Неизвестная операция");
            }
            emit(Map.of("type", "result", "value", result));
        } catch (CancellationException e) {
            emit(Map.of("type", "cancelled"));
        } catch (Exception e) {
            emit(Map.of("type", "error", "message", e.getMessage() == null ? e.toString() : e.getMessage()));
            System.exit(1);
        }
    }

    private static synchronized void emit(Object message) {
        try {
            System.out.println(JSON.writeValueAsString(message));
            System.out.flush();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось передать результат", e);
        }
    }
}
