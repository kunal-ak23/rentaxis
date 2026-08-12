import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import '../widgets/mark_cheque_failed_dialog.dart';

/// Cheque/payment status colors per the Miftah admin design: CLEARED is
/// green, COLLECTED/DEPOSITED is bronze (in transit to the bank), BOUNCED/
/// OVERDUE is red, PENDING is neutral.
Color _chequeStatusColor(LegacyMiftahColors m, String status) {
  switch (status) {
    case 'CLEARED':
      return m.success;
    case 'COLLECTED':
    case 'DEPOSITED':
      return AppColors.accentDark;
    case 'BOUNCED':
    case 'OVERDUE':
      return m.danger;
    default:
      return m.textMuted;
  }
}

/// Text style helper — Arabic uses Noto Naskh instead of Cinzel/Josefin
/// Sans, and never carries the EN tracked-uppercase letterSpacing (breaks
/// glyph joining). See arabic-brief.
TextStyle _display(
  bool ar, {
  double size = 16,
  FontWeight weight = FontWeight.w600,
  Color? color,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size + 1,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.plusJakartaSans(fontSize: size, fontWeight: weight, color: color);

TextStyle _body(
  bool ar, {
  double size = 13,
  FontWeight weight = FontWeight.w400,
  Color? color,
  double letterSpacing = 0,
}) => ar
    ? GoogleFonts.notoNaskhArabic(
        fontSize: size,
        fontWeight: weight,
        color: color,
      )
    : GoogleFonts.plusJakartaSans(
        fontSize: size,
        fontWeight: weight,
        color: color,
        letterSpacing: letterSpacing,
      );

final _leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

final _paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PaymentService(client.dio);
});

class LeaseDetailScreen extends ConsumerStatefulWidget {
  final String leaseId;
  const LeaseDetailScreen({super.key, required this.leaseId});

  @override
  ConsumerState<LeaseDetailScreen> createState() => _LeaseDetailScreenState();
}

class _LeaseDetailScreenState extends ConsumerState<LeaseDetailScreen> {
  Map<String, dynamic>? _lease;
  List<dynamic> _payments = [];
  List<dynamic> _documents = [];
  List<dynamic> _attachments = [];
  bool _isLoading = true;
  bool _isActioning = false;
  String? _error;

  bool _isExtending = false;
  bool _isGeneratingContract = false;

