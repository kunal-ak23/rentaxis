package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.core.service.report.grid.GroupPager.PageSpan;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupPagerTest {

    @Test
    void packsWholeGroupsUpToThePageSize() {
        assertThat(GroupPager.plan(Collections.nCopies(17, 12), 200)).containsExactly(
                new PageSpan(0, 1, 15, 12), new PageSpan(16, 1, 16, 12));
    }

    @Test
    void movesAGroupThatDoesNotFitToTheNextPage() {
        assertThat(GroupPager.plan(List.of(150, 100), 200)).containsExactly(new PageSpan(0, 1, 0, 150), new PageSpan(1, 1, 1, 100));
        assertThat(GroupPager.plan(List.of(200, 4), 200)).containsExactly(new PageSpan(0, 1, 0, 200), new PageSpan(1, 1, 1, 4));
    }

    @Test
    void splitsAGroupLargerThanAPage() {
        assertThat(GroupPager.plan(List.of(250, 4), 200)).containsExactly(new PageSpan(0, 1, 0, 200), new PageSpan(0, 201, 1, 4));
        assertThat(GroupPager.plan(List.of(12, 4), 5)).containsExactly(
                new PageSpan(0, 1, 0, 5), new PageSpan(0, 6, 0, 10), new PageSpan(0, 11, 0, 12), new PageSpan(1, 1, 1, 4));
        // a large group after a part-filled page starts in the space that is left
        assertThat(GroupPager.plan(List.of(150, 300), 200)).containsExactly(
                new PageSpan(0, 1, 1, 50), new PageSpan(1, 51, 1, 250), new PageSpan(1, 251, 1, 300));
        // ... and after an exactly full page it starts a fresh one
        assertThat(GroupPager.plan(List.of(200, 300), 200)).containsExactly(
                new PageSpan(0, 1, 0, 200), new PageSpan(1, 1, 1, 200), new PageSpan(1, 201, 1, 300));
    }

    @Test
    void anOpeningOnlyGroupTakesOneSlotAndNoGroupsIsOneEmptyPage() {
        assertThat(GroupPager.plan(List.of(0, 0), 200)).containsExactly(new PageSpan(0, 1, 1, 0));
        assertThat(GroupPager.plan(List.of(), 200)).containsExactly(new PageSpan(0, 1, -1, 0));
        assertThat(GroupPager.plan(List.of(2, 0), 2)).containsExactly(new PageSpan(0, 1, 0, 2), new PageSpan(1, 1, 1, 0));
    }

    @Test
    void refusesANonPositivePageOrANegativeCount() {
        assertThatThrownBy(() -> GroupPager.plan(List.of(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GroupPager.plan(List.of(-1), 200)).isInstanceOf(IllegalArgumentException.class);
    }
}
