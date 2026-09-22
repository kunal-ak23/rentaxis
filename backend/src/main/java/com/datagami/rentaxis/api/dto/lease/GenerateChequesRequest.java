package com.datagami.rentaxis.api.dto.lease;

import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What to cut the lease's cheque grid into (spec §7.1).
 *
 * <p>Every field is optional and every one has a defined fallback, so an empty
 * body generates the obvious grid: {@code installments} from the lease's payment
 * terms, {@code firstDueDate} from the lease's own first due date, PDC, folded,
 * and the residual on the first cheque.</p>
 *
 * <p>{@code foldDepositsAndFeesIntoFirst} is a {@code Boolean} rather than a
 * {@code boolean} because its default is <em>true</em>: a primitive would read an
 * absent field as false and quietly emit the deposit as its own cheque, which is
 * not what the landlord asked for by saying nothing.</p>
 *
 * @param installments number of rent instalments; defaults to the lease's payment terms.
 * @param firstDueDate date of the first instalment; defaults to the lease's first due date.
 * @param distribution where the rounding residual lands; defaults to FIRST_LARGER.
 * @param payeeBank the renter's bank, printed on every row of the grid.
 * @param debitAccountId where cleared funds land; defaults to the property's BANK mapping.
 * @param foldDepositsAndFeesIntoFirst fold deposits and fees into cheque 1; defaults to true.
 * @param mode instrument for the whole grid; defaults to PDC. ONLINE is refused.
 */
public record GenerateChequesRequest(Integer installments,
                                     LocalDate firstDueDate,
                                     InstallmentDistribution distribution,
                                     String payeeBank,
                                     UUID debitAccountId,
                                     Boolean foldDepositsAndFeesIntoFirst,
                                     ChequeMode mode) {
}
