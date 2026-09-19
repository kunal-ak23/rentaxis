package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.api.dto.ledger.TemplateRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.PropertyAccountTemplateRow;
import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountTemplateRowRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Spec §5.3-5.4: template → per-property leaves + mappings; manual override; tenant defaults. */
@Slf4j
@Service
public class PropertyAccountService {

    private final PropertyAccountTemplateRowRepository templateRepo;
    private final PropertyAccountMappingRepository mappingRepo;
    private final TenantDefaultAccountMappingRepository defaultRepo;
    private final AccountRepository accountRepo;
    private final PropertyRepository propertyRepo;
    private final AccountService accountService;

    public PropertyAccountService(PropertyAccountTemplateRowRepository templateRepo, PropertyAccountMappingRepository mappingRepo,
                                  TenantDefaultAccountMappingRepository defaultRepo, AccountRepository accountRepo,
                                  PropertyRepository propertyRepo, AccountService accountService) {
        this.templateRepo = templateRepo;
        this.mappingRepo = mappingRepo;
        this.defaultRepo = defaultRepo;
        this.accountRepo = accountRepo;
        this.propertyRepo = propertyRepo;
        this.accountService = accountService;
    }

    // ---------- template ----------

    @Transactional(readOnly = true)
    public List<TemplateRowDTO> getTemplate() {
        return templateRepo.findAllByOrderByRoleAsc().stream().map(r -> new TemplateRowDTO(
                r.getRole(), r.getNamePattern(), r.getParentAccount().getId(), r.getParentAccount().getCode(), r.isEnabled())).toList();
    }

    @Transactional
    public List<TemplateRowDTO> saveTemplate(List<TemplateRowDTO> rows) {
        for (TemplateRowDTO dto : rows) {
            if (dto.role() == null) throw new BusinessRuleViolationException("Template row is missing its role");
            if (dto.parentAccountId() == null) throw new BusinessRuleViolationException("Template row " + dto.role() + " is missing its parent account");
            Account parent = accountRepo.findById(dto.parentAccountId()).orElseThrow(() -> new NotFoundException("Parent account not found"));
            if (!parent.isGroup()) throw new BusinessRuleViolationException("Template parent must be a group account: " + parent.getCode());
            if (dto.namePattern() == null || !dto.namePattern().contains("{property}"))
                throw new BusinessRuleViolationException("Name pattern must contain {property}: " + dto.role());
            PropertyAccountTemplateRow row = templateRepo.findByRole(dto.role()).orElseGet(PropertyAccountTemplateRow::new);
            row.setRole(dto.role());
            row.setNamePattern(dto.namePattern());
            row.setParentAccount(parent);
            row.setEnabled(dto.enabled());
            templateRepo.save(row);
        }
        return getTemplate();
    }

