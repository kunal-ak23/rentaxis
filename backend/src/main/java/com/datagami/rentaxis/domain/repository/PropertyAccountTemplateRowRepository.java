package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PropertyAccountTemplateRow;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PropertyAccountTemplateRowRepository extends JpaRepository<PropertyAccountTemplateRow, UUID> {
    List<PropertyAccountTemplateRow> findAllByOrderByRoleAsc();
    List<PropertyAccountTemplateRow> findByEnabledTrue();
    Optional<PropertyAccountTemplateRow> findByRole(AccountRole role);
}
