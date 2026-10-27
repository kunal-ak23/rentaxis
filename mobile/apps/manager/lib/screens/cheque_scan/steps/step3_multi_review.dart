import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart' hide TextDirection;
import 'package:rentaxis_core/rentaxis_core.dart';

import '../cheque_register_api.dart';
import '../widgets/payee_check_notice.dart';
import '../widgets/scan_messages.dart';
import '../widgets/step_header.dart';

/// One cheque found in a multi-cheque photo, as the operator is reviewing it.
class MultiScanRow {
  MultiScanRow(this.scan)
    : chequeNumber = scan.extracted?['chequeNumber']?.toString() ?? '',
      bankName = scan.extracted?['bankName']?.toString() ?? '',
      payerName = scan.extracted?['payerName']?.toString() ?? '',
      chequeDate = DateTime.tryParse(
        scan.extracted?['chequeDate']?.toString() ?? '',
      );

  final ChequeExtractionResult scan;
  String chequeNumber;
  String bankName;
  String payerName;
  DateTime? chequeDate;

  /// The register row this cheque goes onto, or null.
  String? rowId;

  /// Picked by hand: the auto-map leaves it alone.
  bool pinned = false;
  bool payeeConfirmed = false;
  bool cropConfirmed = false;

  /// Attach anyway, though the row's amount differs; asked again whenever
  /// the row changes.
  bool amountConfirmed = false;

  bool get payeeBlocks =>
      scan.payeeCheck == PayeeCheck.mismatch && !payeeConfirmed;
  bool get cropBlocks => scan.cropUnreliable && !cropConfirmed;
}

/// Step 3 for a photo or PDF that held several cheques: one card per cheque
/// (its own crop, fields, payee check and target instalment), all attached to
/// one lease's register rows in a single all-or-nothing bulk-attach — the
/// same flow as the web's BulkChequeUploadFlow.
class Step3MultiReview extends StatefulWidget {
  final ChequeMultiExtractionResult scan;

  /// Opens the register picker; any row picked names the lease.
  final Future<Map<String, dynamic>?> Function() onPickLease;

  /// Every row of one lease (`GET /v1/leases/{id}/cheques`).
  final Future<List<Map<String, dynamic>>> Function(String leaseId)
  loadLeaseRows;

  /// Submit: the lease, the `BulkAttachChequeItem` maps, and one of the
  /// lease's rows (for the success screen's property / unit line).
  final void Function(
    String leaseId,
    List<Map<String, dynamic>> items,
    Map<String, dynamic> leaseRow,
  )
  onSubmit;
  final bool submitting;
  final String? submitError;
  final VoidCallback onBack;
  final VoidCallback? onClose;

  const Step3MultiReview({
    super.key,
    required this.scan,
    required this.onPickLease,
    required this.loadLeaseRows,
    required this.onSubmit,
    required this.submitting,
    required this.submitError,
    required this.onBack,
    this.onClose,
  });

  @override
  State<Step3MultiReview> createState() => _Step3MultiReviewState();
}

class _Step3MultiReviewState extends State<Step3MultiReview> {
  late final List<MultiScanRow> _rows = widget.scan.items
      .map(MultiScanRow.new)
      .toList();
  Map<String, dynamic>? _leaseRow;
  List<Map<String, dynamic>> _eligible = const [];
  bool _loadingRows = false;
  String? _loadError;

  Future<void> _pickLease() async {
    final picked = await widget.onPickLease();
    if (picked != null && mounted) await _selectLease(picked);
  }

  Future<void> _selectLease(Map<String, dynamic> row) async {
    final leaseId = row['leaseId']?.toString();
    if (leaseId == null) return;
    setState(() {
      _leaseRow = row;
      _loadingRows = true;
      _loadError = null;
      for (final r in _rows) {
        r.rowId = null;
        r.pinned = false;
        r.amountConfirmed = false;
      }
    });
    try {
      final all = await widget.loadLeaseRows(leaseId);
      // Another lease was picked while this one loaded.
      if (!mounted || !identical(_leaseRow, row)) return;
      final eligible = all.where(isScannableRow).toList()
        ..sort(
          (a, b) => (asNum(a['seqNo']) ?? 0).compareTo(asNum(b['seqNo']) ?? 0),
        );
      setState(() {
        _eligible = eligible;
        _loadingRows = false;
      });
      _remap();
    } catch (e) {
      if (!mounted || !identical(_leaseRow, row)) return;
      setState(() {
        _loadingRows = false;
        _loadError = errorMessage(e, networkErrorMessage(context.isAr));
      });
    }
  }

