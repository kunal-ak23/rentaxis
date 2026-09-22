package com.datagami.rentaxis.api.dto.ledger;

import java.time.LocalDate;

/** The tenant's accounting calendar: fiscal year start, books start, period lock. */
public record FiscalSettingsDTO(int fiscalYearStartMonth, LocalDate booksStartDate, LocalDate booksLockedThrough) {}