  _L get _l => _L(context.isAr);

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final leaseService = ref.read(_leaseServiceProvider);
      final paymentService = ref.read(_paymentServiceProvider);
      final results = await Future.wait([
        leaseService.getLeaseById(widget.leaseId),
        // Lease-scoped endpoint: the tenant-wide paged list only holds one
        // page, so filtering it client-side silently dropped installments
        // of this lease that fell outside the fetched page.
        paymentService.getPaymentsForLease(widget.leaseId),
        leaseService.getLeaseDocuments(widget.leaseId),
        leaseService.getAttachments(widget.leaseId),
      ]);
      if (!mounted) return;
      // The lease endpoint has no defined ordering — sort like the web
      // schedule editor: rent installments first (by installment number),
      // deposits/charges last, so the timeline reads chronologically.
      final payments = List<dynamic>.from(results[1] as List<dynamic>);
      int kind(dynamic p) =>
          p is Map &&
              (p['isBookingDeposit'] == true ||
                  p['isSecurityDeposit'] == true ||
                  p['isCharge'] == true)
          ? 1
          : 0;
      int installment(dynamic p) =>
          (p is Map ? (p['installmentNumber'] as num?)?.toInt() : null) ?? 0;
      payments.sort((a, b) {
        final byKind = kind(a).compareTo(kind(b));
        if (byKind != 0) return byKind;
        return installment(a).compareTo(installment(b));
      });
      setState(() {
        _lease = results[0] as Map<String, dynamic>;
        _payments = payments;
        _documents = results[2] as List<dynamic>;
        _attachments = results[3] as List<dynamic>;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = _l.failedToLoad;
        _isLoading = false;
      });
    }
  }

  Future<void> _activateLease() async {
    final l = _l;
    final confirmed = await _confirmAction(
      l.activateLeaseTitle,
      l.activateLeaseBody,
    );
    if (!confirmed) return;

    setState(() => _isActioning = true);
    try {
      await ref.read(_leaseServiceProvider).activateLease(widget.leaseId);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.leaseActivated)));
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(_actionError(e, l.failedToActivate))),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  /// Admin-only lease actions answer 403 for other roles — name the
  /// permission problem instead of a generic failure.
  String _actionError(Object e, String fallback) =>
      e is DioException && e.response?.statusCode == 403
      ? _l.notAuthorized
      : fallback;

  Future<bool> _confirmAction(String title, String message) async {
    final l = _l;
    final result = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(title, style: _display(l.ar, size: 17)),
        content: Text(
          message,
          style: _body(l.ar, color: AppColors.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel, style: _body(l.ar, weight: FontWeight.w600)),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.primary),
            child: Text(
              l.confirm,
              style: _body(l.ar, weight: FontWeight.w600, color: Colors.white),
            ),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  Future<void> _uploadAttachment() async {
    final l = _l;
    final source = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Wrap(
          children: [
            ListTile(
              leading: const Icon(Icons.camera_alt_outlined),
              title: Text(l.camera),
              onTap: () => Navigator.pop(ctx, 'camera'),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: Text(l.gallery),
              onTap: () => Navigator.pop(ctx, 'gallery'),
            ),
            ListTile(
              leading: const Icon(Icons.attach_file),
              title: Text(l.file),
              onTap: () => Navigator.pop(ctx, 'file'),
            ),
          ],
        ),
      ),
    );
    if (source == null) return;

    String? filePath;
    String fileName = 'attachment';

    if (source == 'camera' || source == 'gallery') {
      final picker = ImagePicker();
      final image = await picker.pickImage(
        source: source == 'camera' ? ImageSource.camera : ImageSource.gallery,
        imageQuality: 80,
      );
      if (image == null) return;
      filePath = image.path;
      fileName = image.name;
    } else {
      final result = await FilePicker.platform.pickFiles();
      if (result == null || result.files.isEmpty) return;
      filePath = result.files.first.path;
      fileName = result.files.first.name;
    }

    if (filePath == null) return;

    try {
      await ref
          .read(_leaseServiceProvider)
          .uploadAttachment(widget.leaseId, filePath, fileName);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.attachmentUploaded)));
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.failedToUpload)));
      }
    }
  }

  Future<void> _downloadDocument(Map<String, dynamic> doc) async {
    try {
      final bytes = await ref
          .read(_leaseServiceProvider)
          .downloadDocument(doc['id']);
      final dir = await getTemporaryDirectory();
      final fileName = doc['name'] ?? 'document.pdf';
      final file = File('${dir.path}/$fileName');
      await file.writeAsBytes(bytes);
      await OpenFilex.open(file.path);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(_l.failedToDownload)));
      }
    }
  }

  Future<void> _generateContract() async {
    final l = _l;
    setState(() => _isGeneratingContract = true);
    try {
      // 1) Fetch the preview PDF and open it in the system viewer.
      final bytes = await ref
          .read(_leaseServiceProvider)
          .previewContract(widget.leaseId);
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/lease-preview-${widget.leaseId}.pdf');
      await file.writeAsBytes(bytes);
      await OpenFilex.open(file.path);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(_actionError(e, l.failedToPreview))),
        );
        setState(() => _isGeneratingContract = false);
      }
      return;
    }

    if (!mounted) return;

    // 2) Confirm save after the user has reviewed the preview.
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(l.saveContractTitle, style: _display(l.ar, size: 17)),
        content: Text(
          l.saveContractBody,
          style: _body(l.ar, color: AppColors.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel, style: _body(l.ar, weight: FontWeight.w600)),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.primary),
            child: Text(
              l.confirmAndSave,
              style: _body(l.ar, weight: FontWeight.w600, color: Colors.white),
            ),
          ),
        ],
      ),
    );

    if (confirmed != true) {
      if (mounted) setState(() => _isGeneratingContract = false);
      return;
    }

    try {
      await ref.read(_leaseServiceProvider).generateContract(widget.leaseId);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.contractGenerated)));
        await _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(_actionError(e, l.failedToSaveContract))),
        );
      }
    } finally {
      if (mounted) setState(() => _isGeneratingContract = false);
    }
  }

  Future<void> _showExtendDialog() async {
    final l = _l;
    final lease = _lease!;
    final currentEnd =
        DateTime.tryParse(lease['endDate'] ?? '') ?? DateTime.now();
    DateTime picked = currentEnd.add(const Duration(days: 365));

    await showDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) => AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
          ),
          title: Text(l.extendLeaseTitle, style: _display(l.ar, size: 17)),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                l.currentEndDate(Formatters.date(lease['endDate'], ar: l.ar)),
                style: _body(l.ar, color: AppColors.textMuted),
              ),
              const SizedBox(height: 16),
              Text(l.newEndDate, style: _body(l.ar, weight: FontWeight.w600)),
              const SizedBox(height: 8),
              InkWell(
                onTap: () async {
                  final d = await showDatePicker(
                    context: ctx,
                    initialDate: picked,
                    firstDate: currentEnd.add(const Duration(days: 1)),
                    lastDate: DateTime.now().add(const Duration(days: 3650)),
                  );
                  if (d != null) setDialogState(() => picked = d);
                },
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 12,
                  ),
                  decoration: BoxDecoration(
                    border: Border.all(color: AppColors.border),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Row(
                    children: [
                      const Icon(
                        Icons.calendar_today_outlined,
                        size: 18,
                        color: AppColors.primary,
                      ),
                      const SizedBox(width: 8),
                      Text(
                        '${picked.year}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}',
                        style: _body(l.ar, weight: FontWeight.w600),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: Text(
                l.cancel,
                style: _body(l.ar, weight: FontWeight.w600),
              ),
            ),
            ElevatedButton(
              onPressed: _isExtending
                  ? null
                  : () async {
                      setDialogState(() => _isExtending = true);
                      try {
                        final newEndDate =
                            '${picked.year}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}';
                        await ref
                            .read(_leaseServiceProvider)
                            .extendLease(widget.leaseId, newEndDate);
                        if (!mounted || !ctx.mounted) return;
                        setDialogState(() => _isExtending = false);
                        Navigator.pop(ctx);
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text(l.leaseExtended)),
                        );
                        _loadData();
                      } catch (e) {
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text(
                                _actionError(e, l.failedToExtend),
                              ),
                            ),
                          );
                        }
                      } finally {
                        if (ctx.mounted) {
                          setDialogState(() => _isExtending = false);
                        }
                      }
                    },
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.primary,
              ),
              child: _isExtending
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : Text(
                      l.extend,
                      style: _body(
                        l.ar,
                        weight: FontWeight.w600,
                        color: Colors.white,
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _l;

    if (_isLoading) {
      return Scaffold(
        backgroundColor: m.background,
        appBar: AppBar(
          backgroundColor: AppColors.primary,
          title: Text(
            l.leaseTitle,
            style: _display(l.ar, size: 17, color: AppColors.gold400),
          ),
        ),
        body: const Center(
          child: CircularProgressIndicator(color: AppColors.accent),
        ),
      );
    }

    if (_error != null || _lease == null) {
      return Scaffold(
        backgroundColor: m.background,
        appBar: AppBar(
          backgroundColor: AppColors.primary,
          title: Text(
            l.leaseTitle,
            style: _display(l.ar, size: 17, color: AppColors.gold400),
          ),
        ),
        body: ErrorState(message: _error ?? l.notFound, onRetry: _loadData),
      );
    }

    final lease = _lease!;
    final status = (lease['status'] ?? 'DRAFT').toString();
    // Activate, extend and generate-contract(+preview) are
    // SUPER_ADMIN/TENANT_ADMIN only on the backend (LeaseController) — hide
    // those actions for other roles instead of offering a guaranteed 403.
    final role = ref.watch(authProvider).role;
    final canManageLease = role == 'SUPER_ADMIN' || role == 'TENANT_ADMIN';

    return Scaffold(
      backgroundColor: m.background,
      body: LoadingOverlay(
        isLoading: _isActioning,
        child: RefreshIndicator(
          onRefresh: _loadData,
          color: AppColors.accent,
          child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              SliverToBoxAdapter(
                child: _buildChromeHeader(lease, status, l, canManageLease),
              ),
              SliverPadding(
                padding: EdgeInsets.fromLTRB(
                  16,
                  16,
                  16,
                  AppInsets.bottomNav(context),
                ),
                sliver: SliverList(
                  delegate: SliverChildListDelegate([
                    _buildLeaseInfoCard(lease, l),
                    const SizedBox(height: 18),
                    if (canManageLease &&
                        (status == 'DRAFT' ||
                            status == 'PENDING_SIGNATURE')) ...[
                      GoldButton.outlined(
                        label: lease['contractNumber'] != null
                            ? l.regenerateContract
                            : l.generateContract,
                        onPressed: _isGeneratingContract
                            ? null
                            : _generateContract,
                        icon: _isGeneratingContract
                            ? const SizedBox(
                                width: 16,
                                height: 16,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.description_outlined, size: 18),
                      ),
                      const SizedBox(height: 10),
                      GoldButton(
                        label: l.activateLease,
                        onPressed: _activateLease,
                        icon: const Icon(
                          Icons.check_circle_outline,
                          size: 18,
                          color: AppColors.primary,
                        ),
                      ),
                      const SizedBox(height: 18),
                    ],
                    _buildPaymentSchedule(m, l),
                    const SizedBox(height: 20),
                    _buildDocuments(m, l),
                    const SizedBox(height: 20),
                    _buildAttachments(m, l),
                    const SizedBox(height: 20),
                    _buildPenaltiesLink(m, l),
                    const SizedBox(height: 10),
                    _buildSettlementLink(m, l),
                  ]),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildChromeHeader(
    Map<String, dynamic> lease,
    String status,
    _L l,
    bool canManageLease,
  ) {
    final unit =
        '${lease['propertyName'] ?? '-'} · ${lease['unitIdentifier'] ?? lease['unitNumber'] ?? '-'}';
    final contractNumber = lease['contractNumber'];
    final bouncedCount = _payments
        .where((p) => (p is Map ? p['status'] : null) == 'BOUNCED')
        .length;

    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.16)),
        ),
      ),
      padding: EdgeInsets.fromLTRB(
        16,
        MediaQuery.of(context).padding.top + 8,
        16,
        16,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              InkWell(
                borderRadius: BorderRadius.circular(20),
                onTap: () => context.pop(),
                child: Padding(
                  padding: const EdgeInsets.all(4),
                  child: Icon(
                    l.ar ? Icons.arrow_forward : Icons.arrow_back,
                    size: 18,
                    color: Colors.white.withValues(alpha: 0.6),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  contractNumber != null
                      ? l.contractOverline(contractNumber.toString())
                      : l.leaseOverline,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 12,
                          color: AppColors.goldMid,
                        )
                      : GoogleFonts.plusJakartaSans(
                          fontSize: 9.5,
                          letterSpacing: 2.2,
                          color: AppColors.goldMid,
                        ),
                ),
              ),
              if (status == 'ACTIVE' || status == 'NOTICE_GIVEN')
                PopupMenuButton<String>(
                  icon: Icon(
                    Icons.more_vert,
                    size: 19,
                    color: Colors.white.withValues(alpha: 0.6),
                  ),
                  padding: EdgeInsets.zero,
                  color: AppColors.primary,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                    side: BorderSide(
                      color: AppColors.accent.withValues(alpha: 0.18),
                    ),
                  ),
                  onSelected: (v) {
                    if (v == 'terminate') {
                      context.push('/leases/${widget.leaseId}/settlement');
                    }
                    if (v == 'extend') _showExtendDialog();
                  },
                  itemBuilder: (_) => [
                    // POST /extend is SUPER_ADMIN/TENANT_ADMIN only.
                    if (status == 'ACTIVE' && canManageLease)
                      PopupMenuItem(
                        value: 'extend',
                        child: Row(
                          children: [
                            const Icon(
                              Icons.calendar_month_outlined,
                              color: AppColors.accent,
                              size: 18,
                            ),
                            const SizedBox(width: 8),
                            Text(
                              l.extendLeaseTitle,
                              style: _body(l.ar, size: 13, color: Colors.white),
                            ),
                          ],
                        ),
                      ),
                    PopupMenuItem(
                      value: 'terminate',
                      child: Row(
                        children: [
                          const Icon(
                            Icons.account_balance_wallet_outlined,
                            color: AppColors.warning,
                            size: 18,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            l.settleAndTerminate,
                            style: _body(l.ar, size: 13, color: Colors.white),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
            ],
          ),
          const SizedBox(height: 6),
          Text(unit, style: _display(l.ar, size: 20, color: AppColors.gold400)),
          const SizedBox(height: 2),
          Text(
            lease['renterName'] ?? l.unknownRenter,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 13,
                    color: Colors.white70,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 12.5,
                    color: Colors.white70,
                  ),
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 7,
            runSpacing: 6,
            children: [
              _HeaderPill(
                label: l.leaseStatusLabel(status),
                color: _leaseStatusColor(status),
              ),
              if (bouncedCount > 0)
                _HeaderPill(
                  label: l.bouncedCount(bouncedCount),
                  color: AppColors.danger,
                ),
            ],
          ),
        ],
      ),
    );
  }

  Color _leaseStatusColor(String status) {
    switch (status) {
      case 'ACTIVE':
        return AppColors.success;
      case 'NOTICE_GIVEN':
        return AppColors.warning;
      // Matches the shared StatusHelper convention: an expired term is a
      // settled fact, not an amber "needs attention" state.
      case 'EXPIRED':
        return AppColors.textMuted;
      case 'TERMINATED':
        return AppColors.danger;
      case 'DRAFT':
      case 'PENDING_SIGNATURE':
        return AppColors.accentDark;
      default:
        return AppColors.textMuted;
    }
  }

  Widget _buildLeaseInfoCard(Map<String, dynamic> lease, _L l) {
    final m = context.miftah;
    final annualRent = (lease['annualRent'] ?? lease['totalRent'] ?? 0)
        .toDouble();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border.all(color: m.border),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: _InfoBlock(
                  label: l.renter,
                  value: lease['renterName'] ?? l.unknownRenter,
                  sub: lease['renterPhone']?.toString(),
                  m: m,
                  l: l,
                ),
              ),
              _InfoBlock(
                label: l.annualRent,
                value: Formatters.currency(annualRent),
                sub: l.chequeCount(
                  (lease['numberOfPayments'] as num?)?.toInt() ??
                      _payments.length,
                ),
                m: m,
                l: l,
                alignEnd: true,
                isAmount: true,
              ),
            ],
          ),
          if (lease['monthlyRent'] != null) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                Icon(Icons.payments_outlined, size: 14, color: m.textMuted),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    l.monthlyRentLine(
                      Formatters.currency(
                        (lease['monthlyRent'] as num).toDouble(),
                      ),
                    ),
                    style: _body(l.ar, size: 12.5, color: m.textSecondary),
                  ),
                ),
              ],
            ),
          ],
          Divider(height: 24, color: m.divider),
          Row(
            children: [
              Icon(Icons.calendar_today_outlined, size: 14, color: m.textMuted),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  '${Formatters.date(lease['startDate'], ar: l.ar)} — ${Formatters.date(lease['endDate'], ar: l.ar)}',
                  style: _body(l.ar, size: 12.5, color: m.textSecondary),
                ),
              ),
            ],
          ),
          if (lease['agreementDate'] != null) ...[
            const SizedBox(height: 6),
            Row(
              children: [
                Icon(Icons.assignment_outlined, size: 14, color: m.textMuted),
                const SizedBox(width: 6),
                Text(
                  l.agreementDate(
                    Formatters.date(lease['agreementDate'], ar: l.ar),
                  ),
                  style: _body(l.ar, size: 12.5, color: m.textSecondary),
                ),
              ],
            ),
          ],
          if ((lease['adminFee'] != null && (lease['adminFee'] as num) > 0) ||
              (lease['parkingRemoteFee'] != null &&
                  (lease['parkingRemoteFee'] as num) > 0)) ...[
            const SizedBox(height: 12),
            Row(
              children: [
                if (lease['adminFee'] != null &&
                    (lease['adminFee'] as num) > 0) ...[
                  Expanded(
                    child: _InfoBlock(
                      label: l.adminFee,
                      value: Formatters.currency(
                        (lease['adminFee'] as num).toDouble(),
                      ),
                      m: m,
                      l: l,
                    ),
                  ),
                ],
                if (lease['parkingRemoteFee'] != null &&
                    (lease['parkingRemoteFee'] as num) > 0)
                  Expanded(
                    child: _InfoBlock(
                      label: l.parkingRemote,
                      value: Formatters.currency(
                        (lease['parkingRemoteFee'] as num).toDouble(),
                      ),
                      m: m,
                      l: l,
                    ),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  // ─── Cheque schedule (timeline rail, per mock 1e/2b) ─────────────────────

  Widget _buildPaymentSchedule(LegacyMiftahColors m, _L l) {
    final cleared = _payments
        .where((p) => (p is Map ? p['status'] : null) == 'CLEARED')
        .length;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(
              l.ar ? l.chequeSchedule : l.chequeSchedule.toUpperCase(),
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: AppColors.accentDark,
                      fontWeight: FontWeight.w600,
                    )
                  : GoogleFonts.plusJakartaSans(
                      fontSize: 10.5,
                      letterSpacing: 2.2,
                      color: AppColors.accentDark,
                      fontWeight: FontWeight.w600,
                    ),
            ),
            const Spacer(),
            if (_payments.isNotEmpty)
              Text(
                l.chequesClearedOf(cleared, _payments.length),
                style: _body(l.ar, size: 11.5, color: m.textMuted),
              ),
          ],
        ),
        const SizedBox(height: 12),
        if (_payments.isEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: m.surface,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: m.border),
            ),
            child: Column(
              children: [
                Icon(Icons.receipt_long_outlined, size: 36, color: m.textMuted),
                const SizedBox(height: 8),
                Text(
                  l.noPaymentsYet,
                  style: _body(l.ar, color: m.textSecondary),
                ),
                const SizedBox(height: 2),
                Text(
                  l.activateToGenerate,
                  style: _body(l.ar, size: 11.5, color: m.textMuted),
                ),
              ],
            ),
          )
        else
          Column(
            children: _payments.asMap().entries.map((entry) {
              final index = entry.key;
              final payment = entry.value as Map<String, dynamic>;
              final isLast = index == _payments.length - 1;
              return _ChequeTimelineRow(
                index: index,
                payment: payment,
                isLast: isLast,
                m: m,
                l: l,
                onTap: (payment['status'] ?? 'PENDING') == 'DEPOSITED'
                    ? () async {
                        final paymentId = payment['id'] as String? ?? '';
                        final amount = (payment['amount'] ?? 0) as num;
                        final result = await showMarkChequeFailedDialog(
                          context,
                          paymentId: paymentId,
                          installmentNumber: index + 1,
                          amount: amount,
                          paymentService: ref.read(_paymentServiceProvider),
                        );
                        if (result != null && mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text(l.chequeMarkedFailed)),
                          );
                          _loadData();
                        }
                      }
                    : null,
              );
            }).toList(),
          ),
      ],
    );
  }

  Widget _buildDocuments(LegacyMiftahColors m, _L l) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionLabel(
          icon: Icons.folder_outlined,
          text: l.documents,
          m: m,
          l: l,
        ),
        const SizedBox(height: 12),
        if (_documents.isEmpty)
          Text(
            l.noDocuments,
            style: _body(l.ar, size: 12.5, color: m.textSecondary),
          )
        else
          Container(
            decoration: BoxDecoration(
              color: m.surface,
              border: Border.all(color: m.border),
              borderRadius: BorderRadius.circular(14),
            ),
            clipBehavior: Clip.antiAlias,
            child: Column(
              children: [
                for (var i = 0; i < _documents.length; i++) ...[
                  if (i > 0) Divider(height: 1, color: m.divider),
                  _DocumentRow(
                    doc: _documents[i],
                    m: m,
                    l: l,
                    onDownload: () => _downloadDocument(_documents[i]),
                  ),
                ],
              ],
            ),
          ),
      ],
    );
  }

  Widget _buildAttachments(LegacyMiftahColors m, _L l) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(Icons.attach_file, size: 17, color: AppColors.accentDark),
            const SizedBox(width: 8),
            Text(
              l.ar ? l.attachments : l.attachments.toUpperCase(),
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: AppColors.accentDark,
                      fontWeight: FontWeight.w600,
                    )
                  : GoogleFonts.plusJakartaSans(
                      fontSize: 10.5,
                      letterSpacing: 2.2,
                      color: AppColors.accentDark,
                      fontWeight: FontWeight.w600,
                    ),
            ),
            const Spacer(),
            TextButton.icon(
              onPressed: _uploadAttachment,
              icon: const Icon(
                Icons.add,
                size: 16,
                color: AppColors.accentDark,
              ),
              label: Text(
                l.add,
                style: _body(
                  l.ar,
                  size: 12.5,
                  color: AppColors.accentDark,
                  weight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        if (_attachments.isEmpty)
          Text(
            l.noAttachments,
            style: _body(l.ar, size: 12.5, color: m.textSecondary),
          )
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _attachments.map((att) {
              return Chip(
                backgroundColor: m.surfaceAlt,
                side: BorderSide(color: m.border),
                avatar: Icon(
                  Icons.insert_drive_file_outlined,
                  size: 16,
                  color: m.textSecondary,
                ),
                label: Text(
                  att['name'] ?? l.file,
                  style: _body(l.ar, size: 12, color: m.textPrimary),
                ),
                deleteIcon: Icon(Icons.close, size: 16, color: m.textMuted),
                onDeleted: () async {
                  try {
                    await ref
                        .read(_leaseServiceProvider)
                        .deleteAttachment(att['id']);
                    _loadData();
                  } catch (_) {}
                },
              );
            }).toList(),
          ),
      ],
    );
  }

  Widget _buildPenaltiesLink(LegacyMiftahColors m, _L l) {
    return _LinkCard(
      icon: Icons.gavel,
      iconColor: m.danger,
      title: l.penalties,
      subtitle: l.penaltiesSubtitle,
      m: m,
      l: l,
      onTap: () => context.push('/leases/${widget.leaseId}/penalties'),
    );
  }

  Widget _buildSettlementLink(LegacyMiftahColors m, _L l) {
    final status = _lease?['status'] ?? '';
    // Show settlement link for ACTIVE, NOTICE_GIVEN, TERMINATED, CLOSED
    final showSettlementLink = [
      'ACTIVE',
      'NOTICE_GIVEN',
      'TERMINATED',
      'CLOSED',
    ].contains(status);
    if (!showSettlementLink) {
      return const SizedBox.shrink();
    }
    final settlementSubtitle = status == 'ACTIVE'
        ? l.manageSettlementDraft
        : l.viewSettlementPreview;
    return _LinkCard(
      icon: Icons.handshake_outlined,
      iconColor: AppColors.accent,
      title: l.settlement,
      subtitle: settlementSubtitle,
      m: m,
      l: l,
      onTap: () => context.push('/leases/${widget.leaseId}/settlement'),
    );
  }
}

