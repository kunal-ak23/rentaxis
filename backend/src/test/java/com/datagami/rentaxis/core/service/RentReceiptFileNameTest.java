package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Cheque;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** F14-62: the receipt download is named after the RR receipt number. */
class RentReceiptFileNameTest {

    @Test
    void aNumberedReceiptIsNamedAfterItsNumber() {
        Cheque c = new Cheque();
        c.setId(UUID.fromString("090899f6-0000-4000-8000-000000000001"));
        c.setReceiptNumber("RR-26/7");
        assertThat(RentReceiptService.receiptFileName(c)).isEqualTo("receipt-RR-26-7.pdf");
    }

    @Test
    void aReceiptClearedBeforeTheSeriesKeepsTheOldName() {
        Cheque c = new Cheque();
        c.setId(UUID.fromString("090899f6-0000-4000-8000-000000000001"));
        assertThat(RentReceiptService.receiptFileName(c)).isEqualTo("receipt-090899f6.pdf");
    }
}
