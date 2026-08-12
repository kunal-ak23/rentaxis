import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/facility_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
///
/// Arabic wording matches web's `Facilities`/`Bookings` namespaces
/// (`web/messages/ar.json`) — `cancelRequest`/`releaseSpot`, the status
/// labels, and `adminNote`/`decidedAt` (the same fields the manager app's
/// decision sheet shows). English status labels are the app's own uppercase
/// status-chip convention (raw codes, e.g. "PENDING") rather than web's
/// title case — matches facilities_screen.dart and
/// booking_approvals_screen.dart's own EN chips.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'طلباتي' : 'My Requests';
  String get loadFailed =>
      ar ? 'فشل تحميل الطلبات' : 'Failed to load your requests';
  String get nothingYet => ar ? 'لا توجد طلبات بعد' : 'No requests yet';
  String get nothingYetSub => ar
      ? 'عندما تطلب حجز مرفق أو موقف سيارة، تظهر طلباتك هنا.'
      : 'When you request an amenity or a parking spot, '
            'your requests appear here.';
  String get unit => ar ? 'وحدة' : 'Unit';
  String get preferred => ar ? 'التاريخ المفضل:' : 'Preferred:';
  String get adminNoteLabel => ar ? 'ملاحظة الإدارة' : 'Admin note';
  String get decidedAtLabel => ar ? 'تاريخ القرار' : 'Decided on';
  String get cancelRequest => ar ? 'إلغاء الطلب' : 'Cancel request';
  String get releaseSpot => ar ? 'إخلاء الموقف' : 'Release spot';
  String get keep => ar ? 'تراجع' : 'Keep';
  String get confirm => ar ? 'تأكيد' : 'Confirm';
  String get cancelConfirm =>
      ar ? 'إلغاء طلب الحجز هذا؟' : 'Cancel this booking request?';
  String get releaseConfirm => ar
      ? 'هل تريد إخلاء هذا الموقف؟ سيصبح متاحًا لغيرك ولن يعود إليك تلقائيًا.'
      : 'Release this spot? It becomes available to others and does not '
            'come back automatically.';
  String get cancelled => ar ? 'تم إلغاء الطلب' : 'Request cancelled';
  String get released => ar ? 'تم إخلاء الموقف' : 'Spot released';
  String get actionFailed => ar
      ? 'تعذر تنفيذ الإجراء. حاول مرة أخرى.'
      : 'Could not complete the action. Try again.';
  String get alreadyDecided => ar
      ? 'تغيّرت حالة هذا الطلب. جارٍ تحديث القائمة.'
      : 'This request changed state. Refreshing the list.';

  String status(String value) => switch (value) {
    'PENDING' => ar ? 'قيد الانتظار' : 'PENDING',
    'APPROVED' => ar ? 'مقبول' : 'APPROVED',
    'REJECTED' => ar ? 'مرفوض' : 'REJECTED',
    'CANCELLED' => ar ? 'ملغي' : 'CANCELLED',
    'RELEASED' => ar ? 'تم الإخلاء' : 'RELEASED',
    _ => value.replaceAll('_', ' '),
  };
}

Color _statusColor(String? status, LegacyMiftahColors m) => switch (status) {
  'PENDING' => m.warning,
  'APPROVED' => m.success,
  'REJECTED' => m.danger,
  'RELEASED' => AppColors.accentDark,
  _ => m.textMuted,
};

