package com.datagami.rentaxis.core.email.send;

import com.datagami.rentaxis.core.email.outbox.EmailOutbox;

public interface EmailSender {
    SendResult send(EmailOutbox row);
}
