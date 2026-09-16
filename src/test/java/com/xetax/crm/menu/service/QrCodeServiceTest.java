package com.xetax.crm.menu.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A printed QR is useless if a phone cannot read it back — so read it back. */
class QrCodeServiceTest {

    private final QrCodeService service = new QrCodeService();

    private static String decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        RGBLuminanceSource source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }

    @Test
    void theCodeReadsBackAsTheMenuLink() throws Exception {
        String link = "https://app.xetacrm.pro/menu/0123456789abcdef0123456789abcdef";
        assertEquals(link, decode(service.png(link, 512)));
    }

    @Test
    void aTableLinkReadsBackWithItsTable() throws Exception {
        String link = "https://app.xetacrm.pro/menu/abc?table=12";
        assertEquals(link, decode(service.png(link, 256)));
    }

    @Test
    void theSizeStaysWithinBounds() throws Exception {
        BufferedImage tiny = ImageIO.read(new ByteArrayInputStream(service.png("x", 10)));
        BufferedImage huge = ImageIO.read(new ByteArrayInputStream(service.png("x", 99999)));
        assertEquals(QrCodeService.MIN_SIZE, tiny.getWidth());
        assertEquals(QrCodeService.MAX_SIZE, huge.getWidth());
    }
}