  void _remap() {
    final map = autoMapScansToRows(
      items: [
        for (final r in _rows)
          (
            date: r.chequeDate,
            amount: r.scan.amount,
            pinnedRowId: r.pinned ? r.rowId : null,
          ),
      ],
      rows: _eligible,
    );
    setState(() {
      for (var i = 0; i < _rows.length; i++) {
        final r = _rows[i];
        if (r.pinned || r.rowId == map[i]) continue;
        r.rowId = map[i];
        r.amountConfirmed = false;
      }
    });
  }

  Map<String, dynamic>? _rowById(String? id) {
    if (id == null) return null;
    for (final r in _eligible) {
      if (r['id']?.toString() == id) return r;
    }
    return null;
  }

  Set<String> get _duplicateNumbers {
    final seen = <String, int>{};
    for (final r in _rows) {
      final n = r.chequeNumber.trim();
      if (n.isNotEmpty) seen[n] = (seen[n] ?? 0) + 1;
    }
    return {
      for (final e in seen.entries)
        if (e.value > 1) e.key,
    };
  }

  bool _amountBlocks(MultiScanRow r) =>
      amountsDiffer(r.scan.amount, asNum(_rowById(r.rowId)?['amount'])) &&
      !r.amountConfirmed;

  bool _ready(MultiScanRow r, Set<String> dupes) =>
      r.chequeNumber.trim().isNotEmpty &&
      !dupes.contains(r.chequeNumber.trim()) &&
      r.bankName.trim().isNotEmpty &&
      r.chequeDate != null &&
      r.rowId != null &&
      r.scan.imageBlobPath.isNotEmpty &&
      !r.payeeBlocks &&
      !_amountBlocks(r) &&
      !r.cropBlocks;

  bool get _canSubmit {
    if (_leaseRow == null || _rows.isEmpty || widget.submitting) return false;
    final dupes = _duplicateNumbers;
    return _rows.every((r) => _ready(r, dupes));
  }

  void _submit() {
    final lease = _leaseRow;
    if (lease == null) return;
    final items = [
      for (final r in _rows)
        buildAttachItem(
          chequeId: r.rowId!,
          scan: r.scan,
          chequeNumber: r.chequeNumber,
          bankName: r.bankName,
          payerName: r.payerName,
          chequeDate: r.chequeDate!,
          payeeConfirmed: r.payeeConfirmed,
        ),
    ];
    widget.onSubmit(lease['leaseId'].toString(), items, lease);
  }

