package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.UUID;

// STUB — will be replaced with real HMAC JWT impl in Task 13
@Service
public class RenewalTokenService {
    public String sign(UUID opportunityId, RenewalIntent intent, LocalDate leaseEndDate) {
        return "stub-token-" + opportunityId + "-" + intent;
    }
}
