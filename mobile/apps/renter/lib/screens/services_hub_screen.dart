import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Services hub — design screen 09.
///
/// The redesign moves Meetings, Tickets, Facilities, Gate passes and Approvals
/// off the bottom bar and behind this 2-column grid of tinted icon tiles, so
/// the bar can carry four tabs plus the raised Pass action.
class _L {
  static String subtitle(bool ar) =>
      ar ? 'كل ما تحتاجه في مكان واحد' : 'Everything else, in one place';

  static String maintenance(bool ar) => ar ? 'الصيانة' : 'Maintenance';
  static String maintenanceSub(bool ar) =>
      ar ? 'تذاكر الإصلاح' : 'Repair tickets';
  static String meetings(bool ar) => ar ? 'المواعيد' : 'Meetings';
  static String meetingsSub(bool ar) => ar ? 'مع الإدارة' : 'With management';
  static String facilities(bool ar) => ar ? 'المرافق' : 'Facilities';
  static String facilitiesSub(bool ar) =>
      ar ? 'حمام السباحة، القاعة' : 'Pool, hall, parking';
  static String gatePasses(bool ar) => ar ? 'تصاريح الدخول' : 'Gate passes';
  static String gatePassesSub(bool ar) => ar ? 'للزوار' : 'For your visitors';
  static String approvals(bool ar) => ar ? 'الموافقات' : 'Approvals';
  static String approvalsSub(bool ar) =>
      ar ? 'بانتظار ردك' : 'Waiting on you';
  static String penalties(bool ar) => ar ? 'الغرامات' : 'Penalties';
  static String penaltiesSub(bool ar) => ar ? 'رسوم التأخير' : 'Late fees';
  static String saved(bool ar) => ar ? 'المحفوظة' : 'Saved listings';
  static String savedSub(bool ar) => ar ? 'العقارات المحفوظة' : 'Your shortlist';
}

class ServicesHubScreen extends ConsumerWidget {
  const ServicesHubScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ar = context.isAr;

    final entries = <_Service>[
      _Service(
        icon: Icons.handyman_rounded,
        tone: MiftahTone.info,
        label: _L.maintenance(ar),
        sub: _L.maintenanceSub(ar),
        route: '/tickets',
      ),
      _Service(
        icon: Icons.event_rounded,
        tone: MiftahTone.brass,
        label: _L.meetings(ar),
        sub: _L.meetingsSub(ar),
        route: '/meetings',
      ),
      _Service(
        icon: Icons.pool_rounded,
        tone: MiftahTone.success,
        label: _L.facilities(ar),
        sub: _L.facilitiesSub(ar),
        route: '/facilities',
      ),
      _Service(
        icon: Icons.qr_code_2_rounded,
        tone: MiftahTone.neutral,
        label: _L.gatePasses(ar),
        sub: _L.gatePassesSub(ar),
        route: '/gatepass',
      ),
      _Service(
        icon: Icons.how_to_reg_rounded,
        tone: MiftahTone.warning,
        label: _L.approvals(ar),
        sub: _L.approvalsSub(ar),
        route: '/tickets/approvals',
      ),
      _Service(
        icon: Icons.gavel_rounded,
        tone: MiftahTone.danger,
        label: _L.penalties(ar),
        sub: _L.penaltiesSub(ar),
        route: '/penalties',
      ),
      _Service(
        icon: Icons.favorite_rounded,
        tone: MiftahTone.brass,
        label: _L.saved(ar),
        sub: _L.savedSub(ar),
        route: '/wishlist',
      ),
    ];

    return ListView(
      padding: const EdgeInsets.fromLTRB(
        MiftahSpacing.page,
        16,
        MiftahSpacing.page,
        24,
      ),
      children: [
        Text(
          _L.subtitle(ar),
          style: ar ? MiftahType.ar(size: 13) : MiftahType.body(),
        ),
        const SizedBox(height: 18),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: MiftahSpacing.gap,
          crossAxisSpacing: MiftahSpacing.gap,
          childAspectRatio: 1.18,
          children: [
            for (var i = 0; i < entries.length; i++)
              AnimatedListItem(index: i, child: _ServiceTile(entries[i])),
          ],
        ),
      ],
    );
  }
}

class _Service {
  const _Service({
    required this.icon,
    required this.tone,
    required this.label,
    required this.sub,
    required this.route,
  });

  final IconData icon;
  final MiftahTone tone;
  final String label;
  final String sub;
  final String route;
}

class _ServiceTile extends StatelessWidget {
  const _ServiceTile(this.service);

  final _Service service;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return MiftahCard(
      onTap: () => context.push(service.route),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          MiftahIconTile(icon: service.icon, tone: service.tone),
          const Spacer(),
          Text(
            service.label,
            style: ar
                ? MiftahType.ar(size: 15, weight: FontWeight.w700)
                : MiftahType.cardTitle(),
          ),
          const SizedBox(height: 3),
          Text(
            service.sub,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: ar ? MiftahType.ar(size: 11.5) : MiftahType.meta(),
          ),
        ],
      ),
    );
  }
}
