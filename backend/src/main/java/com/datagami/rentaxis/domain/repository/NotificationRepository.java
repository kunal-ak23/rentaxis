package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndIsReadFalse(UUID userId);

    List<Notification> findByUserIdAndIsReadFalse(UUID userId);

    // Unfiltered queries (bypass tenant filter) for notifications that may have null tenant_id
    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM notifications WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Notification> findAllByUserIdUnfiltered(@org.springframework.data.repository.query.Param("userId") UUID userId);

    @org.springframework.data.jpa.repository.Query(value = "SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND is_read = false", nativeQuery = true)
    long countUnreadByUserIdUnfiltered(@org.springframework.data.repository.query.Param("userId") UUID userId);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM notifications WHERE user_id = :userId AND is_read = false", nativeQuery = true)
    List<Notification> findUnreadByUserIdUnfiltered(@org.springframework.data.repository.query.Param("userId") UUID userId);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM notifications WHERE id = :notificationId AND user_id = :userId", nativeQuery = true)
    Optional<Notification> findByIdAndUserIdUnfiltered(
            @org.springframework.data.repository.query.Param("notificationId") UUID notificationId,
            @org.springframework.data.repository.query.Param("userId") UUID userId);

    /**
     * Account deletion: notifications are addressed to the user, so they are
     * personal data. Native + unfiltered for the same reason the reads above
     * are — the user_id is the ownership key, not the tenant filter.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "DELETE FROM notifications WHERE user_id = :userId", nativeQuery = true)
    void deleteByUserIdUnfiltered(@org.springframework.data.repository.query.Param("userId") UUID userId);
}
