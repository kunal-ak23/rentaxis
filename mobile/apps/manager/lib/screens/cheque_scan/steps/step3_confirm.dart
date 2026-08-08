import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart' hide TextDirection;
import 'package:rentaxis_core/rentaxis_core.dart';

import '../widgets/step_header.dart';

enum Disposition { holdInSafe, depositToday, scheduleDeposit }

/// Step 3 — show the matched payment, editable cheque fields, and disposition.
///
/// Match selection: if a paymentId was passed in, the parent populates
/// [matchedPayment]. Otherwise the user can tap "Pick payment" to select.
/// Backend doesn't have an auto-match endpoint yet, so this is manual.
class Step3Confirm extends StatefulWidget {
  final ChequeExtractionResult result;
  final Map<String, dynamic>? matchedPayment;
  final VoidCallback onPickPayment;
  final VoidCallback onBack;
  final VoidCallback? onClose;
  final void Function({
    required String chequeNumber,
    required String bankName,
    required String payerName,
    required DateTime? chequeDate,
    required Disposition disposition,
  })
  onConfirm;
  final bool submitting;
  final String? submitError;

  const Step3Confirm({
    super.key,
    required this.result,
    required this.matchedPayment,
    required this.onPickPayment,
    required this.onBack,
    required this.onConfirm,
    required this.submitting,
    required this.submitError,
    this.onClose,
  });

  @override
  State<Step3Confirm> createState() => _Step3ConfirmState();
}

class _Step3ConfirmState extends State<Step3Confirm> {
  late final TextEditingController _chequeNumberCtrl;
  late final TextEditingController _bankNameCtrl;
  late final TextEditingController _payerNameCtrl;
  DateTime? _chequeDate;
  Disposition _disposition = Disposition.depositToday;

  @override
  void initState() {
    super.initState();
    final ex = widget.result.extracted ?? const {};
    _chequeNumberCtrl = TextEditingController(
      text: ex['chequeNumber']?.toString() ?? '',
    );
    _bankNameCtrl = TextEditingController(
      text: ex['bankName']?.toString() ?? '',
    );
    _payerNameCtrl = TextEditingController(
      text: ex['payerName']?.toString() ?? '',
    );
    final dateStr = ex['chequeDate']?.toString();
    if (dateStr != null && dateStr.isNotEmpty) {
      _chequeDate = DateTime.tryParse(dateStr);
    }
  }

  @override
  void dispose() {
    _chequeNumberCtrl.dispose();
    _bankNameCtrl.dispose();
    _payerNameCtrl.dispose();
    super.dispose();
  }

