package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.api.dto.report.ReportGridDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds the screen's {@link ReportGridDTO} from the writer's events. MONEY and BALANCE values
 * become decimal strings at cents ({@code "12345.00"}), dates ISO, numbers decimal; blank cells are
 * left out. The page passed in says where this page sits; {@link #end} sets its truncated and
 * continues flags.
 */
public final class JsonGridSink implements ReportSink {

    private static final String PLAIN = RowStyle.PLAIN.name();

    private final ReportGridDTO.Page page;
    private final List<ReportGridDTO.Item> items = new ArrayList<>();
    private ReportGridDTO.Meta meta;
    private List<ReportGridDTO.Column> columns;
    private Map<String, ReportColumn> byKey;
    private String balanceKey;
    private ReportGridDTO result;

    public JsonGridSink(ReportGridDTO.Page page) {
        this.page = Objects.requireNonNull(page, "page");
    }

    /** The finished grid; only after {@link #end}. */
    public ReportGridDTO result() {
        if (result == null) {
            throw new IllegalStateException("The report has not ended yet");
        }
        return result;
    }

    @Override
    public void begin(GridHeader header) {
        meta = new ReportGridDTO.Meta(header.reportKey(), header.title(), header.orgName(), header.metaLines(),
                header.generatedLine(), header.lang());
        List<ReportGridDTO.Column> cols = new ArrayList<>();
        Map<String, ReportColumn> index = new LinkedHashMap<>();
        for (int i = 0; i < header.columns().size(); i++) {
            ReportColumn c = header.columns().get(i);
            cols.add(new ReportGridDTO.Column(c.key(), header.columnLabels().get(i), c.kind().name(), c.hidden(), c.link()));
            index.put(c.key(), c);
            if (c.kind() == Kind.BALANCE) {
                balanceKey = c.key();
            }
        }
        columns = List.copyOf(cols);
        byKey = index;
    }

    @Override
    public void band(int level, Band band, String label, boolean continued) {
        items.add(new ReportGridDTO.Item("band", level, band.name(), label, continued, false, Map.of(), PLAIN, Map.of()));
    }

    @Override
    public void opening(int level, String label, BigDecimal balance, boolean carriedForward) {
        Map<String, String> cells = balanceKey == null ? Map.of() : Map.of(balanceKey, cents(balance));
        items.add(new ReportGridDTO.Item("opening", level, null, label, false, carriedForward, cells, PLAIN, Map.of()));
    }

    @Override
    public void row(int level, Map<String, Object> cells, RowStyle style, Map<String, UUID> refs) {
        Map<String, String> ids = new LinkedHashMap<>();
        refs.forEach((k, v) -> ids.put(k, v.toString()));
        items.add(new ReportGridDTO.Item("row", level, null, null, false, false, encode(cells), style.name(),
                Collections.unmodifiableMap(ids)));
    }

    @Override
    public void subtotal(int level, String label, Map<String, Object> totals) {
        items.add(new ReportGridDTO.Item("subtotal", level, null, label, false, false, encode(totals), PLAIN, Map.of()));
    }

    @Override
    public void total(String label, Map<String, Object> totals) {
        items.add(new ReportGridDTO.Item("total", 0, null, label, false, false, encode(totals), PLAIN, Map.of()));
    }

    @Override
    public void end(boolean truncated, boolean continuesOnNextPage) {
        result = new ReportGridDTO(meta, columns, List.copyOf(items), page.withEnd(truncated, continuesOnNextPage));
    }

    private Map<String, String> encode(Map<String, Object> cells) {
        Map<String, String> out = new LinkedHashMap<>();
        cells.forEach((k, v) -> {
            if (v != null) {
                out.put(k, encode(byKey.get(k), v));
            }
        });
        return Collections.unmodifiableMap(out);
    }

    private static String encode(ReportColumn column, Object v) {
        if (v instanceof BigDecimal b) {
            boolean money = column != null && (column.kind() == Kind.MONEY || column.kind() == Kind.BALANCE);
            return money ? cents(b) : b.toPlainString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        return v.toString();
    }

    private static String cents(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
