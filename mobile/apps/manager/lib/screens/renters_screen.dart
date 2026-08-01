import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _renterServiceProvider = Provider<RenterService>((ref) {
  final client = ref.watch(apiClientProvider);
  return RenterService(client.dio);
});

final _rentersProvider = FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_renterServiceProvider);
  return service.getRenters();
});

/// Renters directory, per design 1h: dark chrome search header, rows grouped
/// under alphabetical section labels with balance/status badges.
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
                      final name = (r['name'] ?? '').toString().toLowerCase();
                      final email = (r['email'] ?? '').toString().toLowerCase();
                      final phone = (r['phoneNumber'] ?? r['phone'] ?? '')
                          .toString()
                          .toLowerCase();
                      final unit = (r['unitNumber'] ?? r['unit'] ?? '')
                          .toString()
                          .toLowerCase();
                      return name.contains(_searchQuery) ||
                          email.contains(_searchQuery) ||
                          phone.contains(_searchQuery) ||
                          unit.contains(_searchQuery);
                    }).toList()..sort(
                      (a, b) =>
                          (a['name'] ?? '').toString().toLowerCase().compareTo(
                            (b['name'] ?? '').toString().toLowerCase(),
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
                      AppInsets.bottomNav(context),
                    ),
                    itemCount: _rowCount(filtered),
                    itemBuilder: (context, index) =>
                        _buildRow(context, filtered, index, l),
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        onPressed: () => _showCreateRenterSheet(context),
        child: const Icon(Icons.person_add_outlined, color: Colors.white),
      ),
    );
  }

  /// Interleaves section-label rows ahead of each new starting letter.
  int _rowCount(List<dynamic> renters) {
    var count = 0;
    String? lastLetter;
    for (final r in renters) {
      final letter = _firstLetter(r);
      if (letter != lastLetter) {
        count++;
        lastLetter = letter;
      }
      count++;
    }
    return count;
  }

  String _firstLetter(dynamic renter) {
    final name = (renter['name'] ?? '').toString().trim();
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
      final letter = _firstLetter(r);
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
    String language = 'ENGLISH';

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
                        : GoogleFonts.cinzel(
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
                      DropdownMenuItem(
                        value: 'ENGLISH',
                        child: Text(l.english),
                      ),
                      DropdownMenuItem(value: 'ARABIC', child: Text(l.arabic)),
                    ],
                    onChanged: (v) =>
                        setSheetState(() => language = v ?? 'ENGLISH'),
                  ),
                  const SizedBox(height: 24),
                  GoldButton(
                    label: l.createRenter,
                    onPressed: () async {
                      if (!formKey.currentState!.validate()) return;
                      final service = ref.read(_renterServiceProvider);
                      try {
                        await service.createRenter({
                          'name': nameCtrl.text.trim(),
                          'email': emailCtrl.text.trim(),
                          if (phoneCtrl.text.isNotEmpty)
                            'phoneNumber': phoneCtrl.text.trim(),
                          'preferredLanguage': language,
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
                : GoogleFonts.josefinSans(
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
                : GoogleFonts.cinzel(fontSize: 22, color: AppColors.gold400),
          ),
          const SizedBox(height: 12),
          TextField(
            onChanged: onSearchChanged,
            style: (l.ar
                ? GoogleFonts.notoNaskhArabic
                : GoogleFonts.josefinSans)(fontSize: 13, color: Colors.white),
            decoration: InputDecoration(
              isDense: true,
              hintText: l.searchHint,
              hintStyle:
                  (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(
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
        style: GoogleFonts.josefinSans(
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
    final name = (renter['name'] ?? '').toString();
    final unit = (renter['unitNumber'] ?? renter['unit'] ?? '').toString();
    final property = (renter['propertyName'] ?? renter['property'] ?? '')
        .toString();
    final subtitle = [
      if (property.isNotEmpty) property,
      if (unit.isNotEmpty) unit,
    ].join(' · ');
    final status = (renter['status'] ?? '').toString();
    final balance = renter['balance'];

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
                  style: GoogleFonts.cinzel(
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
                          : GoogleFonts.josefinSans)(
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
                            : GoogleFonts.josefinSans)(
                              fontSize: 11.5,
                              color: m.textMuted,
                            ),
                        overflow: TextOverflow.ellipsis,
                      ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              _trailing(m, status, balance),
            ],
          ),
        ),
      ),
    );
  }

  Widget _trailing(MiftahColors m, String status, dynamic balance) {
    if (status.isNotEmpty) {
      final color = _statusColor(m, status);
      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          color: color.withValues(alpha: 0.08),
          border: Border.all(color: color.withValues(alpha: 0.28)),
        ),
        child: Text(
          l.ar ? l.statusLabel(status) : l.statusLabel(status).toUpperCase(),
          style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
            fontSize: 9.5,
            letterSpacing: l.ar ? 0 : 1.4,
            color: color,
          ),
        ),
      );
    }

    final amount = (balance is num) ? balance : num.tryParse('$balance') ?? 0;
    final overdue = amount > 0;
    final color = overdue ? m.danger : m.success;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Text(
          NumberFormat.decimalPattern('en').format(amount),
          style: GoogleFonts.cinzel(fontSize: 14, color: color),
        ),
        Text(
          overdue ? l.overdue : l.balanceLabel,
          style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
            fontSize: 9.5,
            color: color,
          ),
        ),
      ],
    );
  }

  Color _statusColor(MiftahColors m, String status) {
    switch (status.toUpperCase()) {
      case 'EXPIRING':
        return m.warning;
      case 'OVERDUE':
        return m.danger;
      case 'PENDING':
        return m.textSecondary;
      default:
        return m.textSecondary;
    }
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
  String get searchHint =>
      ar ? 'ابحث بالاسم أو الوحدة أو الهاتف' : 'Search name, unit, phone';
  String get loadFailed =>
      ar ? 'فشل تحميل المستأجرين' : 'Failed to load renters';
  String get noRentersYet => ar ? 'لا يوجد مستأجرون بعد' : 'No renters yet';
  String get noMatchingRenters =>
      ar ? 'لا يوجد مستأجرون مطابقون' : 'No matching renters';
  String get addFirstRenter =>
      ar ? 'أضف أول مستأجر لديك' : 'Add your first renter';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get overdue => ar ? 'متأخر' : 'overdue';
  String get balanceLabel => ar ? 'الرصيد' : 'balance';
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

  String statusLabel(String status) {
    switch (status.toUpperCase()) {
      case 'PENDING':
        return ar ? 'قيد الانتظار' : 'Pending';
      case 'EXPIRING':
        return ar ? 'قارب على الانتهاء' : 'Expiring';
      case 'OVERDUE':
        return ar ? 'متأخر' : 'Overdue';
      default:
        return status;
    }
  }
}