// ─── Small building blocks ─────────────────────────────────────────────────

class _HeaderPill extends StatelessWidget {
  final String label;
  final Color color;
  const _HeaderPill({required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.16),
        border: Border.all(color: color.withValues(alpha: 0.4)),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.plusJakartaSans(
                fontSize: 9,
                letterSpacing: 1.2,
                fontWeight: FontWeight.w600,
                color: color,
              ),
      ),
    );
  }
}

class _InfoBlock extends StatelessWidget {
  final String label;
  final String value;
  final String? sub;
  final LegacyMiftahColors m;
  final _L l;
  final bool alignEnd;
  final bool isAmount;
  const _InfoBlock({
    required this.label,
    required this.value,
    this.sub,
    required this.m,
    required this.l,
    this.alignEnd = false,
    this.isAmount = false,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: alignEnd
          ? CrossAxisAlignment.end
          : CrossAxisAlignment.start,
      children: [
        Text(
          l.ar ? label : label.toUpperCase(),
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(fontSize: 10.5, color: m.textMuted)
              : GoogleFonts.plusJakartaSans(
                  fontSize: 9,
                  letterSpacing: 1.6,
                  color: m.textMuted,
                ),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          style: isAmount
              ? GoogleFonts.plusJakartaSans(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: m.textPrimary,
                )
              : _body(
                  l.ar,
                  size: 14,
                  weight: FontWeight.w600,
                  color: m.textPrimary,
                ),
        ),
        if (sub != null) ...[
          const SizedBox(height: 2),
          Text(sub!, style: _body(l.ar, size: 11.5, color: m.textSecondary)),
        ],
      ],
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final IconData icon;
  final String text;
  final LegacyMiftahColors m;
  final _L l;
  const _SectionLabel({
    required this.icon,
    required this.text,
    required this.m,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 17, color: AppColors.accentDark),
        const SizedBox(width: 8),
        Text(
          l.ar ? text : text.toUpperCase(),
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 13,
                  color: AppColors.accentDark,
                  fontWeight: FontWeight.w600,
                )
              : GoogleFonts.plusJakartaSans(
                  fontSize: 10.5,
                  letterSpacing: 2.2,
                  color: AppColors.accentDark,
                  fontWeight: FontWeight.w600,
                ),
        ),
      ],
    );
  }
}

