package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandlordOrgRepository extends JpaRepository<LandlordOrg, UUID> {

    Optional<LandlordOrg> findBySlug(String slug);

    /**
     * Review of R4-B I4: whether an organisation already has this name, compared as
     * {@link LandlordOrg#normalisedName} compares it — trimmed, inner whitespace runs as
     * one space, lower case.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM landlord_org"
            + " WHERE lower(btrim(regexp_replace(name, '\\s+', ' ', 'g'))) = :normalised)", nativeQuery = true)
    boolean existsByNormalisedName(@Param("normalised") String normalised);

    /** The slugs {@code base} and {@code base-N} already in use (review of R4-B I4). */
    @Query(value = "SELECT slug FROM landlord_org WHERE slug = :base OR slug LIKE (:base || '-%')", nativeQuery = true)
    java.util.List<String> findSlugsStartingWith(@Param("base") String base);

    /**
     * Review r3B I1: the organisation row, locked for the rest of the transaction, so
     * an edit or a status change reads, checks and writes it with no other writer in
     * between — a merge of an entity read before a concurrent deactivation wrote the
     * old status back.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from LandlordOrg o where o.id = :id")
    Optional<LandlordOrg> findByIdForUpdate(@Param("id") UUID id);

    /** The organisation's status alone, for the per-request bearer-token check. */
    @Query(value = "SELECT status FROM landlord_org WHERE id = :id", nativeQuery = true)
    Optional<String> findStatusById(@Param("id") UUID id);
}
