package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.core.service.voucher.VoucherMath;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "voucher_lines")
@Getter
@Setter
public class VoucherLine extends BaseTenantEntity implements VoucherMath.HasAmounts {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    @JsonIgnore
    private Voucher voucher;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "property_id") private UUID propertyId;
    @Column(name = "unit_id") private UUID unitId;
}
