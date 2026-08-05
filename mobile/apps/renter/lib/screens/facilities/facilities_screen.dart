import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/facility_provider.dart';
import '../../providers/gate_pass_provider.dart' show activeLeasesProvider;

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المرافق' : 'Facilities';
  String get amenitiesSection => ar ? 'المرافق' : 'Amenities';
  String get parkingSection => ar ? 'مواقف السيارات' : 'Parking';
  String get loadFailed =>
      ar ? 'فشل تحميل المرافق' : 'Failed to load facilities';
  String get nothingHere => ar ? 'لا توجد مرافق' : 'No facilities yet';
  String get nothingHereSub => ar
      ? 'عندما يضيف مالك العقار مرافق أو مواقف لوحدتك، تظهر هنا.'
      : 'When your landlord adds amenities or parking for your unit, '
            'they appear here.';
  String get request => ar ? 'طلب حجز' : 'Request';
  String get held => ar ? 'محجوز' : 'Held';
  String get notBookable => ar ? 'غير قابل للحجز' : 'Not bookable';
  String get unit => ar ? 'الوحدة' : 'Unit';
  String get preferredDate =>
      ar ? 'التاريخ المفضل (اختياري)' : 'Preferred date (optional)';
  String get pickADate => ar ? 'اختر تاريخًا' : 'Pick a date';
  String get noteOptional => ar ? 'ملاحظة (اختياري)' : 'Note (optional)';
  String get noteHint =>
      ar ? 'مثال: حفلة عائلية يوم الجمعة' : 'e.g. family gathering on Friday';
  String get sendRequest => ar ? 'إرسال الطلب' : 'Send request';
  String get requestSent => ar ? 'تم إرسال طلبك' : 'Your request was sent';
  String get spotTaken => ar
      ? 'هذا الموقف محجوز بالفعل لمستأجر آخر.'
      : 'This spot is already held by another renter.';
  String get cannotBook =>
      ar ? 'هذا المرفق غير قابل للحجز.' : 'This facility is not bookable.';
  String get noLeaseForProperty => ar
      ? 'لا يوجد لديك عقد إيجار نشط في هذا العقار.'
      : 'You have no active lease at this property.';
  String get requestFailed => ar
      ? 'تعذر إرسال الطلب. حاول مرة أخرى.'
      : 'Could not send the request. Try again.';
  String get leasesLoadFailed =>
      ar ? 'تعذر التحقق من عقد إيجارك.' : 'Could not check your tenancy.';
  String requestTitle(String name) =>
      ar ? 'طلب حجز $name' : 'Request $name';
  String pendingHint(int n) => ar
      ? '$n قيد الانتظار'
      : n == 1
          ? '1 pending request'
          : '$n pending requests';
  String levelLabel(String level) => ar ? 'الطابق $level' : 'Level $level';
  String get coveredPill => ar ? 'مسقوف' : 'Covered';

  String status(String value) => switch (value) {
    'PENDING' => ar ? 'قيد الانتظار' : 'PENDING',
    'APPROVED' => ar ? 'مقبول' : 'APPROVED',
    'REJECTED' => ar ? 'مرفوض' : 'REJECTED',
    'CANCELLED' => ar ? 'ملغي' : 'CANCELLED',
    'RELEASED' => ar ? 'تم الإخلاء' : 'RELEASED',
    _ => value.replaceAll('_', ' '),
  };
}

Color _statusColor(String? status, MiftahColors m) => switch (status) {
  'PENDING' => m.warning,
  'APPROVED' => m.success,
  'REJECTED' => m.danger,
  'RELEASED' => AppColors.accentDark,
  _ => m.textMuted,
};

String _displayName(Map<String, dynamic> row, bool ar) {
  final nameAr = row['nameAr']?.toString();
  if (ar && nameAr != null && nameAr.isNotEmpty) return nameAr;
  return row['nameEn']?.toString() ?? '—';
}

List<Map<String, dynamic>> _rows(dynamic value) => (value as List? ?? const [])
    .whereType<Map>()
    .map((r) => Map<String, dynamic>.from(r))
    .toList();

/// The renter's own open request per resource id. PENDING/APPROVED only —
/// terminal statuses do not block a new request. `/v1/bookings/my` is
/// createdAt ASC, so the last write wins (the newest open request).
Map<String, Map<String, dynamic>> _openByResource(
  List<Map<String, dynamic>> mine,
) {
  final result = <String, Map<String, dynamic>>{};
  for (final row in mine) {
    final status = row['status']?.toString();
    if (status != 'PENDING' && status != 'APPROVED') continue;
    final resourceId = (row['amenityId'] ?? row['parkingSpotId'])?.toString();
    if (resourceId != null) result[resourceId] = row;
  }
  return result;
}

