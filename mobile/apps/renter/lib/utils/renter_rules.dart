/// Resident service eligibility mirrors LeaseRepository.findCurrentForRenterUser.
bool isCurrentRenterLease(Map<String, dynamic> lease, {DateTime? now}) {
  if (!const {'ACTIVE', 'NOTICE_GIVEN', 'RENEWED'}.contains(lease['status'])) {
    return false;
  }
  if (lease['yourAccessEndedOn'] != null) return false;
  final today = (now ?? DateTime.now()).toIso8601String().substring(0, 10);
  final start = lease['startDate']?.toString();
  final end = lease['endDate']?.toString();
  return (start == null || start.compareTo(today) <= 0) &&
      (end == null || end.compareTo(today) >= 0);
}

List<Map<String, dynamic>> orderedRenterLeases(
  List<dynamic> leases, {
  DateTime? now,
}) {
  final rows = leases.map((r) => Map<String, dynamic>.from(r as Map)).toList();
  int rank(Map<String, dynamic> l) => isCurrentRenterLease(l, now: now)
      ? 0
      : l['yourAccessEndedOn'] == null &&
            const {
              'ACTIVE',
              'NOTICE_GIVEN',
              'PENDING_SIGNATURE',
              'DRAFT',
            }.contains(l['status'])
      ? 1
      : 2;
  rows.sort((a, b) {
    final c = rank(a).compareTo(rank(b));
    if (c != 0) return c;
    final dates = '${a['startDate'] ?? ''}'.compareTo(
      '${b['startDate'] ?? ''}',
    );
    return rank(a) == 2 ? -dates : dates;
  });
  return rows;
}

bool isLiveInstrument(Map<String, dynamic> row) => !const {
  'DRAFT',
  'CANCELLED',
  'REPLACED',
  'RETURNED',
  'TRANSFERRED',
}.contains(row['status']);

bool isRentInstrument(Map<String, dynamic> row) =>
    isLiveInstrument(row) && row['penaltyAssessmentId'] == null;

String renterPaymentStatus(Map<String, dynamic> row) =>
    row['overdue'] == true &&
        row['due'] == true &&
        ((row['payable'] as num?) ?? 0) > 0
    ? 'OVERDUE'
    : row['status']?.toString() ?? 'REGISTERED';

String paymentStatusLabel(String status, bool ar) => switch (status) {
  'REGISTERED' => ar ? 'مسجل' : 'Registered',
  'DRAFT' => ar ? 'مسودة' : 'Draft',
  'DEPOSITED' => ar ? 'لدى البنك' : 'At the bank',
  'CLEARED' => ar ? 'مسدد' : 'Cleared',
  'BOUNCED' => ar ? 'مرتجع' : 'Bounced',
  'OVERDUE' => ar ? 'متأخر' : 'Overdue',
  'ONLINE_PENDING' => ar ? 'دفع قيد الانتظار' : 'Checkout pending',
  'REPLACED' => ar ? 'مستبدل' : 'Replaced',
  'CANCELLED' => ar ? 'ملغى' : 'Cancelled',
  'RETURNED' => ar ? 'معاد' : 'Returned',
  'TRANSFERRED' => ar ? 'منقول' : 'Transferred',
  _ => status,
};

/// Why the bank returned a cheque (`ChequeFailureReason`), worded as the web's
/// `failureReason` labels.
String chequeFailureReasonLabel(String reason, bool ar) => switch (reason) {
  'BOUNCE' => ar ? 'رصيد غير كافٍ / أخرى' : 'Insufficient funds / other',
  'SIGNATURE_MISMATCH' => ar ? 'عدم تطابق التوقيع' : 'Signature mismatch',
  'ACCOUNT_CLOSED' => ar ? 'الحساب مغلق' : 'Account closed',
  'STOPPED_PAYMENT' => ar ? 'أُوقف الدفع' : 'Payment stopped',
  'TECHNICAL_RETURN' => ar ? 'إرجاع فني' : 'Technical return',
  _ => reason,
};

/// How a lease is paid (`PaymentMethod`).
String paymentMethodLabel(String method, bool ar) => switch (method) {
  'CHEQUE' => ar ? 'شيكات' : 'Cheques',
  'ONLINE' => ar ? 'دفع إلكتروني' : 'Online payment',
  'BANK_TRANSFER' => ar ? 'تحويل بنكي' : 'Bank transfer',
  'CASH' => ar ? 'نقداً' : 'Cash',
  _ => method,
};

bool canCancelRenterBooking(Map<String, dynamic> row, {DateTime? now}) {
  if (row['status'] == 'PENDING') return true;
  if (row['status'] != 'APPROVED' || row['resourceType'] != 'AMENITY') {
    return false;
  }
  final date = row['preferredDate']?.toString();
  if (date == null) return true;
  // The booking's wall clock is Dubai, not the phone's timezone.
  final start = DateTime.tryParse(
    '${date}T${row['preferredStartTime'] ?? '00:00:00'}+04:00',
  );
  return start != null && (now ?? DateTime.now()).isBefore(start);
}

/// The booking is over on Dubai's wall clock: a parking range after its last
/// day, an amenity slot after its end time (after its day when untimed).
bool isPastRenterBooking(Map<String, dynamic> row, {DateTime? now}) {
  final lastDay = row['preferredEndDate'] ?? row['preferredDate'];
  if (lastDay == null) return false;
  final endTime = row['preferredEndDate'] == null
      ? row['preferredEndTime']
      : null;
  final end = endTime == null
      ? DateTime.tryParse('${lastDay}T00:00:00+04:00')?.add(
          const Duration(days: 1),
        )
      : DateTime.tryParse('${lastDay}T$endTime+04:00');
  return end != null && !(now ?? DateTime.now()).isBefore(end);
}
