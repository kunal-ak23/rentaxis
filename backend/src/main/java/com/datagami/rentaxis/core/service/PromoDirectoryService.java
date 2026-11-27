package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.CategoryAdminDTO;
import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.CategoryRequest;
import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.DirectoryBusinessDTO;
import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.DirectoryCategoryDTO;
import com.datagami.rentaxis.api.dto.PromoDirectoryDtos.DirectoryCategoryDetailDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.PromoDirectoryCategory;
import com.datagami.rentaxis.domain.entity.PromoDirectoryLink;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import com.datagami.rentaxis.domain.repository.PromoDirectoryCategoryRepository;
import com.datagami.rentaxis.domain.repository.PromoDirectoryLinkRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The business directory's categories (spec 2026-11-26-business-directory-design.md).
 * No role logic: RBAC lives in {@code PromoDirectoryAdminController}. Every method
 * runs in a transaction (the tenant filter is off outside one) and also scopes by the
 * caller's tenant explicitly. Another org's category is a 404, never a 403; another
 * org's business in a link set is a 400 {@code UNKNOWN_BUSINESS}, because the database
 * does not tie a link's category and business to one tenant.
 */
@Service
@Transactional
public class PromoDirectoryService {

    public static final String UNKNOWN_BUSINESS = "UNKNOWN_BUSINESS";

