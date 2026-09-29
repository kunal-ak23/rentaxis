package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.BoundingBox;
import com.datagami.rentaxis.core.service.cheque.ChequeImageCropper.Crop;
import com.datagami.rentaxis.core.service.cheque.ChequeImageCropper.Refusal;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.datagami.rentaxis.core.service.cheque.ChequeTestImages.COLOURS;
import static com.datagami.rentaxis.core.service.cheque.ChequeTestImages.H;
import static com.datagami.rentaxis.core.service.cheque.ChequeTestImages.RECTS;
import static com.datagami.rentaxis.core.service.cheque.ChequeTestImages.W;
import static com.datagami.rentaxis.core.service.cheque.ChequeTestImages.count;
import static org.assertj.core.api.Assertions.assertThat;

class ChequeImageCropperTest {

    @Test
    void eachCropHoldsItsOwnChequeAndNoneOfTheOthers() {
        BufferedImage img = ChequeTestImages.threeCheques();
        List<Crop> plan = ChequeImageCropper.plan(ChequeTestImages.threeBoxes(), W, H);

        assertThat(plan).hasSize(3).allSatisfy(c -> assertThat(c.ok()).isTrue());
        for (int i = 0; i < 3; i++) {
            BufferedImage crop = ChequeImageCropper.cut(img, plan.get(i));
            int area = RECTS[i][2] * RECTS[i][3];
            assertThat(count(crop, COLOURS[i])).as("crop %d keeps its whole cheque", i).isEqualTo(area);
            for (int j = 0; j < 3; j++) {
                if (j != i) {
                    assertThat(count(crop, COLOURS[j])).as("crop %d holds none of cheque %d", i, j).isZero();
                }
            }
        }
    }

    @Test
    void padsThreePercentOfTheImageOnEachSide() {
        Crop c = ChequeImageCropper.plan(ChequeTestImages.threeBoxes(), W, H).getFirst();
        // red is at (100,100) 400x180; 3% of 1200 = 36, 3% of 900 = 27
        assertThat(c.x()).isEqualTo(64);
        assertThat(c.y()).isEqualTo(73);
        assertThat(c.width()).isEqualTo(400 + 2 * 36);
        assertThat(c.height()).isEqualTo(180 + 2 * 27);
    }

    @Test
    void paddingIsClampedAtTheImageEdges() {
        // A cheque touching the top-left and one touching the bottom-right corner.
        List<BoundingBox> boxes = List.of(
                new BoundingBox(0.0, 0.0, 0.4, 0.3),
                new BoundingBox(0.6, 0.7, 0.4, 0.3));
        List<Crop> plan = ChequeImageCropper.plan(boxes, W, H);

        assertThat(plan.get(0).x()).isZero();
        assertThat(plan.get(0).y()).isZero();
        assertThat(plan.get(1).x() + plan.get(1).width()).isEqualTo(W);
        assertThat(plan.get(1).y() + plan.get(1).height()).isEqualTo(H);
        assertThat(plan).allSatisfy(c -> assertThat(c.ok()).isTrue());
    }

    @Test
    void heavilyOverlappingBoxesAreBothRefusedNotGuessed() {
        List<BoundingBox> boxes = new ArrayList<>(ChequeTestImages.threeBoxes());
        BoundingBox red = boxes.getFirst();
        // A second box sitting mostly on top of the red cheque.
        boxes.set(1, new BoundingBox(red.x() + 0.02, red.y() + 0.02, red.width(), red.height()));

        List<Crop> plan = ChequeImageCropper.plan(boxes, W, H);

        assertThat(plan.get(0).refusal()).isEqualTo(Refusal.OVERLAP);
        assertThat(plan.get(1).refusal()).isEqualTo(Refusal.OVERLAP);
        assertThat(plan.get(2).ok()).isTrue();
        // A refused crop is the whole image.
        assertThat(plan.get(0).width()).isEqualTo(W);
        assertThat(plan.get(0).height()).isEqualTo(H);
    }

