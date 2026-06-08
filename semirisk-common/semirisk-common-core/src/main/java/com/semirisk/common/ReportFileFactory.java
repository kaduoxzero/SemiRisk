package com.semirisk.common;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 企业级风险报告生成器（PDF 使用 OpenPDF 正确渲染中英文，DOCX/PPTX 使用 OpenXML）。
 */
public final class ReportFileFactory {

    private ReportFileFactory() {
    }

    // 版式配色
    private static final Color NAVY   = new Color(24, 40, 91);
    private static final Color ACCENT = new Color(38, 99, 235);
    private static final Color INK    = new Color(27, 38, 66);
    private static final Color MUTED  = new Color(107, 114, 132);
    private static final Color SILVER = new Color(200, 210, 225);
    private static final Color META_BG = new Color(238, 242, 249);
    private static final Color META_BD = new Color(214, 220, 229);

    private static final Pattern HEADING = Pattern.compile("^(?:[一二三四五六七八九十百]+、|\\d+[、.\\)]|【.+?】).*");

    public static ReportFile build(String id, String template, String language, String format, List<String> findings) {
        String normalized = normalizeFormat(format);
        Report report = parse(id, template, language, findings);
        return switch (normalized) {
            case "WORD" -> new ReportFile(filename(id, "docx"),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(report));
            case "PPT" -> new ReportFile(filename(id, "pptx"),
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation", pptx(report));
            default -> new ReportFile(filename(id, "pdf"), "application/pdf", pdf(report));
        };
    }

    // ─────────────────────────── 内容模型 ───────────────────────────

    private record Section(String heading, List<String> paragraphs) {}

    private record Report(String title, String reportId, String templateName, String language,
                          String date, String riskLevel, String modelStatus, List<Section> sections) {}

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
            if (line.isEmpty() || line.startsWith("报告编号") || line.startsWith("写作方式")) continue;
            if (line.startsWith("AI状态") || line.startsWith("AI 状态")) {
                modelStatus = line.replaceFirst("^AI\\s*状态[:：]?", "").trim();
                continue;
            }
            if (line.startsWith("SemiRisk") && line.contains("报告")) { title = line; continue; }
            if (riskLevel.isEmpty()) {
                if (line.contains("高危")) riskLevel = "高危";
                else if (line.contains("中危")) riskLevel = "中危";
                else if (line.contains("低危")) riskLevel = "低危";
            }
            if (HEADING.matcher(line).matches()) {
                current = new Section(line, new ArrayList<>());
                sections.add(current);
            } else {
                if (current == null) { current = new Section("一、报告摘要", new ArrayList<>()); sections.add(current); }
                current.paragraphs().add(line);
            }
        }
        if (sections.isEmpty()) {
            Section s = new Section("一、报告摘要", new ArrayList<>());
            s.paragraphs().add("本报告由 SemiRisk 平台基于已采集公开源情报、风险告警与知识库 SOP 生成。");
            sections.add(s);
        }
        return new Report(title, id, templateName,
                language == null || language.isBlank() ? "中文" : language,
                LocalDate.now().toString(),
                riskLevel.isEmpty() ? "待研判" : riskLevel, modelStatus, sections);
    }

    private static String templateName(String template) {
        if (template == null) return "风险评估报告";
        return switch (template) {
            case "supply-chain" -> "供应链分析报告";
            case "enterprise-dd" -> "企业尽调报告";
            default -> "风险评估报告";
        };
    }

    private static String normalizeFormat(String format) {
        if (format == null) return "PDF";
        String v = format.trim().toUpperCase(Locale.ROOT);
        if (v.contains("WORD") || v.contains("DOC")) return "WORD";
        if (v.contains("PPT") || v.contains("POWERPOINT")) return "PPT";
        return "PDF";
    }

    private static String filename(String id, String ext) {
        String safeId = id == null || id.isBlank() ? "semirisk-report" : id.replaceAll("[^A-Za-z0-9_-]", "-");
        return safeId + "-report." + ext;
    }

    // ─────────────────────────── PDF (OpenPDF) ───────────────────────────

    private static byte[] pdf(Report report) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // A4: top margin 150 so body starts below cover band; bottom 60 for footer
            Document doc = new Document(PageSize.A4, 56, 56, 150, 64);
            PdfWriter writer = PdfWriter.getInstance(doc, baos);

            BaseFont bf = cjkFont();
            Font fTitle = new Font(bf, 21, Font.BOLD, Color.WHITE);
            Font fSub   = new Font(bf, 11, Font.NORMAL, new Color(198, 215, 245));
            Font fMeta  = new Font(bf,  9, Font.NORMAL, INK);
            Font fMetaK = new Font(bf,  9, Font.BOLD,   new Color(21, 39, 106));
            Font fH     = new Font(bf, 13, Font.BOLD,   NAVY);
            Font fBody  = new Font(bf, 11, Font.NORMAL, INK);
            Font fFoot  = new Font(bf,  8, Font.NORMAL, MUTED);

            // Page event: cover band on page 1, footer on all pages
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override
                public void onStartPage(PdfWriter w, Document d) {
                    if (w.getPageNumber() != 1) return;
                    PdfContentByte cb = w.getDirectContentUnder();
                    float top = d.getPageSize().getTop();
                    float width = d.getPageSize().getWidth();
                    // Navy cover band
                    cb.setColorFill(NAVY);
                    cb.rectangle(0, top - 132, width, 132);
                    cb.fill();
                    // Accent bottom stripe
                    cb.setColorFill(ACCENT);
                    cb.rectangle(0, top - 138, width, 6);
                    cb.fill();
                    // Title text
                    cb.beginText();
                    cb.setFontAndSize(bf, 21);
                    cb.setColorFill(Color.WHITE);
                    cb.showTextAligned(PdfContentByte.ALIGN_LEFT, report.title(), 56, top - 68, 0);
                    cb.setFontAndSize(bf, 11);
                    cb.setColorFill(new Color(198, 215, 245));
                    cb.showTextAligned(PdfContentByte.ALIGN_LEFT,
                            report.templateName() + "  |  " + report.date(), 56, top - 92, 0);
                    cb.setFontAndSize(bf, 9);
                    cb.setColorFill(new Color(155, 175, 210));
                    cb.showTextAligned(PdfContentByte.ALIGN_LEFT,
                            "SemiRisk  半导体供应链风险智能平台", 56, top - 114, 0);
                    cb.endText();
                }

                @Override
                public void onEndPage(PdfWriter w, Document d) {
                    PdfContentByte cb = w.getDirectContent();
                    float left = d.getPageSize().getLeft() + 56;
                    float right = d.getPageSize().getRight() - 56;
                    float y = d.getPageSize().getBottom() + 36;
                    // Footer line
                    cb.setColorStroke(SILVER);
                    cb.setLineWidth(0.5f);
                    cb.moveTo(left, y + 12);
                    cb.lineTo(right, y + 12);
                    cb.stroke();
                    // Footer text
                    cb.beginText();
                    cb.setFontAndSize(bf, 8);
                    cb.setColorFill(MUTED);
                    cb.showTextAligned(PdfContentByte.ALIGN_LEFT,
                            "SemiRisk  ·  半导体供应链风险智能平台", left, y, 0);
                    cb.showTextAligned(PdfContentByte.ALIGN_RIGHT,
                            "第 " + w.getPageNumber() + " 页", right, y, 0);
                    cb.endText();
                }
            });

            doc.open();

            // ── Meta info table ──
            PdfPTable metaTable = new PdfPTable(4);
            metaTable.setWidthPercentage(100);
            metaTable.setSpacingAfter(14);
            metaTable.setSpacingBefore(6);
            addMetaCell(metaTable, "报告编号", report.reportId(), fMetaK, fMeta);
            addMetaCell(metaTable, "报告类型", report.templateName(), fMetaK, fMeta);
            addMetaCell(metaTable, "生成日期", report.date(), fMetaK, fMeta);
            addMetaCell(metaTable, "综合风险", report.riskLevel(), fMetaK, riskFont(bf, report.riskLevel()));
            doc.add(metaTable);

            // ── Risk level banner ──
            if (!report.riskLevel().equals("待研判")) {
                Color band = riskColor(report.riskLevel());
                PdfPTable bannerTable = new PdfPTable(1);
                bannerTable.setWidthPercentage(100);
                bannerTable.setSpacingAfter(14);
                PdfPCell bannerCell = new PdfPCell(new Phrase("综合风险等级：" + report.riskLevel(), new Font(bf, 12, Font.BOLD, band)));
                bannerCell.setBackgroundColor(new Color(245, 247, 252));
                bannerCell.setBorderColorLeft(band);
                bannerCell.setBorderWidthLeft(4);
                bannerCell.setBorderWidthTop(0);
                bannerCell.setBorderWidthRight(0);
                bannerCell.setBorderWidthBottom(0);
                bannerCell.setPadding(10);
                bannerTable.addCell(bannerCell);
                doc.add(bannerTable);
            }

            // ── Sections ──
            for (Section section : report.sections()) {
                Paragraph h = new Paragraph(section.heading(), fH);
                h.setSpacingBefore(12);
                h.setSpacingAfter(4);
                doc.add(h);

                // Separator line
                PdfPTable hr = new PdfPTable(1);
                hr.setWidthPercentage(100);
                hr.setSpacingAfter(8);
                PdfPCell hrCell = new PdfPCell(new Phrase(""));
                hrCell.setBorderColorTop(ACCENT);
                hrCell.setBorderWidthTop(1f);
                hrCell.setBorderWidthBottom(0);
                hrCell.setBorderWidthLeft(0);
                hrCell.setBorderWidthRight(0);
                hrCell.setFixedHeight(2);
                hr.addCell(hrCell);
                doc.add(hr);

                for (String para : section.paragraphs()) {
                    boolean bullet = para.startsWith("•") || para.startsWith("-") || para.startsWith("·");
                    String text = bullet ? "  • " + para.replaceFirst("^[•\\-·]\\s*", "") : para;
                    Paragraph p = new Paragraph(text, fBody);
                    p.setLeading(18f);
                    p.setSpacingAfter(3);
                    doc.add(p);
                }
            }

            doc.close();
            return baos.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("PDF report generation failed", ex);
        }
    }

    private static BaseFont cjkFont() {
        try {
            return BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            try {
                return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception e2) {
                throw new IllegalStateException("Cannot load PDF font", e2);
            }
        }
    }

    private static void addMetaCell(PdfPTable table, String key, String value, Font kFont, Font vFont) {
        PdfPCell kc = new PdfPCell(new Phrase(key, kFont));
        kc.setBackgroundColor(META_BG);
        kc.setBorderColor(META_BD);
        kc.setPadding(7);
        table.addCell(kc);

        PdfPCell vc = new PdfPCell(new Phrase(value, vFont));
        vc.setBorderColor(META_BD);
        vc.setPadding(7);
        table.addCell(vc);
    }

    private static Color riskColor(String level) {
        if (level == null) return MUTED;
        if (level.contains("高")) return new Color(220, 38, 38);
        if (level.contains("中")) return new Color(202, 138, 4);
        if (level.contains("低")) return new Color(22, 163, 74);
        return MUTED;
    }

    private static Font riskFont(BaseFont bf, String level) {
        return new Font(bf, 9, Font.BOLD, riskColor(level));
    }

    // ─────────────────────────── DOCX ───────────────────────────

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
        b.append(wp(report.title(), 36, "1F2D5B", true, "center"));
        b.append(wp(report.templateName() + "  ·  " + report.date(), 18, "5A6173", false, "center"));
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/>")
                .append("<w:left w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/>")
                .append("<w:right w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/>")
                .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"D6DCE5\"/></w:tblBorders></w:tblPr>");
        b.append(tr("报告编号", report.reportId(), "报告类型", report.templateName()));
        b.append(tr("生成日期", report.date(), "综合风险", report.riskLevel()));
        b.append("</w:tbl>");
        b.append(wp("", 8, "000000", false, "left"));
        for (Section section : report.sections()) {
            b.append(wp(section.heading(), 24, "15276A", true, "left"));
            for (String para : section.paragraphs()) b.append(wp(para, 21, "1B2542", false, "left"));
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>%s<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
                  <w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr></w:body>
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

    // ─────────────────────────── PPTX ───────────────────────────

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
                        <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
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
        paras.append(slidePara(report.templateName() + "  ·  " + report.date(), 1400, "5A6173", false));
        int count = 0;
        for (Section section : report.sections()) {
            if (count++ > 8) break;
            paras.append(slidePara(section.heading(), 1800, "15276A", true));
            int pc = 0;
            for (String para : section.paragraphs()) {
                if (pc++ > 2) break;
                String text = para.length() > 70 ? para.substring(0, 70) + "…" : para;
                paras.append(slidePara("• " + text, 1200, "1B2542", false));
            }
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                    <p:grpSpPr/>
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

    // ─────────────────────────── 工具方法 ───────────────────────────

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

    public record ReportFile(String filename, String contentType, byte[] body) {}
}