    private static final Sort ORDER = Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("createdAt"));

    private final PromoDirectoryCategoryRepository categories;
    private final PromoDirectoryLinkRepository links;
    private final PromoBusinessRepository businesses;
    private final PromotionService promotions;

    /** Uploaded-image routes made absolute for DTOs; optional like in PromotionAdminController. */
    private PromotionImageService images;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setImages(PromotionImageService images) {
        this.images = images;
    }

    public PromoDirectoryService(PromoDirectoryCategoryRepository categories,
                                 PromoDirectoryLinkRepository links,
                                 PromoBusinessRepository businesses,
                                 PromotionService promotions) {
        this.categories = categories;
        this.links = links;
        this.businesses = businesses;
        this.promotions = promotions;
    }

    // ------------------------------------------------------------------ admin

    /** Live categories, or with {@code archived} the archived ones only; by sort order, then age. */
    @Transactional(readOnly = true)
    public Page<CategoryAdminDTO> listCategories(UUID tenantId, boolean archived, Pageable pageable) {
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), ORDER);
        Page<PromoDirectoryCategory> page = archived
                ? categories.findByTenantIdAndArchivedAtIsNotNull(tenantId, ordered)
                : categories.findByTenantIdAndArchivedAtIsNull(tenantId, ordered);
        // One query for the page's link sets, never one per row.
        List<UUID> ids = page.getContent().stream().map(PromoDirectoryCategory::getId).toList();
        Map<UUID, List<UUID>> linked = ids.isEmpty() ? Map.of()
                : links.findByTenantIdAndCategoryIdIn(tenantId, ids).stream()
                        .collect(Collectors.groupingBy(PromoDirectoryLink::getCategoryId,
                                Collectors.mapping(PromoDirectoryLink::getBusinessId, Collectors.toList())));
        return page.map(c -> toDTO(c, linked.getOrDefault(c.getId(), List.of())));
    }

    public CategoryAdminDTO createCategory(UUID tenantId, CategoryRequest req) {
        PromoDirectoryCategory c = new PromoDirectoryCategory();
        c.setTenantId(tenantId);
        Set<UUID> businessIds = requireOwnBusinesses(tenantId, req.businessIds());
        apply(tenantId, c, req);
        PromoDirectoryCategory saved = categories.save(c);
        replaceLinks(tenantId, saved.getId(), businessIds == null ? Set.of() : businessIds);
        return toDTO(saved, linkedBusinessIds(tenantId, saved.getId()));
    }

    /** Refused (400) while archived; {@code businessIds} replaces the link set unless null. */
    public CategoryAdminDTO updateCategory(UUID tenantId, UUID id, CategoryRequest req) {
        PromoDirectoryCategory c = get(tenantId, id);
        if (c.getArchivedAt() != null) {
            throw new BusinessRuleViolationException("This category is archived — restore it first.");
        }
        Set<UUID> businessIds = requireOwnBusinesses(tenantId, req.businessIds());
        apply(tenantId, c, req);
        PromoDirectoryCategory saved = categories.save(c);
        if (businessIds != null) {
            replaceLinks(tenantId, id, businessIds);
        }
        return toDTO(saved, linkedBusinessIds(tenantId, id));
    }

    /** Hidden from the lists and the renter app, links kept; idempotent. */
    public CategoryAdminDTO archiveCategory(UUID tenantId, UUID id) {
        PromoDirectoryCategory c = get(tenantId, id);
        if (c.getArchivedAt() == null) {
            c.setArchivedAt(Instant.now());
        }
        c.setActive(false);
        return toDTO(categories.save(c), linkedBusinessIds(tenantId, id));
    }

    /** Back in the lists, still inactive (an admin re-activates it by editing), like a business. */
    public CategoryAdminDTO restoreCategory(UUID tenantId, UUID id) {
        PromoDirectoryCategory c = get(tenantId, id);
        if (c.getArchivedAt() == null) {
            throw new BusinessRuleViolationException("This category is not archived.");
        }
        c.setArchivedAt(null);
        c.setActive(false);
        return toDTO(categories.save(c), linkedBusinessIds(tenantId, id));
    }

    // ----------------------------------------------------------------- renter

    /**
     * Live categories holding at least one live business, by sort order then age.
     * Not filtered by property: every renter of the org sees the whole directory.
     */
    @Transactional(readOnly = true)
    public List<DirectoryCategoryDTO> directory(UUID tenantId) {
        return categories.findVisibleWithBusinessCounts(tenantId).stream()
                .map(row -> toDirectoryDTO((PromoDirectoryCategory) row[0], ((Number) row[1]).intValue()))
                .toList();
    }

    /** 404 unless the category is this org's, active and not archived; an empty one still opens. */
    @Transactional(readOnly = true)
    public DirectoryCategoryDetailDTO directoryCategory(UUID tenantId, UUID categoryId) {
        PromoDirectoryCategory c = categories.findVisible(tenantId, categoryId)
                .filter(x -> Objects.equals(x.getTenantId(), tenantId))
                .orElseThrow(() -> new NotFoundException("Category not found"));
        List<DirectoryBusinessDTO> list = businesses.findVisibleInDirectoryCategory(tenantId, categoryId).stream()
                .filter(b -> Objects.equals(b.getTenantId(), tenantId))
                .map(this::toDirectoryDTO)
                .toList();
        return new DirectoryCategoryDetailDTO(toDirectoryDTO(c, list.size()), list);
    }

    /** 404 unless the business is this org's, active, not archived and in a live category. */
    @Transactional(readOnly = true)
    public DirectoryBusinessDTO directoryBusiness(UUID tenantId, UUID businessId) {
        return businesses.findVisibleInDirectory(tenantId, businessId)
                .filter(b -> Objects.equals(b.getTenantId(), tenantId))
                .map(this::toDirectoryDTO)
                .orElseThrow(() -> new NotFoundException("Business not found"));
    }

    // ---------------------------------------------------------------- helpers

    private DirectoryCategoryDTO toDirectoryDTO(PromoDirectoryCategory c, int businessCount) {
        return new DirectoryCategoryDTO(c.getId(), c.getNameEn(), c.getNameAr(), image(c.getIconUrl()), businessCount);
    }

    private DirectoryBusinessDTO toDirectoryDTO(PromoBusiness b) {
        return new DirectoryBusinessDTO(b.getId(), b.getNameEn(), b.getNameAr(), image(b.getLogoUrl()),
                b.getDescriptionEn(), b.getDescriptionAr(), b.getWebsiteUrl(),
                b.getPhoneE164(), b.getWhatsappE164());
    }

    private String image(String stored) {
        return images == null ? stored : images.forDto(stored);
    }

    private PromoDirectoryCategory get(UUID tenantId, UUID id) {
        return categories.findByTenantIdAndId(tenantId, id)
                .filter(c -> Objects.equals(c.getTenantId(), tenantId))
                .orElseThrow(() -> new NotFoundException("Category not found"));
    }

    /** Everything that can throw runs before any field is assigned (same rule as PromotionService). */
    private void apply(UUID tenantId, PromoDirectoryCategory c, CategoryRequest req) {
        String nameEn = trimToNull(req.nameEn());
        if (nameEn == null) {
            throw new BusinessRuleViolationException("A category needs a name");
        }
        String iconUrl = promotions.requireHttpsOrNull(tenantId, trimToNull(req.iconUrl()), "iconUrl");
        c.setNameEn(nameEn);
        c.setNameAr(trimToNull(req.nameAr()));
        c.setIconUrl(iconUrl);
        if (req.sortOrder() != null) {
            c.setSortOrder(req.sortOrder());
        }
        if (req.active() != null) {
            c.setActive(req.active());
        }
    }

    /** The distinct requested ids, every one this tenant's business; null when the request has none. */
    private Set<UUID> requireOwnBusinesses(UUID tenantId, List<UUID> requested) {
        if (requested == null) {
            return null;
        }
        Set<UUID> ids = requested.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return ids;
        }
        Set<UUID> own = businesses.findByTenantIdAndIdIn(tenantId, ids).stream()
                .filter(b -> Objects.equals(b.getTenantId(), tenantId))
                .map(PromoBusiness::getId)
                .collect(Collectors.toSet());
        if (!own.containsAll(ids)) {
            throw new BusinessRuleViolationException(
                    "One or more businesses don't exist in this organisation.", UNKNOWN_BUSINESS, null);
        }
        return ids;
    }

    /**
     * Deletes only the links that go and inserts only the new ones: Hibernate flushes
     * inserts before deletes, so delete-all-then-insert would trip the unique
     * (category_id, business_id) constraint on every link that stays.
     */
    private void replaceLinks(UUID tenantId, UUID categoryId, Set<UUID> wanted) {
        List<PromoDirectoryLink> current = links.findByTenantIdAndCategoryId(tenantId, categoryId);
        Set<UUID> kept = current.stream().map(PromoDirectoryLink::getBusinessId).collect(Collectors.toSet());
        List<PromoDirectoryLink> gone = current.stream().filter(l -> !wanted.contains(l.getBusinessId())).toList();
        links.deleteAll(gone);
        links.saveAll(wanted.stream().filter(b -> !kept.contains(b))
                .map(b -> PromoDirectoryLink.of(tenantId, categoryId, b)).toList());
    }

    private List<UUID> linkedBusinessIds(UUID tenantId, UUID categoryId) {
        return links.findByTenantIdAndCategoryId(tenantId, categoryId).stream()
                .map(PromoDirectoryLink::getBusinessId).toList();
    }

    private CategoryAdminDTO toDTO(PromoDirectoryCategory c, List<UUID> businessIds) {
        List<UUID> sorted = businessIds.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        return new CategoryAdminDTO(c.getId(), c.getNameEn(), c.getNameAr(),
                image(c.getIconUrl()),
                c.getSortOrder(), c.isActive(), sorted, sorted.size(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
