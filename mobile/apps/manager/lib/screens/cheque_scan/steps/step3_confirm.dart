import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
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
  }) onConfirm;
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
    _chequeNumberCtrl =
        TextEditingController(text: ex['chequeNumber']?.toString() ?? '');
    _bankNameCtrl =
        TextEditingController(text: ex['bankName']?.toString() ?? '');
    _payerNameCtrl =
        TextEditingController(text: ex['payerName']?.toString() ?? '');
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

  Future<void> _editField(String label, TextEditingController ctrl) async {
    final result = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (sheetCtx) => Padding(
        padding: EdgeInsets.only(
          left: 20,
          right: 20,
          top: 20,
          bottom: MediaQuery.of(sheetCtx).viewInsets.bottom + 20,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(label,
                style: GoogleFonts.sourceSerif4(
                    fontSize: 18, fontWeight: FontWeight.w600)),
            const SizedBox(height: 12),
            TextField(
              controller: ctrl,
              autofocus: true,
              decoration: const InputDecoration(border: OutlineInputBorder()),
            ),
            const SizedBox(height: 12),
            ElevatedButton(
              onPressed: () => Navigator.pop(sheetCtx, ctrl.text),
              child: const Text('Save'),
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

  String _formatDate(DateTime? d) =>
      d == null ? 'Not detected' : DateFormat('dd MMM yyyy').format(d);

  bool get _canConfirm =>
      widget.matchedPayment != null &&
      _chequeNumberCtrl.text.isNotEmpty &&
      _bankNameCtrl.text.isNotEmpty &&
      _payerNameCtrl.text.isNotEmpty &&
      _chequeDate != null &&
      !widget.submitting;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StepHeader(
          step: 3,
          title: 'Confirm details',
          onBack: widget.onBack,
          onClose: widget.onClose,
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _MatchCard(
                  matchedPayment: widget.matchedPayment,
                  onPick: widget.onPickPayment,
                ),
                const SizedBox(height: 18),
                _SectionLabel(text: 'Cheque details · tap to edit'),
                const SizedBox(height: 8),
                Container(
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    border: Border.all(color: AppColors.border),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    children: [
                      _EditRow(
                        label: 'Cheque #',
                        value: _chequeNumberCtrl.text.isEmpty
                            ? 'Not detected'
                            : _chequeNumberCtrl.text,
                        onTap: () => _editField('Cheque number', _chequeNumberCtrl),
                      ),
                      _EditRow(
                        label: 'Bank',
                        value: _bankNameCtrl.text.isEmpty
                            ? 'Not detected'
                            : _bankNameCtrl.text,
                        onTap: () => _editField('Bank name', _bankNameCtrl),
                      ),
                      _EditRow(
                        label: 'Date',
                        value: _formatDate(_chequeDate),
                        onTap: _editDate,
                      ),
                      _EditRow(
                        label: 'Payer',
                        value: _payerNameCtrl.text.isEmpty
                            ? 'Not detected'
                            : _payerNameCtrl.text,
                        onTap: () => _editField('Payer name', _payerNameCtrl),
                        isLast: true,
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 18),
                _SectionLabel(text: 'What to do next'),
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.all(4),
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    border: Border.all(color: AppColors.border),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    children: [
                      _DispositionRow(
                        icon: Icons.shield_outlined,
                        label: 'Hold in safe',
                        sub: 'Log only — deposit later',
                        selected: _disposition == Disposition.holdInSafe,
                        onTap: () => setState(
                            () => _disposition = Disposition.holdInSafe),
                      ),
                      _DispositionRow(
                        icon: Icons.arrow_forward,
                        label: 'Deposit today',
                        sub: 'Mark cheque as deposited',
                        selected: _disposition == Disposition.depositToday,
                        onTap: () => setState(
                            () => _disposition = Disposition.depositToday),
                      ),
                      _DispositionRow(
                        icon: Icons.calendar_today_outlined,
                        label: 'Schedule deposit',
                        sub: 'Coming soon',
                        selected: false,
                        disabled: true,
                        onTap: () {},
                      ),
                    ],
                  ),
                ),
                if (widget.submitError != null) ...[
                  const SizedBox(height: 12),
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: AppColors.dangerLight,
                      border: Border.all(color: AppColors.danger),
                      borderRadius: BorderRadius.circular(10),
                    ),
                    child: Text(
                      widget.submitError!,
                      style: GoogleFonts.inter(
                          fontSize: 12, color: AppColors.danger),
                    ),
                  ),
                ],
                const SizedBox(height: 18),
                SizedBox(
                  width: double.infinity,
                  height: 50,
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppColors.primary,
                      disabledBackgroundColor:
                          AppColors.primary.withValues(alpha: 0.5),
                      foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    onPressed: _canConfirm
                        ? () => widget.onConfirm(
                              chequeNumber: _chequeNumberCtrl.text,
                              bankName: _bankNameCtrl.text,
                              payerName: _payerNameCtrl.text,
                              chequeDate: _chequeDate,
                              disposition: _disposition,
                            )
                        : null,
                    child: widget.submitting
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              valueColor: AlwaysStoppedAnimation(Colors.white),
                            ),
                          )
                        : Text(
                            'Confirm & log cheque',
                            style: GoogleFonts.inter(
                                fontSize: 14, fontWeight: FontWeight.w600),
                          ),
                  ),
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

  const _MatchCard({required this.matchedPayment, required this.onPick});

  @override
  Widget build(BuildContext context) {
    final hasMatch = matchedPayment != null;
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
          Positioned(
            top: -40,
            right: -30,
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
                    child: const Icon(Icons.check,
                        size: 12, color: AppColors.primary),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    hasMatch ? 'PAYMENT SELECTED' : 'NO MATCH YET',
                    style: GoogleFonts.inter(
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                      color: AppColors.gold400,
                      letterSpacing: 0.7,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              if (hasMatch) ...[
                Text(
                  matchedPayment!['renterName']?.toString() ?? 'Unknown renter',
                  style: GoogleFonts.sourceSerif4(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  '${matchedPayment!['propertyName'] ?? ''} · ${matchedPayment!['unitIdentifier'] ?? ''}'
                      .trim(),
                  style: GoogleFonts.inter(
                    fontSize: 12.5,
                    color: Colors.white.withValues(alpha: 0.7),
                  ),
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    _MatchStat(
                      label: 'Installment',
                      value: '#${matchedPayment!['installmentNumber'] ?? '—'}',
                    ),
                    const SizedBox(width: 14),
                    _MatchStat(
                      label: 'Due',
                      value: _formatDueDate(
                          matchedPayment!['dueDate']?.toString()),
                    ),
                    const SizedBox(width: 14),
                    _MatchStat(
                      label: 'Expected',
                      value: 'AED ${_formatAmount(matchedPayment!['amount'])}',
                      gold: true,
                    ),
                  ],
                ),
              ] else ...[
                Text(
                  'Tap to pick which payment this cheque settles.',
                  style: GoogleFonts.inter(
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
                  label: const Text('Pick payment'),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  String _formatDueDate(String? iso) {
    if (iso == null || iso.isEmpty) return '—';
    final dt = DateTime.tryParse(iso);
    if (dt == null) return '—';
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
  const _MatchStat({
    required this.label,
    required this.value,
    this.gold = false,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: GoogleFonts.inter(
            fontSize: 11.5,
            color: Colors.white.withValues(alpha: 0.5),
          ),
        ),
        const SizedBox(height: 2),
        Text(
          value,
          style: GoogleFonts.inter(
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
  const _SectionLabel({required this.text});

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: GoogleFonts.inter(
        fontSize: 11,
        fontWeight: FontWeight.w600,
        letterSpacing: 0.6,
        color: AppColors.textMuted,
      ),
    );
  }
}

class _EditRow extends StatelessWidget {
  final String label;
  final String value;
  final VoidCallback onTap;
  final bool isLast;
  const _EditRow({
    required this.label,
    required this.value,
    required this.onTap,
    this.isLast = false,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        decoration: BoxDecoration(
          border: isLast
              ? null
              : const Border(bottom: BorderSide(color: AppColors.border)),
        ),
        child: Row(
          children: [
            Text(
              label,
              style: GoogleFonts.inter(
                  fontSize: 12, color: AppColors.textMuted),
            ),
            const Spacer(),
            Flexible(
              child: Text(
                value,
                textAlign: TextAlign.end,
                style: GoogleFonts.jetBrainsMono(
                  fontSize: 13.5,
                  fontWeight: FontWeight.w500,
                  color: value == 'Not detected'
                      ? AppColors.textMuted
                      : AppColors.textPrimary,
                ),
              ),
            ),
            const SizedBox(width: 6),
            const Icon(Icons.arrow_forward_ios,
                size: 12, color: AppColors.textMuted),
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

  const _DispositionRow({
    required this.icon,
    required this.label,
    required this.sub,
    required this.selected,
    required this.onTap,
    this.disabled = false,
  });

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: disabled ? 0.5 : 1.0,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: disabled ? null : onTap,
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 2),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          decoration: BoxDecoration(
            color: selected ? AppColors.surface2 : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            children: [
              Icon(icon,
                  size: 18,
                  color: selected
                      ? AppColors.primary
                      : AppColors.textSecondary),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      label,
                      style: GoogleFonts.inter(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: AppColors.textPrimary),
                    ),
                    Text(
                      sub,
                      style: GoogleFonts.inter(
                          fontSize: 11, color: AppColors.textMuted),
                    ),
                  ],
                ),
              ),
              Container(
                width: 18,
                height: 18,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: selected ? AppColors.primary : Colors.transparent,
                  border: Border.all(
                    color: selected
                        ? AppColors.primary
                        : AppColors.borderStrong,
                    width: 2,
                  ),
                ),
                child: selected
                    ? const Icon(Icons.check,
                        size: 10, color: AppColors.accent)
                    : null,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
