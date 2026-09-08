package com.xetax.crm.invoice;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

/**
 * Clean, professional A4 invoice — pdfbox is already on the classpath (the
 * agents module parses PDFs with it), so no new jar. Standard-14 fonts only:
 * they don't carry the rupee glyph, hence "Rs." everywhere.
 */
@Component
@Slf4j
public class InvoicePdfRenderer {

    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final Color BRAND = new Color(0x4f, 0x46, 0xe5);
    private static final Color INK = new Color(0x1f, 0x24, 0x37);
    private static final Color MUTED = new Color(0x6b, 0x72, 0x80);
    private static final Color LINE = new Color(0xe5, 0xe7, 0xeb);
    private static final Color TINT = new Color(0xf6, 0xf7, 0xfb);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private static final float MARGIN = 48f;

    public byte[] render(Invoice invoice, String companyName) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float width = page.getMediaBox().getWidth();
            float right = width - MARGIN;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;

                // ---- header band ----
                cs.setNonStrokingColor(BRAND);
                cs.addRect(0, y - 26, width, 64);
                cs.fill();
                text(cs, BOLD, 20, Color.WHITE, MARGIN, y, companyName);
                textRight(cs, BOLD, 22, Color.WHITE, right, y, "INVOICE");
                if (notBlank(invoice.getSellerGstin())) {
                    text(cs, REGULAR, 9, new Color(255, 255, 255, 220), MARGIN, y - 15,
                            "GSTIN: " + invoice.getSellerGstin());
                }
                y -= 58;

                // ---- meta ----
                text(cs, BOLD, 10, INK, MARGIN, y, "Bill To");
                textRight(cs, REGULAR, 10, MUTED, right - 110, y, "Invoice No.");
                textRight(cs, BOLD, 10, INK, right, y, invoice.getNumber());
                y -= 14;
                text(cs, REGULAR, 11, INK, MARGIN, y, invoice.getCustomerName());
                textRight(cs, REGULAR, 10, MUTED, right - 110, y, "Issue Date");
                textRight(cs, REGULAR, 10, INK, right, y, invoice.getIssueDate().format(DATE));
                y -= 13;
                float leftY = y;
                if (notBlank(invoice.getCustomerPhone())) {
                    text(cs, REGULAR, 9.5f, MUTED, MARGIN, leftY, invoice.getCustomerPhone());
                    leftY -= 12;
                }
                if (notBlank(invoice.getCustomerEmail())) {
                    text(cs, REGULAR, 9.5f, MUTED, MARGIN, leftY, invoice.getCustomerEmail());
                    leftY -= 12;
                }
                if (notBlank(invoice.getCustomerAddress())) {
                    text(cs, REGULAR, 9.5f, MUTED, MARGIN, leftY, clip(invoice.getCustomerAddress(), 70));
                    leftY -= 12;
                }
                if (notBlank(invoice.getCustomerGstin())) {
                    text(cs, BOLD, 9.5f, MUTED, MARGIN, leftY, "GSTIN: " + invoice.getCustomerGstin());
                    leftY -= 12;
                }
                if (invoice.getDueDate() != null) {
                    textRight(cs, REGULAR, 10, MUTED, right - 110, y, "Due Date");
                    textRight(cs, REGULAR, 10, INK, right, y, invoice.getDueDate().format(DATE));
                    y -= 13;
                }
                textRight(cs, REGULAR, 10, MUTED, right - 110, y, "Status");
                textRight(cs, BOLD, 10, statusColor(invoice.getStatus()), right, y, invoice.getStatus());
                y = Math.min(leftY - 10, y - 30);

                // ---- items table ----
                float colHsn = right - 250, colQty = right - 175, colPrice = right - 95, colAmount = right;
                cs.setNonStrokingColor(TINT);
                cs.addRect(MARGIN - 6, y - 5, width - 2 * MARGIN + 12, 20);
                cs.fill();
                text(cs, BOLD, 9.5f, MUTED, MARGIN, y, "DESCRIPTION");
                textRight(cs, BOLD, 9.5f, MUTED, colHsn, y, "HSN/SAC");
                textRight(cs, BOLD, 9.5f, MUTED, colQty, y, "QTY");
                textRight(cs, BOLD, 9.5f, MUTED, colPrice, y, "RATE");
                textRight(cs, BOLD, 9.5f, MUTED, colAmount, y, "AMOUNT");
                y -= 22;

                for (InvoiceItem item : invoice.getItems()) {
                    text(cs, REGULAR, 10, INK, MARGIN, y, clip(item.getDescription(), 40));
                    textRight(cs, REGULAR, 9.5f, MUTED, colHsn, y,
                            item.getHsn() == null ? "-" : item.getHsn());
                    textRight(cs, REGULAR, 10, INK, colQty, y,
                            trimQty(item.getQuantity())
                                    + (item.getUnit() == null ? "" : " " + item.getUnit()));
                    textRight(cs, REGULAR, 10, INK, colPrice, y, InvoiceService.money(item.getUnitPrice()));
                    textRight(cs, REGULAR, 10, INK, colAmount, y, InvoiceService.money(item.getAmount()));
                    y -= 6;
                    cs.setStrokingColor(LINE);
                    cs.setLineWidth(0.5f);
                    cs.moveTo(MARGIN - 6, y);
                    cs.lineTo(right + 6, y);
                    cs.stroke();
                    y -= 14;
                }