/// `preferredDate` is a LocalDate server-side — send `yyyy-MM-dd`, never an
/// instant (an instant would shift by the device's UTC offset).
String _localDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-'
    '${d.day.toString().padLeft(2, '0')}';

/// Browse the amenities and parking spots visible to my unit(s), and raise
/// booking requests. Parameterless and self-fetching (router discards `extra`
/// on auth rebuilds — see router.dart).
class FacilitiesScreen extends ConsumerWidget {
  const FacilitiesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final facilities = ref.watch(myFacilitiesProvider);
    final mine = ref.watch(myBookingRequestsProvider);

    Future<void> refresh() async {
      ref.invalidate(myFacilitiesProvider);
      ref.invalidate(myBookingRequestsProvider);
      await ref.read(myFacilitiesProvider.future);
    }

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                )
              : null,
        ),
        actions: [
          IconButton(
            key: const Key('my-requests'),
            icon: const Icon(Icons.history),
            onPressed: () => context.push('/facilities/requests'),
          ),
        ],
      ),
      body: RefreshIndicator(
        color: AppColors.accent,
        onRefresh: refresh,
        child: facilities.when(
          loading: () => const ListShimmer(itemCount: 4),
          error: (error, _) => _Scrollable(
            child: ErrorState(message: l.loadFailed, onRetry: refresh),
          ),
          data: (data) {
            final amenities = _rows(data['amenities']);
            final spots = _rows(data['parkingSpots']);
            if (amenities.isEmpty && spots.isEmpty) {
              return _Scrollable(
                child: EmptyState(
                  icon: Icons.pool_outlined,
                  title: l.nothingHere,
                  subtitle: l.nothingHereSub,
                ),
              );
            }
            final open = _openByResource(mine.valueOrNull ?? const []);
            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.fromLTRB(
                16,
                12,
                16,
                AppInsets.bottomNav(context),
              ),
              children: [
                if (amenities.isNotEmpty) ...[
                  _SectionLabel(text: l.amenitiesSection),
                  for (final (i, row) in amenities.indexed)
                    AnimatedListItem(
                      index: i,
                      child: _AmenityCard(
                        row: row,
                        myOpen: open[row['id']?.toString()],
                        l: l,
                      ),
                    ),
                ],
                if (spots.isNotEmpty) ...[
                  _SectionLabel(text: l.parkingSection),
                  for (final (i, row) in spots.indexed)
                    AnimatedListItem(
                      index: i,
                      child: _SpotCard(
                        row: row,
                        myOpen: open[row['id']?.toString()],
                        l: l,
                      ),
                    ),
                ],
              ],
            );
          },
        ),
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel({required this.text});

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 2, top: 8, bottom: 10),
      child: Text(
        ar ? text : text.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 12.5,
                color: AppColors.accentDark,
              )
            : GoogleFonts.josefinSans(
                fontSize: 10.5,
                letterSpacing: 2.4,
                color: AppColors.accentDark,
              ),
      ),
    );
  }
}

