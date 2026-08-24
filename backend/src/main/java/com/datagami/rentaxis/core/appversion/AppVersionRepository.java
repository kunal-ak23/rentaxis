package com.datagami.rentaxis.core.appversion;

import com.datagami.rentaxis.domain.entity.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lives under {@code core/} alongside the other non-tenant repositories (the
 * email outbox/prefs repos), because {@link AppVersion} is global config, not a
 * tenant-scoped domain aggregate.
 *
 * <p>Lookups match on the canonical (upper-cased) enum names stored in the
 * {@code app}/{@code platform} columns; callers normalise the incoming query
 * parameters before calling.
 */
@Repository
public interface AppVersionRepository extends JpaRepository<AppVersion, UUID> {

    Optional<AppVersion> findByAppAndPlatform(String app, String platform);

    List<AppVersion> findAllByOrderByAppAscPlatformAsc();
}
