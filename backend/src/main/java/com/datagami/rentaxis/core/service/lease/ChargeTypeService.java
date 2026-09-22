package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.repository.ChargeTypeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The charge-type catalogue (spec §6.1): the particulars a lease line can be
 * built from, and the account role each one credits.
 */
@Slf4j
@Service
public class ChargeTypeService {

    private final ChargeTypeRepository repo;

    public ChargeTypeService(ChargeTypeRepository repo) {
        this.repo = repo;
    }

    /**
     * The PACT particulars, seeded into every tenant. The role on each row is the
     * <em>credit</em> account of a line of that type, which is why RENT names
     * {@code ADVANCE_RENT} and not {@code RENTAL_INCOME}: rent is unearned when the
     * lease posts and is recognised day by day afterwards (spec §6.1).
     */
    static final List<ChargeTypeDTO> DEFAULTS = List.of(
            new ChargeTypeDTO(null, "RENT", "Rent", "الإيجار",
                    AccountRole.ADVANCE_RENT, ChargeBehaviour.RENT, false, true, 10),
            new ChargeTypeDTO(null, "SECURITY_DEPOSIT", "Security Deposit", "مبلغ التأمين",
                    AccountRole.SECURITY_DEPOSIT, ChargeBehaviour.DEPOSIT, false, true, 20),
            new ChargeTypeDTO(null, "ADMIN_FEE", "Admin Fee", "رسوم إدارية",
                    AccountRole.ADMIN_FEE, ChargeBehaviour.FEE, false, true, 30),
            new ChargeTypeDTO(null, "PARKING_DEPOSIT", "Parking Security Deposit", "تأمين موقف السيارات",
                    AccountRole.PARKING_DEPOSIT, ChargeBehaviour.DEPOSIT, false, true, 40),
            new ChargeTypeDTO(null, "COOLING", "Cooling Charges", "رسوم التبريد",
                    AccountRole.COOLING_CHARGES, ChargeBehaviour.FEE, false, true, 50),
            new ChargeTypeDTO(null, "PARKING_FEE", "Parking Fee", "رسوم موقف السيارات",
                    AccountRole.PARKING_INCOME, ChargeBehaviour.FEE, false, true, 60),
            new ChargeTypeDTO(null, "MAINTENANCE", "Maintenance Charges", "رسوم الصيانة",
                    AccountRole.MAINTENANCE_CHARGES, ChargeBehaviour.FEE, false, true, 70));

    /**
     * The default {@link #seedDefaults()} would create for this code, or null when
     * the code is not one of them.
     *
     * <p>Exists for the cut-over import's validator. Validation runs before anything
     * is written, and the persist phase seeds this catalogue as its first act (the
     * v1 importer does the same), so a workbook naming {@code RENT} into a tenant
     * that has never opened the leasing screens is importable — the code <em>will</em>
     * exist by the time a line is built from it. Without this the validator would
     * refuse the very first cut-over workbook of every new organisation, with an
     * error message telling the accountant to go and seed a catalogue that the
     * import was about to seed for them.</p>
     */
    public static ChargeTypeDTO seededDefault(String code) {
        if (code == null) return null;
        return DEFAULTS.stream()
                .filter(d -> d.code().equalsIgnoreCase(code.trim()))
                .findFirst().orElse(null);
    }

    // ---------- validation ----------

