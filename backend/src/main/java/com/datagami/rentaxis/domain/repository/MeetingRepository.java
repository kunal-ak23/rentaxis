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

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.slotStart >= :dayStart AND m.slotStart < :dayEnd " +
           "AND m.status NOT IN :excluded")
    List<Meeting> findActiveByHostAndDay(@Param("hostUserId") UUID hostUserId,
                                         @Param("dayStart") Instant dayStart,
                                         @Param("dayEnd") Instant dayEnd,
                                         @Param("excluded") List<MeetingStatus> excluded);

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.slotStart = :slotStart " +
           "AND m.status NOT IN :excluded")
    List<Meeting> findConflicts(@Param("hostUserId") UUID hostUserId,
                                @Param("slotStart") Instant slotStart,
                                @Param("excluded") List<MeetingStatus> excluded);

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.slotStart >= :rangeStart AND m.slotStart < :rangeEnd " +
           "AND m.status NOT IN :excluded")
    List<Meeting> findByHostAndRange(@Param("hostUserId") UUID hostUserId,
                                     @Param("rangeStart") Instant rangeStart,
                                     @Param("rangeEnd") Instant rangeEnd,
                                     @Param("excluded") List<MeetingStatus> excluded);

    @Query("SELECT m FROM Meeting m WHERE m.slotStart >= :rangeStart AND m.slotStart < :rangeEnd")
    Page<Meeting> findByDateRange(@Param("rangeStart") Instant rangeStart,
                                   @Param("rangeEnd") Instant rangeEnd,
                                   Pageable pageable);
}
