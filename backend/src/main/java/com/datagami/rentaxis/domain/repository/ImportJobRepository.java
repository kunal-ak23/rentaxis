package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ImportJobRepository extends JpaRepository<ImportJob, UUID> {
}
