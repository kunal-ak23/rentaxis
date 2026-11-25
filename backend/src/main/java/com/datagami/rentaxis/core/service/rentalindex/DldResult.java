package com.datagami.rentaxis.core.service.rentalindex;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What a DLD rental index result PDF states; {@code area} and {@code dldPropertyType} exist only in the newer layout. */
public record DldResult(int dldPercent, BigDecimal increase, BigDecimal rentAfter, String premiseNo,
                        LocalDate contractEnd, BigDecimal currentRent, BigDecimal indexMin, BigDecimal indexMax,
                        String category, String building, String area, String dldPropertyType, String issuedAt) {}
