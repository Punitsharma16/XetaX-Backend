package com.xetax.crm.document;

import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {{field_key}} placeholders in a DOCX with record values.
 *
 * Word often splits a placeholder across several "runs", so each paragraph's
 * runs are merged into one before replacing — the paragraph keeps the
 * formatting of its first run. Unknown placeholders become empty strings so
 * a half-filled template never reaches a customer.
 */
@Component
public class DocumentPersonalizer {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    public byte[] personalize(byte[] docxBytes, Map<String, Object> data) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.getParagraphs().forEach(p -> replaceInParagraph(p, data));
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cell.getParagraphs().forEach(p -> replaceInParagraph(p, data));
                    }
                }
            }
            document.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not process the document — is it a valid .docx file?");
        }
    }

    private void replaceInParagraph(XWPFParagraph paragraph, Map<String, Object> data) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        for (XWPFRun run : runs) {
            String t = run.getText(0);
            if (t != null) text.append(t);
        }
        if (text.indexOf("{{") < 0) return;

        Matcher matcher = VAR.matcher(text);
        StringBuilder replaced = new StringBuilder();
        while (matcher.find()) {
            Object value = data.get(matcher.group(1));
            matcher.appendReplacement(replaced,
                    Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(replaced);

        runs.get(0).setText(replaced.toString(), 0);
        for (int i = runs.size() - 1; i >= 1; i--) {
            paragraph.removeRun(i);
        }
    }
}
