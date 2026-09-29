package com.datagami.rentaxis.core.service.cheque;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChequePdfRendererTest {

    @Test
    void rendersEveryPageToAnUprightImageInPageOrder() throws Exception {
        byte[] pdf = ChequeTestImages.pdfOf(ChequeTestImages.oneCheque(Color.RED), ChequeTestImages.threeCheques());

        List<byte[]> pages = ChequePdfRenderer.renderToJpeg(pdf, 10);

        assertThat(pages).hasSize(2);
        BufferedImage first = ChequeImageCropper.decode(pages.get(0));
        BufferedImage second = ChequeImageCropper.decode(pages.get(1));
        // Page 1 is the landscape red cheque, page 2 the three-cheque sheet.
        assertThat(first.getWidth()).isGreaterThan(first.getHeight());
        assertThat(ChequeTestImages.count(first, Color.RED)).isPositive();
        assertThat(ChequeTestImages.count(second, Color.BLUE)).isPositive();
        assertThat(ChequeTestImages.count(second, Color.GREEN)).isPositive();
    }

    @Test
    void tooManyPagesIsRefusedBeforeRendering() {
        BufferedImage p = ChequeTestImages.oneCheque(Color.RED);
        byte[] pdf = ChequeTestImages.pdfOf(p, p, p);

        assertThatThrownBy(() -> ChequePdfRenderer.renderToJpeg(pdf, 2))
                .isInstanceOf(ChequePdfRenderer.PdfRefusedException.class)
                .satisfies(e -> assertThat(((ChequePdfRenderer.PdfRefusedException) e).code())
                        .isEqualTo(ChequeUploadRefusedException.TOO_MANY_PAGES));
    }

    @Test
    void notAPdfIsUnreadable() {
        assertThatThrownBy(() -> ChequePdfRenderer.renderToJpeg("hello".getBytes(), 10))
                .isInstanceOf(ChequePdfRenderer.PdfRefusedException.class)
                .satisfies(e -> assertThat(((ChequePdfRenderer.PdfRefusedException) e).code())
                        .isEqualTo(ChequeUploadRefusedException.PDF_UNREADABLE));
    }

    @Test
    void aHugePageIsRenderedNoLargerThanTheCap() throws Exception {
        BufferedImage big = new BufferedImage(4000, 200, BufferedImage.TYPE_INT_RGB);
        // pdfOf halves the pixel size into points: 2000pt wide, which at 200 DPI would be ~5555px.
        byte[] pdf = ChequeTestImages.pdfOf(big);

        BufferedImage page = ChequeImageCropper.decode(ChequePdfRenderer.renderToJpeg(pdf, 10).getFirst());

        assertThat(Math.max(page.getWidth(), page.getHeight())).isLessThanOrEqualTo(ChequePdfRenderer.MAX_SIDE_PX);
    }
}