                // ---- totals ----
                y -= 4;
                float labelX = right - 150;
                totalLine(cs, labelX, right, y, "Subtotal", "Rs. " + InvoiceService.money(invoice.getSubtotal()), false);
                y -= 15;
                if (invoice.getTaxAmount() > 0) {
                    if ("INTRA".equals(invoice.getGstMode())) {
                        String half = trimQty(invoice.getTaxPercent() / 2);
                        double halfAmt = invoice.getTaxAmount() / 2;
                        totalLine(cs, labelX, right, y, "CGST (" + half + "%)",
                                "Rs. " + InvoiceService.money(halfAmt), false);
                        y -= 15;
                        totalLine(cs, labelX, right, y, "SGST (" + half + "%)",
                                "Rs. " + InvoiceService.money(invoice.getTaxAmount() - halfAmt), false);
                        y -= 15;
                    } else if ("INTER".equals(invoice.getGstMode())) {
                        totalLine(cs, labelX, right, y, "IGST (" + trimQty(invoice.getTaxPercent()) + "%)",
                                "Rs. " + InvoiceService.money(invoice.getTaxAmount()), false);
                        y -= 15;
                    } else {
                        totalLine(cs, labelX, right, y, "Tax (" + trimQty(invoice.getTaxPercent()) + "%)",
                                "Rs. " + InvoiceService.money(invoice.getTaxAmount()), false);
                        y -= 15;
                    }
                }
                if (invoice.getRoundOff() != 0) {
                    totalLine(cs, labelX, right, y, "Round Off",
                            (invoice.getRoundOff() > 0 ? "+ " : "- ")
                                    + "Rs. " + InvoiceService.money(Math.abs(invoice.getRoundOff())), false);
                    y -= 15;
                }
                if (invoice.getDiscount() > 0) {
                    totalLine(cs, labelX, right, y, "Discount", "- Rs. " + InvoiceService.money(invoice.getDiscount()), false);
                    y -= 15;
                }
                cs.setNonStrokingColor(TINT);
                cs.addRect(labelX - 12, y - 6, right - labelX + 18, 20);
                cs.fill();
                totalLine(cs, labelX, right, y, "Total", "Rs. " + InvoiceService.money(invoice.getTotal()), true);
                y -= 15;
                if (invoice.getAmountPaid() > 0) {
                    totalLine(cs, labelX, right, y, "Paid", "Rs. " + InvoiceService.money(invoice.getAmountPaid()), false);
                    y -= 15;
                    totalLine(cs, labelX, right, y, "Balance Due",
                            "Rs. " + InvoiceService.money(Math.max(0, invoice.getTotal() - invoice.getAmountPaid())), true);
                    y -= 15;
                }

                // ---- amount in words ----
                y -= 16;
                text(cs, BOLD, 9, MUTED, MARGIN, y, "Amount in words");
                y -= 12;
                text(cs, REGULAR, 9.5f, INK, MARGIN, y,
                        clip(InvoiceService.amountInWords(invoice.getTotal()), 95));

                // ---- bank details ----
                if (notBlank(invoice.getBankDetails())) {
                    y -= 18;
                    text(cs, BOLD, 9, MUTED, MARGIN, y, "Bank Details");
                    y -= 12;
                    text(cs, REGULAR, 9.5f, INK, MARGIN, y, clip(invoice.getBankDetails(), 95));
                }

                // ---- notes + footer ----
                if (notBlank(invoice.getNotes())) {
                    y -= 14;
                    text(cs, BOLD, 9.5f, MUTED, MARGIN, y, "Notes");
                    y -= 13;
                    text(cs, REGULAR, 9.5f, INK, MARGIN, y, clip(invoice.getNotes(), 100));
                }
                text(cs, REGULAR, 9, MUTED, MARGIN, MARGIN - 10, "Thank you for your business!");
                textRight(cs, REGULAR, 8, MUTED, right, MARGIN - 10, "Generated by XetaX CRM");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Invoice PDF render failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Could not generate the invoice PDF");
        }
    }

    // ---------------------------------------------------------------- drawing

    private void totalLine(PDPageContentStream cs, float labelX, float right, float y,
                           String label, String value, boolean bold) throws Exception {
        text(cs, bold ? BOLD : REGULAR, bold ? 11 : 10, bold ? INK : MUTED, labelX, y, label);
        textRight(cs, bold ? BOLD : REGULAR, bold ? 11 : 10, INK, right, y, value);
    }

    private void text(PDPageContentStream cs, PDType1Font font, float size, Color color,
                      float x, float y, String value) throws Exception {
        if (value == null) return;
        cs.setNonStrokingColor(color);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitize(value));
        cs.endText();
    }

    private void textRight(PDPageContentStream cs, PDType1Font font, float size, Color color,
                           float rightX, float y, String value) throws Exception {
        if (value == null) return;
        float width = font.getStringWidth(sanitize(value)) / 1000f * size;
        text(cs, font, size, color, rightX - width, y, value);
    }

    /** Standard-14 fonts are WinAnsi — replace anything they cannot draw. */
    private String sanitize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(c == '₹' ? "Rs." : (c >= 32 && c <= 255) ? c : '?');
        }
        return sb.toString();
    }

    private Color statusColor(String status) {
        return switch (status) {
            case "PAID" -> new Color(0x16, 0xa3, 0x4a);
            case "PARTIAL" -> new Color(0xf5, 0x9e, 0x0b);
            case "CANCELLED" -> new Color(0xef, 0x44, 0x44);
            default -> INK;
        };
    }

    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }
    private static String clip(String v, int max) {
        String single = v.replaceAll("\\s+", " ").trim();
        return single.length() > max ? single.substring(0, max - 1) + "…" : single;
    }
    private static String trimQty(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
