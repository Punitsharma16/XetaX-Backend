package com.xetax.crm.menu.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * QR codes drawn on this server.
 *
 * <p>Generated here rather than by a third-party QR site so the menu link is
 * never sent anywhere else, and so the image is same-origin and downloadable.
 */
@Service
public class QrCodeService {

    public static final int MIN_SIZE = 128;
    public static final int MAX_SIZE = 1024;

    public byte[] png(String text, int size) {
        int side = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, Map.of(
                    EncodeHintType.MARGIN, 2,
                    // M survives a smudged table sticker without making the code dense.
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not draw the QR code", e);
        }
    }
}
