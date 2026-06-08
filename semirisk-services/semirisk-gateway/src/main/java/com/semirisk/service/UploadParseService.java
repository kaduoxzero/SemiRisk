package com.semirisk.service;

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

/**
 * 上传文件真实解析服务。
 *
 * <p>对从 MinIO 取回的真实文件字节做结构化解析：CSV/TSV 按分隔符解析，Excel(.xlsx/.xls) 用 Apache POI 解析，
 * 统计真实数据行数、抽取表头、并对缺失关键字段（供应商/物料/交付周期）给出真实告警。
 * 不再使用随机行数或写死告警。</p>
 */
@Service
public class UploadParseService {

    private static final List<String> EXPECTED_FIELDS = List.of("supplier", "material", "lead_time_days");

    public ParseResult parse(String filename, byte[] content) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                return parseExcel(content);
            }
            return parseDelimited(content, lower.endsWith(".tsv") ? "\t" : ",");
        } catch (Exception ex) {
            return new ParseResult(0, List.of(), List.of("[ERROR] 文件解析失败：" + ex.getClass().getSimpleName()));
        }
    }

    private ParseResult parseDelimited(byte[] content, String separator) {
        String text = new String(content, StandardCharsets.UTF_8).replace("﻿", "");
        String[] lines = text.split("\\r?\\n");
        List<String> headers = new ArrayList<>();
        int dataRows = 0;
        int incompleteRows = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(java.util.regex.Pattern.quote(separator), -1);
            if (headers.isEmpty()) {
                headers = Arrays.stream(cells).map(String::trim).toList();
                continue;
            }
            dataRows++;
            boolean incomplete = Arrays.stream(cells).anyMatch(cell -> cell.trim().isEmpty());
            if (incomplete || cells.length < headers.size()) {
                incompleteRows++;
            }
        }
        return new ParseResult(dataRows, headers, buildWarnings(headers, dataRows, incompleteRows));
    }

    private ParseResult parseExcel(byte[] content) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
            if (sheet == null) {
                return new ParseResult(0, List.of(), List.of("[WARN] Excel 不含任何工作表"));
            }
            DataFormatter formatter = new DataFormatter();
            List<String> headers = new ArrayList<>();
            int dataRows = 0;
            int incompleteRows = 0;
            for (Row row : sheet) {
                if (row == null) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                boolean blankRow = true;
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                    if (!value.isEmpty()) {
                        blankRow = false;
                    }
                    values.add(value);
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
            return new ParseResult(dataRows, headers, buildWarnings(headers, dataRows, incompleteRows));
        }
    }

    private List<String> buildWarnings(List<String> headers, int dataRows, int incompleteRows) {
        List<String> warnings = new ArrayList<>();
        List<String> lowerHeaders = headers.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        for (String field : EXPECTED_FIELDS) {
            if (lowerHeaders.stream().noneMatch(h -> h.contains(field))) {
                warnings.add("[WARN] 缺少建议字段 " + field + "，已按可解析列继续导入");
            }
        }
        if (incompleteRows > 0) {
            warnings.add("[WARN] 检测到 " + incompleteRows + " 行存在空字段，已标记待人工复核");
        }
        if (dataRows == 0) {
            warnings.add("[WARN] 未解析到任何数据行，请检查文件内容与表头");
        } else {
            warnings.add("[INFO] 成功解析 " + dataRows + " 行数据，表头 " + headers.size() + " 列");
        }
        return warnings;
    }

    public record ParseResult(int rows, List<String> headers, List<String> warnings) {
    }
}
