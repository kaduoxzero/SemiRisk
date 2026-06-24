package com.semirisk.service;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class UploadParseService {

    private static final int MAX_CELL_LENGTH = 512;
    private static final List<String> EXPECTED_FIELDS = List.of("supplier", "material", "lead_time_days");

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(10L * 1024L * 1024L);
        ZipSecureFile.setMaxTextSize(5L * 1024L * 1024L);
    }

    public ParseResult parse(String filename, byte[] content) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                return parseExcel(content);
            }
            return parseDelimited(content, lower.endsWith(".tsv") ? "\t" : ",");
        } catch (Exception ex) {
            return new ParseResult(0, List.of(), List.of("[ERROR] File parse failed: " + ex.getClass().getSimpleName()));
        }
    }

    private ParseResult parseDelimited(byte[] content, String separator) {
        String text = new String(content, StandardCharsets.UTF_8).replace("\uFEFF", "");
        String[] lines = text.split("\\r?\\n");
        List<String> headers = new ArrayList<>();
        int dataRows = 0;
        int incompleteRows = 0;
        int sanitizedCells = 0;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] rawCells = line.split(java.util.regex.Pattern.quote(separator), -1);
            List<SanitizedCell> cells = Arrays.stream(rawCells).map(this::sanitizeCell).toList();
            sanitizedCells += (int) cells.stream().filter(SanitizedCell::changed).count();
            if (headers.isEmpty()) {
                headers = cells.stream().map(SanitizedCell::value).toList();
                continue;
            }
            dataRows++;
            boolean incomplete = cells.stream().anyMatch(cell -> cell.value().trim().isEmpty());
            if (incomplete || cells.size() < headers.size()) {
                incompleteRows++;
            }
        }
        return new ParseResult(dataRows, headers, buildWarnings(headers, dataRows, incompleteRows, sanitizedCells));
    }

    private ParseResult parseExcel(byte[] content) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                return new ParseResult(0, List.of(), List.of("[WARN] Excel workbook does not contain sheets"));
            }
            DataFormatter formatter = new DataFormatter();
            List<String> headers = new ArrayList<>();
            int dataRows = 0;
            int incompleteRows = 0;
            int sanitizedCells = 0;
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                boolean blankRow = true;
                short lastCellNum = row.getLastCellNum();
                for (int c = 0; c < Math.max(lastCellNum, 0); c++) {
                    Cell cell = row.getCell(c);
                    SanitizedCell sanitized = sanitizeCell(cell == null ? "" : formatter.formatCellValue(cell));
                    if (sanitized.changed()) {
                        sanitizedCells++;
                    }
                    if (!sanitized.value().isEmpty()) {
                        blankRow = false;
                    }
                    values.add(sanitized.value());
                }
                if (blankRow) {
                    continue;
                }
                if (headers.isEmpty()) {
                    headers = values;
                    continue;
                }
                dataRows++;
                if (values.stream().anyMatch(String::isEmpty)) {
                    incompleteRows++;
                }
            }
            return new ParseResult(dataRows, headers, buildWarnings(headers, dataRows, incompleteRows, sanitizedCells));
        }
    }

    private SanitizedCell sanitizeCell(String value) {
        String original = value == null ? "" : value;
        String cleaned = original
                .replace("\u0000", "")
                .replaceAll("(?i)<\\s*/?\\s*script[^>]*>", "")
                .replaceAll("(?i)javascript\\s*:", "")
                .trim();
        if (cleaned.length() > MAX_CELL_LENGTH) {
            cleaned = cleaned.substring(0, MAX_CELL_LENGTH);
        }
        if (!cleaned.isEmpty() && isFormulaTrigger(cleaned.charAt(0))) {
            cleaned = "'" + cleaned;
        }
        return new SanitizedCell(cleaned, !cleaned.equals(original.trim()));
    }

    private boolean isFormulaTrigger(char ch) {
        return ch == '=' || ch == '+' || ch == '-' || ch == '@' || ch == '\t' || ch == '\r' || ch == '\n';
    }

    private List<String> buildWarnings(List<String> headers, int dataRows, int incompleteRows, int sanitizedCells) {
        List<String> warnings = new ArrayList<>();
        List<String> lowerHeaders = headers.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        for (String field : EXPECTED_FIELDS) {
            if (lowerHeaders.stream().noneMatch(h -> h.contains(field))) {
                warnings.add("[WARN] Missing recommended field " + field + "; import continued with available columns");
            }
        }
        if (sanitizedCells > 0) {
            warnings.add("[WARN] Sanitized " + sanitizedCells + " potentially unsafe cells");
        }
        if (incompleteRows > 0) {
            warnings.add("[WARN] Detected " + incompleteRows + " rows with empty fields");
        }
        if (dataRows == 0) {
            warnings.add("[WARN] No data rows parsed; check file content and headers");
        } else {
            warnings.add("[INFO] Parsed " + dataRows + " rows with " + headers.size() + " headers");
        }
        return warnings;
    }

    private record SanitizedCell(String value, boolean changed) {}

    public record ParseResult(int rows, List<String> headers, List<String> warnings) {
    }
}
