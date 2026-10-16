package com.datagami.rentaxis.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The name a ticket attachment keeps (S18-03 follow-up): a long name keeps exactly its own extension. */
class TicketUploadedFileNameTest {

    @Test
    void aLongNameWithQueryOrHashCharactersKeepsItsOneExtension() {
        String name = "quote?#" + "x".repeat(300) + ".pdf";

        String kept = MaintenanceTicketService.uploadedFileName(name);

        assertThat(kept).hasSize(255).endsWith(".pdf").doesNotEndWith(".pdf.pdf");
    }

    @Test
    void aShortNameIsKeptAsTyped() {
        assertThat(MaintenanceTicketService.uploadedFileName("C:\\Users\\a\\quote*2026?.pdf"))
                .isEqualTo("quote*2026?.pdf");
    }
}