class _AmenityCard extends ConsumerWidget {
  final Map<String, dynamic> row;
  final Map<String, dynamic>? myOpen;
  final _L l;
  const _AmenityCard({required this.row, required this.myOpen, required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final bookable = row['bookable'] == true;
    final pendingCount = (row['pendingCount'] as num?)?.toInt() ?? 0;
    final property = row['propertyName']?.toString();
    final description = row['description']?.toString();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.pool_outlined, size: 18, color: AppColors.accentDark),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  _displayName(row, l.ar),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                ),
              ),
              if (myOpen != null)
                StatusBadge(
                  label: l.status(myOpen!['status']?.toString() ?? ''),
                  color: _statusColor(myOpen!['status']?.toString(), m),
                )
              else if (!bookable)
                StatusBadge(label: l.notBookable, color: m.textMuted),
            ],
          ),
          if (property != null) ...[
            const SizedBox(height: 4),
            Text(
              property,
              style: GoogleFonts.josefinSans(
                fontSize: 11.5,
                color: m.textMuted,
              ),
            ),
          ],
          if (description != null && description.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              description,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(
                fontSize: 12.5,
                color: m.textSecondary,
                height: 1.4,
              ),
            ),
          ],
          if (pendingCount > 0) ...[
            const SizedBox(height: 6),
            Text(
              l.pendingHint(pendingCount),
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(fontSize: 12, color: m.warning),
            ),
          ],
          if (bookable && myOpen == null) ...[
            const SizedBox(height: 12),
            GoldButton(
              label: l.request,
              height: 42,
              onPressed: () => _openRequestSheet(
                context,
                ref,
                resourceType: 'AMENITY',
                row: row,
                l: l,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _SpotCard extends ConsumerWidget {
  final Map<String, dynamic> row;
  final Map<String, dynamic>? myOpen;
  final _L l;
  const _SpotCard({required this.row, required this.myOpen, required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final held = row['held'] == true;
    final pendingCount = (row['pendingCount'] as num?)?.toInt() ?? 0;
    final level = row['level']?.toString();
    final property = row['propertyName']?.toString();
    final meta = [
      ?property,
      if (level != null && level.isNotEmpty) l.levelLabel(level),
      if (row['covered'] == true) l.coveredPill,
    ].join(' · ');

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.local_parking_outlined,
                size: 18,
                color: AppColors.accentDark,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  row['spotNumber']?.toString() ?? '—',
                  style: GoogleFonts.josefinSans(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
                ),
              ),
              if (myOpen != null)
                StatusBadge(
                  label: l.status(myOpen!['status']?.toString() ?? ''),
                  color: _statusColor(myOpen!['status']?.toString(), m),
                )
              else if (held)
                StatusBadge(label: l.held, color: m.warning),
            ],
          ),
          if (meta.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              meta,
              style: GoogleFonts.josefinSans(
                fontSize: 11.5,
                color: m.textMuted,
              ),
            ),
          ],
          if (pendingCount > 0) ...[
            const SizedBox(height: 6),
            Text(
              l.pendingHint(pendingCount),
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(fontSize: 12, color: m.warning),
            ),
          ],
          if (!held && myOpen == null) ...[
            const SizedBox(height: 12),
            GoldButton(
              label: l.request,
              height: 42,
              onPressed: () => _openRequestSheet(
                context,
                ref,
                resourceType: 'PARKING_SPOT',
                row: row,
                l: l,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

Future<void> _openRequestSheet(
  BuildContext context,
  WidgetRef ref, {
  required String resourceType,
  required Map<String, dynamic> row,
  required _L l,
}) async {
  final resourceId = row['id']?.toString();
  final propertyId = row['propertyId']?.toString();
  if (resourceId == null || propertyId == null) return;
  final name = resourceType == 'AMENITY'
      ? _displayName(row, l.ar)
      : (row['spotNumber']?.toString() ?? '—');
  final created = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    backgroundColor: context.miftah.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (_) => _RequestSheet(
      resourceType: resourceType,
      resourceId: resourceId,
      propertyId: propertyId,
      resourceName: name,
    ),
  );
  if (created != true) return;
  // The sheet awaited a network round trip before popping — the calling
  // widget (this card) may have been disposed in the meantime (e.g. a pull
  // to refresh swapped the list out from under it), so `ref`/`context` are
  // only safe to touch past this await once `mounted` is re-checked.
  if (!context.mounted) return;
  ref.invalidate(myFacilitiesProvider);
  ref.invalidate(myBookingRequestsProvider);
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(content: Text(l.requestSent), behavior: SnackBarBehavior.floating),
  );
}

/// Booking-request sheet: preferred date + note, unit resolved from the
/// caller's ACTIVE leases at the resource's property.
///
/// The unit comes from an ACTIVE lease and only an ACTIVE one — the backend
/// 404s a unit that is not on the caller's active lease (deliberately 404,
/// not 403), same rule as the gate-pass create screen.
///
/// `facilities/my` is a cross-unit UNION across every property the renter
/// has an active lease at, so — unlike the gate-pass create screen, which
/// only ever deals with one property at a time — the matching leases here
/// are filtered to the *resource's* property first. Exactly one match is
/// shown as read-only text (still surfaced, not just silently assumed, since
/// a multi-property renter should see which unit the request is for); more
/// than one shows a picker; zero is a dead end explained in place. Either
/// way, the posted `unitId` only ever comes from this filtered set — never a
/// unit id typed or guessed some other way.
class _RequestSheet extends ConsumerStatefulWidget {
  final String resourceType;
  final String resourceId;
  final String propertyId;
  final String resourceName;

  const _RequestSheet({
    required this.resourceType,
    required this.resourceId,
    required this.propertyId,
    required this.resourceName,
  });

  @override
  ConsumerState<_RequestSheet> createState() => _RequestSheetState();
}

class _RequestSheetState extends ConsumerState<_RequestSheet> {
  final _noteCtrl = TextEditingController();
  DateTime? _preferredDate;
  String? _selectedUnitId;
  bool _submitting = false;

  @override
  void dispose() {
    _noteCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final l = _L(context.isAr);
    final unitId = _selectedUnitId;
    if (unitId == null) return;
    setState(() => _submitting = true);
    try {
      await ref.read(facilityServiceProvider).createBooking({
        'resourceType': widget.resourceType,
        'resourceId': widget.resourceId,
        'unitId': unitId,
        if (_preferredDate != null)
          'preferredDate': _localDate(_preferredDate!),
        if (_noteCtrl.text.trim().isNotEmpty) 'note': _noteCtrl.text.trim(),
      });
      if (!mounted) return;
      Navigator.pop(context, true);
    } on DioException catch (error) {
      if (!mounted) return;
      setState(() => _submitting = false);
      _toast(_describe(error, l));
    } catch (error) {
      if (!mounted) return;
      setState(() => _submitting = false);
      _toast(errorMessage(error, l.requestFailed));
    }
  }

  /// Status-code specific fallback, but the server's own message wins when
  /// it sent one — mirrors `booking_approvals_screen.dart`'s `_decide`
  /// (`errorMessage(error, <status-specific fallback>)`), not a hardcoded
  /// string per status.
  String _describe(DioException error, _L l) {
    final status = error.response?.statusCode;
    if (status == 409) return errorMessage(error, l.spotTaken);
    if (status == 400) return errorMessage(error, l.cannotBook);
    if (status == 404) return errorMessage(error, l.noLeaseForProperty);
    return errorMessage(error, l.requestFailed);
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final leases = ref.watch(activeLeasesProvider);

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
            child: leases.when(
              loading: () => const SizedBox(
                height: 180,
                child: Center(
                  child: CircularProgressIndicator(color: AppColors.accent),
                ),
              ),
              error: (error, _) => ErrorState(
                message: l.leasesLoadFailed,
                onRetry: () => ref.invalidate(activeLeasesProvider),
              ),
              data: (rows) {
                final mine = rows
                    .where(
                      (r) => r['propertyId']?.toString() == widget.propertyId,
                    )
                    .toList();
                if (mine.isEmpty) {
                  // Every field below would be filled in for a request the
                  // server will 404; carry the explanation here instead.
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 24),
                    child: Text(
                      l.noLeaseForProperty,
                      style: (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.josefinSans)(
                        fontSize: 13.5,
                        color: m.textSecondary,
                        height: 1.5,
                      ),
                    ),
                  );
                }
                // One lease is the common case; select it silently.
                _selectedUnitId ??= mine.first['unitId']?.toString();
                return Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l.requestTitle(widget.resourceName),
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 17,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            ),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      l.unit,
                      style: TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w700,
                        color: m.textSecondary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    if (mine.length > 1)
                      DropdownButtonFormField<String>(
                        initialValue: _selectedUnitId,
                        decoration: const InputDecoration(),
                        items: [
                          for (final lease in mine)
                            DropdownMenuItem(
                              value: lease['unitId']?.toString(),
                              child: Text(
                                lease['unitIdentifier']?.toString() ?? '—',
                              ),
                            ),
                        ],
                        onChanged: (id) =>
                            setState(() => _selectedUnitId = id),
                      )
                    else
                      Text(
                        mine.first['unitIdentifier']?.toString() ?? '—',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                      ),
                    const SizedBox(height: 12),
                    Text(
                      l.preferredDate,
                      style: TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w700,
                        color: m.textSecondary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    InkWell(
                      borderRadius: BorderRadius.circular(12),
                      onTap: () async {
                        final now = DateTime.now();
                        final picked = await showDatePicker(
                          context: context,
                          initialDate: _preferredDate ?? now,
                          firstDate: DateTime(now.year, now.month, now.day),
                          lastDate: now.add(const Duration(days: 365)),
                        );
                        if (picked != null) {
                          setState(() => _preferredDate = picked);
                        }
                      },
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 14,
                          vertical: 15,
                        ),
                        decoration: BoxDecoration(
                          color: m.surface,
                          border: Border.all(color: m.border),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Row(
                          children: [
                            Icon(
                              Icons.calendar_today_outlined,
                              size: 17,
                              color: m.textMuted,
                            ),
                            const SizedBox(width: 10),
                            Text(
                              _preferredDate == null
                                  ? l.pickADate
                                  : '${_preferredDate!.day.toString().padLeft(2, '0')}/'
                                      '${_preferredDate!.month.toString().padLeft(2, '0')}/'
                                      '${_preferredDate!.year}',
                              style: TextStyle(
                                fontSize: 14,
                                color: _preferredDate == null
                                    ? m.textMuted
                                    : m.textPrimary,
                                fontWeight: _preferredDate == null
                                    ? FontWeight.w400
                                    : FontWeight.w600,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      key: const Key('booking-note'),
                      controller: _noteCtrl,
                      maxLength: 2000,
                      maxLines: 2,
                      decoration: InputDecoration(
                        labelText: l.noteOptional,
                        hintText: l.noteHint,
                        counterText: '',
                      ),
                    ),
                    const SizedBox(height: 16),
                    GoldButton(
                      key: const Key('booking-submit'),
                      label: l.sendRequest,
                      height: 48,
                      onPressed: _submitting ? null : _submit,
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so the
/// empty and error states are given something to scroll.
class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}