    /** Called from the seed entry point once the CoA exists. Idempotent. */
    @Transactional
    public void seedDefaultTemplateAndDefaults() {
        if (templateRepo.count() == 0) {
            template(AccountRole.RENT_RECEIVABLE, "Rent Receivable - {property}", "A-02-01");
            template(AccountRole.ADVANCE_RENT, "Advance Rent - {property}", "B-01-01");
            template(AccountRole.RENTAL_INCOME, "Rental Income {property}", "C-01-01");
            template(AccountRole.PDC_RECEIVABLE, "PDC Receivable {property}", "A-02-03");
            template(AccountRole.BANK, "Emirates Islamic - {property}", "A-02-02");
            template(AccountRole.SECURITY_DEPOSIT, "Security Deposit {property}", "B-01-02");
            template(AccountRole.ADMIN_FEE, "Admin Fee - {property}", "C-01-01");
            template(AccountRole.PARKING_INCOME, "Additional Parking - {property}", "C-01-01");
            template(AccountRole.PARKING_DEPOSIT, "Parking Security Deposit {property}", "B-01-02");
            template(AccountRole.COOLING_CHARGES, "Cooling Charges - {property}", "C-01");
            template(AccountRole.MAINTENANCE_CHARGES, "Maintenance Charges - {property}", "C-01-02");
            template(AccountRole.RENT_PENALTY, "Rent Penalty - {property}", "C-01-02");
            template(AccountRole.CHEQUE_RETURN_PENALTY, "Cheque Return Penalty - {property}", "C-01-02");
            // Where a penalty raised for neither of the above lands (spec §7.3,
            // PenaltyReason.OTHER). Without a row here the "Other" reason exists in
            // the UI and refuses on approval with an unmapped-role error.
            template(AccountRole.OTHER_INCOME, "Other Income - {property}", "C-01-02");
        }
        defaultIfMissing(AccountRole.CASH, "A-02-05-001");
        defaultIfMissing(AccountRole.OUTPUT_VAT, "B-01-03-001");
        defaultIfMissing(AccountRole.INPUT_VAT, "A-02-04-001");
        defaultIfMissing(AccountRole.ROUNDING_OFF, "D-02-001");
        defaultIfMissing(AccountRole.DISCOUNT_ALLOWED, "D-02-002");
        // FORFEITED_INCOME is property-scoped in AccountRole, but it is seeded as a
        // tenant default on purpose: it is the fallback a property with no forfeiture
        // leaf of its own resolves to, and no template row creates one per property.
        defaultIfMissing(AccountRole.FORFEITED_INCOME, "C-01-02-001");
        defaultIfMissing(AccountRole.OPENING_BALANCE_DIFFERENCE, "F-02");
    }

    /**
     * Resolve-if-present, per row. A tenant whose chart is not the PACT seed has
     * none of these codes, and {@code getAccountByCode} would throw NotFoundException
     * out of the seed endpoint — turning a partial seed into a 404 for the whole
     * request. A missing parent skips its own row only.
     */
    private void template(AccountRole role, String pattern, String parentCode) {
        Optional<Account> parent = accountRepo.findByCode(parentCode);
        if (parent.isEmpty()) {
            log.warn("no account {} for role {} \u2014 skipping seed", parentCode, role);
            return;
        }
        PropertyAccountTemplateRow r = new PropertyAccountTemplateRow();
        r.setRole(role);
        r.setNamePattern(pattern);
        r.setParentAccount(parent.get());
        r.setEnabled(true);
        templateRepo.save(r);
    }

    /** Same per-row skip as {@link #template}: an absent code is warned about, not fatal. */
    private void defaultIfMissing(AccountRole role, String code) {
        if (defaultRepo.findByRole(role).isPresent()) return;
        Optional<Account> account = accountRepo.findByCode(code);
        if (account.isEmpty()) {
            log.warn("no account {} for role {} \u2014 skipping seed", code, role);
            return;
        }
        TenantDefaultAccountMapping m = new TenantDefaultAccountMapping();
        m.setRole(role);
        m.setAccount(account.get());
        defaultRepo.save(m);
    }

    // ---------- per-property ----------

    /** Creates a leaf per enabled template row that has no mapping yet, reusing a same-named leaf under the same parent. */
    @Transactional
    public List<RoleMappingDTO> generateMissing(UUID propertyId) {
        Property property = propertyRepo.findById(propertyId).orElseThrow(() -> new NotFoundException("Property not found"));
        List<PropertyAccountTemplateRow> rows = templateRepo.findByEnabledTrue();
        if (rows.isEmpty()) {
            log.warn("No account template for tenant; skipping account generation for property {}", propertyId);
            return getMappings(propertyId);
        }
        for (PropertyAccountTemplateRow row : rows) {
            if (mappingRepo.findByPropertyIdAndRole(propertyId, row.getRole()).isPresent()) continue;
            String name = row.getNamePattern().replace("{property}", property.getNameEn());
            Account leaf = accountRepo.findByNameAndParent_Id(name, row.getParentAccount().getId())
                    .orElseGet(() -> accountService.createLeaf(name, row.getParentAccount(), propertyId));
            if (leaf.getProperty() == null) {
                leaf.setProperty(property);
                accountRepo.save(leaf);
            }
            PropertyAccountMapping m = new PropertyAccountMapping();
            m.setPropertyId(propertyId);
            m.setRole(row.getRole());
            m.setAccount(leaf);
            mappingRepo.save(m);
        }
        return getMappings(propertyId);
    }

