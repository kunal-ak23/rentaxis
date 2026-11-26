package com.datagami.rentaxis.core.service.rentalindex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Synthetic stand-ins for DLD rental index result PDFs; every value is fictional. */
public final class DldPdfFixtures {

    private DldPdfFixtures() {}

    /** Figures in whole AED; dates dd/MM/yyyy. */
    public record Values(int pct, long increase, long after, long rent, long min, long max, String premise,
                  String building, String category, String endDate, String area, String issuedAt) {
        public static Values valid() {
            return new Values(5, 6000, 126000, 120000, 138000, 162000, "123456789", "Test Tower",
                    "2 bed rooms+hall", "28/02/2027", "Test Area", "25 November 2026, 9:05 AM");
        }
        Values withBuilding(String b) {
            return new Values(pct, increase, after, rent, min, max, premise, b, category, endDate, area, issuedAt);
        }
        Values withEndDate(String d) {
            return new Values(pct, increase, after, rent, min, max, premise, building, category, d, area, issuedAt);
        }
        public Values withIncrease(long inc, long aft) {
            return new Values(pct, inc, aft, rent, min, max, premise, building, category, endDate, area, issuedAt);
        }
    }

    private static String grouped(long v) { return String.format("%,d", v); }

    public static byte[] layoutOctober(Values v) {
        return pdf(List.of(
                "Rental Index Result",
                "Rental Index Type Percentage increase Amount of increase",
                "Residential - Unit " + v.pct() + "% " + grouped(v.increase()) + " AED Annually",
                "Rent amount after increase Building DEWA (Premise No.)",
                grouped(v.after()) + " AED Annually " + v.building() + " " + v.premise(),
                "Property Category Contract End Date Current Annual Rent",
                v.category() + " " + v.endDate() + " " + String.format("%,d.00", v.rent()) + " AED",
                "From " + v.min() + " to " + v.max() + " AED per year",
                v.issuedAt(),
                "Dubai Land Department, Dubai"));
    }

    public static byte[] layoutNovember(Values v) {
        return pdf(List.of(
                "Rental Index Result",
                "Rental Index Type Area Property Type",
                "Residential " + v.area() + " Apartment",
                "Category Percentage Increase Rent amount after increase",
                v.category() + " " + v.pct() + "% " + grouped(v.after()) + " AED Annually",
                "Amount of Increase Contract End Date Current Annual Rent",
                grouped(v.increase()) + " AED Annually " + v.endDate() + " " + grouped(v.rent()) + " AED",
                "DEWA (Premise No.) Building Name",
                v.premise() + " " + v.building(),
                "Rental Index for Same Property Type*",
                "From " + v.min() + " to " + v.max() + " AED per year",
                "Dubai Land Department, Dubai"));
    }

    public static byte[] pdf(List<String> lines) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float y = 760;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (String line : lines) {
                    cs.beginText();
                    cs.setFont(font, 11);
                    cs.newLineAtOffset(50, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 22;
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
