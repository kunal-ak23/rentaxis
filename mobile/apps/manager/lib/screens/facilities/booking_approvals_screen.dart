import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../providers/facility_provider.dart';
import '../../providers/gate_pass_provider.dart' show propertiesProvider;
import 'facilities_utils.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
///
/// Arabic action/status wording mirrors web's `Bookings` namespace
/// (`web/messages/ar.json`) verbatim, so a bilingual admin sees the same
/// words on web and mobile.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'طلبات الحجز' : 'Booking Requests';
  String get property => ar ? 'العقار' : 'Property';
  String get allProperties => ar ? 'كل العقارات' : 'All properties';
  String get selectPropertyPrompt => ar
      ? 'اختر عقارًا لعرض طلبات الحجز الخاصة به.'
      : 'Select a property to view its booking requests.';
  String get propertiesLoadFailed =>
      ar ? 'فشل تحميل العقارات' : 'Failed to load properties';
  String get loadFailed =>
      ar ? 'فشل تحميل طلبات الحجز' : 'Failed to load booking requests';
  String get pendingFilter => ar ? 'قيد الانتظار' : 'Pending';
  String get approvedFilter => ar ? 'مقبولة' : 'Approved';
  String get allFilter => ar ? 'الكل' : 'All';
  String get nothingHere => ar ? 'لا توجد طلبات' : 'No requests here';
  String get nothingHereSub => ar
      ? 'تظهر هنا طلبات حجز المرافق والمواقف التي يرسلها المستأجرون.'
      : 'Facility and parking requests raised by renters appear here.';
  String get renter => ar ? 'مستأجر' : 'Renter';
  String get unit => ar ? 'وحدة' : 'Unit';
  String get preferred => ar ? 'التاريخ المفضل:' : 'Preferred:';
  String get call => ar ? 'اتصال' : 'Call';
  String get email => ar ? 'بريد' : 'Email';
  String get otherRequests =>
      ar ? 'طلبات أخرى لنفس المرفق' : 'Other requests for this resource';
  String get noOtherRequests => ar ? 'لا توجد طلبات أخرى' : 'No other requests';
  String get adminNote =>
      ar ? 'ملاحظة للمستأجر (اختياري)' : 'Note to renter (optional)';
  // Distinct from `adminNote` above (the compose field's label): this is the
  // display label for a note already on a decided request — matches web
  // ar.json's `adminNote` key (the compose field there is `adminNoteLabel`).
  String get decidedAdminNoteLabel => ar ? 'ملاحظة الإدارة' : 'Admin note';
  String get decidedAtLabel => ar ? 'تاريخ القرار' : 'Decided on';
  String get approve => ar ? 'قبول' : 'Approve';
  String get reject => ar ? 'رفض' : 'Reject';
  String get release => ar ? 'إخلاء' : 'Release spot';
  String get approvedToast => ar ? 'تم اعتماد الطلب' : 'Request approved';
  String get rejectedToast => ar ? 'تم رفض الطلب' : 'Request rejected';
  String get releasedToast => ar ? 'تم تحرير الموقف' : 'Spot released';
  String get alreadyDecided => ar
      ? 'تم البت في هذا الطلب مسبقًا. جارٍ تحديث القائمة.'
      : 'This request was already decided. Refreshing the list.';
  String get spotHeld => ar
      ? 'هذا الموقف محجوز بالفعل لمستأجر آخر.'
      : 'This spot is already held by another renter.';
  String get actionFailed => ar
      ? 'تعذر تنفيذ الإجراء. حاول مرة أخرى.'
      : 'Could not complete the action. Try again.';
  String showingCount(int shown, int total) =>
      ar ? 'عرض $shown من $total' : 'Showing $shown of $total';

  String status(String value) => switch (value) {
    'PENDING' => ar ? 'قيد الانتظار' : 'Pending',
    'APPROVED' => ar ? 'مقبول' : 'Approved',
    'REJECTED' => ar ? 'مرفوض' : 'Rejected',
    'CANCELLED' => ar ? 'ملغي' : 'Cancelled',
    'RELEASED' => ar ? 'تم الإخلاء' : 'Released',
    _ => value.replaceAll('_', ' '),
  };

  String type(String value) => switch (value) {
    'AMENITY' => ar ? 'مرفق' : 'Amenity',
    'PARKING_SPOT' => ar ? 'موقف سيارة' : 'Parking spot',
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

Future<void> _launch(String url) async {
  final uri = Uri.parse(url);
  if (await canLaunchUrl(uri)) await launchUrl(uri);
}

/// The tenant's booking-request inbox, pending first.
///
/// Scope is *not* purely server-side the way the gate-pass approvals queue
/// is: the backend 403s a PROPERTY_MANAGER's `/v1/bookings` call that omits
/// `propertyId` (`BookingController.checkPropertyManagerAccess`), so this
/// screen must never fire that request for a PM until one is picked — mirrors
/// web's `dashboard/bookings/page.tsx` `needsPropertySelection` gate exactly.
/// SUPER_ADMIN/TENANT_ADMIN aren't scoped to a property at all and default to
/// the tenant-wide view (no `propertyId`), with the same dropdown available
/// as an optional filter rather than a requirement.
class BookingApprovalsScreen extends ConsumerStatefulWidget {
  const BookingApprovalsScreen({super.key});

  @override
  ConsumerState<BookingApprovalsScreen> createState() =>
      _BookingApprovalsScreenState();
}

class _BookingApprovalsScreenState
    extends ConsumerState<BookingApprovalsScreen> {
  String? _status = 'PENDING';
  String? _propertyId;

  BookingFilter get _filter => (propertyId: _propertyId, status: _status);

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final isPM = ref.watch(authProvider).role == 'PROPERTY_MANAGER';
    final properties = ref.watch(propertiesProvider);

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
      ),
      body: properties.when(
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.accent),
        ),
        error: (error, _) => FacilityScrollable(
          child: ErrorState(
            message: l.propertiesLoadFailed,
            onRetry: () => ref.invalidate(propertiesProvider),
          ),
        ),
        data: (propertyRows) {
          final needsPropertySelection = isPM && _propertyId == null;

          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                child: DropdownButtonFormField<String?>(
                  key: const Key('booking-property'),
                  initialValue: _propertyId,
                  decoration: InputDecoration(labelText: l.property),
                  hint: isPM ? Text(l.selectPropertyPrompt) : null,
                  items: [
                    // Not a valid choice for a PM — the backend 403s a null
                    // propertyId for that role.
                    if (!isPM)
                      DropdownMenuItem(
                        value: null,
                        child: Text(l.allProperties),
                      ),
                    for (final p in propertyRows)
                      DropdownMenuItem(
                        value: p['id']?.toString(),
                        child: Text(
                          p['name']?.toString() ?? '—',
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                  onChanged: (id) => setState(() => _propertyId = id),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                child: Row(
                  children: [
                    _FilterChip(
                      label: l.pendingFilter,
                      selected: _status == 'PENDING',
                      onTap: () => setState(() => _status = 'PENDING'),
                    ),
                    const SizedBox(width: 8),
                    _FilterChip(
                      label: l.approvedFilter,
                      selected: _status == 'APPROVED',
                      onTap: () => setState(() => _status = 'APPROVED'),
                    ),
                    const SizedBox(width: 8),
                    _FilterChip(
                      label: l.allFilter,
                      selected: _status == null,
                      onTap: () => setState(() => _status = null),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: needsPropertySelection
                    ? FacilityScrollable(
                        child: EmptyState(
                          icon: Icons.apartment_outlined,
                          title: l.selectPropertyPrompt,
                        ),
                      )
                    : _BookingList(filter: _filter, l: l, onOpen: _openDetail),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _openDetail(Map<String, dynamic> booking) async {
    final id = booking['id']?.toString();
    if (id == null) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _BookingDetailSheet(bookingId: id),
    );
    // Resync unconditionally, not only on a successful decision: a barrier
    // tap or drag-to-dismiss closes the sheet too, and the list can still be
    // stale by then (another admin decided the same row while this one was
    // open, or the sheet's own 400 branch already closed it). Invalidate the
    // whole family: a decision changes every filtered view, not just this one.
    //
    // riverpod 2.6.1's `invalidate` throws StateError after the provider's
    // container is disposed, and this router rebuilds on `authProvider` — the
    // state backing `context`/`ref` can already be gone by the time the
    // awaited sheet returns (same guard as facilities_screen.dart).
    if (mounted) ref.invalidate(bookingsProvider);
  }
}

/// The refreshable, paged list of bookings for one filter — split out of
/// [BookingApprovalsScreen] so the property/status filter row above it stays
/// on screen (and interactive) independent of this section's own loading and
/// error states.
class _BookingList extends ConsumerWidget {
  final BookingFilter filter;
  final _L l;
  final ValueChanged<Map<String, dynamic>> onOpen;

  const _BookingList({
    required this.filter,
    required this.l,
    required this.onOpen,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bookings = ref.watch(bookingsProvider(filter));
    return RefreshIndicator(
      color: AppColors.accent,
      onRefresh: () => ref.refresh(bookingsProvider(filter).future),
      child: bookings.when(
        loading: () => const ListShimmer(itemCount: 4),
        error: (error, _) => FacilityScrollable(
          child: ErrorState(
            message: l.loadFailed,
            onRetry: () => ref.invalidate(bookingsProvider(filter)),
          ),
        ),
        data: (page) {
          final rows = page.rows;
          if (rows.isEmpty) {
            return FacilityScrollable(
              child: EmptyState(
                icon: Icons.event_available_outlined,
                title: l.nothingHere,
                subtitle: l.nothingHereSub,
              ),
            );
          }
          final truncated = rows.length < page.total;
          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: EdgeInsets.fromLTRB(
              16,
              8,
              16,
              AppInsets.bottomNav(context),
            ),
            itemCount: rows.length + (truncated ? 1 : 0),
            itemBuilder: (context, i) {
              if (i == rows.length) {
                return _TruncationFooter(
                  shown: rows.length,
                  total: page.total,
                  l: l,
                );
              }
              return AnimatedListItem(
                index: i,
                child: _BookingCard(
                  booking: rows[i],
                  l: l,
                  onTap: () => onOpen(rows[i]),
                ),
              );
            },
          );
        },
      ),
    );
  }
}

/// Shown under a list that a page `size` truncated — same pattern as
/// facilities_screen.dart's, kept as a small private duplicate here since
/// each screen's `_L` type differs (this one takes an already-formatted
/// label rather than sharing the localization class).
class _TruncationFooter extends StatelessWidget {
  final int shown;
  final int total;
  final _L l;

  const _TruncationFooter({
    required this.shown,
    required this.total,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Center(
        child: Text(
          l.showingCount(shown, total),
          style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
            fontSize: 12,
            color: m.textMuted,
          ),
        ),
      ),
    );
  }
}

class _FilterChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _FilterChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          gradient: selected ? MiftahGradients.gold : null,
          border: selected ? null : Border.all(color: m.borderStrong),
        ),
        child: Text(
          context.isAr ? label : label.toUpperCase(),
          style: context.isAr
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: selected ? AppColors.primary : m.textSecondary,
                )
              : GoogleFonts.josefinSans(
                  fontSize: 10.5,
                  letterSpacing: 1.4,
                  fontWeight: FontWeight.w600,
                  color: selected ? AppColors.primary : m.textSecondary,
                ),
        ),
      ),
    );
  }
}

