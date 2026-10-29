package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PartnerUnitDtos.PartnerUnit;
import com.datagami.rentaxis.api.dto.PartnerUnitDtos.UnitCount;
import com.datagami.rentaxis.api.dto.PartnerUnitDtos.UnitPage;
import com.datagami.rentaxis.core.security.PartnerPrincipal;
import com.datagami.rentaxis.domain.repository.PromoterPropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The promoter developer API's units feed: every unit of the promoter's granted
 * properties. Paging is in memory over the one ordered list, so a page and the
 * fingerprint always describe the same snapshot.
 */
@Service
@RequiredArgsConstructor
public class PartnerUnitFeedService {

    public static final int DEFAULT_SIZE = 100;
    public static final int MAX_SIZE = 500;

    private final UnitRepository units;
    private final PromoterPropertyRepository grants;

    @Transactional(readOnly = true)
    public UnitCount count(PartnerPrincipal p) {
        List<UnitRepository.PartnerFeedRow> rows = units.findPartnerFeedRows(p.tenantId(), p.promoterUserId());
        int properties = (int) grants.countGrantedProperties(p.tenantId(), p.promoterUserId());
        return new UnitCount(properties, rows.size(), fingerprint(rows), Instant.now());
    }

    @Transactional(readOnly = true)
    public UnitPage page(PartnerPrincipal p, Integer page, Integer size, UUID propertyId) {
        List<UnitRepository.PartnerFeedRow> rows = units.findPartnerFeedRows(p.tenantId(), p.promoterUserId());
        String fingerprint = fingerprint(rows);
        int pageNo = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);

        List<UnitRepository.PartnerFeedRow> scope = propertyId == null ? rows
                : rows.stream().filter(r -> propertyId.equals(r.getPropertyId())).toList();
        long total = scope.size();
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        long from = (long) pageNo * pageSize;
        List<PartnerUnit> content = from >= total ? List.of()
                : scope.subList((int) from, (int) Math.min(total, from + pageSize)).stream()
                        .map(r -> new PartnerUnit(r.getUnitId(), r.getUnitNumber(), r.getPropertyId(),
                                r.getPropertyName()))
                        .toList();
        return new UnitPage(content, pageNo, pageSize, total, totalPages, fingerprint);
    }

    /** {@code "sha256:" + hex(SHA-256(rows joined by "\n"))}, each row {@code unitId|unitNumber|propertyId|propertyName}. */
    static String fingerprint(List<UnitRepository.PartnerFeedRow> rows) {
        String joined = rows.stream()
                .map(r -> s(r.getUnitId()) + "|" + s(r.getUnitNumber()) + "|" + s(r.getPropertyId()) + "|"
                        + s(r.getPropertyName()))
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString();
    }
}
