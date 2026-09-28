/// Which instalment the Tenant home's hero card calls the "next payment", read
/// off `GET /v1/online-payments/my-payments` (`RenterChequeDTO` rows).
///
/// The server decides what the Tenant owes: `due` is the Tenant's side of the
/// rules (a cheque already deposited with the bank is not due from them, a
/// post-dated cheque the landlord holds is not due before its date) and
/// `overdue` is `due` past the grace window. The card used to look for the v1
/// `OVERDUE` / `PENDING` status strings this endpoint no longer returns, so it
/// never found a row — and the web card, which read the flags, showed
/// "AED 0 · 84 days overdue" for a deposited cheque (tutorial bug 2026-09-28-03).
library;

/// The earliest row on [leaseId] the Tenant can actually pay today, or null.
Map<String, dynamic>? nextPaymentFor(List<dynamic> payments, String? leaseId) {
  if (leaseId == null) return null;
  final owed =
      payments
          .whereType<Map<String, dynamic>>()
          .where((p) => p['leaseId'] == leaseId && isOwedNow(p))
          .toList()
        ..sort((a, b) {
          final ad = a['dueDate']?.toString() ?? '';
          final bd = b['dueDate']?.toString() ?? '';
          final c = ad.compareTo(bd); // ISO dates sort as strings
          if (c != 0) return c;
          final ai = (a['installmentNumber'] ?? 0) as num;
          final bi = (b['installmentNumber'] ?? 0) as num;
          return ai.compareTo(bi);
        });
  return owed.isEmpty ? null : owed.first;
}

/// Owed by the Tenant now: flagged `due` by the server with something payable.
bool isOwedNow(Map<String, dynamic> p) {
  final payable = p['payable'];
  return p['due'] == true && payable is num && payable > 0;
}

/// Overdue for the Tenant: the server's `overdue`, never on a row with nothing
/// to pay.
bool isOverdueForTenant(Map<String, dynamic>? p) =>
    p != null && isOwedNow(p) && p['overdue'] == true;
