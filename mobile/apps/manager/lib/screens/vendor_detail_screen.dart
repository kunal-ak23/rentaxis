import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _vendorServiceProvider = Provider<VendorService>((ref) {
  final client = ref.watch(apiClientProvider);
  return VendorService(client.dio);
});

/// Vendor detail: dark chrome hero card, detail rows — mirrors the staff
/// detail restyle for the admin design language.
class VendorDetailScreen extends ConsumerStatefulWidget {
  final String vendorId;
  const VendorDetailScreen({super.key, required this.vendorId});

  @override
  ConsumerState<VendorDetailScreen> createState() => _VendorDetailScreenState();
}

class _VendorDetailScreenState extends ConsumerState<VendorDetailScreen> {
  Map<String, dynamic>? _vendor;
  bool _isLoading = true;
  String? _error;

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
      final service = ref.read(_vendorServiceProvider);
      final vendor = await service.getVendorById(widget.vendorId);
      if (!mounted) return;
      setState(() {
        _vendor = vendor;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      final l = _L(context.isAr);
      setState(() {
        _error = l.loadFailed;
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);

    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: Text(l.title)),
        body: Center(
          child: CircularProgressIndicator(
            color: m.isDark ? AppColors.accent : AppColors.primary,
          ),
        ),
      );
    }

    if (_error != null || _vendor == null) {
      return Scaffold(
        appBar: AppBar(title: Text(l.title)),
        body: ErrorState(message: _error ?? l.notFound, onRetry: _loadData),
      );
    }

    final vendor = _vendor!;
    final name = (vendor['name'] ?? l.unknown).toString();
    final email = (vendor['email'] ?? '').toString();
    final phone = (vendor['phone'] ?? '').toString();
    final address = (vendor['address'] ?? '').toString();
    final trn = (vendor['trn'] ?? '').toString();

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(title: Text(l.title)),
      body: RefreshIndicator(
        onRefresh: _loadData,
        color: m.isDark ? AppColors.accent : AppColors.primary,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: EdgeInsets.fromLTRB(
            16,
            16,
            16,
            AppInsets.bottomNav(context),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Dark chrome hero card
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  color: AppColors.primary,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: AppColors.accent.withValues(alpha: 0.14),
                  ),
                ),
                child: Column(
                  children: [
                    Container(
                      width: 64,
                      height: 64,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: AppColors.accent.withValues(alpha: 0.35),
                        ),
                      ),
                      alignment: Alignment.center,
                      child: Icon(
                        Icons.store_rounded,
                        color: AppColors.accent,
                        size: 30,
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      name,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 19,
                              fontWeight: FontWeight.w600,
                              color: AppColors.gold400,
                            )
                          : GoogleFonts.cinzel(
                              fontSize: 19,
                              color: AppColors.gold400,
                            ),
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 22),

              Text(
                l.details,
                style:
                    (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.josefinSans)(
                      fontSize: 10,
                      letterSpacing: l.ar ? 0 : 2.2,
                      color: m.textMuted,
                    ),
              ),
              const SizedBox(height: 12),
              Container(
                decoration: BoxDecoration(
                  color: m.surface,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: m.border),
                ),
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 4,
                ),
                child: Column(
                  children: [
                    if (email.isNotEmpty)
                      _DetailRow(
                        icon: Icons.email_outlined,
                        label: l.emailLabel,
                        value: email,
                      ),
                    if (phone.isNotEmpty)
                      _DetailRow(
                        icon: Icons.phone_outlined,
                        label: l.phoneLabel,
                        value: phone,
                      ),
                    if (address.isNotEmpty)
                      _DetailRow(
                        icon: Icons.location_on_outlined,
                        label: l.addressLabel,
                        value: address,
                      ),
                    if (trn.isNotEmpty)
                      _DetailRow(
                        icon: Icons.receipt_long_outlined,
                        label: l.trnLabel,
                        value: trn,
                      ),
                    if (email.isEmpty &&
                        phone.isEmpty &&
                        address.isEmpty &&
                        trn.isEmpty)
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 24),
                        child: Center(
                          child: Text(
                            l.noDetails,
                            style:
                                (l.ar
                                ? GoogleFonts.notoNaskhArabic
                                : GoogleFonts.josefinSans)(
                                  color: m.textMuted,
                                  fontSize: 14,
                                ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;

  const _DetailRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: AppColors.accentDark),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style:
                      (ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                        fontSize: 11,
                        letterSpacing: ar ? 0 : 1.4,
                        color: m.textMuted,
                      ),
                ),
                const SizedBox(height: 2),
                Text(
                  value,
                  style:
                      (ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: m.textPrimary,
                      ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'تفاصيل المورّد' : 'Vendor Details';
  String get loadFailed =>
      ar ? 'فشل تحميل تفاصيل المورد' : 'Failed to load vendor details';
  String get notFound => ar ? 'غير موجود' : 'Not found';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get details => ar ? 'التفاصيل' : 'Details';
  String get emailLabel => ar ? 'البريد الإلكتروني' : 'Email';
  String get phoneLabel => ar ? 'الهاتف' : 'Phone';
  String get addressLabel => ar ? 'العنوان' : 'Address';
  String get trnLabel => ar ? 'الرقم الضريبي' : 'TRN';
  String get noDetails =>
      ar ? 'لا توجد تفاصيل إضافية' : 'No additional details available';
}
