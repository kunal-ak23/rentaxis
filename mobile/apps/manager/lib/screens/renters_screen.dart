import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _renterServiceProvider = Provider<RenterService>((ref) {
  final client = ref.watch(apiClientProvider);
  return RenterService(client.dio);
});

final _rentersProvider = FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_renterServiceProvider);
  return service.getRenters();
});

/// Locale-aware display name from the RenterDTO (nameEn/nameAr).
String _renterName(dynamic renter, bool ar) {
  final nameAr = (renter['nameAr'] ?? '').toString().trim();
  if (ar && nameAr.isNotEmpty) return nameAr;
  return (renter['nameEn'] ?? '').toString().trim();
}

/// Renters directory, per design 1h: dark chrome search header, rows grouped
/// under alphabetical section labels with email/phone contact lines.
class RentersScreen extends ConsumerStatefulWidget {
  const RentersScreen({super.key});

  @override
  ConsumerState<RentersScreen> createState() => _RentersScreenState();
}

class _RentersScreenState extends ConsumerState<RentersScreen> {
  String _searchQuery = '';

  Future<void> _refresh() async {
    ref.invalidate(_rentersProvider);
  }

  @override
  Widget build(BuildContext context) {
    final rentersAsync = ref.watch(_rentersProvider);
    final m = context.miftah;
    final l = _L(context.isAr);
    // POST /v1/renters is restricted to SUPER_ADMIN/TENANT_ADMIN.
    final canCreate = ref.watch(authProvider).role != 'PROPERTY_MANAGER';

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(
            count: rentersAsync.asData?.value.length,
            l: l,
            onSearchChanged: (v) =>
                setState(() => _searchQuery = v.toLowerCase()),
          ),
          Expanded(
            child: rentersAsync.when(
              loading: () => Center(
                child: CircularProgressIndicator(
                  color: m.isDark ? AppColors.accent : AppColors.primary,
                ),
              ),
              error: (e, _) =>
                  ErrorState(message: l.loadFailed, onRetry: _refresh),
              data: (renters) {
                final filtered =
                    renters.where((r) {
                      final name = _renterName(r, l.ar).toLowerCase();
                      final email = (r['email'] ?? '').toString().toLowerCase();
                      final phone = (r['phone'] ?? '').toString().toLowerCase();
                      return name.contains(_searchQuery) ||
                          email.contains(_searchQuery) ||
                          phone.contains(_searchQuery);
                    }).toList()..sort(
                      (a, b) => _renterName(a, l.ar).toLowerCase().compareTo(
                        _renterName(b, l.ar).toLowerCase(),
                      ),
                    );

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.people_outline,
                    title: _searchQuery.isEmpty
                        ? l.noRentersYet
                        : l.noMatchingRenters,
                    subtitle: _searchQuery.isEmpty ? l.addFirstRenter : null,
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: m.isDark ? AppColors.accent : AppColors.primary,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: EdgeInsets.fromLTRB(
                      0,
                      8,
                      0,
                      24,
                    ),
                    itemCount: _rowCount(filtered, l.ar),
                    itemBuilder: (context, index) =>
                        _buildRow(context, filtered, index, l),
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: canCreate
          ? FloatingActionButton(
              backgroundColor: AppColors.primary,
              onPressed: () => _showCreateRenterSheet(context),
              child: const Icon(Icons.person_add_outlined, color: Colors.white),
            )
          : null,
    );
  }

  /// Interleaves section-label rows ahead of each new starting letter.
  int _rowCount(List<dynamic> renters, bool ar) {
    var count = 0;
    String? lastLetter;
    for (final r in renters) {
      final letter = _firstLetter(r, ar);
      if (letter != lastLetter) {
        count++;
        lastLetter = letter;
      }
      count++;
    }
    return count;
  }

  String _firstLetter(dynamic renter, bool ar) {
    final name = _renterName(renter, ar);
    return name.isNotEmpty ? name[0].toUpperCase() : '#';
  }

  Widget _buildRow(
    BuildContext context,
    List<dynamic> renters,
    int index,
    _L l,
  ) {
    var i = 0;
    String? lastLetter;
    for (final r in renters) {
      final letter = _firstLetter(r, l.ar);
      if (letter != lastLetter) {
        if (i == index) return _SectionLabel(letter: letter);
        i++;
        lastLetter = letter;
      }
      if (i == index) {
        return _RenterRow(renter: r, l: l);
      }
      i++;
    }
    return const SizedBox.shrink();
  }

  void _showCreateRenterSheet(BuildContext context) {
    final l = _L(context.isAr);
    final nameCtrl = TextEditingController();
    final emailCtrl = TextEditingController();
    final phoneCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();
    String language = 'EN';

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
                    l.newRenter,
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
                      labelText: l.fullName,
                      prefixIcon: const Icon(Icons.person_outline),
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
                    validator: (v) {
                      if (v == null || v.trim().isEmpty) {
                        return l.emailRequired;
                      }
                      if (!RegExp(r'^[^@]+@[^@]+\.[^@]+$').hasMatch(v.trim())) {
                        return l.emailInvalid;
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: phoneCtrl,
                    keyboardType: TextInputType.phone,
                    decoration: InputDecoration(
                      labelText: l.phoneNumber,
                      prefixIcon: const Icon(Icons.phone_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  DropdownButtonFormField<String>(
                    initialValue: language,
                    decoration: InputDecoration(
                      labelText: l.preferredLanguage,
                      prefixIcon: const Icon(Icons.language),
                    ),
                    items: [
                      DropdownMenuItem(value: 'EN', child: Text(l.english)),
                      DropdownMenuItem(value: 'AR', child: Text(l.arabic)),
                    ],
                    onChanged: (v) => setSheetState(() => language = v ?? 'EN'),
                  ),
                  const SizedBox(height: 24),
                  GoldButton(
                    label: l.createRenter,
                    onPressed: () async {
                      if (!formKey.currentState!.validate()) return;
                      final service = ref.read(_renterServiceProvider);
                      final email = emailCtrl.text.trim();
                      try {
                        final created = await service.createRenter({
                          'nameEn': nameCtrl.text.trim(),
                          'email': email,
                          if (phoneCtrl.text.isNotEmpty)
                            'phone': phoneCtrl.text.trim(),
                          'primaryLanguage': language,
                        });
                        if (ctx.mounted) Navigator.pop(ctx);
                        _refresh();
                        final portalPassword = created['portalPassword']
                            ?.toString();
                        if (portalPassword != null &&
                            portalPassword.isNotEmpty &&
                            mounted) {
                          _showPortalCredentialsDialog(email, portalPassword);
                        }
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

  /// Portal credentials are returned once on creation (RenterDTO
  /// portalPassword) — surface them so the admin can share them, like web.
  void _showPortalCredentialsDialog(String email, String password) {
    final l = _L(context.isAr);
    final body = l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.plusJakartaSans;
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          l.portalCredentialsTitle,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 19,
                  fontWeight: FontWeight.w600,
                )
              : GoogleFonts.plusJakartaSans(fontSize: 17, fontWeight: FontWeight.w600),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              l.portalCredentialsNote,
              style: body(fontSize: 13, color: AppColors.textSecondary),
            ),
            const SizedBox(height: 14),
            SelectableText('${l.email}: $email', style: body(fontSize: 13.5)),
            const SizedBox(height: 6),
            SelectableText(
              '${l.password}: $password',
              style: body(fontSize: 13.5, fontWeight: FontWeight.w600),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(l.done, style: body(fontWeight: FontWeight.w600)),
          ),
        ],
      ),
    );
  }
}

/// Dark chrome header: renter count overline, "Directory" title, pill search.
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
            count != null ? l.rentersCount(count!) : l.directory,
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
            l.directory,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 22,
                    fontWeight: FontWeight.w600,
                    color: AppColors.gold400,
                  )
                : GoogleFonts.plusJakartaSans(fontSize: 22, color: AppColors.gold400),
          ),
          const SizedBox(height: 12),
          TextField(
            onChanged: onSearchChanged,
            style: (l.ar
                ? GoogleFonts.notoNaskhArabic
                : GoogleFonts.plusJakartaSans)(fontSize: 13, color: Colors.white),
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

class _SectionLabel extends StatelessWidget {
  final String letter;
  const _SectionLabel({required this.letter});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 18, top: 14, bottom: 6),
      child: Text(
        letter,
        style: GoogleFonts.plusJakartaSans(
          fontSize: 9,
          letterSpacing: 2.6,
          color: AppColors.accentDark,
        ),
      ),
    );
  }
}

class _RenterRow extends StatelessWidget {
  final Map<String, dynamic> renter;
  final _L l;
  const _RenterRow({required this.renter, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final name = _renterName(renter, l.ar);
    final email = (renter['email'] ?? '').toString();
    final phone = (renter['phone'] ?? '').toString();
    final subtitle = [
      if (email.isNotEmpty) email,
      if (phone.isNotEmpty) phone,
    ].join(' · ');

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        border: Border(
          top: BorderSide(color: m.divider),
          bottom: BorderSide(color: m.divider),
        ),
      ),
      child: InkWell(
        onTap: () {},
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 11),
          child: Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: m.background,
                  border: Border.all(color: m.borderStrong),
                ),
                alignment: Alignment.center,
                child: Text(
                  _initials(name),
                  style: GoogleFonts.plusJakartaSans(
                    fontSize: 12,
                    color: AppColors.accentDark,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name.isNotEmpty ? name : l.unknown,
                      style:
                          (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.plusJakartaSans)(
                            fontSize: 14,
                            color: m.textPrimary,
                          ),
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (subtitle.isNotEmpty)
                      Text(
                        subtitle,
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.plusJakartaSans)(
                              fontSize: 11.5,
                              color: m.textMuted,
                            ),
                        overflow: TextOverflow.ellipsis,
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

  String _initials(String name) {
    final parts = name.trim().split(RegExp(r'\s+'));
    if (parts.isEmpty || parts.first.isEmpty) return '?';
    if (parts.length == 1) return parts.first[0].toUpperCase();
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get directory => ar ? 'الدليل' : 'Directory';
  String rentersCount(int n) => ar ? '$n مستأجرًا' : '$n RENTERS';
  String get searchHint => ar
      ? 'ابحث بالاسم أو البريد الإلكتروني أو الهاتف'
      : 'Search name, email, phone';
  String get loadFailed =>
      ar ? 'فشل تحميل المستأجرين' : 'Failed to load renters';
  String get noRentersYet => ar ? 'لا يوجد مستأجرون بعد' : 'No renters yet';
  String get noMatchingRenters =>
      ar ? 'لا يوجد مستأجرون مطابقون' : 'No matching renters';
  String get addFirstRenter =>
      ar ? 'أضف أول مستأجر لديك' : 'Add your first renter';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get newRenter => ar ? 'مستأجر جديد' : 'New Renter';
  String get fullName => ar ? 'الاسم الكامل' : 'Full Name';
  String get nameRequired => ar ? 'الاسم مطلوب' : 'Name is required';
  String get email => ar ? 'البريد الإلكتروني' : 'Email';
  String get emailRequired =>
      ar ? 'البريد الإلكتروني مطلوب' : 'Email is required';
  String get emailInvalid =>
      ar ? 'أدخل بريدًا إلكترونيًا صالحًا' : 'Enter a valid email';
  String get phoneNumber => ar ? 'رقم الهاتف' : 'Phone Number';
  String get preferredLanguage => ar ? 'اللغة المفضلة' : 'Preferred Language';
  String get english => ar ? 'الإنجليزية' : 'English';
  String get arabic => ar ? 'العربية' : 'Arabic';
  String get createRenter => ar ? 'إنشاء مستأجر' : 'Create Renter';
  String get createFailed =>
      ar ? 'فشل إنشاء المستأجر' : 'Failed to create renter';
  String get portalCredentialsTitle =>
      ar ? 'تم إنشاء حساب البوابة' : 'Portal Account Created';
  String get portalCredentialsNote => ar
      ? 'شارك بيانات الدخول هذه مع المستأجر — تظهر كلمة المرور مرة واحدة فقط.'
      : 'Share these credentials with the renter — the password is shown only once.';
  String get password => ar ? 'كلمة المرور' : 'Password';
  String get done => ar ? 'تم' : 'Done';
}