  Future<void> _editField(
    String label,
    TextEditingController ctrl,
    _L l,
  ) async {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    final result = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      backgroundColor: m.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (sheetCtx) => Padding(
        padding: EdgeInsetsDirectional.only(
          start: 20,
          end: 20,
          top: 20,
          bottom: MediaQuery.of(sheetCtx).viewInsets.bottom + 20,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              label,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                      color: m.textPrimary,
                    )
                  : GoogleFonts.cinzel(
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                      color: m.textPrimary,
                    ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: ctrl,
              autofocus: true,
              textDirection: l.ar ? TextDirection.rtl : TextDirection.ltr,
              style: bodyFont(color: m.textPrimary),
              decoration: const InputDecoration(border: OutlineInputBorder()),
            ),
            const SizedBox(height: 12),
            GoldButton(
              label: l.save,
              onPressed: () => Navigator.pop(sheetCtx, ctrl.text),
            ),
          ],
        ),
      ),
    );
    if (result != null) setState(() {});
  }

  Future<void> _editDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _chequeDate ?? DateTime.now(),
      firstDate: DateTime.now().subtract(const Duration(days: 365 * 5)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 5)),
    );
    if (picked != null) setState(() => _chequeDate = picked);
  }

  String _formatDate(DateTime? d, _L l) =>
      d == null ? l.notDetected : DateFormat('dd MMM yyyy').format(d);

  bool get _canConfirm =>
      widget.matchedPayment != null &&
      _chequeNumberCtrl.text.isNotEmpty &&
      _bankNameCtrl.text.isNotEmpty &&
      _payerNameCtrl.text.isNotEmpty &&
      _chequeDate != null &&
      !widget.submitting;

  /// Human list of what still blocks the confirm button (empty when ready).
  List<String> _missingItems(_L l) => [
    if (widget.matchedPayment == null) l.missingPayment,
    if (_chequeNumberCtrl.text.isEmpty) l.missingChequeNumber,
    if (_bankNameCtrl.text.isEmpty) l.missingBankName,
    if (_chequeDate == null) l.missingChequeDate,
    if (_payerNameCtrl.text.isEmpty) l.missingPayerName,
  ];

  num? get _scannedAmount => widget.result.amount;

  num? get _expectedAmount {
    final raw = widget.matchedPayment?['amount'];
    if (raw == null) return null;
    return raw is num ? raw : num.tryParse(raw.toString());
  }

  /// Non-blocking, exact-to-the-cent comparison — mirrors the web's
  /// PaymentScheduleEditor mismatch chip.
  bool get _amountMismatch {
    final scanned = _scannedAmount;
    final expected = _expectedAmount;
    if (scanned == null || expected == null) return false;
    return (scanned * 100).round() != (expected * 100).round();
  }

  String _formatAed(num n) => 'AED ${NumberFormat('#,##0.##').format(n)}';

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    final missing = _missingItems(l);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StepHeader(
          step: 3,
          title: l.title,
          onBack: widget.onBack,
          onClose: widget.onClose,
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsetsDirectional.fromSTEB(20, 0, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _MatchCard(
                  matchedPayment: widget.matchedPayment,
                  onPick: widget.onPickPayment,
                  l: l,
                ),
                if (widget.result.confidence == 'LOW' ||
                    widget.result.warnings.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  _WarningsCard(
                    lowConfidence: widget.result.confidence == 'LOW',
                    warnings: widget.result.warnings,
                    l: l,
                  ),
                ],
                const SizedBox(height: 18),
                _SectionLabel(text: l.chequeDetailsHeader, l: l),
                const SizedBox(height: 8),
                Container(
                  decoration: BoxDecoration(
                    color: m.surface,
                    border: Border.all(color: m.border),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    children: [
                      _EditRow(
                        label: l.fieldChequeNumber,
                        value: _chequeNumberCtrl.text.isEmpty
                            ? l.notDetected
                            : _chequeNumberCtrl.text,
                        notDetected: _chequeNumberCtrl.text.isEmpty,
                        onTap: () => _editField(
                          l.fieldChequeNumber,
                          _chequeNumberCtrl,
                          l,
                        ),
                        l: l,
                      ),
                      _EditRow(
                        label: l.fieldBankName,
                        value: _bankNameCtrl.text.isEmpty
                            ? l.notDetected
                            : _bankNameCtrl.text,
                        notDetected: _bankNameCtrl.text.isEmpty,
                        onTap: () =>
                            _editField(l.fieldBankName, _bankNameCtrl, l),
                        l: l,
                      ),
                      _EditRow(
                        label: l.fieldChequeDate,
                        value: _formatDate(_chequeDate, l),
                        notDetected: _chequeDate == null,
                        onTap: _editDate,
                        l: l,
                      ),
                      _EditRow(
                        label: l.fieldPayerName,
                        value: _payerNameCtrl.text.isEmpty
                            ? l.notDetected
                            : _payerNameCtrl.text,
                        notDetected: _payerNameCtrl.text.isEmpty,
                        onTap: () =>
                            _editField(l.fieldPayerName, _payerNameCtrl, l),
                        isLast: _scannedAmount == null,
                        l: l,
                      ),
                      if (_scannedAmount != null)
                        _StaticRow(
                          label: l.scannedAmountLabel,
                          value: _formatAed(_scannedAmount!),
                          isLast: true,
                          l: l,
                        ),
                    ],
                  ),
                ),
                if (_amountMismatch) ...[
                  const SizedBox(height: 8),
                  _MismatchChip(
                    text: l.amountMismatch(
                      _formatAed(_scannedAmount!),
                      _formatAed(_expectedAmount!),
                    ),
                    l: l,
                  ),
                ],
                const SizedBox(height: 18),
                _SectionLabel(text: l.whatNext, l: l),
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.all(4),
                  decoration: BoxDecoration(
                    color: m.surface,
                    border: Border.all(color: m.border),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    children: [
                      _DispositionRow(
                        icon: Icons.shield_outlined,
                        label: l.holdInSafe,
                        sub: l.holdInSafeSub,
                        selected: _disposition == Disposition.holdInSafe,
                        onTap: () => setState(
                          () => _disposition = Disposition.holdInSafe,
                        ),
                        l: l,
                      ),
                      _DispositionRow(
                        icon: l.ar ? Icons.arrow_back : Icons.arrow_forward,
                        label: l.depositToday,
                        sub: l.depositTodaySub,
                        selected: _disposition == Disposition.depositToday,
                        onTap: () => setState(
                          () => _disposition = Disposition.depositToday,
                        ),
                        l: l,
                      ),
                      _DispositionRow(
                        icon: Icons.calendar_today_outlined,
                        label: l.scheduleDeposit,
                        sub: l.comingSoon,
                        selected: false,
                        disabled: true,
                        onTap: () {},
                        l: l,
                      ),
                    ],
                  ),
                ),
                if (widget.submitError != null) ...[
                  const SizedBox(height: 12),
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: m.dangerBg,
                      border: Border.all(color: m.danger),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(
                      widget.submitError!,
                      style: bodyFont(fontSize: 12, color: m.danger),
                    ),
                  ),
                ],
                if (!widget.submitting && missing.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Text(
                    l.toContinue(missing.join(l.ar ? '، ' : ' · ')),
                    style: bodyFont(fontSize: 12, color: m.textMuted),
                  ),
                ],
                const SizedBox(height: 18),
                GoldButton(
                  height: 50,
                  label: l.confirmAndLog,
                  icon: widget.submitting
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            valueColor: AlwaysStoppedAnimation(
                              AppColors.primary,
                            ),
                          ),
                        )
                      : null,
                  onPressed: _canConfirm
                      ? () => widget.onConfirm(
                          chequeNumber: _chequeNumberCtrl.text,
                          bankName: _bankNameCtrl.text,
                          payerName: _payerNameCtrl.text,
                          chequeDate: _chequeDate,
                          disposition: _disposition,
                        )
                      : null,
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _MatchCard extends StatelessWidget {
  final Map<String, dynamic>? matchedPayment;
  final VoidCallback onPick;
  final _L l;

  const _MatchCard({
    required this.matchedPayment,
    required this.onPick,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final hasMatch = matchedPayment != null;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.primary, AppColors.primaryLight],
        ),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Stack(
        children: [
          PositionedDirectional(
            top: -40,
            end: -30,
            child: Container(
              width: 140,
              height: 140,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: [
                    AppColors.accent.withValues(alpha: 0.3),
                    Colors.transparent,
                  ],
                ),
              ),
            ),
          ),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 22,
                    height: 22,
                    decoration: const BoxDecoration(
                      shape: BoxShape.circle,
                      color: AppColors.accent,
                    ),
                    child: const Icon(
                      Icons.check,
                      size: 12,
                      color: AppColors.primary,
                    ),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    l.ar
                        ? (hasMatch ? l.paymentSelected : l.noMatchYet)
                        : (hasMatch ? l.paymentSelected : l.noMatchYet)
                              .toUpperCase(),
                    style: bodyFont(
                      fontSize: l.ar ? 12.5 : 11,
                      fontWeight: FontWeight.w700,
                      color: AppColors.gold400,
                      letterSpacing: l.ar ? 0 : 0.7,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              if (hasMatch) ...[
                Text(
                  matchedPayment!['renterName']?.toString() ?? l.unknownRenter,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                          color: Colors.white,
                        )
                      : GoogleFonts.cinzel(
                          fontSize: 18,
                          fontWeight: FontWeight.w500,
                          color: Colors.white,
                        ),
                ),
                const SizedBox(height: 2),
                Text(
                  '${matchedPayment!['propertyName'] ?? ''} · ${matchedPayment!['unitIdentifier'] ?? ''}'
                      .trim(),
                  style: bodyFont(
                    fontSize: 12.5,
                    color: Colors.white.withValues(alpha: 0.7),
                  ),
                ),
                const SizedBox(height: 14),
                // Expanded stats so long Arabic month names can't overflow
                // the card on narrow screens.
                Row(
                  children: [
                    Expanded(
                      child: _MatchStat(
                        label: l.installment,
                        value:
                            '#${matchedPayment!['installmentNumber'] ?? '—'}',
                        l: l,
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: _MatchStat(
                        label: l.due,
                        value: _formatDueDate(
                          matchedPayment!['dueDate']?.toString(),
                          l,
                        ),
                        l: l,
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: _MatchStat(
                        label: l.expected,
                        value:
                            'AED ${_formatAmount(matchedPayment!['amount'])}',
                        gold: true,
                        l: l,
                      ),
                    ),
                  ],
                ),
              ] else ...[
                Text(
                  l.pickPaymentHint,
                  style: bodyFont(
                    fontSize: 13,
                    color: Colors.white.withValues(alpha: 0.7),
                  ),
                ),
                const SizedBox(height: 14),
                OutlinedButton.icon(
                  onPressed: onPick,
                  style: OutlinedButton.styleFrom(
                    side: const BorderSide(color: AppColors.gold400),
                    foregroundColor: AppColors.gold400,
                  ),
                  icon: const Icon(Icons.touch_app, size: 16),
                  label: Text(l.pickPayment, style: bodyFont()),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  String _formatDueDate(String? iso, _L l) {
    if (iso == null || iso.isEmpty) return '—';
    final dt = DateTime.tryParse(iso);
    if (dt == null) return '—';
    if (l.ar) return DateFormat('d MMMM', 'ar').format(dt);
    return DateFormat('d MMM').format(dt);
  }

  String _formatAmount(dynamic amount) {
    if (amount == null) return '—';
    final n = amount is num ? amount : num.tryParse(amount.toString());
    if (n == null) return '—';
    return NumberFormat('#,##0').format(n);
  }
}

class _MatchStat extends StatelessWidget {
  final String label;
  final String value;
  final bool gold;
  final _L l;
  const _MatchStat({
    required this.label,
    required this.value,
    required this.l,
    this.gold = false,
  });

  @override
  Widget build(BuildContext context) {
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: bodyFont(
            fontSize: 11.5,
            color: Colors.white.withValues(alpha: 0.5),
          ),
        ),
        const SizedBox(height: 2),
        Text(
          value,
          style: bodyFont(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: gold ? AppColors.gold400 : Colors.white,
          ),
        ),
      ],
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String text;
  final _L l;
  const _SectionLabel({required this.text, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Text(
      text,
      style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
        fontSize: l.ar ? 12.5 : 11,
        fontWeight: FontWeight.w600,
        letterSpacing: l.ar ? 0 : 0.6,
        color: m.textMuted,
      ),
    );
  }
}

/// Amber, non-blocking banner mirroring the web scanner's low-confidence
/// banner + warning list.
class _WarningsCard extends StatelessWidget {
  final bool lowConfidence;
  final List<String> warnings;
  final _L l;

  const _WarningsCard({
    required this.lowConfidence,
    required this.warnings,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    final lines = <String>[
      if (lowConfidence) l.lowConfidenceBanner,
      ...warnings,
    ];
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: m.warningBg,
        border: Border.all(color: m.warning.withValues(alpha: 0.5)),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var i = 0; i < lines.length; i++) ...[
            if (i > 0) const SizedBox(height: 6),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.warning_amber_rounded, size: 15, color: m.warning),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    lines[i],
                    style: bodyFont(fontSize: 12, color: m.warning),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

/// Read-only detail row (no chevron / tap target).
class _StaticRow extends StatelessWidget {
  final String label;
  final String value;
  final bool isLast;
  final _L l;
  const _StaticRow({
    required this.label,
    required this.value,
    required this.l,
    this.isLast = false,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return Container(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: 14,
        vertical: 13,
      ),
      decoration: BoxDecoration(
        border: isLast ? null : Border(bottom: BorderSide(color: m.border)),
      ),
      child: Row(
        children: [
          Text(label, style: bodyFont(fontSize: 12, color: m.textMuted)),
          const Spacer(),
          Flexible(
            child: Text(
              value,
              textAlign: l.ar ? TextAlign.start : TextAlign.end,
              style: GoogleFonts.jetBrainsMono(
                fontSize: 13.5,
                fontWeight: FontWeight.w500,
                color: m.textPrimary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Amber pill matching the web's amount-mismatch chip. Informational only —
/// it never blocks the confirm action.
class _MismatchChip extends StatelessWidget {
  final String text;
  final _L l;
  const _MismatchChip({required this.text, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Align(
      alignment: AlignmentDirectional.centerStart,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: m.warningBg,
          border: Border.all(color: m.warning.withValues(alpha: 0.5)),
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          text,
          style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
            fontSize: 11,
            color: m.warning,
          ),
        ),
      ),
    );
  }
}

class _EditRow extends StatelessWidget {
  final String label;
  final String value;
  final bool notDetected;
  final VoidCallback onTap;
  final bool isLast;
  final _L l;
  const _EditRow({
    required this.label,
    required this.value,
    required this.onTap,
    required this.l,
    this.notDetected = false,
    this.isLast = false,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return InkWell(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsetsDirectional.symmetric(
          horizontal: 14,
          vertical: 13,
        ),
        decoration: BoxDecoration(
          border: isLast ? null : Border(bottom: BorderSide(color: m.border)),
        ),
        child: Row(
          children: [
            Text(label, style: bodyFont(fontSize: 12, color: m.textMuted)),
            const Spacer(),
            Flexible(
              child: Text(
                value,
                textAlign: l.ar ? TextAlign.start : TextAlign.end,
                style: GoogleFonts.jetBrainsMono(
                  fontSize: 13.5,
                  fontWeight: FontWeight.w500,
                  color: notDetected ? m.textMuted : m.textPrimary,
                ),
              ),
            ),
            const SizedBox(width: 6),
            Icon(
              l.ar ? Icons.arrow_back_ios : Icons.arrow_forward_ios,
              size: 12,
              color: m.textMuted,
            ),
          ],
        ),
      ),
    );
  }
}

class _DispositionRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String sub;
  final bool selected;
  final bool disabled;
  final VoidCallback onTap;
  final _L l;

  const _DispositionRow({
    required this.icon,
    required this.label,
    required this.sub,
    required this.selected,
    required this.onTap,
    required this.l,
    this.disabled = false,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return Opacity(
      opacity: disabled ? 0.5 : 1.0,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: disabled ? null : onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOut,
          margin: const EdgeInsets.symmetric(vertical: 2),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          decoration: BoxDecoration(
            color: selected ? m.surfaceAlt : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            children: [
              Icon(
                icon,
                size: 18,
                color: selected ? AppColors.accentDark : m.textSecondary,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      label,
                      style: bodyFont(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      ),
                    ),
                    Text(
                      sub,
                      style: bodyFont(fontSize: 11, color: m.textMuted),
                    ),
                  ],
                ),
              ),
              Container(
                width: 18,
                height: 18,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: selected ? MiftahGradients.gold : null,
                  border: Border.all(
                    color: selected ? AppColors.accent : m.borderStrong,
                    width: 2,
                  ),
                ),
                child: selected
                    ? const Icon(
                        Icons.check,
                        size: 10,
                        color: AppColors.primary,
                      )
                    : null,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Step 3 strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
/// Field labels mirror web/messages/ar.json's `cheque.scanner.field*` keys.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'تأكيد التفاصيل' : 'Confirm details';
  String get save => ar ? 'حفظ' : 'Save';
  String get notDetected => ar ? 'غير مكتشف' : 'Not detected';

  String get chequeDetailsHeader =>
      ar ? 'تفاصيل الشيك · اضغط للتعديل' : 'Cheque details · tap to edit';
  String get whatNext => ar ? 'ماذا تريد أن تفعل' : 'What to do next';

  String get fieldChequeNumber => ar ? 'رقم الشيك' : 'Cheque #';
  String get fieldBankName => ar ? 'اسم البنك' : 'Bank';
  String get fieldChequeDate => ar ? 'تاريخ الشيك' : 'Date';
  String get fieldPayerName => ar ? 'اسم الدافع' : 'Payer';
  String get scannedAmountLabel =>
      ar ? 'المبلغ (من الشيك)' : 'Amount (scanned)';

  String amountMismatch(String scanned, String expected) => ar
      ? 'الشيك $scanned ≠ المتوقع $expected'
      : 'Cheque $scanned ≠ expected $expected';

  String get holdInSafe => ar ? 'إبقاء في الخزنة' : 'Hold in safe';
  String get holdInSafeSub =>
      ar ? 'تسجيل فقط — إيداع لاحقاً' : 'Log only — deposit later';
  String get depositToday => ar ? 'إيداع اليوم' : 'Deposit today';
  String get depositTodaySub =>
      ar ? 'تحديد الشيك كمودَع' : 'Mark cheque as deposited';
  String get scheduleDeposit => ar ? 'جدولة الإيداع' : 'Schedule deposit';
  String get comingSoon => ar ? 'قريباً' : 'Coming soon';

  String get paymentSelected => ar ? 'تم اختيار الدفعة' : 'Payment selected';
  String get noMatchYet => ar ? 'لا تطابق بعد' : 'No match yet';
  String get unknownRenter => ar ? 'مستأجر غير معروف' : 'Unknown renter';
  String get pickPaymentHint => ar
      ? 'اضغط لاختيار الدفعة التي يسدّدها هذا الشيك.'
      : 'Tap to pick which payment this cheque settles.';
  String get pickPayment => ar ? 'اختيار دفعة' : 'Pick payment';

  String get installment => ar ? 'القسط' : 'Installment';
  String get due => ar ? 'الاستحقاق' : 'Due';
  String get expected => ar ? 'المتوقع' : 'Expected';

  String get lowConfidenceBanner => ar
      ? 'لم يتمكن الذكاء الاصطناعي من القراءة بوضوح — يرجى التحقق من القيم.'
      : "AI couldn't read this clearly — please double-check the values.";

  String get missingPayment => ar ? 'اختيار دفعة' : 'pick a payment';
  String get missingChequeNumber => ar ? 'رقم الشيك' : 'cheque number';
  String get missingBankName => ar ? 'اسم البنك' : 'bank name';
  String get missingChequeDate => ar ? 'تاريخ الشيك' : 'cheque date';
  String get missingPayerName => ar ? 'اسم الدافع' : 'payer name';
  String toContinue(String items) =>
      ar ? 'للمتابعة، أضف: $items' : 'To continue, add: $items';

  String get confirmAndLog =>
      ar ? 'تأكيد وتسجيل الشيك' : 'Confirm & log cheque';
}
