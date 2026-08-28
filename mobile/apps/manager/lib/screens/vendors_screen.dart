import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _vendorServiceProvider = Provider<VendorService>((ref) {
  final client = ref.watch(apiClientProvider);
  return VendorService(client.dio);
});

final _vendorsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_vendorServiceProvider);
  return service.getVendors();
});

/// Vendors directory, restyled to match the admin design language: dark
/// chrome search header, contact detail cards.
class VendorsScreen extends ConsumerStatefulWidget {
  const VendorsScreen({super.key});

  @override
  ConsumerState<VendorsScreen> createState() => _VendorsScreenState();
}

class _VendorsScreenState extends ConsumerState<VendorsScreen> {
  String _searchQuery = '';

  Future<void> _refresh() async {
    ref.invalidate(_vendorsProvider);
  }

  @override
  Widget build(BuildContext context) {
    final vendorsAsync = ref.watch(_vendorsProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(
            count: vendorsAsync.asData?.value.length,
            l: l,
            onSearchChanged: (v) =>
                setState(() => _searchQuery = v.toLowerCase()),
          ),
          Expanded(
            child: vendorsAsync.when(
              loading: () => Center(
                child: CircularProgressIndicator(
                  color: m.isDark ? AppColors.accent : AppColors.primary,
                ),
              ),
              error: (e, _) =>
                  ErrorState(message: l.loadFailed, onRetry: _refresh),
              data: (vendors) {
                final filtered = vendors.where((v) {
                  final nameEn = (v['nameEn'] ?? '').toString().toLowerCase();
                  final nameAr = (v['nameAr'] ?? '').toString().toLowerCase();
                  final email = (v['email'] ?? '').toString().toLowerCase();
                  final phone = (v['phone'] ?? '').toString().toLowerCase();
                  return nameEn.contains(_searchQuery) ||
                      nameAr.contains(_searchQuery) ||
                      email.contains(_searchQuery) ||
                      phone.contains(_searchQuery);
                }).toList();

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.store_outlined,
                    title: _searchQuery.isEmpty
                        ? l.noVendorsYet
                        : l.noMatchingVendors,
                    subtitle: _searchQuery.isEmpty ? l.addFirstVendor : null,
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: m.isDark ? AppColors.accent : AppColors.primary,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: EdgeInsets.fromLTRB(16, 8, 16, 24),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final vendor = filtered[index];
                      return _VendorCard(
                        vendor: vendor,
                        l: l,
                        onTap: () => context.push('/vendors/${vendor['id']}'),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: m.isDark ? AppColors.accent : AppColors.primary,
        onPressed: () => _showCreateVendorSheet(context),
        child: Icon(
          Icons.add,
          color: m.isDark ? AppColors.primary : Colors.white,
        ),
      ),
    );
  }

  void _showCreateVendorSheet(BuildContext context) {
    final l = _L(context.isAr);
    final nameCtrl = TextEditingController();
    final emailCtrl = TextEditingController();
    final phoneCtrl = TextEditingController();
    final addressCtrl = TextEditingController();
    final trnCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(
            24,
            24,
            24,
            MediaQuery.of(ctx).viewInsets.bottom + 24,
          ),
          child: Form(
            key: formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Center(
                    child: Container(
                      width: 40,
                      height: 4,
                      decoration: BoxDecoration(
                        color: AppColors.border,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    l.newVendor,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 19,
                            fontWeight: FontWeight.w600,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 18,
                            fontWeight: FontWeight.w600,
                          ),
                  ),
                  const SizedBox(height: 20),
                  TextFormField(
                    controller: nameCtrl,
                    decoration: InputDecoration(
                      labelText: l.name,
                      prefixIcon: const Icon(Icons.store_outlined),
                    ),
                    validator: (v) =>
                        v == null || v.trim().isEmpty ? l.nameRequired : null,
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: emailCtrl,
                    keyboardType: TextInputType.emailAddress,
                    decoration: InputDecoration(
                      labelText: l.email,
                      prefixIcon: const Icon(Icons.email_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: phoneCtrl,
                    keyboardType: TextInputType.phone,
                    decoration: InputDecoration(
                      labelText: l.phone,
                      prefixIcon: const Icon(Icons.phone_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: addressCtrl,
                    decoration: InputDecoration(
                      labelText: l.address,
                      prefixIcon: const Icon(Icons.location_on_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: trnCtrl,
                    decoration: InputDecoration(
                      labelText: l.trn,
                      prefixIcon: const Icon(Icons.receipt_long_outlined),
                    ),
                  ),
                  const SizedBox(height: 24),
                  GoldButton(
                    label: l.createVendor,
                    onPressed: () async {
                      if (!formKey.currentState!.validate()) return;
                      final service = ref.read(_vendorServiceProvider);
                      try {
                        await service.createVendor({
                          'nameEn': nameCtrl.text.trim(),
                          if (emailCtrl.text.isNotEmpty)
                            'email': emailCtrl.text.trim(),
                          if (phoneCtrl.text.isNotEmpty)
                            'phone': phoneCtrl.text.trim(),
                          if (addressCtrl.text.isNotEmpty)
                            'address': addressCtrl.text.trim(),
                          if (trnCtrl.text.isNotEmpty)
                            'trn': trnCtrl.text.trim(),
                        });
                        if (ctx.mounted) Navigator.pop(ctx);
                        _refresh();
                      } catch (e) {
                        if (ctx.mounted) {
                          ScaffoldMessenger.of(ctx).showSnackBar(
                            SnackBar(content: Text(l.createFailed)),
                          );
                        }
                      }
                    },
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ChromeHeader extends StatelessWidget {
  final int? count;
  final _L l;
  final ValueChanged<String> onSearchChanged;

  const _ChromeHeader({
    required this.count,
    required this.l,
    required this.onSearchChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            count != null ? l.vendorsCount(count!) : l.title,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 12,
                    color: AppColors.goldMid,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 10,
                    letterSpacing: 2.6,
                    color: AppColors.goldMid,
                  ),
          ),
          const SizedBox(height: 4),
          Text(
            l.title,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                    color: AppColors.gold400,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 22,
                    color: AppColors.gold400,
                  ),
          ),
          const SizedBox(height: 12),
          TextField(
            onChanged: onSearchChanged,
            style: (l.ar
                ? GoogleFonts.notoNaskhArabic
                : GoogleFonts
                      .plusJakartaSans)(fontSize: 13, color: Colors.white),
            decoration: InputDecoration(
              isDense: true,
              hintText: l.searchHint,
              hintStyle:
                  (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.plusJakartaSans)(
                    fontSize: 12.5,
                    color: Colors.white.withValues(alpha: 0.45),
                  ),
              prefixIcon: Icon(
                Icons.search,
                size: 18,
                color: Colors.white.withValues(alpha: 0.45),
              ),
              filled: true,
              fillColor: Colors.white.withValues(alpha: 0.07),
              contentPadding: const EdgeInsets.symmetric(vertical: 10),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(999),
                borderSide: BorderSide(
                  color: AppColors.accent.withValues(alpha: 0.2),
                ),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(999),
                borderSide: BorderSide(
                  color: AppColors.accent.withValues(alpha: 0.2),
                ),
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(999),
                borderSide: BorderSide(
                  color: AppColors.accent.withValues(alpha: 0.5),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _VendorCard extends StatelessWidget {
  final Map<String, dynamic> vendor;
  final _L l;
  final VoidCallback onTap;

  const _VendorCard({
    required this.vendor,
    required this.l,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final nameAr = (vendor['nameAr'] ?? '').toString();
    final name = l.ar && nameAr.isNotEmpty
        ? nameAr
        : (vendor['nameEn'] ?? l.unknown).toString();
    final email = (vendor['email'] ?? '').toString();
    final phone = (vendor['phone'] ?? '').toString();
    final trn = (vendor['trn'] ?? '').toString();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: m.background,
                  border: Border.all(color: m.borderStrong),
                ),
                alignment: Alignment.center,
                child: name.isNotEmpty
                    ? Text(
                        name[0].toUpperCase(),
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 13,
                          color: AppColors.accentDark,
                        ),
                      )
                    : Icon(
                        Icons.store_outlined,
                        color: AppColors.accentDark,
                        size: 18,
                      ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name,
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.plusJakartaSans)(
                            fontWeight: FontWeight.w600,
                            fontSize: 14,
                            color: m.textPrimary,
                          ),
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 4),
                    if (email.isNotEmpty)
                      Text(
                        email,
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.plusJakartaSans)(
                              fontSize: 12,
                              color: m.textSecondary,
                            ),
                      ),
                    if (phone.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        phone,
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.plusJakartaSans)(
                              fontSize: 12,
                              color: m.textMuted,
                            ),
                      ),
                    ],
                    if (trn.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        l.trnLine(trn),
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.plusJakartaSans)(
                              fontSize: 11,
                              color: m.textMuted,
                            ),
                      ),
                    ],
                  ],
                ),
              ),
              Icon(Icons.chevron_right, color: m.textMuted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المورّدون' : 'Vendors';
  String vendorsCount(int n) => ar ? '$n موردًا' : '$n VENDORS';
  String get searchHint => ar ? 'ابحث في الموردين' : 'Search vendors...';
  String get loadFailed => ar ? 'فشل تحميل الموردين' : 'Failed to load vendors';
  String get noVendorsYet => ar ? 'لا يوجد موردون بعد' : 'No vendors yet';
  String get noMatchingVendors =>
      ar ? 'لا يوجد موردون مطابقون' : 'No matching vendors';
  String get addFirstVendor =>
      ar ? 'أضف أول مورد لديك' : 'Add your first vendor';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get newVendor => ar ? 'مورّد جديد' : 'New Vendor';
  String get name => ar ? 'الاسم' : 'Name';
  String get nameRequired => ar ? 'الاسم مطلوب' : 'Name is required';
  String get email => ar ? 'البريد الإلكتروني' : 'Email';
  String get phone => ar ? 'الهاتف' : 'Phone';
  String get address => ar ? 'العنوان' : 'Address';
  String get trn => ar ? 'الرقم الضريبي' : 'TRN (Tax Registration Number)';
  String trnLine(String trn) => ar ? 'الرقم الضريبي: $trn' : 'TRN: $trn';
  String get createVendor => ar ? 'إنشاء مورّد' : 'Create Vendor';
  String get createFailed =>
      ar ? 'فشل إنشاء المورد' : 'Failed to create vendor';
}
