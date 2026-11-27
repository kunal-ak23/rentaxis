package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoDirectoryCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromoDirectoryCategoryRepository extends JpaRepository<PromoDirectoryCategory, UUID> {

    Page<PromoDirectoryCategory> findByTenantIdAndArchivedAtIsNull(UUID tenantId, Pageable pageable);

    Page<PromoDirectoryCategory> findByTenantIdAndArchivedAtIsNotNull(UUID tenantId, Pageable pageable);

    Optional<PromoDirectoryCategory> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * The renter's directory: each live category with how many live businesses it
     * holds, categories with none left out, in one query. Every table is scoped by
     * the caller's tenant: links carry no FK tying their category and business to one.
     * Rows are {@code [PromoDirectoryCategory, Long]}.
     */
    @Query("""
            SELECT c, COUNT(b.id) FROM PromoDirectoryCategory c, PromoDirectoryLink l, PromoBusiness b
             WHERE c.tenantId = :tenantId AND l.tenantId = :tenantId AND b.tenantId = :tenantId
               AND l.categoryId = c.id AND b.id = l.businessId
               AND c.active = true AND c.archivedAt IS NULL
               AND b.active = true AND b.archivedAt IS NULL
             GROUP BY c
             ORDER BY c.sortOrder ASC, c.createdAt ASC, c.id ASC
            """)
    List<Object[]> findVisibleWithBusinessCounts(@Param("tenantId") UUID tenantId);

    /** One live category of this tenant, or empty (missing, another org's, archived or inactive). */
    @Query("""
            SELECT c FROM PromoDirectoryCategory c
             WHERE c.tenantId = :tenantId AND c.id = :id
               AND c.active = true AND c.archivedAt IS NULL
            """)
    Optional<PromoDirectoryCategory> findVisible(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
