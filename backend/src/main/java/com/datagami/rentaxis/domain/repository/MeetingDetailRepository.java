package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.MeetingDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MeetingDetailRepository extends JpaRepository<MeetingDetail, UUID> {
    Optional<MeetingDetail> findByMeetingId(UUID meetingId);
}
