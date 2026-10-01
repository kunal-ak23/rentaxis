package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AzureOpenAIChequeExtractorMultiTest {

    private final AzureOpenAIChequeExtractor extractor =
            new AzureOpenAIChequeExtractor(mock(OpenAIClient.class), new AzureOpenAIConfig());

    private static String cheque(String number, String date, String amount, String box) {
        return """
                {"chequeNumber":"%s","bankName":"Emirates NBD","payerName":"A Renter","chequeDate":"%s",
                 "amount":%s,"confidence":"HIGH","warnings":[],"box":%s}
                """.formatted(number, date, amount, box);
    }

    @Test
    void parsesThreeChequesWithTheirBoxesInOrder() {
        String json = "{\"cheques\":["
                + cheque("000101", "2026-03-01", "12750", "{\"x\":0.05,\"y\":0.1,\"width\":0.4,\"height\":0.2}") + ","
                + cheque("000102", "2026-06-01", "12750", "{\"x\":0.05,\"y\":0.6,\"width\":0.4,\"height\":0.2}") + ","
                + cheque("000103", "2026-09-01", "12750.50", "{\"x\":0.55,\"y\":0.35,\"width\":0.4,\"height\":0.2}")
                + "],\"warnings\":[\"glare on the right\"]}";

        var r = extractor.parseMultiResponse(json);

        assertThat(r.cheques()).hasSize(3);
        assertThat(r.cheques()).extracting(c -> c.extracted().chequeNumber())
                .containsExactly("000101", "000102", "000103");
        assertThat(r.cheques().get(1).extracted().chequeDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(r.cheques().get(2).extracted().amount()).isEqualByComparingTo("12750.50");
        assertThat(r.cheques().get(2).box()).isEqualTo(new ChequeExtractor.BoundingBox(0.55, 0.35, 0.4, 0.2));
        assertThat(r.warnings()).containsExactly("glare on the right");
    }

    @Test
    void oneChequeIsAListOfOne() {
        String json = "{\"cheques\":[" + cheque("7", "2026-03-01", "5000",
                "{\"x\":0,\"y\":0,\"width\":1,\"height\":1}") + "],\"warnings\":[]}";

        var r = extractor.parseMultiResponse(json);

        assertThat(r.cheques()).hasSize(1);
        assertThat(r.cheques().getFirst().extracted().confidence()).isEqualTo(ExtractedChequeDTO.Confidence.HIGH);
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void aMalformedBoxBecomesNoBoxButTheChequeIsKept() {
        String json = "{\"cheques\":["
                + cheque("1", "2026-03-01", "1", "{\"x\":\"left\",\"y\":0,\"width\":1,\"height\":1}") + ","
                + cheque("2", "2026-03-01", "1", "null") + ","
                + cheque("3", "2026-03-01", "1", "{\"x\":0,\"y\":0,\"width\":1}")
                + "],\"warnings\":[]}";

        var r = extractor.parseMultiResponse(json);

        assertThat(r.cheques()).hasSize(3).allSatisfy(c -> assertThat(c.box()).isNull());
        assertThat(r.cheques()).allSatisfy(c -> assertThat(c.extracted()).isNotNull());
    }

    @Test
    void overlappingBoxesAreReturnedAsReportedForTheCropperToJudge() {
        String box = "{\"x\":0.1,\"y\":0.1,\"width\":0.5,\"height\":0.3}";
        var r = extractor.parseMultiResponse("{\"cheques\":[" + cheque("1", "2026-03-01", "1", box) + ","
                + cheque("2", "2026-04-01", "1", box) + "],\"warnings\":[]}");

        assertThat(r.cheques()).hasSize(2);
        var plan = ChequeImageCropper.plan(r.cheques().stream().map(ChequeExtractor.DetectedCheque::box).toList(),
                1000, 1000);
        assertThat(plan).allSatisfy(c -> assertThat(c.refusal()).isEqualTo(ChequeImageCropper.Refusal.OVERLAP));
    }

    @Test
    void malformedJsonOrAMissingListIsNoChequesAndAWarning() {
        for (String bad : new String[]{"not json", "{\"warnings\":[]}", "[]", "{\"cheques\":{}}"}) {
            var r = extractor.parseMultiResponse(bad);
            assertThat(r.cheques()).as(bad).isEmpty();
            assertThat(r.warnings()).as(bad).containsExactly("Extraction failed: malformed model response");
        }
    }

    @Test
    void theMultiSchemaIsStrictAndWrapsTheSingleChequeFieldSetPlusABox() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode multi = m.readTree(AzureOpenAIChequeExtractor.MULTI_SCHEMA);
        assertThat(multi.get("additionalProperties").asBoolean()).isFalse();
        JsonNode item = multi.at("/properties/cheques/items");
        assertThat(item.get("additionalProperties").asBoolean()).isFalse();
        assertThat(item.at("/properties/box/additionalProperties").asBoolean()).isFalse();

        // Every single-cheque field is there, and required, plus the box.
        JsonNode single = m.readTree(extractorSchema());
        single.get("properties").fieldNames().forEachRemaining(f ->
                assertThat(item.get("properties").has(f)).as(f).isTrue());
        assertThat(item.get("required").toString()).contains("\"box\"");
        for (JsonNode req : single.get("required")) {
            assertThat(item.get("required").toString()).contains("\"" + req.asText() + "\"");
        }
    }

    @Test
    void extractAllCallsTheModelOnceAndFallsBackSoftly() {
        var stub = new AzureOpenAIChequeExtractor(mock(OpenAIClient.class), new AzureOpenAIConfig()) {
            @Override
            protected String fetchMultiContent(byte[] imageBytes, String contentType) {
                return "{\"cheques\":[" + cheque("1", "2026-03-01", "1",
                        "{\"x\":0,\"y\":0,\"width\":1,\"height\":1}") + "],\"warnings\":[]}";
            }
        };
        assertThat(stub.extractAll(new byte[]{1}, "image/jpeg").cheques()).hasSize(1);

        var fallback = extractor.extractAllFallback(new byte[]{1}, "image/jpeg", new IllegalStateException("x"));
        assertThat(fallback.cheques()).isEmpty();
        assertThat(fallback.warnings()).containsExactly("Extraction failed: IllegalStateException");
    }

    @Test
    void theDefaultExtractAllWrapsASingleChequeExtractor() {
        ChequeExtractor single = (b, t) -> new ChequeExtractor.ExtractionResult(
                new ExtractedChequeDTO("9", null, null, null, null, ExtractedChequeDTO.Confidence.LOW), java.util.List.of("w"));
        var r = single.extractAll(new byte[]{1}, "image/png");
        assertThat(r.cheques()).hasSize(1);
        assertThat(r.cheques().getFirst().box()).isNull();

        var none = new UnavailableChequeExtractor().extractAll(new byte[]{1}, "image/png");
        assertThat(none.cheques()).isEmpty();
        assertThat(none.warnings()).isNotEmpty();
    }

    private static String extractorSchema() throws Exception {
        var f = AzureOpenAIChequeExtractor.class.getDeclaredField("SCHEMA");
        f.setAccessible(true);
        return (String) f.get(null);
    }
}
