package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailCategory;
import com.datagami.rentaxis.core.email.prefs.EmailPreferences;
import com.datagami.rentaxis.core.email.prefs.EmailPreferencesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailPreferenceServiceTest {

    @Mock EmailPreferencesRepository repo;
    @InjectMocks EmailPreferenceService service;

    @Test
    void transactionalAlwaysAllowedEvenIfMarketingDisabled() {
        UUID user = UUID.randomUUID();

        assertTrue(service.shouldSend(user, EmailCategory.TRANSACTIONAL));
        verifyNoInteractions(repo);
    }

    @Test
    void marketingBlockedWhenDisabled() {
        UUID user = UUID.randomUUID();
        EmailPreferences prefs = new EmailPreferences();
        prefs.setUserId(user);
        prefs.setMarketingEnabled(false);
        when(repo.findById(user)).thenReturn(Optional.of(prefs));

        assertFalse(service.shouldSend(user, EmailCategory.MARKETING));
    }

    @Test
    void missingPrefsRowDefaultsToMarketingOn() {
        UUID user = UUID.randomUUID();
        when(repo.findById(user)).thenReturn(Optional.empty());

        assertTrue(service.shouldSend(user, EmailCategory.MARKETING));
        assertTrue(service.shouldSend(user, EmailCategory.TRANSACTIONAL));
    }
}