    @Test
    void aSlightTouchBetweenNeighboursIsNotAnOverlap() {
        List<BoundingBox> boxes = List.of(
                new BoundingBox(0.0, 0.0, 0.5, 0.4),
                new BoundingBox(0.48, 0.0, 0.5, 0.4));
        assertThat(ChequeImageCropper.plan(boxes, W, H)).allSatisfy(c -> assertThat(c.ok()).isTrue());
    }

    @Test
    void tinyOutsideMissingAndNonsenseBoxesAreRefused() {
        List<BoundingBox> boxes = Arrays.asList(
                new BoundingBox(0.5, 0.5, 0.01, 0.01),   // tiny
                new BoundingBox(1.2, 0.1, 0.3, 0.2),     // entirely outside
                new BoundingBox(0.85, 0.1, 0.3, 0.2),    // half outside
                null,                                    // no box
                new BoundingBox(Double.NaN, 0.1, 0.3, 0.2),
                new BoundingBox(0.1, 0.1, -0.3, 0.2));

        List<Refusal> refusals = ChequeImageCropper.plan(boxes, W, H).stream().map(Crop::refusal).toList();

        assertThat(refusals).containsExactly(Refusal.TINY, Refusal.OUTSIDE, Refusal.OUTSIDE,
                Refusal.NO_BOX, Refusal.INVALID, Refusal.INVALID);
    }

    @Test
    void aBoxSpillingSlightlyPastTheEdgeIsClampedNotRefused() {
        Crop c = ChequeImageCropper.plan(List.of(new BoundingBox(0.62, 0.1, 0.4, 0.3)), W, H).getFirst();
        assertThat(c.ok()).isTrue();
        assertThat(c.x() + c.width()).isEqualTo(W);
    }

    @Test
    void exifRotatedJpegIsDecodedUpright() {
        // Stored sideways, as a phone does: 400 wide, 200 tall, red block top-left.
        BufferedImage stored = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = stored.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 200);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 100, 100);
        g.dispose();

        byte[] jpeg = ChequeTestImages.jpegWithOrientation(stored, 6);
        assertThat(ChequeImageCropper.exifOrientation(jpeg)).isEqualTo(6);

        BufferedImage upright = ChequeImageCropper.decode(jpeg);

        // Orientation 6 = turn 90° clockwise to view: 200 wide, 400 tall, red top-right.
        assertThat(upright.getWidth()).isEqualTo(200);
        assertThat(upright.getHeight()).isEqualTo(400);
        assertThat(ChequeTestImages.near(new Color(upright.getRGB(150, 50)), Color.RED)).isTrue();
        assertThat(ChequeTestImages.near(new Color(upright.getRGB(50, 50)), Color.WHITE)).isTrue();
    }

    @Test
    void everyExifOrientationKeepsThePixelCountAndSwapsSidesFromFive() {
        BufferedImage stored = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        for (int o = 1; o <= 8; o++) {
            BufferedImage out = ChequeImageCropper.orient(stored, o);
            if (o >= 5) {
                assertThat(out.getWidth()).as("orientation %d", o).isEqualTo(20);
                assertThat(out.getHeight()).isEqualTo(40);
            } else {
                assertThat(out.getWidth()).as("orientation %d", o).isEqualTo(40);
            }
        }
    }

    @Test
    void aJpegWithoutExifAndGarbageReadAsUpright() {
        assertThat(ChequeImageCropper.exifOrientation(ChequeImageCropper.toJpeg(ChequeTestImages.threeCheques(), 0.8f)))
                .isEqualTo(1);
        assertThat(ChequeImageCropper.exifOrientation(new byte[]{1, 2, 3})).isEqualTo(1);
        assertThat(ChequeImageCropper.decode(new byte[]{1, 2, 3})).isNull();
    }
}
