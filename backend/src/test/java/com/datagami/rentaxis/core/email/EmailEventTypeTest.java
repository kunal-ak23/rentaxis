package com.datagami.rentaxis.core.email;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmailEventTypeTest {

    @Test
    void chequeBouncedIsTransactional() {
        assertEquals(EmailCategory.TRANSACTIONAL, EmailEventType.CHEQUE_BOUNCED.category());
    }

    @Test
    void chequeBouncedRecipientsIncludeRenterAndManager() {
        assertTrue(EmailEventType.CHEQUE_BOUNCED.recipientRoles().contains(RecipientRole.RENTER));
        assertTrue(EmailEventType.CHEQUE_BOUNCED.recipientRoles().contains(RecipientRole.PROPERTY_MANAGER));
    }

    @Test
    void rentReceiptHasPdfAttachmentPolicy() {
        assertEquals(EmailEventType.AttachmentPolicy.PDF,
                EmailEventType.RENT_RECEIPT_AVAILABLE.attachmentPolicy());
    }

    @Test
    void leaseContractHasSignedUrlAttachmentPolicy() {
        assertEquals(EmailEventType.AttachmentPolicy.SIGNED_URL,
                EmailEventType.LEASE_CONTRACT_GENERATED.attachmentPolicy());
    }

    @Test
    void snakeReturnsLowercaseSnakeCase() {
        assertEquals("cheque_bounced", EmailEventType.CHEQUE_BOUNCED.snake());
    }
}
