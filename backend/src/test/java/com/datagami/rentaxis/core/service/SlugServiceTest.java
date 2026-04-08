package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SlugServiceTest {

    private final SlugService service = new SlugService();

    @Test
    void slugify_lowercasesAndReplacesSpecials() {
        assertThat(service.slugify("Hello World!")).isEqualTo("hello-world");
    }

    @Test
    void slugify_collapsesMultiDashes() {
        assertThat(service.slugify("A  --  B")).isEqualTo("a-b");
    }

    @Test
    void slugify_trimsLeadingTrailingDashes() {
        assertThat(service.slugify("---foo---")).isEqualTo("foo");
    }

    @Test
    void slugify_capsAtEightyChars() {
        String longInput = "a".repeat(200);
        assertThat(service.slugify(longInput).length()).isLessThanOrEqualTo(80);
    }

    @Test
    void uniqueSlug_returnsBaseWhenNotTaken() {
        assertThat(service.uniqueSlug("foo", s -> false)).isEqualTo("foo");
    }

    @Test
    void uniqueSlug_incrementsCounterWhenTaken() {
        Set<String> taken = new HashSet<>(Set.of("foo", "foo-2", "foo-3"));
        assertThat(service.uniqueSlug("foo", taken::contains)).isEqualTo("foo-4");
    }
}