    /**
     * The roles a charge type is allowed to credit, and the account type each one's
     * leaf carries. It is an allow-list, not a default: a role absent from this map
     * cannot be named by a charge type at all, whatever the behaviour.
     *
     * <p>That distinction is the whole point. Charging a line to {@code BANK} or
     * {@code RENT_RECEIVABLE} would credit the asset the line is supposed to
     * <em>debit</em>, netting the receivable to zero and leaving a lease that looks
     * paid the moment it posts; crediting {@code OUTPUT_VAT} would book tax the
     * tenant never charged. Under the previous "everything else is INCOME" default
     * both were accepted — the arithmetic balanced, so nothing downstream would
     * have objected. The nine roles left out are the asset, tax and
     * contra/adjustment ones: RENT_RECEIVABLE, PDC_RECEIVABLE, BANK, CASH,
     * OUTPUT_VAT, INPUT_VAT, DISCOUNT_ALLOWED, ROUNDING_OFF and
     * OPENING_BALANCE_DIFFERENCE. Posting reaches all of them, but only ever as the
     * other side of an entry the posting rules own, never as a line a user picked.</p>
     *
     * <p>The types mirror the chart seeded by
     * {@code AccountService.seedDefaultAccounts}; they are not read from the
     * accounts table on purpose, so a charge type can be validated before the
     * tenant has mapped that role to a leaf.</p>
     */
    static final Map<AccountRole, AccountType> CREDITABLE_ROLES;
    static {
        EnumMap<AccountRole, AccountType> m = new EnumMap<>(AccountRole.class);
        // deposits and unearned rent: money held, not yet earned
        m.put(AccountRole.ADVANCE_RENT, AccountType.LIABILITY);
        m.put(AccountRole.SECURITY_DEPOSIT, AccountType.LIABILITY);
        m.put(AccountRole.PARKING_DEPOSIT, AccountType.LIABILITY);
        // earned on the line
        m.put(AccountRole.RENTAL_INCOME, AccountType.INCOME);
        m.put(AccountRole.ADMIN_FEE, AccountType.INCOME);
        m.put(AccountRole.PARKING_INCOME, AccountType.INCOME);
        m.put(AccountRole.COOLING_CHARGES, AccountType.INCOME);
        m.put(AccountRole.MAINTENANCE_CHARGES, AccountType.INCOME);
        m.put(AccountRole.RENT_PENALTY, AccountType.INCOME);
        m.put(AccountRole.CHEQUE_RETURN_PENALTY, AccountType.INCOME);
        m.put(AccountRole.OTHER_INCOME, AccountType.INCOME);
        m.put(AccountRole.FORFEITED_INCOME, AccountType.INCOME);
        CREDITABLE_ROLES = Collections.unmodifiableMap(m);
    }

    /**
     * The account type a line crediting {@code role} must land on, or {@code null}
     * when the role cannot be credited by a charge type at all.
     *
     * <p>Exposed because the rule outlives charge-type creation: a lease line may
     * override its credit account with any leaf the user picks, and that override
     * has to satisfy the same constraint the charge type did. Without it, a line
     * on an ADMIN_FEE charge type could be pointed at the property's bank leaf and
     * the entry would still balance — it would simply credit the asset it was
     * meant to debit.</p>
     */
    public static AccountType expectedTypeFor(AccountRole role) {
        return role == null ? null : CREDITABLE_ROLES.get(role);
    }

    /**
     * Behaviour and credit role have to agree, or the line posts to the wrong side
     * of the books and nothing downstream notices: a deposit credited to income is
     * revenue that was never earned and cannot be refunded off the balance sheet.
     *
     * <p>{@code RENT} + {@code ADVANCE_RENT} is the one liability-credit that is not
     * a deposit — rent lines credit unearned rent (spec §6.1) — so it is named
     * explicitly rather than by relaxing the rule for the whole behaviour.</p>
     *
     * <p>The role has to clear {@link #CREDITABLE_ROLES} first, whatever the
     * behaviour: a role outside that allow-list is refused before the
     * behaviour rules are consulted at all.</p>
     */
    private static void validate(String code, String nameEn, AccountRole role, ChargeBehaviour behaviour) {
        if (code == null || code.isBlank()) throw new BusinessRuleViolationException("Charge type code is required");
        if (nameEn == null || nameEn.isBlank()) throw new BusinessRuleViolationException("Charge type " + code + " is missing its English name");
        if (role == null) throw new BusinessRuleViolationException("Charge type " + code + " is missing its credit role");
        if (behaviour == null) throw new BusinessRuleViolationException("Charge type " + code + " is missing its behaviour");

        AccountType expected = CREDITABLE_ROLES.get(role);
        if (expected == null) {
            throw new BusinessRuleViolationException("Role " + role + " cannot be credited by a charge type");
        }
        boolean rentException = behaviour == ChargeBehaviour.RENT && role == AccountRole.ADVANCE_RENT;
        if (behaviour == ChargeBehaviour.DEPOSIT) {
            if (expected != AccountType.LIABILITY) {
                throw new BusinessRuleViolationException("A DEPOSIT charge type must credit a liability role; "
                        + role + " is " + expected);
            }
        } else if (expected == AccountType.LIABILITY && !rentException) {
            throw new BusinessRuleViolationException("A " + behaviour + " charge type must credit a non-liability role; "
                    + role + " is " + expected);
        }
    }

