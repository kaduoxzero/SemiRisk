package org.dromara.semirisk.monolith;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class PdfReportService {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public byte[] generate(RiskReport report, List<RiskEvent> events) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 42, 42, 42, 42);
            PdfWriter.getInstance(document, out);
            document.open();

            Font title = new Font(baseFont(), 18, Font.BOLD);
            Font heading = new Font(baseFont(), 12, Font.BOLD);
            Font normal = new Font(baseFont(), 10, Font.NORMAL);

            Paragraph titleParagraph = new Paragraph(report.reportTitle, title);
            titleParagraph.setAlignment(Element.ALIGN_CENTER);
            titleParagraph.setSpacingAfter(18);
            document.add(titleParagraph);

            document.add(paragraph("报告类型：" + report.templateType, normal));
            document.add(paragraph("数据范围：" + report.dateRange, normal));
            document.add(paragraph("生成时间：" + FORMATTER.format(report.createTime), normal));
            document.add(paragraph("数据来源：本地数据库中已爬取/导入的真实风险事件。", normal));
            document.add(section("一、风险概览", heading));
            document.add(paragraph("本报告基于当前本地风险库生成，纳入事件 " + events.size() + " 条。高危事件 "
                + events.stream().filter(item -> "CRITICAL".equals(item.riskLevel)).count()
                + " 条，中危事件 " + events.stream().filter(item -> "WARNING".equals(item.riskLevel)).count()
                + " 条。", normal));

            document.add(section("二、重点风险事件", heading));
            PdfPTable table = new PdfPTable(new float[]{2.2F, 2.2F, 1.1F, 1.1F, 2.4F});
            table.setWidthPercentage(100);
            addCell(table, "事件", heading);
            addCell(table, "主体", heading);
            addCell(table, "等级", heading);
            addCell(table, "风险分", heading);
            addCell(table, "来源", heading);
            events.stream().limit(20).forEach(event -> {
                addCell(table, value(event.eventTitle), normal);
                addCell(table, value(event.enterpriseName), normal);
                addCell(table, value(event.riskLevel), normal);
                addCell(table, score(event.riskScore), normal);
                addCell(table, value(event.sourceName), normal);
            });
            document.add(table);

            document.add(section("三、处置建议", heading));
            document.add(paragraph("1. 对 CRITICAL 事件优先建立处置闭环，保留来源 URL 与处置记录。", normal));
            document.add(paragraph("2. 对 GIS 坐标事件结合地理分布排查物流、供应链与自然灾害影响。", normal));
            document.add(paragraph("3. 对同一企业多次出现的风险事件，进入企业画像页做本地知识库关联分析。", normal));

            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("PDF 报告生成失败", ex);
        }
    }

    private static Paragraph section(String text, Font font) {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setSpacingBefore(14);
        paragraph.setSpacingAfter(8);
        return paragraph;
    }

    private static Paragraph paragraph(String text, Font font) {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setLeading(18);
        paragraph.setSpacingAfter(5);
        return paragraph;
    }

    private static void addCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(6);
        cell.setLeading(14, 0);
        table.addCell(cell);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "--" : value;
    }

    private static String score(BigDecimal value) {
        return value == null ? "--" : value.toPlainString();
    }

    private static BaseFont baseFont() throws Exception {
        String[] candidates = {
            "C:/Windows/Fonts/msyh.ttc,0",
            "C:/Windows/Fonts/simsun.ttc,0",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
        };
        for (String candidate : candidates) {
            try {
                return BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignored) {
            }
        }
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }
}
