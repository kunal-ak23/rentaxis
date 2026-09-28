import 'package:flutter_test/flutter_test.dart';
import 'package:renter/screens/next_payment.dart';

/// Tutorial bug 2026-09-28-03: a cheque already deposited with the bank was the
/// "next payment" at AED 0 and "84 days overdue". The hero card now takes the
/// server's Tenant-side `due` / `overdue` flags and never a row with nothing
/// payable on it.
Map<String, dynamic> row(
  String id,
  String status,
  String dueDate, {
  bool due = false,
  bool overdue = false,
  num payable = 0,
  int n = 1,
  String lease = 'l1',
}) => {
  'id': id,
  'leaseId': lease,
  'installmentNumber': n,
  'status': status,
  'dueDate': dueDate,
  'amount': 21250,
  'due': due,
  'overdue': overdue,
  'payable': payable,
};

void main() {
  test('skips a deposited cheque and picks the earliest payable row', () {
    final rows = [
      row('dep', 'DEPOSITED', '2026-07-01', n: 3),
      row(
        'late',
        'REGISTERED',
        '2026-08-01',
        due: true,
        overdue: true,
        payable: 21250,
        n: 4,
      ),
      row('later', 'BOUNCED', '2026-09-01', due: true, payable: 21250, n: 5),
    ];
    expect(nextPaymentFor(rows, 'l1')?['id'], 'late');
    expect(isOverdueForTenant(nextPaymentFor(rows, 'l1')), isTrue);
  });

  test(
    'a deposited row an older server flags due and overdue is never the next payment',
    () {
      final rows = [
        row('dep', 'DEPOSITED', '2026-07-01', due: true, overdue: true),
      ];
      expect(nextPaymentFor(rows, 'l1'), isNull);
      expect(isOverdueForTenant(rows.first), isFalse);
    },
  );

  test('cleared rows and held post-dated cheques are not owed', () {
    final rows = [
      row('clr', 'CLEARED', '2026-04-01'),
      row('pdc', 'REGISTERED', '2026-12-01'),
    ];
    expect(nextPaymentFor(rows, 'l1'), isNull);
  });

  test('only rows on the given lease count', () {
    final rows = [
      row(
        'other',
        'REGISTERED',
        '2026-08-01',
        due: true,
        payable: 100,
        lease: 'l2',
      ),
    ];
    expect(nextPaymentFor(rows, 'l1'), isNull);
    expect(nextPaymentFor(rows, null), isNull);
  });

  test('inside grace: owed but not overdue', () {
    final r = row('g', 'REGISTERED', '2026-09-27', due: true, payable: 21250);
    expect(nextPaymentFor([r], 'l1')?['id'], 'g');
    expect(isOverdueForTenant(r), isFalse);
  });
}
