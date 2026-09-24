package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.RoleMappingDTO;
import com.datagami.rentaxis.api.dto.ledger.TemplateRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.report.ReportLines;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.PropertyAccountTemplateRow;
import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
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

    /** Parent of the per-property building-cost leaves. */
    private static final String DIRECT_EXPENSE_PARENT_CODE = "D-01";

    // The direct-expense categories (names, Arabic names, report lines) live in
    // ReportLines.DIRECT_EXPENSE_CATEGORIES, shared with the P&L and changeset 109.

    /**
     * The Arabic label a property leaf is named with, per role (gap #68).
     *
     * <p>Not the group's Arabic name alone: several roles share a group (Rental
     * Income, Admin Fee and Additional Parking all sit under C-01-01; four income
     * roles under C-01-02), so naming a leaf after its group would give four
     * different accounts the same Arabic name. Generic on purpose: the English
     * pattern is tenant-editable ("Emirates Islamic - {property}" is only the
     * seed), so the label says what the account is, not which bank. A role not
     * listed falls back to the group's Arabic name.</p>
     */
    private static final Map<AccountRole, String> ROLE_LABEL_AR = Map.ofEntries(
            Map.entry(AccountRole.RENT_RECEIVABLE, "إيجارات مستحقة"),
            Map.entry(AccountRole.ADVANCE_RENT, "إيجار مقدم"),
            Map.entry(AccountRole.RENTAL_INCOME, "إيرادات الإيجار"),
            Map.entry(AccountRole.PDC_RECEIVABLE, "شيكات مؤجلة مستحقة"),
            Map.entry(AccountRole.BANK, "الحساب البنكي"),
            Map.entry(AccountRole.SECURITY_DEPOSIT, "تأمين الإيجار"),
            Map.entry(AccountRole.ADMIN_FEE, "الرسوم الإدارية"),
            Map.entry(AccountRole.PARKING_INCOME, "مواقف إضافية"),
            Map.entry(AccountRole.PARKING_DEPOSIT, "تأمين المواقف"),
            Map.entry(AccountRole.COOLING_CHARGES, "رسوم التبريد"),
            Map.entry(AccountRole.MAINTENANCE_CHARGES, "رسوم الصيانة"),
            Map.entry(AccountRole.RENT_PENALTY, "غرامة تأخير الإيجار"),
            Map.entry(AccountRole.CHEQUE_RETURN_PENALTY, "غرامة الشيكات المرتجعة"),
            Map.entry(AccountRole.OTHER_INCOME, "إيرادات أخرى"));

    /**
     * "label - property", the Arabic counterpart of the English leaf name. The
     * property's Arabic name when it has one, else its English name: a building's
     * name is a proper noun, and an Arabic reader is better served by an Arabic
     * account label next to a Latin building name than by an all-English line.
     * Null when there is no Arabic label at all.
     */
    static String arabicLeafName(String labelAr, Property property) {
        if (labelAr == null || labelAr.isBlank()) return null;
        String building = property.getNameAr() != null && !property.getNameAr().isBlank()
                ? property.getNameAr() : property.getNameEn();
        return labelAr + " - " + building;
    }

    private final PropertyAccountTemplateRowRepository templateRepo;
    private final PropertyAccountMappingRepository mappingRepo;
    private final TenantDefaultAccountMappingRepository defaultRepo;
    private final AccountRepository accountRepo;
    private final PropertyRepository propertyRepo;
    private final AccountService accountService;

    private final com.datagami.rentaxis.core.service.bank.OwnedBankLeaf ownedBankLeaf;

    public PropertyAccountService(PropertyAccountTemplateRowRepository templateRepo, PropertyAccountMappingRepository mappingRepo,
                                  TenantDefaultAccountMappingRepository defaultRepo, AccountRepository accountRepo,
                                  PropertyRepository propertyRepo, AccountService accountService,
                                  com.datagami.rentaxis.core.service.bank.OwnedBankLeaf ownedBankLeaf) {
        this.ownedBankLeaf = ownedBankLeaf;
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
        defaultIfMissing(AccountRole.OUTPUT_VAT_DEFERRED, "B-01-03-002");
        defaultIfMissing(AccountRole.INPUT_VAT, "A-02-04-001");
        defaultIfMissing(AccountRole.ROUNDING_OFF, "D-02-001");
        defaultIfMissing(AccountRole.DISCOUNT_ALLOWED, "D-02-002");
        // FORFEITED_INCOME is property-scoped in AccountRole, but it is seeded as a
        // tenant default on purpose: it is the fallback a property with no forfeiture
        // leaf of its own resolves to, and no template row creates one per property.
        defaultIfMissing(AccountRole.FORFEITED_INCOME, "C-01-02-001");
        defaultIfMissing(AccountRole.OPENING_BALANCE_DIFFERENCE, "F-02");
        defaultIfMissing(AccountRole.PDC_PAYABLE, "B-02-001");
        defaultIfMissing(AccountRole.BANK_CHARGES, "D-02-003");
        defaultIfMissing(AccountRole.BANK_INTEREST_INCOME, "C-02-001");
        defaultIfMissing(AccountRole.BANK_SUSPENSE, "B-01-06");
        defaultIfMissing(AccountRole.RENTER_REFUND_PAYABLE, "B-01-07");
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
            if (row.getRole() == AccountRole.BANK && mapToOwnedBankLeaf(propertyId)) continue;
            // F14-44: with bank accounts in place, a generated "<bank> – <property>"
            // leaf would be owned by none of them and could never be reconciled.
            // The role stays unmapped; receipts fall back to the tenant default.
            if (row.getRole() == AccountRole.BANK && ownedBankLeaf.anyOwned()) continue;
            String name = row.getNamePattern().replace("{property}", property.getNameEn());
            String nameAr = arabicLeafName(
                    ROLE_LABEL_AR.getOrDefault(row.getRole(), row.getParentAccount().getNameAr()), property);
            Account leaf = accountRepo.findByNameAndParent_Id(name, row.getParentAccount().getId())
                    .orElseGet(() -> accountService.createLeaf(name, nameAr, row.getParentAccount(), propertyId));
            boolean dirty = false;
            if (leaf.getProperty() == null) {
                leaf.setProperty(property);
                dirty = true;
            }
            // The P&L row key (finance-ops §1). A reused leaf already carrying a line
            // keeps it: the first role it was mapped to wins, as in changeset 109.
            if (leaf.getReportLine() == null && reportLineFits(row.getRole().name(), leaf)) {
                leaf.setReportLine(row.getRole().name());
                dirty = true;
            }
            if (dirty) accountRepo.save(leaf);
            PropertyAccountMapping m = new PropertyAccountMapping();
            m.setPropertyId(propertyId);
            m.setRole(row.getRole());
            m.setAccount(leaf);
            mappingRepo.save(m);
        }
        generateDirectExpenseLeaves(property, propertyId);
        return getMappings(propertyId);
    }

    /**
     * F14-16: a new property's receipts land in a leaf some bank account owns. When
     * the tenant has a bank account with a ledger leaf, the property's BANK role is
     * mapped to that leaf ({@link com.datagami.rentaxis.core.service.bank.OwnedBankLeaf})
     * instead of generating "Emirates Islamic – &lt;property&gt;", which no bank
     * account owned and so could never be reconciled. A tenant with no bank account
     * yet keeps the generated leaf; the bank-rec screen can adopt it later.
     */
    private boolean mapToOwnedBankLeaf(UUID propertyId) {
        Optional<UUID> owned = ownedBankLeaf.forProperty(propertyId);
        if (owned.isEmpty()) return false;
        Account leaf = accountRepo.findById(owned.get()).orElse(null);
        if (leaf == null) return false;
        PropertyAccountMapping m = new PropertyAccountMapping();
        m.setPropertyId(propertyId);
        m.setRole(AccountRole.BANK);
        m.setAccount(leaf);
        mappingRepo.save(m);
        return true;
    }

    /**
     * Building running costs, one leaf per property per category — which is what
     * {@code D-01 Direct Expense} has always described itself as holding, while
     * shipping with no children at all.
     *
     * <p>Consequence of the gap: a fresh tenant's only postable expense accounts
     * were {@code Rounding Off}, {@code Discount Allowed} and {@code Bank Charges},
     * so the account lookup on the Purchase / Service Invoice screen answered
     * nothing for "Repairs" and a supplier bill could not be coded at all. Every
     * P&L and NOI report showed income with no costs against it.</p>
     *
     * <p>These leaves deliberately carry no {@link AccountRole}: nothing posts to
     * them automatically. A voucher line picks the account by name, so they need
     * to exist and be postable, not to be resolvable from a posting rule.</p>
     */
    private void generateDirectExpenseLeaves(Property property, UUID propertyId) {
        Optional<Account> parent = accountRepo.findByCode(DIRECT_EXPENSE_PARENT_CODE);
        if (parent.isEmpty()) {
            // A tenant whose chart is not the standard seed has no D-01 to hang
            // these from. Skip quietly rather than failing the whole generation.
            log.warn("no account {} — skipping direct-expense leaves for property {}",
                    DIRECT_EXPENSE_PARENT_CODE, propertyId);
            return;
        }
        for (ReportLines.ExpenseCategory category : ReportLines.DIRECT_EXPENSE_CATEGORIES) {
            String prefix = category.leafPrefix();
            String name = prefix + property.getNameEn();
            // Scoped to this property, not just this name: two properties with the
            // same display name must not share one leaf (see the repository method's
            // javadoc). The prefix, not the full name, so a leaf that survives a
            // property rename still counts; "category - " rather than the bare
            // category, so a hand-made "Security Deposit Refunds" is not mistaken
            // for the generated "Security - <Property>".
            if (accountRepo.existsByParent_IdAndProperty_IdAndNameStartingWith(parent.get().getId(), propertyId, prefix)) {
                continue;
            }
            Account leaf = accountService.createLeaf(name,
                    arabicLeafName(category.nameAr(), property), parent.get(), propertyId);
            if (reportLineFits(category.reportLine(), leaf)) {
                leaf.setReportLine(category.reportLine());
                accountRepo.save(leaf);
            }
        }
    }

    /**
     * Whether a report line may be written onto this leaf automatically: only when
     * its account type is the line's natural one (ReportLines.naturalType), the same
     * rule the chart of accounts enforces. A mismatch — a template parent of an
     * unexpected type on an imported chart — is skipped and logged, never fatal:
     * the leaf is then its own P&L row.
     */
    static boolean reportLineFits(String key, Account leaf) {
        if (ReportLines.naturalType(key) == leaf.getAccountType()) return true;
        log.warn("not setting report line {} on {} {} ({}): it belongs on a {} account", key, leaf.getCode(),
                leaf.getName(), leaf.getAccountType(), ReportLines.naturalType(key));
        return false;
    }

    /**
     * F14-12: the HTTP doors name a property by id, so they must 404 one that is not
     * this tenant's rather than answer with an empty mapping (and write to it).
     * {@code findById} is not subject to the tenant filter, hence the explicit check.
     */
    @Transactional(readOnly = true)
    public void requireOwnProperty(UUID propertyId) {
        propertyRepo.findById(propertyId)
                .filter(p -> com.datagami.rentaxis.core.service.TenantReferences.inCurrentTenant(p.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Property not found"));
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

    /**
     * The account type a leaf must carry to play {@code role} here, or null when the
     * tenant's template says nothing about that role.
     *
     * <p>Taken from the template row's parent, which is where the chart itself
     * decides whether "Advance Rent" is a liability. Public because the cut-over
     * import validates a whole workbook of role → account-name pairs <em>before</em>
     * writing anything: {@link #setMapping} throws on a mismatch, and an exception
     * thrown halfway through a bulk import loses the workbook instead of reporting
     * the cell. Both doors now ask this one question.</p>
     */
    @Transactional(readOnly = true)
    public AccountType expectedAccountTypeFor(AccountRole role) {
        return templateRepo.findByRole(role)
                .map(row -> row.getParentAccount().getAccountType())
                .orElse(null);
    }

    @Transactional
    public RoleMappingDTO setMapping(UUID propertyId, AccountRole role, UUID accountId) {
        propertyRepo.findById(propertyId).orElseThrow(() -> new NotFoundException("Property not found"));
        if (accountId == null) throw new BusinessRuleViolationException("accountId is required");
        Account account = accountRepo.findById(accountId).orElseThrow(() -> new NotFoundException("Account not found"));
        if (account.isGroup()) throw new BusinessRuleViolationException("Cannot map a group account");
        AccountType expected = expectedAccountTypeFor(role);
        if (expected != null && expected != account.getAccountType()) {
            throw new BusinessRuleViolationException(role + " expects an " + expected
                    + " account, got " + account.getAccountType());
        }
        PropertyAccountMapping m = mappingRepo.findByPropertyIdAndRole(propertyId, role).orElseGet(PropertyAccountMapping::new);
        m.setPropertyId(propertyId);
        m.setRole(role);
        m.setAccount(account);
        mappingRepo.save(m);
        // A leaf mapped by hand joins its role's P&L row unless it already has one.
        if (account.getReportLine() == null && reportLineFits(role.name(), account)) {
            account.setReportLine(role.name());
            accountRepo.save(account);
        }
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
