package com.datagami.rentaxis.core.email.event;

import com.datagami.rentaxis.core.email.EmailEventType;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class EmailEvent extends ApplicationEvent {

    private final EmailEventType type;
    private final UUID tenantId;
    private final Object payload;
    private final String dedupKey;

    public EmailEvent(Object source, EmailEventType type, UUID tenantId, Object payload, String dedupKey) {
        super(source);
        this.type = type;
        this.tenantId = tenantId;
        this.payload = payload;
        this.dedupKey = dedupKey;
    }

    public EmailEventType getType() { return type; }
    public UUID getTenantId() { return tenantId; }
    public Object getPayload() { return payload; }
    public String getDedupKey() { return dedupKey; }
}
