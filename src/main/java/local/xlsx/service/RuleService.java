package local.xlsx.service;

import local.xlsx.model.Condition;
import local.xlsx.model.OutputField;
import local.xlsx.model.RuleBranch;
import local.xlsx.model.TransformationStep;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleService {
    private RuleService() {
    }

    public static BigDecimal number(String s) {
        String cleaned = s.strip()
                .replace("\u00a0", "")
                .replace("\u202f", "")
                .replace(" ", "")
                .replace(',', '.');
        return new BigDecimal(cleaned);
    }

    public static LocalDate date(String s, String pattern) {
        if (!pattern.isBlank())
            return LocalDate.parse(
                    s,
                    DateTimeFormatter.ofPattern(pattern.replace("yyyy", "uuuu"))
                            .withResolverStyle(ResolverStyle.STRICT));
        for (String p : List.of("dd.MM.uuuu", "uuuu-MM-dd", "dd/MM/uuuu")) {
            try {
                return LocalDate.parse(s, DateTimeFormatter.ofPattern(p).withResolverStyle(ResolverStyle.STRICT));
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new IllegalArgumentException("Не удалось распознать дату: " + s);
    }

    public static String read(Map<String, String> row, String source) {
        if (source.isBlank()) return "";
        if (!row.containsKey(source)) throw new IllegalArgumentException("Не найден входной столбец: " + source);
        return row.get(source);
    }

    public static boolean matches(List<Condition> conditions, boolean all, Map<String, String> row) {
        if (conditions.isEmpty()) return true;
        // Evaluate every condition so a missing column cannot disappear behind short-circuit evaluation.
        boolean result = all;
        for (Condition c : conditions) {
            boolean m = test(c, row);
            result = all ? result && m : result || m;
        }
        return result;
    }

    public static boolean test(Condition c, Map<String, String> row) {
        String a = read(row, c.getSource()), b = c.getValue();
        if (c.isIgnoreCase()) {
            a = a.toLowerCase(Locale.ROOT);
            b = b.toLowerCase(Locale.ROOT);
        }
        return switch (c.getOperator()) {
            case EQUALS -> a.equals(b);
            case NOT_EQUALS -> !a.equals(b);
            case CONTAINS -> a.contains(b);
            case NOT_CONTAINS -> !a.contains(b);
            case STARTS_WITH -> a.startsWith(b);
            case EMPTY -> a.isBlank();
            case NOT_EMPTY -> !a.isBlank();
            case GREATER -> number(a).compareTo(number(b)) > 0;
            case LESS -> number(a).compareTo(number(b)) < 0;
            case DATE_BEFORE -> date(a, "").isBefore(date(b, ""));
            case DATE_AFTER -> date(a, "").isAfter(date(b, ""));
        };
    }

    public static String apply(OutputField f, Map<String, String> row) {
        String value = read(row, f.getSource());
        for (TransformationStep s : f.getSteps()) {
            if (!matches(s.getConditions(), s.isMatchAll(), row)) continue;
            value = switch (s.getAction()) {
                case TRIM -> value.strip();
                case LOWER -> value.toLowerCase(Locale.ROOT);
                case UPPER -> value.toUpperCase(Locale.ROOT);
                case CONSTANT -> s.getArgument();
                case IF_EMPTY -> value.isBlank() ? s.getArgument() : value;
                case IF_EMPTY_SOURCE -> value.isBlank() ? read(row, s.getArgument()) : value;
                case REPLACE -> value.replace(s.getArgument(), s.getReplacement());
                case EXTRACT -> {
                    Matcher matcher = Pattern.compile(s.getArgument(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                            .matcher(value);
                    if (!matcher.find()) throw new IllegalArgumentException("Шаблон не нашёл значение");
                    yield matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
                }
                case NUMBER -> number(value).toPlainString();
                case DATE -> date(value, s.getArgument()).toString();
                case CLASSIFY -> {
                    String found = value;
                    for (RuleBranch c : s.getCases())
                        if (matches(c.getConditions(), c.isMatchAll(), row)) {
                            found = c.getValue();
                            break;
                        }
                    yield found;
                }
            };
        }
        if (f.isRequired() && value.isBlank()) throw new IllegalArgumentException("Обязательное значение отсутствует");
        return value;
    }
}