class _ChequeTimelineRow extends StatelessWidget {
  final int index;
  final Map<String, dynamic> payment;
  final bool isLast;
  final LegacyMiftahColors m;
  final _L l;
  final VoidCallback? onTap;

  const _ChequeTimelineRow({
    required this.index,
    required this.payment,
    required this.isLast,
    required this.m,
    required this.l,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final status = (payment['status'] ?? 'PENDING').toString();
    final color = _chequeStatusColor(m, status);
    final isBounced = status == 'BOUNCED';
    final amount = Formatters.currency((payment['amount'] ?? 0).toDouble());
    final dueDate = Formatters.date(payment['dueDate'], ar: l.ar);

    final content = isBounced
        ? Container(
            padding: const EdgeInsets.all(12),
            margin: EdgeInsetsDirectional.only(bottom: isLast ? 0 : 12),
            decoration: BoxDecoration(
              color: m.surface,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: m.danger.withValues(alpha: 0.3)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      l.chequeLabel(index + 1, dueDate),
                      style: _body(l.ar, size: 13.5, color: m.textPrimary),
                    ),
                    Text(
                      amount,
                      style: GoogleFonts.plusJakartaSans(fontSize: 14, color: m.danger),
                    ),
                  ],
                ),
                const SizedBox(height: 2),
                Text(
                  l.bouncedReason(payment['failureReason']?.toString()),
                  style: _body(l.ar, size: 11.5, color: m.danger),
                ),
              ],
            ),
          )
        : Padding(
            padding: EdgeInsetsDirectional.only(bottom: isLast ? 0 : 12),
            child: GestureDetector(
              onTap: onTap,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Text(
                      l.chequeLabel(index + 1, dueDate),
                      style: _body(
                        l.ar,
                        size: 13.5,
                        color: status == 'PENDING'
                            ? m.textSecondary
                            : m.textPrimary,
                      ),
                    ),
                  ),
                  Text(
                    amount,
                    style: GoogleFonts.plusJakartaSans(
                      fontSize: 14,
                      color: status == 'PENDING' ? m.textMuted : m.textPrimary,
                    ),
                  ),
                ],
              ),
            ),
          );

    final subline = isBounced
        ? const SizedBox.shrink()
        : Padding(
            padding: EdgeInsetsDirectional.only(
              bottom: isLast ? 0 : 12,
              top: 2,
            ),
            child: Text(
              l.chequeStatusLine(status, payment),
              style: _body(l.ar, size: 11.5, color: color),
            ),
          );

    return Padding(
      padding: const EdgeInsetsDirectional.only(bottom: 0),
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 14,
              child: Column(
                children: [
                  Container(
                    width: 9,
                    height: 9,
                    margin: const EdgeInsets.only(top: 5),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: color,
                    ),
                  ),
                  if (!isLast)
                    Expanded(
                      child: Container(
                        width: 1,
                        margin: const EdgeInsets.only(top: 3),
                        color: m.border,
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: isBounced
                  ? content
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [content, subline],
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DocumentRow extends StatelessWidget {
  final Map<String, dynamic> doc;
  final LegacyMiftahColors m;
  final _L l;
  final VoidCallback onDownload;
  const _DocumentRow({
    required this.doc,
    required this.m,
    required this.l,
    required this.onDownload,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: AppColors.info.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Icon(
              Icons.picture_as_pdf_outlined,
              color: AppColors.info,
              size: 19,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  doc['name'] ?? l.document,
                  style: _body(l.ar, size: 13.5, color: m.textPrimary),
                ),
                Text(
                  Formatters.date(doc['createdAt'], ar: l.ar),
                  style: _body(l.ar, size: 11, color: m.textMuted),
                ),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(
              Icons.download_outlined,
              color: AppColors.accentDark,
              size: 20,
            ),
            onPressed: onDownload,
          ),
        ],
      ),
    );
  }
}

class _LinkCard extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final String title;
  final String subtitle;
  final LegacyMiftahColors m;
  final _L l;
  final VoidCallback onTap;
  const _LinkCard({
    required this.icon,
    required this.iconColor,
    required this.title,
    required this.subtitle,
    required this.m,
    required this.l,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: m.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: m.border),
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: iconColor.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(icon, color: iconColor, size: 21),
            ),
            const SizedBox(width: 13),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: _body(
                      l.ar,
                      size: 14.5,
                      weight: FontWeight.w600,
                      color: m.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: _body(l.ar, size: 11.5, color: m.textSecondary),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right, color: m.textMuted),
          ],
        ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get leaseTitle => ar ? 'العقد' : 'Lease';
  String get notFound => ar ? 'غير موجود' : 'Not found';
  String get failedToLoad =>
      ar ? 'تعذر تحميل تفاصيل العقد' : 'Failed to load lease details';
  String get leaseOverline => ar ? 'عقد الإيجار' : 'LEASE';
  String contractOverline(String number) =>
      ar ? 'العقد رقم $number' : 'CONTRACT NO. $number';
  String get unknownRenter => ar ? 'مستأجر غير معروف' : 'Unknown Renter';
  String bouncedCount(int n) => ar ? '$n مرتجع' : '$n bounced';

  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get confirm => ar ? 'تأكيد' : 'Confirm';
  String get confirmAndSave => ar ? 'تأكيد وحفظ' : 'Confirm & Save';
  String get extend => ar ? 'تمديد' : 'Extend';

  String get activateLeaseTitle => ar ? 'تفعيل العقد' : 'Activate Lease';
  String get activateLeaseBody => ar
      ? 'هل أنت متأكد من تفعيل هذا العقد؟ سيتم إنشاء جدول الدفعات.'
      : 'Are you sure you want to activate this lease? This will generate the payment schedule.';
  String get leaseActivated =>
      ar ? 'تم تفعيل العقد بنجاح' : 'Lease activated successfully';
  String get failedToActivate =>
      ar ? 'فشل تفعيل العقد' : 'Failed to activate lease';
  String get notAuthorized => ar
      ? 'ليس لديك صلاحية لهذا الإجراء'
      : 'You do not have permission for this action';

  String get camera => ar ? 'الكاميرا' : 'Camera';
  String get gallery => ar ? 'معرض الصور' : 'Gallery';
  String get file => ar ? 'ملف' : 'File';
  String get attachmentUploaded => ar ? 'تم رفع المرفق' : 'Attachment uploaded';
  String get failedToUpload =>
      ar ? 'فشل رفع المرفق' : 'Failed to upload attachment';
  String get failedToDownload =>
      ar ? 'فشل تحميل المستند' : 'Failed to download document';

  String get saveContractTitle => ar ? 'حفظ العقد؟' : 'Save Contract?';
  String get saveContractBody => ar
      ? 'بمجرد الحفظ، سيتم تخصيص رقم للعقد وحفظه في العقد. هل تريد المتابعة؟'
      : 'Once saved, this contract will be assigned a contract number and stored on the lease. Continue?';
  String get contractGenerated => ar ? 'تم إنشاء العقد' : 'Contract generated';
  String get failedToPreview =>
      ar ? 'فشل إنشاء المعاينة' : 'Failed to generate preview';
  String get failedToSaveContract =>
      ar ? 'فشل حفظ العقد' : 'Failed to save contract';
  String get generateContract => ar ? 'إنشاء العقد' : 'Generate Contract';
  String get regenerateContract =>
      ar ? 'إعادة إنشاء العقد' : 'Re-generate Contract';
  String get activateLease => ar ? 'تفعيل العقد' : 'Activate Lease';

  String get extendLeaseTitle => ar ? 'تمديد العقد' : 'Extend Lease';
  String get settleAndTerminate => ar ? 'تسوية وإنهاء' : 'Settle & Terminate';
  String currentEndDate(String date) =>
      ar ? 'تاريخ الانتهاء الحالي: $date' : 'Current end date: $date';
  String get newEndDate => ar ? 'تاريخ الانتهاء الجديد:' : 'New end date:';
  String get leaseExtended =>
      ar ? 'تم تمديد العقد بنجاح' : 'Lease extended successfully';
  String get failedToExtend =>
      ar ? 'فشل تمديد العقد' : 'Failed to extend lease';

  String get renter => ar ? 'المستأجر' : 'RENTER';
  String get annualRent => ar ? 'الإيجار السنوي' : 'ANNUAL RENT';
  String monthlyRentLine(String amount) =>
      ar ? 'الإيجار الشهري: $amount' : 'Monthly rent: $amount';
  String get adminFee => ar ? 'رسوم إدارية' : 'ADMIN FEE';
  String get parkingRemote => ar ? 'ريموت موقف السيارات' : 'PARKING REMOTE';
  String agreementDate(String date) =>
      ar ? 'تاريخ الاتفاقية: $date' : 'Agreement: $date';
  String chequeCount(int n) {
    if (!ar) return '$n cheques';
    if (n == 1) return 'شيك واحد';
    if (n == 2) return 'شيكان';
    if (n >= 3 && n <= 10) return '$n شيكات';
    return '$n شيكاً';
  }

  String get chequeSchedule => ar ? 'جدول الشيكات' : 'Cheque schedule';
  String chequesClearedOf(int cleared, int total) =>
      ar ? '$cleared من $total مصروف' : '$cleared of $total cleared';
  String get noPaymentsYet => ar ? 'لا توجد دفعات بعد' : 'No payments yet';
  String get activateToGenerate => ar
      ? 'قم بتفعيل العقد لإنشاء الجدول'
      : 'Activate the lease to generate the schedule';
  String get chequeMarkedFailed =>
      ar ? 'تم تسجيل الشيك كمرتجع' : 'Cheque marked as failed';
  String get markCheque => ar ? 'تسجيل فشل الشيك' : 'Mark Failed';

  String chequeLabel(int n, String date) =>
      ar ? 'شيك $n · $date' : 'CHQ $n · $date';

  String bouncedReason(String? reason) {
    final label = switch (reason) {
      'BOUNCE' => ar ? 'رصيد غير كافٍ' : 'insufficient funds',
      'SIGNATURE_MISMATCH' => ar ? 'عدم تطابق التوقيع' : 'signature mismatch',
      'ACCOUNT_CLOSED' => ar ? 'الحساب مغلق' : 'account closed',
      _ => ar ? 'مرتد' : 'bounced',
    };
    return ar ? 'مرتجع · $label' : 'Bounced · $label';
  }

  String chequeStatusLine(String status, Map<String, dynamic> payment) {
    final bank = payment['bankName']?.toString();
    switch (status) {
      case 'CLEARED':
        return ar
            ? (bank != null ? 'مصروف · $bank' : 'مصروف')
            : (bank != null ? 'Cleared · $bank' : 'Cleared');
      case 'COLLECTED':
        return ar ? 'تم التحصيل' : 'Collected';
      case 'DEPOSITED':
        return ar ? 'تم الإيداع بالبنك' : 'Deposited';
      default:
        final due = payment['dueDate'] != null
            ? Formatters.date(payment['dueDate'], ar: ar)
            : null;
        return ar
            ? (due != null ? 'محتجز · إيداع $due' : 'قيد الانتظار')
            : (due != null ? 'Held · deposit $due' : 'Pending');
    }
  }

  String get documents => ar ? 'المستندات' : 'Documents';
  String get noDocuments => ar ? 'لا توجد مستندات' : 'No documents available';
  String get document => ar ? 'مستند' : 'Document';

  String get attachments => ar ? 'المرفقات' : 'Attachments';
  String get add => ar ? 'إضافة' : 'Add';
  String get noAttachments => ar ? 'لا توجد مرفقات' : 'No attachments';

  String get penalties => ar ? 'الغرامات' : 'Penalties';
  String get penaltiesSubtitle => ar
      ? 'عرض وإدارة غرامات التأخير'
      : 'View and manage late payment penalties';
  String get settlement => ar ? 'التسوية' : 'Settlement';
  String get manageSettlementDraft =>
      ar ? 'إدارة مسودة التسوية' : 'Manage settlement draft';
  String get viewSettlementPreview => ar
      ? 'عرض معاينة أو تفاصيل التسوية'
      : 'View settlement preview or details';

  String leaseStatusLabel(String status) {
    switch (status) {
      case 'ACTIVE':
        return ar ? 'ساري' : 'Active';
      case 'DRAFT':
        return ar ? 'مسودة' : 'Draft';
      case 'PENDING_SIGNATURE':
        return ar ? 'بانتظار التوقيع' : 'Pending Signature';
      case 'EXPIRED':
        return ar ? 'منتهي' : 'Expired';
      case 'NOTICE_GIVEN':
        return ar ? 'إشعار إنهاء' : 'Notice Given';
      case 'TERMINATED':
        return ar ? 'منهى' : 'Terminated';
      case 'CLOSED':
        return ar ? 'مغلق' : 'Closed';
      default:
        return status;
    }
  }
}
