import 'dart:io';

import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../widgets/step_header.dart';

/// Step 2 — show the captured image while extraction runs, then reveal
/// fields progressively. The backend returns all fields at once, so the
/// reveal is a UX flourish (200ms stagger). When the result arrives the
/// parent advances to step 3 automatically.
class Step2Reading extends StatefulWidget {
  final File capturedImage;
  final ChequeExtractionResult? result; // null while loading
  final String? error;
  final VoidCallback onBack;
  final VoidCallback? onClose;

  const Step2Reading({
    super.key,
    required this.capturedImage,
    required this.result,
    required this.error,
    required this.onBack,
    this.onClose,
  });

  @override
  State<Step2Reading> createState() => _Step2ReadingState();
}

class _Step2ReadingState extends State<Step2Reading>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    );
    if (widget.result != null) _ctrl.forward();
  }

  @override
  void didUpdateWidget(covariant Step2Reading old) {
    super.didUpdateWidget(old);
    if (old.result == null && widget.result != null) _ctrl.forward();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StepHeader(
          step: 2,
          title: widget.error != null ? l.couldNotRead : l.reading,
          onBack: widget.onBack,
          onClose: widget.onClose,
        ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsetsDirectional.fromSTEB(20, 0, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(14),
                  child: Image.file(
                    widget.capturedImage,
                    fit: BoxFit.cover,
                    width: double.infinity,
                    height: 180,
                  ),
                ),
                const SizedBox(height: 18),
                Text(
                  l.ar
                      ? l.recognisingFields
                      : l.recognisingFields.toUpperCase(),
                  style: bodyFont(
                    fontSize: l.ar ? 12.5 : 11,
                    fontWeight: FontWeight.w600,
                    color: m.textMuted,
                    letterSpacing: l.ar ? 0 : 0.6,
                  ),
                ),
                const SizedBox(height: 8),
                _FieldsList(
                  result: widget.result,
                  error: widget.error,
                  controller: _ctrl,
                  l: l,
                ),
                const SizedBox(height: 18),
                if (widget.error == null)
                  Center(
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const SizedBox(
                          width: 12,
                          height: 12,
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            valueColor: AlwaysStoppedAnimation(
                              AppColors.accent,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          widget.result == null ? l.reading : l.preparing,
                          style: bodyFont(fontSize: 12, color: m.textMuted),
                        ),
                      ],
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

class _FieldsList extends StatelessWidget {
  final ChequeExtractionResult? result;
  final String? error;
  final AnimationController controller;
  final _L l;

  const _FieldsList({
    required this.result,
    required this.error,
    required this.controller,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final extracted = result?.extracted;
    final amount = result?.amount;
    final rows = <_Row>[
      _Row(l.fieldBank, extracted?['bankName']?.toString()),
      _Row(l.fieldChequeNumber, extracted?['chequeNumber']?.toString()),
      _Row(l.fieldDate, extracted?['chequeDate']?.toString()),
      _Row(l.fieldPayer, extracted?['payerName']?.toString()),
      _Row(
        l.fieldAmount,
        amount == null
            ? null
            : 'AED ${NumberFormat('#,##0.##').format(amount)}',
      ),
    ];
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        children: List.generate(rows.length, (i) {
          final row = rows[i];
          // Stagger reveal — each field at i * 280ms within the 1400ms window.
          final from = (i / rows.length).clamp(0.0, 1.0);
          final to = ((i + 1) / rows.length).clamp(0.0, 1.0);
          return AnimatedBuilder(
            animation: controller,
            builder: (context, _) {
              final t = ((controller.value - from) / (to - from)).clamp(
                0.0,
                1.0,
              );
              final done = result != null && t >= 1.0;
              return _FieldRow(
                label: row.label,
                value: row.value,
                done: done,
                isLast: i == rows.length - 1,
                hasError: error != null && i == 0,
                errorText: error,
                l: l,
              );
            },
          );
        }),
      ),
    );
  }
}

class _Row {
  final String label;
  final String? value;
  _Row(this.label, this.value);
}

class _FieldRow extends StatelessWidget {
  final String label;
  final String? value;
  final bool done;
  final bool isLast;
  final bool hasError;
  final String? errorText;
  final _L l;

  const _FieldRow({
    required this.label,
    required this.value,
    required this.done,
    required this.isLast,
    required this.hasError,
    required this.l,
    this.errorText,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.josefinSans;
    return Container(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: 12,
        vertical: 11,
      ),
      decoration: BoxDecoration(
        border: isLast ? null : Border(bottom: BorderSide(color: m.border)),
      ),
      child: Row(
        children: [
          Text(label, style: bodyFont(fontSize: 12, color: m.textMuted)),
          const Spacer(),
          if (hasError)
            Text(
              errorText ?? l.failed,
              style: bodyFont(fontSize: 12, color: m.danger),
            )
          else ...[
            Text(
              done && value != null && value!.isNotEmpty ? value! : '…',
              style: GoogleFonts.jetBrainsMono(
                fontSize: 13,
                fontWeight: FontWeight.w500,
                color: done ? m.textPrimary : m.textMuted,
              ),
            ),
            const SizedBox(width: 8),
            done
                ? Icon(Icons.check, size: 13, color: m.success)
                : SizedBox(
                    width: 13,
                    height: 13,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      valueColor: const AlwaysStoppedAnimation(
                        AppColors.accent,
                      ),
                      backgroundColor: m.border,
                    ),
                  ),
          ],
        ],
      ),
    );
  }
}

/// Step 2 strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
/// Field labels mirror web/messages/ar.json's `cheque.scanner.field*` keys.
class _L {
  _L(this.ar);
  final bool ar;

  String get couldNotRead => ar ? 'تعذّرت قراءة الشيك' : "Couldn't read cheque";
  String get reading => ar ? 'جارٍ قراءة الشيك…' : 'Reading cheque…';
  String get preparing =>
      ar ? 'جارٍ تجهيز التأكيد…' : 'Preparing confirmation…';
  String get recognisingFields =>
      ar ? 'التعرّف على الحقول' : 'Recognising fields';
  String get failed => ar ? 'فشل' : 'Failed';

  String get fieldBank => ar ? 'اسم البنك' : 'Bank';
  String get fieldChequeNumber => ar ? 'رقم الشيك' : 'Cheque #';
  String get fieldDate => ar ? 'تاريخ الشيك' : 'Date';
  String get fieldPayer => ar ? 'اسم الدافع' : 'Payer';
  String get fieldAmount => ar ? 'المبلغ' : 'Amount';
}
