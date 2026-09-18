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

import java.util.List;
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

    // ---------- validation ----------

    /**
     * The account type a role's leaf carries, for the roles a charge type can
     * legitimately credit. The three deposit-shaped roles sit on the liability
     * side; everything else a charge type may name is income. This mirrors the
     * chart seeded by {@code AccountService.seedDefaultAccounts} — it is not read
     * from the accounts table on purpose, so a charge type can be validated
     * before the tenant has mapped that role to a leaf.
     */
    static AccountType expectedType(AccountRole role) {
        return switch (role) {
            case ADVANCE_RENT, SECURITY_DEPOSIT, PARKING_DEPOSIT -> AccountType.LIABILITY;
            default -> AccountType.INCOME;
        };
    }

    /**
     * Behaviour and credit role have to agree, or the line posts to the wrong side
     * of the books and nothing downstream notices: a deposit credited to income is
     * revenue that was never earned and cannot be refunded off the balance sheet.
     *
     * <p>{@code RENT} + {@code ADVANCE_RENT} is the one liability-credit that is not
     * a deposit — rent lines credit unearned rent (spec §6.1) — so it is named
     * explicitly rather than by relaxing the rule for the whole behaviour.</p>
     */
    private static void validate(String code, String nameEn, AccountRole role, ChargeBehaviour behaviour) {
        if (code == null || code.isBlank()) throw new BusinessRuleViolationException("Charge type code is required");
        if (nameEn == null || nameEn.isBlank()) throw new BusinessRuleViolationException("Charge type " + code + " is missing its English name");
        if (role == null) throw new BusinessRuleViolationException("Charge type " + code + " is missing its credit role");
        if (behaviour == null) throw new BusinessRuleViolationException("Charge type " + code + " is missing its behaviour");

        AccountType expected = expectedType(role);
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
