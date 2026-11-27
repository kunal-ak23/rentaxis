import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/promotion_provider.dart';
import '../../utils/promo_url.dart';
import '../../widgets/ad_card.dart';
import '../../widgets/business_logo.dart';
import '../../widgets/promo_actions.dart';

/// One directory business: its details, a way to reach it (website, call,
/// WhatsApp — each only when set) and its current offers.
///
/// **Owns the promo event flush.** This is the only screen that shows
/// [AdCard]s, so it is what drains [promoEventQueueProvider]: on dispose (the
/// renter leaves) and when the app is paused (backgrounded, or killed from
/// there). Without it, impressions and clicks recorded here would sit in the
/// queue and never reach the server.
///
/// Self-fetching from the id in the path, as the other detail screens are. No
/// FAB: the renter shell's floating nav pill covers that corner.
class BusinessScreen extends ConsumerStatefulWidget {
  const BusinessScreen({super.key, required this.businessId});

  final String businessId;

  @override
  ConsumerState<BusinessScreen> createState() => _BusinessScreenState();
}

class _BusinessScreenState extends ConsumerState<BusinessScreen>
    with WidgetsBindingObserver {
  /// Resolved once and held rather than read on demand: Riverpod's element is
  /// already defunct by the time `State.dispose` runs, so `ref` there throws
  /// `Cannot use "ref" after the widget was disposed`. Holding the instance
  /// costs nothing — [promoEventQueueProvider] is deliberately not
  /// `autoDispose`, so the queue outlives this screen either way.
  late final PromoEventQueue _queue;

  @override
  void initState() {
    super.initState();
    _queue = ref.read(promoEventQueueProvider);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // Not awaited: dispose cannot be async, and a dropped flush costs at most
    // a missing analytics row.
    unawaited(_queue.flush());
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      unawaited(_queue.flush());
    }
  }

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final l = _L(isAr);
    final id = widget.businessId;
    final async = ref.watch(directoryBusinessProvider(id));

    return Scaffold(
      appBar: AppBar(),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => isNotFoundError(error)
            ? EmptyState(
                icon: Icons.search_off_rounded,
                title: l.notFoundTitle,
                subtitle: l.notFoundMessage,
              )
            : ErrorState(
                message: l.loadFailed,
                onRetry: () => ref.invalidate(directoryBusinessProvider(id)),
              ),
        data: (business) => _details(context, business, l, isAr),
      ),
    );
  }

  Widget _details(
    BuildContext context,
    DirectoryBusiness business,
    _L l,
    bool isAr,
  ) {
    final m = context.miftah;
    final actions = ref.read(promoActionsProvider);
    final description = business.description(isAr);
    // The exact check a WEBSITE ad passes; a link that fails it gets no
    // button at all rather than a button that silently does nothing.
    final website = parseSafePromoUri(business.websiteUrl);
    final phone = _present(business.phoneE164);
    final whatsapp = _present(business.whatsappE164);

    return ListView(
      padding: const EdgeInsets.fromLTRB(
        MiftahSpacing.page,
        4,
        MiftahSpacing.page,
        96, // clears the shell's floating nav pill
      ),
      children: [
        Row(
          children: [
            BusinessLogo(url: business.logoUrl, size: 64),
            const SizedBox(width: 16),
            Expanded(
              child: Text(
                business.name(isAr),
                style: isAr
                    ? MiftahType.ar(
                        size: 21,
                        weight: FontWeight.w700,
                        color: m.textPrimary,
                      )
                    : MiftahType.title(color: m.textPrimary),
              ),
            ),
          ],
        ),
        if (description != null && description.isNotEmpty) ...[
          const SizedBox(height: 14),
          Text(
            description,
            textAlign: TextAlign.start,
            style: isAr
                ? MiftahType.ar(size: 14, color: m.textSecondary)
                : MiftahType.body(size: 14, color: m.textSecondary),
          ),
        ],
        if (website != null) ...[
          const SizedBox(height: 18),
          FilledButton.icon(
            onPressed: () => actions.openBusinessWebsite(business.websiteUrl),
            icon: const Icon(Icons.open_in_new_rounded, size: 18),
            label: Text(l.visit(website.host)),
          ),
        ],
        if (phone != null || whatsapp != null) ...[
          const SizedBox(height: 10),
          Row(
            children: [
              if (phone != null)
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () => actions.callPhone(phone),
                    icon: const Icon(Icons.call_outlined, size: 18),
                    label: Text(l.call),
                  ),
                ),
              if (phone != null && whatsapp != null) const SizedBox(width: 10),
              if (whatsapp != null)
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () => actions.openWhatsApp(whatsapp),
                    icon: const Icon(Icons.chat_outlined, size: 18),
                    label: Text(l.whatsapp),
                  ),
                ),
            ],
          ),
        ],
        _OffersSection(
          businessId: business.id.isEmpty ? widget.businessId : business.id,
          queue: _queue,
          l: l,
        ),
      ],
    );
  }

  static String? _present(String? raw) {
    final v = raw?.trim();
    return (v == null || v.isEmpty) ? null : v;
  }
}