    // ---------- reads ----------

    @Transactional(readOnly = true)
    public List<ChargeTypeDTO> list(boolean activeOnly) {
        List<ChargeType> rows = activeOnly
                ? repo.findByActiveTrueOrderByDisplayOrderAscCodeAsc()
                : repo.findAllByOrderByDisplayOrderAscCodeAsc();
        return rows.stream().map(ChargeTypeService::toDTO).toList();
    }

    /** The entity, because callers (lease lines, posting) need its role and behaviour. */
    @Transactional(readOnly = true)
    public ChargeType getByCode(String code) {
        return repo.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Charge type not found: " + code));
    }

    // ---------- writes ----------

    /**
     * Creates a charge type. Duplicate codes are refused by
     * {@code uq_charge_types_tenant_code} rather than by a prior lookup — hence the
     * flush, which turns the collision into a {@code DataIntegrityViolationException}
     * here instead of at commit time. {@code GlobalExceptionHandler} renders it as 409.
     */
    @Transactional
    public ChargeTypeDTO create(ChargeTypeDTO dto) {
        validate(dto.code(), dto.nameEn(), dto.role(), dto.behaviour());
        ChargeType e = new ChargeType();
        e.setCode(dto.code().trim());
        apply(e, dto);
        return toDTO(repo.saveAndFlush(e));
    }

    /**
     * Full replacement of the mutable fields — the client sends the whole DTO back.
     *
     * <p>{@code code} is not one of them: it is the stable handle that seeding,
     * lease lines and posting rules look a type up by, and renaming it would let
     * {@link #seedDefaults()} re-create the original as a second row. A body that
     * carries a different code is refused rather than silently ignored.</p>
     */
    @Transactional
    public ChargeTypeDTO update(UUID id, ChargeTypeDTO dto) {
        ChargeType e = repo.findById(id).orElseThrow(() -> new NotFoundException("Charge type not found"));
        if (dto.code() != null && !dto.code().trim().equals(e.getCode())) {
            throw new BusinessRuleViolationException("A charge type's code cannot be changed (" + e.getCode() + ")");
        }
        validate(e.getCode(), dto.nameEn(), dto.role(), dto.behaviour());
        apply(e, dto);
        return toDTO(repo.save(e));
    }

    /**
     * Inserts the PACT particulars this tenant is missing, by code. Idempotent, and
     * safe to re-run after new defaults are added to {@link #DEFAULTS}: rows the
     * tenant already has — edited or deactivated — are left exactly as they are.
     */
    @Transactional
    public void seedDefaults() {
        int created = 0;
        for (ChargeTypeDTO dto : DEFAULTS) {
            if (repo.findByCode(dto.code()).isPresent()) continue;
            validate(dto.code(), dto.nameEn(), dto.role(), dto.behaviour());
            ChargeType e = new ChargeType();
            e.setCode(dto.code());
            apply(e, dto);
            repo.save(e);
            created++;
        }
        if (created > 0) log.info("Seeded {} charge type(s)", created);
    }

    // ---------- mapping ----------

    private static void apply(ChargeType e, ChargeTypeDTO dto) {
        e.setNameEn(dto.nameEn());
        e.setNameAr(dto.nameAr());
        e.setRole(dto.role());
        e.setBehaviour(dto.behaviour());
        e.setVatApplicableDefault(dto.vatApplicableDefault());
        e.setActive(dto.active());
        e.setDisplayOrder(dto.displayOrder());
    }

    public static ChargeTypeDTO toDTO(ChargeType e) {
        return new ChargeTypeDTO(e.getId(), e.getCode(), e.getNameEn(), e.getNameAr(), e.getRole(),
                e.getBehaviour(), e.isVatApplicableDefault(), e.isActive(), e.getDisplayOrder());
    }
}
