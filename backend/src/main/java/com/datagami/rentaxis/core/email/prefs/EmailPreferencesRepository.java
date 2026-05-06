package com.datagami.rentaxis.core.email.prefs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailPreferencesRepository extends JpaRepository<EmailPreferences, UUID> {
    Optional<EmailPreferences> findByUnsubscribeToken(String token);
}
