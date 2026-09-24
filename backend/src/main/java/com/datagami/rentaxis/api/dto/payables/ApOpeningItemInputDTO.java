package com.datagami.rentaxis.api.dto.payables;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Create or edit a cut-over open item. {@code dueDate} null means the invoice date plus the vendor's terms. */
public record ApOpeningItemInputDTO(@NotNull UUID vendorId, @NotBlank @Size(max = 60) String invoiceNumber,
                                    @NotNull LocalDate invoiceDate, LocalDate dueDate,
                                    @NotNull @Positive BigDecimal amount, UUID propertyId) { }
