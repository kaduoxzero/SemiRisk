package com.semirisk.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 企业级风险报告生成器。
 *
 * <p>输出专业排版的 PDF / DOCX / PPTX：封面色带标题、机密标识、报告元信息、风险等级标注、
 * 带编号的小节标题与分隔线、自动分页、页眉页脚页码。中文使用 PDF 标准 CID 字体 STSong-Light，
 * 无需额外字体依赖。报告正文来自真实公开源 + 风险快照 + DeepSeek/本地 RAG 生成的内容。</p>
 */
public final class ReportFileFactory {

    private ReportFileFactory() {
    }

    // 企业版式配色（浅色专业主题）
    private static final float[] NAVY = {0.094f, 0.157f, 0.357f};      // 封面/标题深藏蓝
    private static final float[] ACCENT = {0.149f, 0.388f, 0.922f};    // 强调蓝
    private static final float[] INK = {0.106f, 0.149f, 0.231f};       // 正文近黑
    private static final float[] GREY = {0.42f, 0.45f, 0.52f};         // 次要灰
    private static final float[] LIGHT = {0.945f, 0.957f, 0.976f};     // 浅底
    private static final float[] DANGER = {0.86f, 0.15f, 0.15f};
    private static final float[] WARN = {0.78f, 0.55f, 0.05f};
    private static final float[] OK = {0.13f, 0.55f, 0.33f};

    private static final float PAGE_W = 595f;
    private static final float PAGE_H = 842f;
    private static final float MARGIN_L = 56f;
    private static final float MARGIN_R = 539f;
    private static final float CONTENT_W = MARGIN_R - MARGIN_L;
    private static final float BOTTOM = 64f;

    private static final Pattern HEADING = Pattern.compile("^(?:[一二三四五六七八九十百]+、|\\d+[、.\\)]|【.+?】).*");