/// The renter's own booking requests, with self-service actions:
/// cancel a PENDING request, release an APPROVED parking spot.
///
/// Parameterless and self-fetching (router discards `extra` on auth
/// rebuilds — see router.dart and facilities_screen.dart's own header).
class MyRequestsScreen extends ConsumerWidget {
  const MyRequestsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final requests = ref.watch(myBookingRequestsProvider);

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
      body: RefreshIndicator(
        color: AppColors.accent,
        onRefresh: () => ref.refresh(myBookingRequestsProvider.future),
        child: requests.when(
          loading: () => const ListShimmer(itemCount: 4),
          error: (error, _) => _Scrollable(
            child: ErrorState(
              message: l.loadFailed,
              onRetry: () => ref.invalidate(myBookingRequestsProvider),
            ),
          ),
          data: (rows) {
            if (rows.isEmpty) {
              return _Scrollable(
                child: EmptyState(
                  icon: Icons.event_note_outlined,
                  title: l.nothingYet,
                  subtitle: l.nothingYetSub,
                ),
              );
            }
            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.fromLTRB(
                16,
                12,
                16,
                24,
              ),
              // `rows` is createdAt ASC as returned by the server — never
              // re-sorted client-side (the project's created-ascending
              // standard).
              itemCount: rows.length,
              itemBuilder: (context, i) => AnimatedListItem(
                index: i,
                child: _RequestCard(request: rows[i], l: l),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Stateful for [_busy]: cancel/release are network calls, and a double-tap
/// would post two — the second coming back 400 on a request that did in fact
/// change, turning success into an error message.
class _RequestCard extends ConsumerStatefulWidget {
  final Map<String, dynamic> request;
  final _L l;
  const _RequestCard({required this.request, required this.l});

  @override
  ConsumerState<_RequestCard> createState() => _RequestCardState();
}

class _RequestCardState extends ConsumerState<_RequestCard> {
  bool _busy = false;

  Future<void> _run({required bool release}) async {
    final l = widget.l;
    final id = widget.request['id']?.toString();
    if (id == null || _busy) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        content: Text(
          release ? l.releaseConfirm : l.cancelConfirm,
          style: (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.plusJakartaSans)(
            fontSize: 14,
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(
              l.keep,
              style:
                  (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.plusJakartaSans)(
                    fontWeight: FontWeight.w600,
                  ),
            ),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: AppColors.danger),
            child: Text(
              l.confirm,
              style:
                  (l.ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.plusJakartaSans)(
                    fontWeight: FontWeight.w600,
                  ),
            ),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted || _busy) return;
    setState(() => _busy = true);
    try {
      final service = ref.read(facilityServiceProvider);
      if (release) {
        await service.releaseBooking(id);
      } else {
        await service.cancelBooking(id);
      }
      if (!mounted) return;
      ref.invalidate(myBookingRequestsProvider);
      ref.invalidate(myFacilitiesProvider); // held state / counts changed
      _toast(release ? l.released : l.cancelled, AppColors.success);
    } on DioException catch (error) {
      if (!mounted) return;
      if (error.response?.statusCode == 400) {
        // The request left the state this action needs (someone decided it,
        // or it was already released). Refresh instead of advising a retry —
        // mirrors booking_approvals_screen.dart's `_decide` 400 branch. Both
        // providers, not just the requests list: a 400 means server state
        // already moved, so the held/pendingCount facts below are stale too.
        ref.invalidate(myBookingRequestsProvider);
        ref.invalidate(myFacilitiesProvider);
        _toast(l.alreadyDecided, AppColors.warning);
      } else {
        _toast(errorMessage(error, l.actionFailed), AppColors.danger);
      }
    } catch (error) {
      if (mounted) _toast(errorMessage(error, l.actionFailed), AppColors.danger);
    } finally {
      if (mounted) setState(() => _busy = false);
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
    final l = widget.l;
    final request = widget.request;
    final id = request['id']?.toString() ?? '';
    final status = request['status']?.toString() ?? '';
    final parking = request['resourceType'] == 'PARKING_SPOT';
    final note = request['note']?.toString();
    final adminNote = request['adminNote']?.toString();
    final decidedAt = request['decidedAt']?.toString();

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
                parking ? Icons.local_parking_outlined : Icons.pool_outlined,
                size: 17,
                color: AppColors.accentDark,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  request['resourceName']?.toString() ?? '—',
                  // Parking spot codes are alphanumeric (e.g. "P-12") and
                  // read wrong mirrored inside an RTL layout — same rule as
                  // facilities_screen.dart's _SpotCard.
                  textDirection: parking ? TextDirection.ltr : null,
                  style: parking
                      ? GoogleFonts.plusJakartaSans(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.plusJakartaSans(
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
          const SizedBox(height: 6),
          Text(
            [
              if (request['unitNumber'] != null)
                '${l.unit} ${request['unitNumber']}',
              if (request['preferredDate'] != null)
                '${l.preferred} ${Formatters.date(request['preferredDate']?.toString(), ar: l.ar)}',
              if (request['createdAt'] != null)
                Formatters.timeAgo(request['createdAt']?.toString(), ar: l.ar),
              if (decidedAt != null)
                '${l.decidedAtLabel} ${Formatters.date(decidedAt, ar: l.ar)}',
            ].join(' · '),
            style: (l.ar
                ? GoogleFonts.notoNaskhArabic
                : GoogleFonts.plusJakartaSans)(
              fontSize: 12,
              color: m.textSecondary,
            ),
          ),
          if (note != null && note.isNotEmpty) ...[
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: m.surfaceAlt,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                note,
                style: (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.plusJakartaSans)(
                  fontSize: 12.5,
                  color: m.textSecondary,
                  height: 1.4,
                ),
              ),
            ),
          ],
          if (adminNote != null && adminNote.isNotEmpty) ...[
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.accent.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: AppColors.accent.withValues(alpha: 0.3),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.adminNoteLabel,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                            color: AppColors.accentDark,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 10,
                            letterSpacing: 1.8,
                            fontWeight: FontWeight.w600,
                            color: AppColors.accentDark,
                          ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    adminNote,
                    style: (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.plusJakartaSans)(
                      fontSize: 12.5,
                      color: m.textSecondary,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
          ],
          if (status == 'PENDING') ...[
            const SizedBox(height: 12),
            GoldButton.outlined(
              key: Key('cancel-$id'),
              label: l.cancelRequest,
              height: 42,
              onPressed: _busy ? null : () => _run(release: false),
            ),
          ] else if (status == 'APPROVED' && parking) ...[
            const SizedBox(height: 12),
            GoldButton.outlined(
              key: Key('release-$id'),
              label: l.releaseSpot,
              height: 42,
              onPressed: _busy ? null : () => _run(release: true),
            ),
          ],
        ],
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
