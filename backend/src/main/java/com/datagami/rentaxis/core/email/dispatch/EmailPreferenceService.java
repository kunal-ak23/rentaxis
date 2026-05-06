package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailCategory;
import com.datagami.rentaxis.core.email.prefs.EmailPreferences;
import com.datagami.rentaxis.core.email.prefs.EmailPreferencesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailPreferenceService {

    private static final SecureRandom RNG = new SecureRandom();
    private final EmailPreferencesRepository repo;

    public boolean shouldSend(UUID userId, EmailCategory category) {
        if (category == EmailCategory.TRANSACTIONAL) return true;
        return repo.findById(userId).map(EmailPreferences::isMarketingEnabled).orElse(true);
    }

    @Transactional
    public EmailPreferences ensureRow(UUID userId) {
        return repo.findById(userId).orElseGet(() -> {
            EmailPreferences p = new EmailPreferences();
            p.setUserId(userId);
            p.setMarketingEnabled(true);
            p.setPreferencesJson("{}");
            p.setUnsubscribeToken(newToken());
            return repo.save(p);
        });
    }

    @Transactional
    public Optional<UUID> disableMarketingByToken(String token) {
        return repo.findByUnsubscribeToken(token).map(p -> {
            p.setMarketingEnabled(false);
            repo.save(p);
            return p.getUserId();
        });
    }

    @Transactional
    public void setMarketing(UUID userId, boolean enabled) {
        EmailPreferences p = ensureRow(userId);
        p.setMarketingEnabled(enabled);
        repo.save(p);
    }

    public String unsubscribeToken(UUID userId) {
        return ensureRow(userId).getUnsubscribeToken();
    }

    private static String newToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
