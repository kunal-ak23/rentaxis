package com.datagami.rentaxis.core.email.prefs;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_preferences")
@Getter
@Setter
public class EmailPreferences {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "marketing_enabled", nullable = false)
    private boolean marketingEnabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences_json", nullable = false, columnDefinition = "jsonb")
    private String preferencesJson = "{}";

    @Column(name = "unsubscribe_token", nullable = false, unique = true, length = 64)
    private String unsubscribeToken;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private Instant updatedAt;
}