  Future<void> _editText(
    String label,
    String initial,
    ValueChanged<String> onSaved,
  ) async {
    final m = context.miftah;
    final l = _ML(context.isAr);
    final ctrl = TextEditingController(text: initial);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.plusJakartaSans;
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
              style: bodyFont(
                fontSize: 18,
                fontWeight: FontWeight.w600,
                color: m.textPrimary,
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: ctrl,
              autofocus: true,
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
    ctrl.dispose();
    if (result != null && mounted) setState(() => onSaved(result));
  }

  Future<void> _editDate(MultiScanRow r) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: r.chequeDate ?? DateTime.now(),
      firstDate: DateTime.now().subtract(const Duration(days: 365 * 5)),
      lastDate: DateTime.now().add(const Duration(days: 365 * 5)),
    );
    if (picked == null || !mounted) return;
    setState(() => r.chequeDate = picked);
    // A new date is a new match, exactly as on the web.
    if (!r.pinned) _remap();
  }

  void _removeRow(MultiScanRow r) {
    setState(() => _rows.remove(r));
    _remap();
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _ML(context.isAr);
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.plusJakartaSans;
    final dupes = _duplicateNumbers;
    final ready = _rows.where((r) => _ready(r, dupes)).length;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        StepHeader(
          step: 3,
          title: l.title(_rows.length),
          onBack: widget.onBack,
          onClose: widget.onClose,
        ),
        Expanded(
          child: ListView(
            key: const ValueKey('multiReviewList'),
            padding: const EdgeInsetsDirectional.fromSTEB(20, 0, 20, 24),
            children: [
              _LeaseCard(
                leaseRow: _leaseRow,
                loading: _loadingRows,
                error: _loadError,
                noEligible:
                    _leaseRow != null && !_loadingRows && _eligible.isEmpty,
                onPick: _pickLease,
                l: l,
              ),
              if (widget.scan.warnings.isNotEmpty) ...[
                const SizedBox(height: 10),
                for (final w in widget.scan.warnings)
                  Text(w, style: bodyFont(fontSize: 11.5, color: m.warning)),
              ],
              for (var i = 0; i < _rows.length; i++) ...[
                const SizedBox(height: 14),
                _ChequeCard(
                  key: ValueKey('multiCheque$i'),
                  index: i,
                  total: _rows.length,
                  row: _rows[i],
                  eligible: _eligible,
                  takenRowIds: {
                    for (final o in _rows)
                      if (!identical(o, _rows[i]) && o.rowId != null) o.rowId!,
                  },
                  duplicateNumber: dupes.contains(_rows[i].chequeNumber.trim()),
                  target: _rowById(_rows[i].rowId),
                  onEditNumber: () => _editText(
                    l.fieldChequeNumber,
                    _rows[i].chequeNumber,
                    (v) => _rows[i].chequeNumber = v.trim(),
                  ),
                  onEditBank: () => _editText(
                    l.fieldBankName,
                    _rows[i].bankName,
                    (v) => _rows[i].bankName = v.trim(),
                  ),
                  onEditPayer: () => _editText(
                    l.fieldPayerName,
                    _rows[i].payerName,
                    (v) => _rows[i].payerName = v.trim(),
                  ),
                  onEditDate: () => _editDate(_rows[i]),
                  onPickRow: (id) => setState(() {
                    _rows[i].rowId = id;
                    _rows[i].pinned = id != null;
                    _rows[i].amountConfirmed = false;
                  }),
                  onAmountConfirmed: (v) =>
                      setState(() => _rows[i].amountConfirmed = v),
                  onPayeeConfirmed: (v) =>
                      setState(() => _rows[i].payeeConfirmed = v),
                  onCropConfirmed: (v) =>
                      setState(() => _rows[i].cropConfirmed = v),
                  onRemove: _rows.length > 1
                      ? () => _removeRow(_rows[i])
                      : null,
                  l: l,
                ),
              ],
              if (widget.submitError != null) ...[
                const SizedBox(height: 12),
                Container(
                  key: const ValueKey('multiSubmitError'),
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
              const SizedBox(height: 14),
              Text(
                l.readyCount(ready, _rows.length),
                style: bodyFont(fontSize: 12, color: m.textMuted),
              ),
              const SizedBox(height: 10),
              GoldButton(
                key: const ValueKey('multiAttachButton'),
                height: 50,
                label: l.attachAll(_rows.length),
                icon: widget.submitting
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          valueColor: AlwaysStoppedAnimation(AppColors.primary),
                        ),
                      )
                    : null,
                onPressed: _canSubmit ? _submit : null,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _LeaseCard extends StatelessWidget {
  final Map<String, dynamic>? leaseRow;
  final bool loading;
  final String? error;
  final bool noEligible;
  final VoidCallback onPick;
  final _ML l;

  const _LeaseCard({
    required this.leaseRow,
    required this.loading,
    required this.error,
    required this.noEligible,
    required this.onPick,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.plusJakartaSans;
    final row = leaseRow;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.leaseLabel,
            style: bodyFont(
              fontSize: 11.5,
              fontWeight: FontWeight.w600,
              color: m.textMuted,
            ),
          ),
          const SizedBox(height: 4),
          if (row == null)
            Text(
              l.pickLeaseHint,
              style: bodyFont(fontSize: 12.5, color: m.textSecondary),
            )
          else ...[
            Text(
              row['renterName']?.toString() ?? '—',
              style: bodyFont(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: m.textPrimary,
              ),
            ),
            Text(
              '${row['propertyName'] ?? ''} · ${row['unitIdentifier'] ?? ''}',
              style: bodyFont(fontSize: 12, color: m.textMuted),
            ),
          ],
          if (loading) ...[
            const SizedBox(height: 8),
            const LinearProgressIndicator(minHeight: 2),
          ],
          if (error != null) ...[
            const SizedBox(height: 8),
            Text(
              l.loadRowsFailed(error!),
              style: bodyFont(fontSize: 12, color: m.danger),
            ),
          ],
          if (noEligible) ...[
            const SizedBox(height: 8),
            Text(
              l.noEligibleRows,
              style: bodyFont(fontSize: 12, color: m.warning),
            ),
          ],
          const SizedBox(height: 8),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: OutlinedButton.icon(
              key: const ValueKey('multiPickLease'),
              onPressed: onPick,
              icon: const Icon(Icons.touch_app, size: 16),
              label: Text(
                row == null ? l.pickLease : l.changeLease,
                style: bodyFont(),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ChequeCard extends StatelessWidget {
  final int index;
  final int total;
  final MultiScanRow row;
  final List<Map<String, dynamic>> eligible;
  final Set<String> takenRowIds;
  final bool duplicateNumber;
  final Map<String, dynamic>? target;
  final VoidCallback onEditNumber;
  final VoidCallback onEditBank;
  final VoidCallback onEditPayer;
  final VoidCallback onEditDate;
  final ValueChanged<String?> onPickRow;
  final ValueChanged<bool> onPayeeConfirmed;
  final ValueChanged<bool> onCropConfirmed;
  final ValueChanged<bool> onAmountConfirmed;
  final VoidCallback? onRemove;
  final _ML l;

  const _ChequeCard({
    super.key,
    required this.index,
    required this.total,
    required this.row,
    required this.eligible,
    required this.takenRowIds,
    required this.duplicateNumber,
    required this.target,
    required this.onEditNumber,
    required this.onEditBank,
    required this.onEditPayer,
    required this.onEditDate,
    required this.onPickRow,
    required this.onPayeeConfirmed,
    required this.onCropConfirmed,
    required this.onAmountConfirmed,
    required this.onRemove,
    required this.l,
  });

  Uint8List? _thumb() {
    final url = row.scan.thumbnailUrl;
    if (url == null) return null;
    final comma = url.indexOf(',');
    if (comma < 0) return null;
    try {
      return base64Decode(url.substring(comma + 1));
    } catch (_) {
      return null;
    }
  }

  String _rowLabel(Map<String, dynamic> r) {
    final date = DateTime.tryParse(rowChequeDate(r) ?? '');
    final d = date == null
        ? '—'
        : (l.ar
              ? DateFormat('d MMM yyyy', 'ar').format(date)
              : DateFormat('d MMM yyyy').format(date));
    final amt = asNum(r['amount']);
    final a = amt == null ? '' : ' · AED ${NumberFormat('#,##0').format(amt)}';
    return '#${r['seqNo'] ?? '—'} · $d$a';
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.plusJakartaSans;
    final thumb = _thumb();
    final scanned = row.scan.amount;
    final expected = asNum(target?['amount']);
    final amountOff = amountsDiffer(scanned, expected);

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(6),
                child: thumb == null
                    ? Container(
                        width: 72,
                        height: 34,
                        color: m.surfaceAlt,
                        child: Icon(
                          Icons.image_outlined,
                          size: 18,
                          color: m.textMuted,
                        ),
                      )
                    : Image.memory(
                        thumb,
                        width: 72,
                        height: 34,
                        fit: BoxFit.cover,
                        errorBuilder: (_, _, _) =>
                            const SizedBox(width: 72, height: 34),
                      ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  l.chequeOf(index + 1, total),
                  style: bodyFont(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
                ),
              ),
              if (onRemove != null)
                IconButton(
                  tooltip: l.remove,
                  onPressed: onRemove,
                  icon: Icon(
                    Icons.delete_outline,
                    size: 18,
                    color: m.textMuted,
                  ),
                ),
            ],
          ),
          if (row.scan.nothingRead) ...[
            const SizedBox(height: 6),
            Text(
              l.nothingRead,
              style: bodyFont(fontSize: 11.5, color: m.warning),
            ),
          ],
          if (row.scan.cropUnreliable) ...[
            const SizedBox(height: 6),
            Container(
              padding: const EdgeInsetsDirectional.fromSTEB(10, 8, 6, 0),
              decoration: BoxDecoration(
                color: m.warningBg,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.cropUnreliable,
                    style: bodyFont(fontSize: 11.5, color: m.warning),
                  ),
                  CheckboxListTile(
                    key: ValueKey('cropConfirm$index'),
                    value: row.cropConfirmed,
                    onChanged: (v) => onCropConfirmed(v ?? false),
                    controlAffinity: ListTileControlAffinity.leading,
                    contentPadding: EdgeInsets.zero,
                    dense: true,
                    title: Text(
                      l.confirmCrop,
                      style: bodyFont(fontSize: 12, color: m.textPrimary),
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 6),
          _Field(
            label: l.fieldChequeNumber,
            value: row.chequeNumber,
            error: duplicateNumber ? l.duplicateNumber : null,
            onTap: onEditNumber,
            l: l,
          ),
          _Field(
            label: l.fieldBankName,
            value: row.bankName,
            onTap: onEditBank,
            l: l,
          ),
          _Field(
            label: l.fieldChequeDate,
            value: row.chequeDate == null
                ? ''
                : DateFormat(
                    'dd MMM yyyy',
                    l.ar ? 'ar' : null,
                  ).format(row.chequeDate!),
            onTap: onEditDate,
            l: l,
          ),
          _Field(
            label: l.fieldPayerName,
            value: row.payerName,
            onTap: onEditPayer,
            l: l,
          ),
          if (scanned != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Row(
                children: [
                  Text(
                    l.scannedAmount,
                    style: bodyFont(fontSize: 12, color: m.textMuted),
                  ),
                  const Spacer(),
                  Text(
                    'AED ${NumberFormat('#,##0.##').format(scanned)}',
                    style: GoogleFonts.jetBrainsMono(
                      fontSize: 13,
                      color: m.textPrimary,
                    ),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 6),
          DropdownButtonFormField<String?>(
            // Re-created when the auto-map moves the row, so the field shows it.
            key: ValueKey('multiTarget$index|${row.rowId}|${eligible.length}'),
            initialValue: row.rowId,
            isExpanded: true,
            decoration: InputDecoration(
              labelText: l.instalment,
              isDense: true,
              border: const OutlineInputBorder(),
            ),
            items: [
              DropdownMenuItem<String?>(
                value: null,
                child: Text(l.pickInstalment, style: bodyFont(fontSize: 13)),
              ),
              for (final r in eligible)
                DropdownMenuItem<String?>(
                  value: r['id']?.toString(),
                  enabled: !takenRowIds.contains(r['id']?.toString()),
                  child: Text(
                    _rowLabel(r),
                    overflow: TextOverflow.ellipsis,
                    style: bodyFont(
                      fontSize: 13,
                      color: takenRowIds.contains(r['id']?.toString())
                          ? m.textMuted
                          : m.textPrimary,
                    ),
                  ),
                ),
            ],
            onChanged: eligible.isEmpty ? null : onPickRow,
          ),
          if (amountOff) ...[
            const SizedBox(height: 6),
            Text(
              l.amountMismatch(
                NumberFormat('#,##0.##').format(scanned),
                NumberFormat('#,##0.##').format(expected),
              ),
              style: bodyFont(fontSize: 11.5, color: m.warning),
            ),
            CheckboxListTile(
              key: ValueKey('amountConfirm$index'),
              value: row.amountConfirmed,
              onChanged: (v) => onAmountConfirmed(v ?? false),
              controlAffinity: ListTileControlAffinity.leading,
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: Text(
                amountConfirmLabel(l.ar),
                style: bodyFont(fontSize: 12, color: m.textPrimary),
              ),
            ),
          ],
          if (row.scan.payeeCheck != null) ...[
            const SizedBox(height: 10),
            PayeeCheckNotice(
              payeeCheck: row.scan.payeeCheck,
              payeeName: row.scan.payeeName,
              confirmed: row.payeeConfirmed,
              onConfirmedChanged: onPayeeConfirmed,
              checkboxKey: ValueKey('payeeMismatchConfirm$index'),
            ),
          ],
        ],
      ),
    );
  }
}

class _Field extends StatelessWidget {
  final String label;
  final String value;
  final String? error;
  final VoidCallback onTap;
  final _ML l;

  const _Field({
    required this.label,
    required this.value,
    required this.onTap,
    required this.l,
    this.error,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bodyFont = l.ar
        ? GoogleFonts.notoNaskhArabic
        : GoogleFonts.plusJakartaSans;
    final missing = value.trim().isEmpty;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            Text(label, style: bodyFont(fontSize: 12, color: m.textMuted)),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                missing ? l.notDetected : value,
                textAlign: TextAlign.end,
                overflow: TextOverflow.ellipsis,
                style: GoogleFonts.jetBrainsMono(
                  fontSize: 13,
                  color: error != null
                      ? m.danger
                      : (missing ? m.danger : m.textPrimary),
                ),
              ),
            ),
            const SizedBox(width: 6),
            Icon(
              // Mirrors itself under RTL.
              Icons.arrow_forward_ios,
              size: 11,
              color: m.textMuted,
            ),
          ],
        ),
      ),
    );
  }
}

/// Multi-cheque review strings (EN/AR). Lightweight per-screen pattern.
class _ML {
  _ML(this.ar);
  final bool ar;

  String title(int n) =>
      ar ? 'مراجعة ${arCheques(n, genitive: true)}' : 'Review $n cheques';
  String get save => ar ? 'حفظ' : 'Save';
  String get notDetected => ar ? 'غير مكتشف' : 'Not detected';
  String get leaseLabel => ar ? 'العقد' : 'Lease';
  String get pickLeaseHint => ar
      ? 'اختر أي شيك من العقد الذي تخصّه هذه الشيكات.'
      : 'Pick any cheque of the lease these cheques belong to.';
  String get pickLease => ar ? 'اختيار العقد' : 'Pick lease';
  String get changeLease => ar ? 'تغيير العقد' : 'Change lease';
  String loadRowsFailed(String e) => ar
      ? 'تعذّر تحميل شيكات العقد: $e'
      : "Couldn't load the lease's cheques: $e";
  String get noEligibleRows => ar
      ? 'لا يوجد في هذا العقد شيك مؤجل بانتظار المسح.'
      : 'This contract has no post-dated cheque waiting for a scan.';
  String chequeOf(int i, int n) => ar ? 'الشيك $i من $n' : 'Cheque $i of $n';
  String get remove => ar ? 'إزالة' : 'Remove';
  String get nothingRead => ar
      ? 'تعذّرت قراءة هذا الشيك — أدخل البيانات يدويًا أو أعد تصوير الشيك.'
      : "Couldn't read this cheque — enter the details or retake the photo.";
  String get cropUnreliable => ar
      ? 'تعذّر فصل هذا الشيك بوضوح — تحقّق من الصورة المقتطعة'
      : 'Could not separate this cheque cleanly — check the crop';
  String get confirmCrop =>
      ar ? 'تحقّقت من هذه الصورة' : 'I checked this image';
  String get fieldChequeNumber => ar ? 'رقم الشيك' : 'Cheque #';
  String get fieldBankName => ar ? 'اسم البنك' : 'Bank';
  String get fieldChequeDate => ar ? 'تاريخ الشيك' : 'Date';
  String get fieldPayerName => ar ? 'اسم الدافع' : 'Payer';
  String get scannedAmount => ar ? 'المبلغ (من الشيك)' : 'Amount (scanned)';
  String get duplicateNumber => ar ? 'رقم مكرر' : 'Duplicate number';
  String get instalment => ar ? 'القسط' : 'Instalment';
  String get pickInstalment => ar ? 'اختر قسطًا' : 'Pick an instalment';
  String amountMismatch(String scanned, String expected) => ar
      ? 'المبلغ $scanned ≠ القسط $expected'
      : 'Amount $scanned ≠ instalment $expected';
  String readyCount(int ready, int total) =>
      ar ? 'جاهز: $ready من $total' : 'Ready: $ready of $total';
  String attachAll(int n) =>
      ar ? 'إرفاق ${arCheques(n, genitive: true)}' : 'Attach $n cheques';
}
