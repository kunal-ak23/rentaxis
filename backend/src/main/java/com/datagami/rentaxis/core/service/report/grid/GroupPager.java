package com.datagami.rentaxis.core.service.report.grid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits a grouped report into screen pages of at most {@code pageRows} rows.
 *
 * <p>Greedy: whole groups are packed onto a page while they fit; a group that does not fit moves to
 * a fresh page; only a group longer than a page (or one that follows a part-filled page and is
 * longer than a page) is split, and the next page continues it. A group with no rows still takes
 * one slot, for its opening row.
 */
public final class GroupPager {

    /**
     * One page: from row {@code firstRow} of group {@code firstGroup} to row {@code lastRow} of group
     * {@code lastGroup}. Groups are 0-based indexes, rows 1-based and inclusive. A group without rows
     * reports {@code lastRow = 0}; a report without groups is the one page {@code (0, 1, -1, 0)}.
     */
    public record PageSpan(int firstGroup, int firstRow, int lastGroup, int lastRow) {
    }

    private GroupPager() {
    }

    /** The pages for groups with these row counts. */
    public static List<PageSpan> plan(List<Integer> groupRowCounts, int pageRows) {
        if (pageRows < 1) {
            throw new IllegalArgumentException("A page holds at least one row, not " + pageRows);
        }
        Objects.requireNonNull(groupRowCounts, "groupRowCounts");
        if (groupRowCounts.isEmpty()) {
            return List.of(new PageSpan(0, 1, -1, 0));
        }
        Plan plan = new Plan();
        int used = 0;
        for (int g = 0; g < groupRowCounts.size(); g++) {
            Integer count = groupRowCounts.get(g);
            if (count == null || count < 0) {
                throw new IllegalArgumentException("Group " + g + " has an invalid row count " + count);
            }
            int start = 1;
            int left = Math.max(count, 1);
            while (left > 0) {
                if (used == pageRows) {
                    // an exactly full page: the plan's pseudo-code would add an empty range here
                    plan.close();
                    used = 0;
                }
                int space = pageRows - used;
                if (left <= space) {
                    plan.add(g, start, start + left - 1, count);
                    used += left;
                    left = 0;
                } else if (used > 0 && left <= pageRows) {
                    plan.close();
                    used = 0;
                } else {
                    plan.add(g, start, start + space - 1, count);
                    start += space;
                    left -= space;
                    plan.close();
                    used = 0;
                }
            }
        }
        plan.close();
        return List.copyOf(plan.pages);
    }

    /** The pages built so far and the one being filled. */
    private static final class Plan {
        private final List<PageSpan> pages = new ArrayList<>();
        private boolean open;
        private int firstGroup;
        private int firstRow;
        private int lastGroup;
        private int lastRow;

        void add(int group, int from, int to, int rowCount) {
            if (!open) {
                open = true;
                firstGroup = group;
                firstRow = from;
            }
            lastGroup = group;
            lastRow = Math.min(to, rowCount);
        }

        void close() {
            if (open) {
                pages.add(new PageSpan(firstGroup, firstRow, lastGroup, lastRow));
                open = false;
            }
        }
    }
}
