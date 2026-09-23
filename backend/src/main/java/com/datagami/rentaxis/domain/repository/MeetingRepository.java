package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Meeting;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    Page<Meeting> findByHostUserId(UUID hostUserId, Pageable pageable);

    Page<Meeting> findByRequesterUserId(UUID requesterUserId, Pageable pageable);

    Page<Meeting> findByTenantId(UUID tenantId, Pageable pageable);

    /** A property manager's meetings: their own, plus those on their buildings (round 5, audit P1-6). */
    @Query(value = "SELECT m FROM Meeting m LEFT JOIN m.property p LEFT JOIN m.unit u LEFT JOIN u.property up " +
           "LEFT JOIN m.lease l LEFT JOIN l.unit lu LEFT JOIN lu.property lup " +
           "WHERE m.tenantId = :tenantId AND (m.hostUserId = :userId OR m.requesterUserId = :userId " +
           "OR p.id IN :propertyIds OR up.id IN :propertyIds OR lup.id IN :propertyIds)",
           countQuery = "SELECT COUNT(m) FROM Meeting m LEFT JOIN m.property p LEFT JOIN m.unit u LEFT JOIN u.property up " +
           "LEFT JOIN m.lease l LEFT JOIN l.unit lu LEFT JOIN lu.property lup " +
           "WHERE m.tenantId = :tenantId AND (m.hostUserId = :userId OR m.requesterUserId = :userId " +
           "OR p.id IN :propertyIds OR up.id IN :propertyIds OR lup.id IN :propertyIds)")
    Page<Meeting> findScoped(@Param("tenantId") UUID tenantId,
                             @Param("userId") UUID userId,
                             @Param("propertyIds") java.util.Collection<UUID> propertyIds,
                             Pageable pageable);

    @Query(value = "SELECT m FROM Meeting m LEFT JOIN m.property p LEFT JOIN m.unit u LEFT JOIN u.property up " +
           "LEFT JOIN m.lease l LEFT JOIN l.unit lu LEFT JOIN lu.property lup " +
           "WHERE m.tenantId = :tenantId " +
           "AND m.slotStart >= :rangeStart AND m.slotStart < :rangeEnd AND (m.hostUserId = :userId OR m.requesterUserId = :userId " +
           "OR p.id IN :propertyIds OR up.id IN :propertyIds OR lup.id IN :propertyIds)",
           countQuery = "SELECT COUNT(m) FROM Meeting m LEFT JOIN m.property p LEFT JOIN m.unit u LEFT JOIN u.property up " +
           "LEFT JOIN m.lease l LEFT JOIN l.unit lu LEFT JOIN lu.property lup " +
           "WHERE m.tenantId = :tenantId " +
           "AND m.slotStart >= :rangeStart AND m.slotStart < :rangeEnd AND (m.hostUserId = :userId OR m.requesterUserId = :userId " +
           "OR p.id IN :propertyIds OR up.id IN :propertyIds OR lup.id IN :propertyIds)")
    Page<Meeting> findScopedInRange(@Param("tenantId") UUID tenantId,
                                    @Param("userId") UUID userId,
                                    @Param("propertyIds") java.util.Collection<UUID> propertyIds,
                                    @Param("rangeStart") Instant rangeStart,
                                    @Param("rangeEnd") Instant rangeEnd,
                                    Pageable pageable);

    @Query("SELECT m FROM Meeting m WHERE m.tenantId = :tenantId AND m.slotStart >= :rangeStart AND m.slotStart < :rangeEnd")
    Page<Meeting> findByTenantIdAndDateRange(@Param("tenantId") UUID tenantId,
                                              @Param("rangeStart") Instant rangeStart,
                                              @Param("rangeEnd") Instant rangeEnd,
                                              Pageable pageable);

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.tenantId = :tenantId " +
           "AND m.slotStart >= :dayStart AND m.slotStart < :dayEnd " +
           "AND m.status NOT IN :excluded")
    List<Meeting> findActiveByHostAndDay(@Param("hostUserId") UUID hostUserId,
                                         @Param("tenantId") UUID tenantId,
                                         @Param("dayStart") Instant dayStart,
                                         @Param("dayEnd") Instant dayEnd,
                                         @Param("excluded") List<MeetingStatus> excluded);

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.tenantId = :tenantId " +
           "AND m.slotStart = :slotStart " +
           "AND m.status NOT IN :excluded")
    List<Meeting> findConflicts(@Param("hostUserId") UUID hostUserId,
                                @Param("tenantId") UUID tenantId,
                                @Param("slotStart") Instant slotStart,
                                @Param("excluded") List<MeetingStatus> excluded);

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.tenantId = :tenantId " +
           "AND m.slotStart >= :rangeStart AND m.slotStart < :rangeEnd " +
           "AND m.status NOT IN :excluded")
    List<Meeting> findByHostAndRange(@Param("hostUserId") UUID hostUserId,
                                     @Param("tenantId") UUID tenantId,
                                     @Param("rangeStart") Instant rangeStart,
                                     @Param("rangeEnd") Instant rangeEnd,
                                     @Param("excluded") List<MeetingStatus> excluded);

}
