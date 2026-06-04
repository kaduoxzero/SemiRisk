package com.semirisk.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ReportFileFactory {

    private ReportFileFactory() {
    }

    public static ReportFile build(String id, String template, String language, String format, List<String> findings) {
        String normalized = normalizeFormat(format);
        List<String> lines = reportLines(id, template, language, findings);
        return switch (normalized) {
            case "WORD" -> new ReportFile(filename(id, "docx"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(lines));
            case "PPT" -> new ReportFile(filename(id, "pptx"), "application/vnd.openxmlformats-officedocument.presentationml.presentation", pptx(lines));
            default -> new ReportFile(filename(id, "pdf"), "application/pdf", pdf(lines));
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

    private static List<String> reportLines(String id, String template, String language, List<String> findings) {
        List<String> lines = new ArrayList<>();
        lines.add("SemiRisk AI 风险报告");
        lines.add("报告编号：" + id);
        lines.add("模板：" + template + " / 语言：" + language);
        lines.add("结论：报告内容由已采集公开源、风险告警和本地规则引擎生成。");
        lines.add("建议：核验高分公开源、同步供应商确认结果，并按 SOP 推进闭环处置。");
        if (findings != null && !findings.isEmpty()) {
            lines.add("公开源信号：");
            findings.stream().limit(8).forEach(item -> lines.add(" - " + item));
        }
        return lines;
    }

    private static byte[] pdf(List<String> lines) {
        StringBuilder stream = new StringBuilder();
        stream.append("BT\n/F1 18 Tf\n72 770 Td\n").append(hexText(lines.get(0))).append(" Tj\n");
        stream.append("/F1 11 Tf\n");
        for (int i = 1; i < lines.size(); i++) {
            stream.append("0 -24 Td\n").append(hexText(lines.get(i))).append(" Tj\n");
        }
        stream.append("ET\n");
        byte[] content = stream.toString().getBytes(StandardCharsets.ISO_8859_1);
        List<byte[]> objects = List.of(
                ascii("<< /Type /Catalog /Pages 2 0 R >>"),
                ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"),
                concat(ascii("<< /Length " + content.length + " >>\nstream\n"), content, ascii("endstream")),
                ascii("<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H /DescendantFonts [6 0 R] >>"),
                ascii("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 2 >> >>")
        );
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
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

    private static String hexText(String value) {
        return "<" + HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_16BE)).toUpperCase(Locale.ROOT) + ">";
    }

    private static byte[] docx(List<String> lines) {
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
                zip(zip, "word/document.xml", wordDocument(lines));
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Word report generation failed", ex);
        }
    }

    private static String wordDocument(List<String> lines) {
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            body.append("<w:p><w:r><w:t>").append(xml(line)).append("</w:t></w:r></w:p>");
        }
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>%s<w:sectPr/></w:body>
                </w:document>
                """.formatted(body);
    }

    private static byte[] pptx(List<String> lines) {
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
                zip(zip, "ppt/slides/slide1.xml", slide(lines));
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("PPT report generation failed", ex);
        }
    }

    private static String slide(List<String> lines) {
        String body = String.join("\n", lines.stream().map(ReportFileFactory::xml).toList());
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
                  <p:cSld><p:spTree>
                    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
                    <p:sp><p:nvSpPr><p:cNvPr id="2" name="SemiRisk Report"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                      <p:spPr><a:xfrm><a:off x="685800" y="457200"/><a:ext cx="10668000" cy="5943600"/></a:xfrm></p:spPr>
                      <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="2200"/><a:t>%s</a:t></a:r></a:p></p:txBody>
                    </p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
                """.formatted(body);
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
