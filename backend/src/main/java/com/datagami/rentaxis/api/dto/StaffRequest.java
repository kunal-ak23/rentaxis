package com.datagami.rentaxis.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST} and {@code PUT /api/v1/staff}.
 *
 * <p>The endpoints used to bind the {@code Staff} entity (PR #340 review, C3).
 * {@code property: {id}} became a stub Hibernate wrote unchecked, so a staff row
 * could point at another tenant's property, and {@code Staff.property} is EAGER,
 * so every later {@code GET /staff} joined that foreign row into the response.
 * {@code salaryAccount: {id}} arrived with a null id ({@code Account.id} is
 * READ_ONLY to Jackson) and failed at flush with a 500, the #67 bug.
 * {@code StaffService} now resolves both ids in the caller's tenant.</p>
 *
 * <p>The web and mobile forms' shape is accepted unchanged; anything else,
 * including an echoed {@code id}, is ignored. As before, a PUT replaces every
 * field, so a client must send the ones it does not edit.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StaffRequest(
        String nameEn,
        String nameAr,
        String employeeId,
        String designation,
        String department,
        BigDecimal monthlySalary,
        LocalDate joinDate,
        String phone,
        String emiratesId,
        String passportNumber,
        IdRef property,
        IdRef salaryAccount,
        Boolean active) {
}