class _BookingCard extends StatelessWidget {
  final Map<String, dynamic> booking;
  final _L l;
  final VoidCallback onTap;

  const _BookingCard({
    required this.booking,
    required this.l,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = booking['status']?.toString() ?? '';
    final parking = booking['resourceType'] == 'PARKING_SPOT';

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    parking
                        ? Icons.local_parking_outlined
                        : Icons.pool_outlined,
                    size: 17,
                    color: AppColors.accentDark,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      booking['resourceName']?.toString() ?? '—',
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
                  StatusBadge(
                    label: l.status(status),
                    color: _statusColor(status, m),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                [
                  booking['renterName']?.toString() ?? l.renter,
                  if (booking['unitNumber'] != null)
                    '${l.unit} ${booking['unitNumber']}',
                  Formatters.timeAgo(
                    booking['createdAt']?.toString(),
                    ar: l.ar,
                  ),
                ].join(' · '),
                style: (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts
                          .josefinSans)(fontSize: 12.5, color: m.textSecondary),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Decision sheet: the request, the renter's contact details, every competing
/// PENDING/APPROVED request for the same resource, and the actions.
class _BookingDetailSheet extends ConsumerStatefulWidget {
  final String bookingId;
  const _BookingDetailSheet({required this.bookingId});

  @override
  ConsumerState<_BookingDetailSheet> createState() =>
      _BookingDetailSheetState();
}

class _BookingDetailSheetState extends ConsumerState<_BookingDetailSheet> {
  final _noteCtrl = TextEditingController();
  bool _deciding = false;

  @override
  void dispose() {
    _noteCtrl.dispose();
    super.dispose();
  }

  Future<void> _decide(String action) async {
    if (_deciding) return;
    final l = _L(context.isAr);
    setState(() => _deciding = true);
    final service = ref.read(facilityServiceProvider);
    final note = _noteCtrl.text.trim();
    try {
      switch (action) {
        case 'approve':
          await service.approveBooking(
            widget.bookingId,
            adminNote: note.isEmpty ? null : note,
          );
        case 'reject':
          await service.rejectBooking(
            widget.bookingId,
            adminNote: note.isEmpty ? null : note,
          );
        case 'release':
          await service.releaseBooking(widget.bookingId);
      }
      if (!mounted) return;
      // No invalidate here: the parent's `_openDetail` always resyncs the
      // list once this sheet closes (on every close, not just a successful
      // decision — see its comment), so doing it here too would just be a
      // second, redundant refetch racing the first.
      _toast(switch (action) {
        'approve' => l.approvedToast,
        'reject' => l.rejectedToast,
        _ => l.releasedToast,
      }, AppColors.success);
      Navigator.pop(context);
    } on DioException catch (error) {
      if (!mounted) return;
      final code = error.response?.statusCode;
      if (code == 409) {
        // The spot is APPROVED to someone else. Retrying cannot fix it, but
        // the manager may still want to reject this request — keep the sheet
        // open. The 409 body is the booking endpoints' `{error: "<msg>"}`
        // shape (not the app's usual `{message}` envelope) — errorMessage
        // checks both, so the server's actual conflict message is shown
        // rather than a generic one. Also refresh the detail provider: the
        // competitor that just won the race now belongs in `otherRequests`
        // with an APPROVED badge instead of the stale PENDING one fetched
        // when this sheet first opened.
        setState(() => _deciding = false);
        ref.invalidate(bookingDetailProvider(widget.bookingId));
        _toast(errorMessage(error, l.spotHeld), AppColors.warning);
      } else if (code == 400) {
        // Someone else decided first; the sheet is stale — close (the
        // parent's always-on-close resync picks up the fresher state).
        _toast(l.alreadyDecided, AppColors.warning);
        Navigator.pop(context);
      } else {
        setState(() => _deciding = false);
        _toast(errorMessage(error, l.actionFailed), AppColors.danger);
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _deciding = false);
      _toast(errorMessage(error, l.actionFailed), AppColors.danger);
    }
  }

  void _toast(String message, Color background) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: background,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final detail = ref.watch(bookingDetailProvider(widget.bookingId));

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85,
          ),
          child: detail.when(
            loading: () => const SizedBox(
              height: 220,
              child: Center(
                child: CircularProgressIndicator(color: AppColors.accent),
              ),
            ),
            error: (error, _) => Padding(
              padding: const EdgeInsets.all(24),
              child: ErrorState(
                message: l.loadFailed,
                onRetry: () =>
                    ref.invalidate(bookingDetailProvider(widget.bookingId)),
              ),
            ),
            data: (data) {
              final request = Map<String, dynamic>.from(
                data['request'] as Map? ?? {},
              );
              final others = (data['otherRequests'] as List? ?? const [])
                  .whereType<Map>()
                  .map((r) => Map<String, dynamic>.from(r))
                  .toList();
              final status = request['status']?.toString() ?? '';
              final parking = request['resourceType'] == 'PARKING_SPOT';
              final phone = request['renterPhone']?.toString();
              final email = request['renterEmail']?.toString();
              final note = request['note']?.toString();
              final decidedNote = request['adminNote']?.toString();
              final decidedAt = request['decidedAt']?.toString();

              return SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            request['resourceName']?.toString() ?? '—',
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
                        ),
                        StatusBadge(
                          label: l.status(status),
                          color: _statusColor(status, m),
                        ),
                      ],
                    ),
                    const SizedBox(height: 2),
                    Text(
                      l.type(request['resourceType']?.toString() ?? ''),
                      style: GoogleFonts.josefinSans(
                        fontSize: 11,
                        letterSpacing: 1.4,
                        color: m.textMuted,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _DetailRow(
                      icon: Icons.person_outline,
                      value: request['renterName']?.toString() ?? l.renter,
                      m: m,
                    ),
                    if (request['unitNumber'] != null)
                      _DetailRow(
                        icon: Icons.home_outlined,
                        value: '${l.unit} ${request['unitNumber']}',
                        m: m,
                      ),
                    if (request['preferredDate'] != null)
                      _DetailRow(
                        icon: Icons.event_outlined,
                        value:
                            '${l.preferred} ${Formatters.date(request['preferredDate']?.toString(), ar: l.ar)}',
                        m: m,
                      ),
                    if (request['createdAt'] != null)
                      _DetailRow(
                        icon: Icons.schedule,
                        value: Formatters.timeAgo(
                          request['createdAt']?.toString(),
                          ar: l.ar,
                        ),
                        m: m,
                      ),
                    if (decidedAt != null)
                      _DetailRow(
                        icon: Icons.event_available_outlined,
                        value:
                            '${l.decidedAtLabel} ${Formatters.date(decidedAt, ar: l.ar)}',
                        m: m,
                      ),
                    if (note != null && note.isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: m.surfaceAlt,
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Text(
                          note,
                          style:
                              (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts.josefinSans)(
                                fontSize: 13,
                                color: m.textSecondary,
                                height: 1.5,
                              ),
                        ),
                      ),
                    ],
                    if (decidedNote != null && decidedNote.isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: m.surfaceAlt,
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Text.rich(
                          TextSpan(
                            children: [
                              TextSpan(
                                text: '${l.decidedAdminNoteLabel}: ',
                                style:
                                    (l.ar
                                    ? GoogleFonts.notoNaskhArabic
                                    : GoogleFonts.josefinSans)(
                                      fontSize: 13,
                                      fontWeight: FontWeight.w600,
                                      color: m.textSecondary,
                                    ),
                              ),
                              TextSpan(
                                text: decidedNote,
                                style:
                                    (l.ar
                                    ? GoogleFonts.notoNaskhArabic
                                    : GoogleFonts.josefinSans)(
                                      fontSize: 13,
                                      color: m.textSecondary,
                                      height: 1.5,
                                    ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                    if (phone != null || email != null) ...[
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          if (phone != null)
                            Expanded(
                              child: _ContactBtn(
                                icon: Icons.phone_outlined,
                                label: l.call,
                                ar: l.ar,
                                onTap: () => _launch('tel:$phone'),
                              ),
                            ),
                          if (phone != null && email != null)
                            const SizedBox(width: 10),
                          if (email != null)
                            Expanded(
                              child: _ContactBtn(
                                icon: Icons.email_outlined,
                                label: l.email,
                                ar: l.ar,
                                onTap: () => _launch('mailto:$email'),
                              ),
                            ),
                        ],
                      ),
                    ],
                    const SizedBox(height: 16),
                    Text(
                      l.ar ? l.otherRequests : l.otherRequests.toUpperCase(),
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 12,
                              color: AppColors.accentDark,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 10,
                              letterSpacing: 2.2,
                              color: AppColors.accentDark,
                            ),
                    ),
                    const SizedBox(height: 8),
                    if (others.isEmpty)
                      Text(
                        l.noOtherRequests,
                        style:
                            (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.josefinSans)(
                              fontSize: 12.5,
                              color: m.textMuted,
                            ),
                      )
                    else
                      for (final other in others)
                        _OtherRequestRow(row: other, l: l),
                    const SizedBox(height: 16),
                    if (status == 'PENDING') ...[
                      TextField(
                        key: const Key('admin-note'),
                        controller: _noteCtrl,
                        maxLength: 2000,
                        decoration: InputDecoration(
                          labelText: l.adminNote,
                          counterText: '',
                        ),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: GoldButton.outlined(
                              key: const Key('booking-reject'),
                              label: l.reject,
                              height: 46,
                              onPressed: _deciding
                                  ? null
                                  : () => _decide('reject'),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: GoldButton(
                              key: const Key('booking-approve'),
                              label: l.approve,
                              height: 46,
                              onPressed: _deciding
                                  ? null
                                  : () => _decide('approve'),
                            ),
                          ),
                        ],
                      ),
                    ] else if (status == 'APPROVED' && parking)
                      GoldButton.outlined(
                        key: const Key('booking-release'),
                        label: l.release,
                        height: 46,
                        onPressed: _deciding ? null : () => _decide('release'),
                      ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class _OtherRequestRow extends StatelessWidget {
  final Map<String, dynamic> row;
  final _L l;
  const _OtherRequestRow({required this.row, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = row['status']?.toString() ?? '';
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  row['renterName']?.toString() ?? l.renter,
                  style:
                      (l.ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      ),
                ),
                Text(
                  [
                    if (row['unitNumber'] != null)
                      '${l.unit} ${row['unitNumber']}',
                    Formatters.timeAgo(row['createdAt']?.toString(), ar: l.ar),
                  ].join(' · '),
                  style: GoogleFonts.josefinSans(
                    fontSize: 11,
                    color: m.textMuted,
                  ),
                ),
              ],
            ),
          ),
          StatusBadge(label: l.status(status), color: _statusColor(status, m)),
        ],
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.icon, required this.value, required this.m});

  final IconData icon;
  final String value;
  final MiftahColors m;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 15, color: m.textMuted),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              value,
              style: context.isAr
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: m.textSecondary,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 13,
                      color: m.textSecondary,
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ContactBtn extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool ar;
  final VoidCallback onTap;

  const _ContactBtn({
    required this.icon,
    required this.label,
    required this.ar,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.accent.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.accent.withValues(alpha: 0.3)),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 16, color: AppColors.accentDark),
            const SizedBox(width: 6),
            Text(
              label,
              style:
                  (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
                    fontSize: 12,
                    color: m.isDark ? AppColors.accent : AppColors.accentDark,
                    fontWeight: FontWeight.w600,
                    letterSpacing: ar ? 0 : 0.4,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}