/// "Current offers": this business's live ads. Hidden while loading and when
/// there are none. A failed load never takes the page down — the business
/// details above still render — and shows a small retry instead.
class _OffersSection extends ConsumerWidget {
  const _OffersSection({
    required this.businessId,
    required this.queue,
    required this.l,
  });

  final String businessId;
  final PromoEventQueue queue;
  final _L l;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ({String? category, String? businessId}) filter =
        (category: null, businessId: businessId);
    final async = ref.watch(promoOffersProvider(filter));
    final m = context.miftah;
    final isAr = context.isAr;

    return async.when(
      loading: () => const SizedBox.shrink(),
      error: (_, _) => Padding(
        padding: const EdgeInsets.only(top: 24),
        child: Row(
          children: [
            Expanded(
              child: Text(
                l.offersFailed,
                style: isAr
                    ? MiftahType.ar(size: 13, color: m.textSecondary)
                    : MiftahType.body(color: m.textSecondary),
              ),
            ),
            TextButton(
              key: const Key('business-offers-retry'),
              onPressed: () => ref.invalidate(promoOffersProvider(filter)),
              child: Text(l.retry),
            ),
          ],
        ),
      ),
      data: (ads) {
        if (ads.isEmpty) return const SizedBox.shrink();
        final actions = ref.read(promoActionsProvider);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const SizedBox(height: 28),
            Text(
              l.currentOffers,
              style: isAr
                  ? MiftahType.ar(
                      size: 16,
                      weight: FontWeight.w700,
                      color: m.textPrimary,
                    )
                  : MiftahType.cardTitle(color: m.textPrimary),
            ),
            for (final ad in ads) ...[
              const SizedBox(height: MiftahSpacing.gap),
              _Impression(
                key: ValueKey('impression-${ad.id}'),
                onShown: () => queue.recordImpression(ad.id),
                child: SizedBox(
                  height: adCardHeight(context) * 1.2,
                  child: AdCard(
                    ad: ad,
                    onTap: () {
                      queue.recordClick(ad.id);
                      actions.handleTap(context, ad);
                    },
                  ),
                ),
              ),
            ],
          ],
        );
      },
    );
  }
}

/// Calls [onShown] once, when the card first mounts. The queue de-duplicates
/// per ad, so a rebuild or a retry cannot inflate the count.
class _Impression extends StatefulWidget {
  const _Impression({super.key, required this.onShown, required this.child});

  final VoidCallback onShown;
  final Widget child;

  @override
  State<_Impression> createState() => _ImpressionState();
}

class _ImpressionState extends State<_Impression> {
  @override
  void initState() {
    super.initState();
    widget.onShown();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

class _L {
  const _L(this.ar);
  final bool ar;

  /// The host goes in a first-strong isolate in Arabic so the Latin domain
  /// keeps its own direction inside the RTL sentence.
  String visit(String host) => ar ? 'زيارة \u2068$host\u2069' : 'Visit $host';
  String get call => ar ? 'اتصال' : 'Call';
  String get whatsapp => ar ? 'واتساب' : 'WhatsApp';
  String get currentOffers => ar ? 'العروض الحالية' : 'Current offers';
  String get offersFailed => ar ? 'تعذر تحميل العروض' : 'Could not load offers';
  String get retry => ar ? 'إعادة المحاولة' : 'Retry';
  String get loadFailed =>
      ar ? 'تعذر تحميل النشاط التجاري' : 'Could not load this business';
  String get notFoundTitle => ar
      ? 'هذا النشاط التجاري لم يعد مدرجاً'
      : 'This business is no longer listed';
  String get notFoundMessage => ar
      ? 'ربما أُزيل من الدليل.'
      : 'It may have been removed from the directory.';
}