    @Transactional(readOnly = true)
    public List<RoleMappingDTO> getMappings(UUID propertyId) {
        Map<AccountRole, PropertyAccountMapping> own = new EnumMap<>(AccountRole.class);
        mappingRepo.findByPropertyId(propertyId).forEach(m -> own.put(m.getRole(), m));
        Map<AccountRole, TenantDefaultAccountMapping> defaults = new EnumMap<>(AccountRole.class);
        defaultRepo.findAllByOrderByRoleAsc().forEach(m -> defaults.put(m.getRole(), m));
        List<RoleMappingDTO> out = new ArrayList<>();
        for (AccountRole role : AccountRole.values()) {
            if (!role.isPropertyScoped()) continue;
            PropertyAccountMapping m = own.get(role);
            if (m != null) {
                out.add(dto(role, m.getAccount(), false));
                continue;
            }
            TenantDefaultAccountMapping d = defaults.get(role);
            out.add(d != null ? dto(role, d.getAccount(), true) : new RoleMappingDTO(role, null, null, null, false));
        }
        return out;
    }

    @Transactional
    public RoleMappingDTO setMapping(UUID propertyId, AccountRole role, UUID accountId) {
        propertyRepo.findById(propertyId).orElseThrow(() -> new NotFoundException("Property not found"));
        if (accountId == null) throw new BusinessRuleViolationException("accountId is required");
        Account account = accountRepo.findById(accountId).orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.isGroup()) throw new BusinessRuleViolationException("Cannot map a group account");
        templateRepo.findByRole(role).ifPresent(row -> {
            if (row.getParentAccount().getAccountType() != account.getAccountType()) {
                throw new BusinessRuleViolationException(role + " expects an " + row.getParentAccount().getAccountType()
                        + " account, got " + account.getAccountType());
            }
        });
        PropertyAccountMapping m = mappingRepo.findByPropertyIdAndRole(propertyId, role).orElseGet(PropertyAccountMapping::new);
        m.setPropertyId(propertyId);
        m.setRole(role);
        m.setAccount(account);
        mappingRepo.save(m);
        return dto(role, account, false);
    }

    @Transactional
    public void clearMapping(UUID propertyId, AccountRole role) {
        mappingRepo.findByPropertyIdAndRole(propertyId, role).ifPresent(mappingRepo::delete);
    }

    // ---------- tenant defaults ----------

    @Transactional(readOnly = true)
    public List<RoleMappingDTO> getTenantDefaults() {
        Map<AccountRole, TenantDefaultAccountMapping> defaults = new EnumMap<>(AccountRole.class);
        defaultRepo.findAllByOrderByRoleAsc().forEach(m -> defaults.put(m.getRole(), m));
        List<RoleMappingDTO> out = new ArrayList<>();
        for (AccountRole role : AccountRole.values()) {
            TenantDefaultAccountMapping d = defaults.get(role);
            out.add(d != null ? dto(role, d.getAccount(), false) : new RoleMappingDTO(role, null, null, null, false));
        }
        return out;
    }

    @Transactional
    public RoleMappingDTO setTenantDefault(AccountRole role, UUID accountId) {
        if (accountId == null) throw new BusinessRuleViolationException("accountId is required");
        Account account = accountRepo.findById(accountId).orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.isGroup()) throw new BusinessRuleViolationException("Cannot map a group account");
        TenantDefaultAccountMapping m = defaultRepo.findByRole(role).orElseGet(TenantDefaultAccountMapping::new);
        m.setRole(role);
        m.setAccount(account);
        defaultRepo.save(m);
        return dto(role, account, false);
    }

    private static RoleMappingDTO dto(AccountRole role, Account a, boolean inherited) {
        return new RoleMappingDTO(role, a.getId(), a.getCode(), a.getName(), inherited);
    }
}
