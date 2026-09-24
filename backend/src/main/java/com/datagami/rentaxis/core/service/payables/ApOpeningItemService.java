package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.api.dto.payables.ApOpeningItemDTO;
import com.datagami.rentaxis.api.dto.payables.ApOpeningItemInputDTO;
import com.datagami.rentaxis.api.dto.payables.ApOpeningSummaryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ApOpeningItem;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.repository.ApOpeningItemRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import com.datagami.rentaxis.domain.repository.VoucherAllocationRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cut-over open supplier invoices (finance-ops spec §2, {@code ap_opening_items}).
 * Their money arrived on the vendor's payable leaf as an OB line; these rows say
 * which invoices make it up, so payments can be allocated to them. Per vendor,
 * Σ items is compared with that OB line — a check, not a block.
 *
 * <p>An item that has allocations keeps its vendor, and its amount cannot fall
 * below what is allocated; one with allocations (live or released — they are
 * history) cannot be deleted.</p>
 */
@Service
public class ApOpeningItemService {

    private final ApOpeningItemRepository items;
    private final VendorRepository vendors;
    private final PropertyRepository properties;
    private final VoucherAllocationRepository allocations;
    private final NamedParameterJdbcTemplate jdbc;

    public ApOpeningItemService(ApOpeningItemRepository items, VendorRepository vendors, PropertyRepository properties,
                                VoucherAllocationRepository allocations, NamedParameterJdbcTemplate jdbc) {
        this.items = items;
        this.vendors = vendors;
        this.properties = properties;
        this.allocations = allocations;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ApOpeningSummaryDTO summary(UUID vendorId) {
        UUID t = requireTenant();
        Map<UUID, Vendor> names = vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, v -> v));
        List<ApOpeningItem> rows = vendorId == null ? items.findAllByOrderByInvoiceDateAscCreatedAtAsc()
                : items.findByVendorIdOrderByInvoiceDateAscCreatedAtAsc(vendorId);
        List<ApOpeningItemDTO> dtos = rows.stream().map(o -> ApOpeningItemDTO.of(o, name(names, o.getVendorId()),
                allocations.liveTotalForOpeningItem(o.getId()))).toList();

        Map<UUID, BigDecimal> ob = new HashMap<>();
        jdbc.query("""
                select d.id as vendor_id, coalesce(sum(l.credit - l.debit), 0) as ob
                from vendors d
                join journal_lines l on l.account_id = d.payable_account_id and l.tenant_id = d.tenant_id
                join journal_entries e on e.id = l.journal_entry_id
                where d.tenant_id = :t and e.doc_type = 'OB'
                group by d.id
                """, new MapSqlParameterSource("t", t),
                rs -> { ob.put(rs.getObject("vendor_id", UUID.class), rs.getBigDecimal("ob")); });

        Map<UUID, BigDecimal> totals = rows.stream().collect(Collectors.toMap(ApOpeningItem::getVendorId,
                ApOpeningItem::getAmount, BigDecimal::add, LinkedHashMap::new));
        Set<UUID> ids = new LinkedHashSet<>(totals.keySet());
        ob.forEach((id, v) -> { if (v.signum() != 0 && (vendorId == null || vendorId.equals(id))) ids.add(id); });
        List<ApOpeningSummaryDTO.VendorCheck> checks = ids.stream().map(id -> {
            BigDecimal itemsTotal = totals.getOrDefault(id, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            BigDecimal obTotal = ob.getOrDefault(id, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            return new ApOpeningSummaryDTO.VendorCheck(id, name(names, id), itemsTotal, obTotal, obTotal.subtract(itemsTotal));
        }).sorted(Comparator.comparing(c -> Objects.toString(c.vendorName(), ""))).toList();
        return new ApOpeningSummaryDTO(dtos, checks);
    }

    @Transactional
    public ApOpeningItemDTO create(ApOpeningItemInputDTO in) {
        requireTenant();
        Vendor vendor = vendor(in.vendorId());
        ApOpeningItem o = new ApOpeningItem();
        apply(o, in, vendor);
        o.setCreatedBy(currentUserId());
        o = items.save(o);
        return ApOpeningItemDTO.of(o, vendor.getNameEn(), BigDecimal.ZERO);
    }

    @Transactional
    public ApOpeningItemDTO update(UUID id, ApOpeningItemInputDTO in) {
        requireTenant();
        ApOpeningItem o = items.findById(id).orElseThrow(() -> new NotFoundException("Opening item not found"));
        Vendor vendor = vendor(in.vendorId());
        boolean hasAllocations = allocations.existsByOpeningItemId(o.getId());
        if (hasAllocations && !o.getVendorId().equals(vendor.getId())) {
            throw new BusinessRuleViolationException("Payments are allocated to this item; its vendor cannot change");
        }
        BigDecimal allocated = allocations.liveTotalForOpeningItem(o.getId());
        if (in.amount().setScale(2, RoundingMode.HALF_UP).compareTo(allocated) < 0) {
            throw new BusinessRuleViolationException("Payments of " + allocated.toPlainString()
                    + " are allocated to this item; its amount cannot be less");
        }
        apply(o, in, vendor);
        o.setUpdatedAt(Instant.now());
        return ApOpeningItemDTO.of(items.save(o), vendor.getNameEn(), allocated);
    }

    @Transactional
    public void delete(UUID id) {
        requireTenant();
        ApOpeningItem o = items.findById(id).orElseThrow(() -> new NotFoundException("Opening item not found"));
        if (allocations.existsByOpeningItemId(o.getId())) {
            throw new BusinessRuleViolationException("Payments have been allocated to this item; release them first. "
                    + "An item with allocation history is kept.");
        }
        items.delete(o);
    }

    private void apply(ApOpeningItem o, ApOpeningItemInputDTO in, Vendor vendor) {
        if (in.invoiceNumber() == null || in.invoiceNumber().isBlank()) {
            throw new BusinessRuleViolationException("An opening item needs the supplier's invoice number");
        }
        if (in.invoiceDate() == null) throw new BusinessRuleViolationException("An opening item needs its invoice date");
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw new BusinessRuleViolationException("An opening item needs an amount greater than zero");
        }
        if (in.propertyId() != null && properties.findById(in.propertyId()).isEmpty()) {
            throw new NotFoundException("Property not found");
        }
        var due = in.dueDate() != null ? in.dueDate()
                : in.invoiceDate().plusDays(vendor.getPaymentTermsDays() == null ? 30 : vendor.getPaymentTermsDays());
        if (due.isBefore(in.invoiceDate())) {
            throw new BusinessRuleViolationException("The due date cannot be before the invoice date");
        }
        o.setVendorId(vendor.getId());
        o.setInvoiceNumber(in.invoiceNumber().trim());
        o.setInvoiceDate(in.invoiceDate());
        o.setDueDate(due);
        o.setAmount(in.amount().setScale(2, RoundingMode.HALF_UP));
        o.setPropertyId(in.propertyId());
    }

    private Vendor vendor(UUID id) {
        if (id == null) throw new BusinessRuleViolationException("Choose the vendor");
        return vendors.findById(id).orElseThrow(() -> new NotFoundException("Vendor not found"));
    }

    private static String name(Map<UUID, Vendor> names, UUID id) {
        Vendor v = names.get(id);
        return v == null ? null : v.getNameEn();
    }

    private static UUID requireTenant() {
        UUID t = TenantContextHolder.getTenantId();
        if (t == null) throw new BusinessRuleViolationException("Select an organisation first");
        return t;
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
