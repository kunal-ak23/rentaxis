package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    List<DeviceToken> findByUserId(UUID userId);

    void deleteByUserIdAndToken(UUID userId, String token);

    /** Account deletion: stop every push the user's devices were registered for. */
    void deleteByUserId(UUID userId);
}
