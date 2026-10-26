package com.datagami.rentaxis.api.dto.report;

import java.util.List;
import java.util.Map;

/**
 * One page of a grouped report as the screen receives it (built by {@code JsonGridSink}).
 *
 * <p>Amounts are decimal strings ({@code "12345.00"}, {@code "-8000.00"}; balances are debit-positive),
 * dates ISO ({@code "2026-09-16"}), integers decimal; a blank cell is absent from {@code cells}.
 * The client renders the cells as given and never adds them up.
 */
public record ReportGridDTO(Meta meta, List<Column> columns, List<Item> items, Page page) {

    /** The title band: report key, title, organisation, period/filter lines, the "Generated … by …" line, and the language. */
    public record Meta(String reportKey, String title, String orgName, List<String> lines, String generated, String lang) {}

    /** A column: {@code kind} is a {@code Kind} name; {@code link} is {@code journal}, {@code lease}, {@code cheque} or {@code null}. */
    public record Column(String key, String label, String kind, boolean hidden, String link) {}

    /**
     * One printed line, in order.
     *
     * @param type           {@code band}, {@code opening}, {@code row}, {@code subtotal} or {@code total}
     * @param level          the group depth (1 = outermost; 0 for the total and anything outside a group)
     * @param band           {@code ACCOUNT}, {@code PARTY} or {@code GROUP} on a band, else {@code null}
     * @param label          the band, opening, subtotal or total label; {@code null} on a row
     * @param continued      a band of a group that began on an earlier page
     * @param carriedForward an opening that carries the last balance of the earlier page
     * @param cells          column key → value (opening: the balance column only); never {@code null}
     * @param style          {@code PLAIN} or {@code HIGHLIGHT} ({@code PLAIN} on anything but a row)
     * @param refs           drill-down ids on a row ({@code entryId}, {@code leaseId}, {@code chequeId}); never {@code null}
     */
    public record Item(String type, int level, String band, String label, boolean continued, boolean carriedForward,
                       Map<String, String> cells, String style, Map<String, String> refs) {}

    /**
     * Where this page sits: {@code page} is 0-based of {@code pageCount}; {@code pageRows} the page size (0 when unpaged);
     * {@code totalRows}/{@code totalGroups} cover the whole report; {@code firstGroup}..{@code lastGroup} are the
     * 0-based groups on this page ({@code lastGroup} −1 when there are none); {@code truncated} when rows were
     * dropped at the row cap; {@code continuesOnNextPage} when the page stops inside a group.
     */
    public record Page(int page, int pageCount, int pageRows, long totalRows, int totalGroups, int firstGroup, int lastGroup,
                       boolean truncated, boolean continuesOnNextPage) {

        /** The whole report on one unpaged page. */
        public static Page whole(long totalRows, int totalGroups) {
            return new Page(0, 1, 0, totalRows, totalGroups, 0, totalGroups - 1, false, false);
        }

        /** This page with the writer's end flags. */
        public Page withEnd(boolean truncated, boolean continuesOnNextPage) {
            return new Page(page, pageCount, pageRows, totalRows, totalGroups, firstGroup, lastGroup, truncated,
                    continuesOnNextPage);
        }
    }
}
