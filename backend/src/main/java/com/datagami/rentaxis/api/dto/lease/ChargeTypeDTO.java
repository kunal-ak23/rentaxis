package com.datagami.rentaxis.api.dto.lease;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;

import java.util.UUID;

/**
 * Wire shape of a charge type, used for both directions.
 *
 * <p>{@code id} is ignored on create and on update (the path variable wins), so a
 * body carrying one cannot redirect the write at another row.</p>
 */
public record ChargeTypeDTO(UUID id, String code, String nameEn, String nameAr, AccountRole role,
                            ChargeBehaviour behaviour, boolean vatApplicableDefault, boolean active,
                            int displayOrder) {
}
