package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RentalIndexRange;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RentalIndexRangeRepository extends JpaRepository<RentalIndexRange, UUID> {

    List<RentalIndexRange> findByPropertyIdOrderByIndexYearDescUnitTypeAsc(UUID propertyId);

    Optional<RentalIndexRange> findByPropertyIdAndUnitTypeAndIndexYear(UUID propertyId, UnitType unitType, int indexYear);
}
