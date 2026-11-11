package com.datagami.rentaxis.api.dto.rentalindex;

import java.util.List;

/** What a CSV import did: rows saved, and "Row N: why" for each row refused. Nothing is saved when any row fails. */
public record RentalIndexImportResult(int saved, List<String> errors) {
}
