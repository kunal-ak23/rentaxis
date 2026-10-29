package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.core.service.PromoterApiKeyService.IssuedKey;
import com.datagami.rentaxis.domain.entity.PromoterApiKey;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Wire shapes of the promoter API-key endpoints. Neither carries the key hash. */
public final class PromoterApiKeyDtos {

    private PromoterApiKeyDtos() {
    }

    public record ApiKeyDTO(UUID id, String name, String keyPrefix, Instant createdAt, Instant lastUsedAt,
                            Instant expiresAt, Instant revokedAt, String revokedReason, boolean revokedByAdmin,
                            String status) {

        public static ApiKeyDTO of(PromoterApiKey k, Clock clock) {
            return new ApiKeyDTO(k.getId(), k.getName(), k.getKeyPrefix(), k.getCreatedAt(), k.getLastUsedAt(),
                    k.getExpiresAt(), k.getRevokedAt(), k.getRevokedReason(),
                    k.getRevokedBy() != null && !k.getRevokedBy().equals(k.getUserId()),
                    k.statusAt(clock.instant()));
        }
    }

    /** The only place a secret ever appears: the response to create / rotate. */
    public record IssuedKeyDTO(ApiKeyDTO key, String secret) {

        public static IssuedKeyDTO of(IssuedKey issued, Clock clock) {
            return new IssuedKeyDTO(ApiKeyDTO.of(issued.key(), clock), issued.secret());
        }
    }

    public record CreateKeyRequest(String name) {
    }

    public record RevokeKeyRequest(String reason) {
    }
}