    public static ReportFile build(String id, String template, String language, String format, List<String> findings) {
        String normalized = normalizeFormat(format);
        Report report = parse(id, template, language, findings);
        return switch (normalized) {
            case "WORD" -> new ReportFile(filename(id, "docx"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(report));
            case "PPT" -> new ReportFile(filename(id, "pptx"), "application/vnd.openxmlformats-officedocument.presentationml.presentation", pptx(report));
            default -> new ReportFile(filename(id, "pdf"), "application/pdf", pdf(report));
        };
    }

    // ---------------------------------------------------------------------
    // 内容模型解析
    // ---------------------------------------------------------------------

    private record Section(String heading, List<String> paragraphs) {
    }

    private record Report(String title, String reportId, String templateName, String language,
                          String date, String classification, String riskLevel, String modelStatus,
                          List<Section> sections) {
    }

    private static Report parse(String id, String template, String language, List<String> findings) {
        String templateName = templateName(template);
        String title = "SemiRisk 企业供应链风险报告";
        String modelStatus = "";
        String riskLevel = "";
        List<Section> sections = new ArrayList<>();
        Section current = null;
        List<String> source = findings == null ? List.of() : findings;
        for (String raw : source) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("报告编号") || line.startsWith("写作方式")) {
                continue;
            }
            if (line.startsWith("AI状态") || line.startsWith("AI 状态")) {
                modelStatus = line.replaceFirst("^AI\\s*状态[:：]?", "").trim();
                continue;
            }
            if (line.startsWith("SemiRisk") && (line.contains("报告"))) {
                title = line;
                continue;
            }
            if (riskLevel.isEmpty()) {
                if (line.contains("高危")) {
                    riskLevel = "高危";
                } else if (line.contains("中危")) {
                    riskLevel = "中危";
                } else if (line.contains("低危")) {
                    riskLevel = "低危";
                }
            }
            if (HEADING.matcher(line).matches()) {
                current = new Section(line, new ArrayList<>());
                sections.add(current);
            } else {
                if (current == null) {
                    current = new Section("一、报告摘要", new ArrayList<>());
                    sections.add(current);
                }
                current.paragraphs().add(line);
            }
        }
        if (sections.isEmpty()) {
            Section s = new Section("一、报告摘要", new ArrayList<>());
            s.paragraphs().add("本报告由 SemiRisk 平台基于已采集公开源情报、风险告警与知识库 SOP 生成。");
            sections.add(s);
        }
        return new Report(title, id, templateName, language == null || language.isBlank() ? "中文" : language,
                LocalDate.now().toString(), "企业机密 · Confidential",
                riskLevel.isEmpty() ? "待研判" : riskLevel, modelStatus, sections);
    }

    private static String templateName(String template) {
        if (template == null) {
            return "风险评估报告";
        }
        return switch (template) {
            case "supply-chain" -> "供应链分析报告";
            case "enterprise-dd" -> "企业尽调报告";
            default -> "风险评估报告";
        };
    }

    private static String normalizeFormat(String format) {
        if (format == null) {
            return "PDF";
        }
        String value = format.trim().toUpperCase(Locale.ROOT);
        if (value.contains("WORD") || value.contains("DOC")) {
            return "WORD";
        }
        if (value.contains("PPT") || value.contains("POWERPOINT")) {
            return "PPT";
        }
        return "PDF";
    }

    private static String filename(String id, String extension) {
        String safeId = id == null || id.isBlank() ? "semirisk-report" : id.replaceAll("[^A-Za-z0-9_-]", "-");
        return safeId + "-report." + extension;
    }

    // ---------------------------------------------------------------------
    // PDF（企业版式，自动分页）
    // ---------------------------------------------------------------------

    private static byte[] pdf(Report report) {
        PdfDoc doc = new PdfDoc();
        doc.newPage();
        // 封面色带
        doc.fillRect(0, PAGE_H - 132, PAGE_W, 132, NAVY);
        doc.fillRect(0, PAGE_H - 138, PAGE_W, 6, ACCENT);
        doc.text(MARGIN_L, PAGE_H - 64, 22, report.title(), white());
        doc.text(MARGIN_L, PAGE_H - 92, 12, report.templateName() + " · " + report.classification(), new float[]{0.78f, 0.85f, 0.96f});
        doc.text(MARGIN_L, PAGE_H - 114, 10, "SemiRisk 半导体供应链风险智能平台", new float[]{0.66f, 0.74f, 0.90f});
        doc.setY(PAGE_H - 168);

        // 元信息表
        metaRow(doc, "报告编号", report.reportId(), "报告类型", report.templateName());
        metaRow(doc, "生成日期", report.date(), "报告语言", report.language());
        metaRow(doc, "综合风险", report.riskLevel(), "密级", report.classification());
        doc.gapY(8);

        // 风险等级标注框
        float[] band = levelColor(report.riskLevel());
        doc.ensure(46);
        float by = doc.y() - 40;
        doc.fillRect(MARGIN_L, by, CONTENT_W, 40, LIGHT);
        doc.fillRect(MARGIN_L, by, 5, 40, band);
        doc.text(MARGIN_L + 16, by + 24, 12, "综合风险等级：" + report.riskLevel(), band);
        if (!report.modelStatus().isEmpty()) {
            doc.text(MARGIN_L + 16, by + 9, 8.5f, truncate(report.modelStatus(), 78), GREY);
        }
        doc.setY(by - 18);

        // 小节
        for (Section section : report.sections()) {
            renderHeading(doc, section.heading());
            for (String para : section.paragraphs()) {
                renderParagraph(doc, para);
            }
            doc.gapY(6);
        }

        doc.finish(report);
        return doc.bytes();
    }

    private static void metaRow(PdfDoc doc, String k1, String v1, String k2, String v2) {
        doc.ensure(26);
        float y = doc.y() - 22;
        doc.fillRect(MARGIN_L, y, CONTENT_W, 22, LIGHT);
        float half = CONTENT_W / 2f;
        doc.text(MARGIN_L + 12, y + 7, 9.5f, k1 + "：" + v1, INK);
        doc.text(MARGIN_L + half + 12, y + 7, 9.5f, k2 + "：" + v2, INK);
        doc.setY(y - 4);
    }

    private static void renderHeading(PdfDoc doc, String heading) {
        doc.ensure(34);
        doc.gapY(6);
        float y = doc.y() - 16;
        doc.text(MARGIN_L, y, 13.5f, heading, NAVY);
        doc.lineRect(MARGIN_L, y - 6, CONTENT_W, ACCENT, 1.1f);
        doc.setY(y - 16);
    }

    private static void renderParagraph(PdfDoc doc, String para) {
        boolean bullet = para.startsWith("•") || para.startsWith("-") || para.startsWith("·") || para.startsWith("*");
        String text = bullet ? para.replaceFirst("^[•\\-·*]\\s*", "") : para;
        List<String> wrapped = wrap(text, 11, CONTENT_W - (bullet ? 16 : 0));
        for (int i = 0; i < wrapped.size(); i++) {
            doc.ensure(17);
            float y = doc.y() - 14;
            float x = MARGIN_L + (bullet ? 16 : 0);
            if (bullet && i == 0) {
                doc.text(MARGIN_L + 2, y, 11, "•", ACCENT);
            }
            doc.text(x, y, 11, wrapped.get(i), INK);
            doc.setY(y - 3);
        }
    }

    private static float[] levelColor(String level) {
        if (level == null) {
            return GREY;
        }
        if (level.contains("高")) {
            return DANGER;
        }
        if (level.contains("中")) {
            return WARN;
        }
        if (level.contains("低")) {
            return OK;
        }
        return GREY;
    }

    private static float[] white() {
        return new float[]{1f, 1f, 1f};
    }

    /** 近似按字符宽度换行（CJK 约等于字号宽，ASCII 约 0.55 字号宽）。 */
    private static List<String> wrap(String text, float size, float maxWidth) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            float cw = (c < 128 ? 0.55f : 1.0f) * size;
            if (w + cw > maxWidth && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                w = 0;
            }
            cur.append(c);
            w += cw;
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out.isEmpty() ? List.of("") : out;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 极简 PDF 写入器，支持多页、文本、填充矩形、横线，中文用 STSong-Light CID 字体。 */
    private static final class PdfDoc {
        private final List<StringBuilder> pages = new ArrayList<>();
        private StringBuilder cur;
        private float y = PAGE_H;

        void newPage() {
            cur = new StringBuilder();
            pages.add(cur);
            y = PAGE_H;
        }

        float y() {
            return y;
        }

        void setY(float value) {
            y = value;
        }

        void gapY(float d) {
            y -= d;
        }

        void ensure(float needed) {
            if (y - needed < BOTTOM) {
                newPage();
                y = PAGE_H - 56;
            }
        }

        void text(float x, float y, float size, String value, float[] color) {
            cur.append(String.format(Locale.ROOT, "BT %.3f %.3f %.3f rg /F1 %.1f Tf %.2f %.2f Td %s Tj ET\n",
                    color[0], color[1], color[2], size, x, y, hexText(value)));
        }

        void fillRect(float x, float y, float w, float h, float[] color) {
            cur.append(String.format(Locale.ROOT, "%.3f %.3f %.3f rg %.2f %.2f %.2f %.2f re f\n",
                    color[0], color[1], color[2], x, y, w, h));
        }

        void lineRect(float x, float y, float w, float[] color, float lw) {
            cur.append(String.format(Locale.ROOT, "%.3f %.3f %.3f RG %.2f w %.2f %.2f m %.2f %.2f l S\n",
                    color[0], color[1], color[2], lw, x, y, x + w, y));
        }

        void finish(Report report) {
            // 每页页眉细线 + 页脚
            for (int i = 0; i < pages.size(); i++) {
                StringBuilder p = pages.get(i);
                StringBuilder footer = new StringBuilder();
                footer.append(String.format(Locale.ROOT, "%.3f %.3f %.3f RG 0.6 w %.2f %.2f m %.2f %.2f l S\n",
                        0.80f, 0.83f, 0.88f, MARGIN_L, 50f, MARGIN_R, 50f));
                footer.append(String.format(Locale.ROOT, "BT %.3f %.3f %.3f rg /F1 8 Tf %.2f %.2f Td %s Tj ET\n",
                        GREY[0], GREY[1], GREY[2], MARGIN_L, 36f, hexText("SemiRisk · 半导体供应链风险智能平台 · " + report.classification())));
                footer.append(String.format(Locale.ROOT, "BT %.3f %.3f %.3f rg /F1 8 Tf %.2f %.2f Td %s Tj ET\n",
                        GREY[0], GREY[1], GREY[2], MARGIN_R - 60f, 36f, hexText("第 " + (i + 1) + " / " + pages.size() + " 页")));
                p.append(footer);
            }
        }

        byte[] bytes() {
            int pageCount = pages.size();
            // 对象编号：1 Catalog, 2 Pages, 3 Font(Type0), 4 CIDFont, 5 FontDescriptor 略；
            // 每页 2 个对象（Page + Contents），从对象 5 开始。
            List<byte[]> objects = new ArrayList<>();
            // 占位，稍后填充 1..4
            int firstPageObj = 5;
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < pageCount; i++) {
                kids.append(firstPageObj + i * 2).append(" 0 R ");
            }
            objects.add(ascii("<< /Type /Catalog /Pages 2 0 R >>"));
            objects.add(ascii("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>"));
            objects.add(ascii("<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H /DescendantFonts [4 0 R] >>"));
            objects.add(ascii("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 2 >> >>"));
            for (int i = 0; i < pageCount; i++) {
                int contentObj = firstPageObj + i * 2 + 1;
                objects.add(ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) PAGE_W + " " + (int) PAGE_H
                        + "] /Resources << /Font << /F1 3 0 R >> >> /Contents " + contentObj + " 0 R >>"));
                byte[] content = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
                objects.add(concat(ascii("<< /Length " + content.length + " >>\nstream\n"), content, ascii("endstream")));
            }
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                write(out, "%PDF-1.4\n%âãÏÓ\n");
                List<Integer> offsets = new ArrayList<>();
                for (int i = 0; i < objects.size(); i++) {
                    offsets.add(out.size());
                    write(out, (i + 1) + " 0 obj\n");
                    out.write(objects.get(i));
                    write(out, "\nendobj\n");
                }
                int xref = out.size();
                write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
                for (Integer offset : offsets) {
                    write(out, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
                }
                write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
                return out.toByteArray();
            } catch (IOException ex) {
                throw new IllegalStateException("PDF report generation failed", ex);
            }
        }
    }

    private static String hexText(String value) {
        return "<" + HexFormat.of().formatHex((value == null ? "" : value).getBytes(StandardCharsets.UTF_16BE)).toUpperCase(Locale.ROOT) + ">";
    }

    // ---------------------------------------------------------------------
    // DOCX（企业版式：标题、标题样式、元信息表格、小节）
    // ---------------------------------------------------------------------

    private static byte[] docx(Report report) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                zip(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                          <Default Extension="xml" ContentType="application/xml"/>
                          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                        </Types>
                        """);
                zip(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                        </Relationships>
                        """);
                zip(zip, "word/document.xml", wordDocument(report));
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Word report generation failed", ex);
        }
    }

    private static String wordDocument(Report report) {
        StringBuilder b = new StringBuilder();
        // 标题
        b.append(wp(report.title(), 36, "1F2D5B", true, "center"));
        b.append(wp(report.templateName() + " · " + report.classification(), 18, "5A6173", false, "center"));
        // 元信息表
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/><w:left w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/><w:right w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/></w:tblBorders></w:tblPr>");
        b.append(tr("报告编号", report.reportId(), "报告类型", report.templateName()));
        b.append(tr("生成日期", report.date(), "报告语言", report.language()));
        b.append(tr("综合风险", report.riskLevel(), "密级", report.classification()));
        b.append("</w:tbl>");
        b.append(wp("", 8, "000000", false, "left"));
        // 小节
        for (Section section : report.sections()) {
            b.append(wp(section.heading(), 24, "15276A", true, "left"));
            for (String para : section.paragraphs()) {
                b.append(wp(para, 21, "1B2542", false, "left"));
            }
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>%s<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body>
                </w:document>
                """.formatted(b);
    }

    private static String wp(String text, int halfPt, String color, boolean bold, String align) {
        return "<w:p><w:pPr><w:jc w:val=\"" + align + "\"/><w:spacing w:after=\"120\"/></w:pPr>"
                + "<w:r><w:rPr><w:rFonts w:ascii=\"Microsoft YaHei\" w:eastAsia=\"Microsoft YaHei\" w:hAnsi=\"Microsoft YaHei\"/>"
                + (bold ? "<w:b/>" : "") + "<w:color w:val=\"" + color + "\"/><w:sz w:val=\"" + halfPt + "\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + xml(text) + "</w:t></w:r></w:p>";
    }

    private static String tr(String k1, String v1, String k2, String v2) {
        return "<w:tr>" + tc(k1, true) + tc(v1, false) + tc(k2, true) + tc(v2, false) + "</w:tr>";
    }

    private static String tc(String text, boolean head) {
        String shade = head ? "<w:shd w:val=\"clear\" w:fill=\"EEF2F9\"/>" : "";
        return "<w:tc><w:tcPr><w:tcW w:w=\"1250\" w:type=\"pct\"/>" + shade + "</w:tcPr>"
                + "<w:p><w:pPr><w:spacing w:after=\"40\"/></w:pPr><w:r><w:rPr>"
                + "<w:rFonts w:ascii=\"Microsoft YaHei\" w:eastAsia=\"Microsoft YaHei\"/>"
                + (head ? "<w:b/><w:color w:val=\"15276A\"/>" : "<w:color w:val=\"1B2542\"/>")
                + "<w:sz w:val=\"20\"/></w:rPr><w:t xml:space=\"preserve\">" + xml(text) + "</w:t></w:r></w:p></w:tc>";
    }

    // ---------------------------------------------------------------------
    // PPTX（标题页 + 内容页，较此前更清晰）
    // ---------------------------------------------------------------------

    private static byte[] pptx(Report report) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                zip(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                          <Default Extension="xml" ContentType="application/xml"/>
                          <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                          <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                        </Types>
                        """);
                zip(zip, "_rels/.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                        </Relationships>
                        """);
                zip(zip, "ppt/_rels/presentation.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                        </Relationships>
                        """);
                zip(zip, "ppt/presentation.xml", """
                        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                        <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                          <p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
                          <p:sldSz cx="12192000" cy="6858000" type="screen16x9"/>
                        </p:presentation>
                        """);
                zip(zip, "ppt/slides/slide1.xml", slide(report));
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("PPT report generation failed", ex);
        }
    }

    private static String slide(Report report) {
        StringBuilder paras = new StringBuilder();
        paras.append(slidePara(report.title(), 2800, "1F2D5B", true));
        paras.append(slidePara(report.templateName() + " · " + report.classification() + " · " + report.date(), 1400, "5A6173", false));
        int count = 0;
        for (Section section : report.sections()) {
            if (count++ > 8) {
                break;
            }
            paras.append(slidePara(section.heading(), 1800, "15276A", true));
            int pcount = 0;
            for (String para : section.paragraphs()) {
                if (pcount++ > 2) {
                    break;
                }
                paras.append(slidePara("• " + truncate(para, 70), 1200, "1B2542", false));
            }
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
                    <p:sp><p:nvSpPr><p:cNvPr id="2" name="SemiRisk Report"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                      <p:spPr><a:xfrm><a:off x="685800" y="457200"/><a:ext cx="10820400" cy="5943600"/></a:xfrm></p:spPr>
                      <p:txBody><a:bodyPr/><a:lstStyle/>%s</p:txBody>
                    </p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
                """.formatted(paras);
    }

    private static String slidePara(String text, int sz, String color, boolean bold) {
        return "<a:p><a:r><a:rPr lang=\"zh-CN\" sz=\"" + sz + "\" b=\"" + (bold ? 1 : 0) + "\">"
                + "<a:solidFill><a:srgbClr val=\"" + color + "\"/></a:solidFill></a:rPr>"
                + "<a:t>" + xml(text) + "</a:t></a:r></a:p>";
    }

    private static String xml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static void zip(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    public record ReportFile(String filename, String contentType, byte[] body) {
    }
}
