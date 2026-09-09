import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../gatepass/pass_display.dart';
import '../providers/gate_pass_provider.dart';
import '../auth/phone_auth_service.dart';
import 'approvals_screen.dart';

/// The guard's home: today's expected visitors, the scanner, and the approvals
/// queue.
///
/// Scan is a nav destination that *pushes* rather than a tab body, because the
/// scanner is a task with a result screen on top of it, not a place — keeping it
/// as a tab would leave a live camera running behind the other two tabs all
/// shift. So tapping Scan navigates and leaves the selected tab where it was;
/// coming back from /scan lands on the tab the guard actually left.
class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  int _tab = 0;

  static const _scanDestination = 1;

  void _onDestinationSelected(int index) {
    if (index == _scanDestination) {
      context.push('/scan');
      return;
    }
    setState(() => _tab = index);
  }

  Future<void> _signOut() async {
    try {
      await ref.read(phoneAuthServiceProvider).signOut();
    } finally {
      await ref.read(authProvider.notifier).logout();
    }
  }

  /// After the backend deleted the account and core cleared the session, the
  /// Firebase phone session is the one thing left to end.
  Future<void> _afterAccountDeleted() =>
      ref.read(phoneAuthServiceProvider).signOut();

  void _openSettings() {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (_) => _SettingsSheet(
        onSignOut: _signOut,
        onAccountDeleted: _afterAccountDeleted,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: SafeArea(
        bottom: false,
        child: _tab == 0
            ? Column(
                children: [
                  _HomeHeader(l: l, onSettingsTap: _openSettings),
                  _ExpectedHeading(l: l),
                  Expanded(child: const _VisitorsTab()),
                ],
              )
            // Shift settings (and sign-out) used to live on the shell app bar,
            // so they were reachable from both tabs. The redesign moved them
            // into the Home header — this keeps the Approvals tab's own way in.
            : Column(
                children: [
                  MiftahScreenHeader(
                    isAr: l.ar,
                    title: l.approvals,
                    actions: [
                      MiftahCircleButton(
                        icon: Icons.tune_rounded,
                        tooltip: l.settings,
                        onTap: _openSettings,
                      ),
                    ],
                  ),
                  const Expanded(child: ApprovalsView()),
                ],
              ),
      ),
      bottomNavigationBar: _GateNavBar(
        selectedIndex: _tab,
        l: l,
        onTap: _onDestinationSelected,
      ),
    );
  }
}

/// Ink header: guard identity and posting, the gold scan hero, and the two
/// secondary actions. One of the five ink headers the redesign keeps — the
/// guard's context has to stay legible against a bright gate at night.
class _HomeHeader extends ConsumerWidget {
  const _HomeHeader({required this.l, required this.onSettingsTap});

  final _L l;
  final VoidCallback onSettingsTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authProvider);
    final properties = ref.watch(myPropertiesProvider);

    return Container(
      width: double.infinity,
      color: MiftahColors.ink,
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      authState.name ?? l.guardFallback,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: l.ar
                          ? MiftahType.ar(
                              size: 20,
                              weight: FontWeight.w700,
                              color: Colors.white,
                            )
                          : MiftahType.title(
                              color: Colors.white,
                            ).copyWith(fontSize: 21),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        // Green pip = on shift. The posting line below it is
                        // the guard's authority to admit anyone at all.
                        Container(
                          width: 7,
                          height: 7,
                          decoration: const BoxDecoration(
                            shape: BoxShape.circle,
                            color: Color(0xFF6FD79B),
                          ),
                        ),
                        const SizedBox(width: 7),
                        Expanded(
                          child: Text(
                            properties.when(
                              data: (rows) =>
                                  l.onShiftLine(l.propertyLine(rows)),
                              loading: () => l.loadingPosting,
                              error: (_, _) => l.postingUnavailable,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: l.ar
                                ? MiftahType.ar(
                                    size: 12.5,
                                    weight: FontWeight.w600,
                                    color: const Color(0xFF8C86A0),
                                  )
                                : MiftahType.meta(
                                    color: const Color(0xFF8C86A0),
                                  ).copyWith(
                                    fontSize: 12.5,
                                    fontWeight: FontWeight.w600,
                                  ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              GestureDetector(
                onTap: onSettingsTap,
                child: Container(
                  width: 38,
                  height: 38,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: Colors.white.withValues(alpha: 0.1),
                  ),
                  child: const Icon(
                    Icons.tune_rounded,
                    size: 19,
                    color: MiftahColors.brassLight,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 18),
          // Scan is the whole hero — it is the only thing a guard does at a
          // gate, so it gets the gradient and the shadow.
          GestureDetector(
            key: const Key('scanPassHero'),
            onTap: () => context.push('/scan'),
            child: Container(
              height: 74,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                gradient: MiftahGradients.gold,
                borderRadius: BorderRadius.circular(18),
                boxShadow: MiftahShadows.gold,
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(
                    Icons.qr_code_scanner_rounded,
                    size: 28,
                    color: MiftahColors.ink,
                  ),
                  const SizedBox(width: 12),
                  Text(
                    l.scanAPass,
                    style: l.ar
                        ? MiftahType.ar(
                            size: 20,
                            weight: FontWeight.w700,
                            color: MiftahColors.ink,
                          )
                        : MiftahType.button(size: 21, color: MiftahColors.ink),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: _QuickTile(
                  icon: Icons.how_to_reg_rounded,
                  label: l.approvals,
                  l: l,
                  onTap: () => context.push('/approvals'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _QuickTile(
                  icon: Icons.person_add_alt_1_rounded,
                  label: l.walkIn,
                  l: l,
                  onTap: () => context.push('/walk-in'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Secondary action on the ink header. 52px tall — at the guard-app minimum,
/// do not shrink.
class _QuickTile extends StatelessWidget {
  const _QuickTile({
    required this.icon,
    required this.label,
    required this.onTap,
    required this.l,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final _L l;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        height: 52,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 19, color: MiftahColors.brassLight),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: l.ar
                    ? MiftahType.ar(
                        size: 13.5,
                        weight: FontWeight.w700,
                        color: Colors.white,
                      )
                    : MiftahType.button(size: 13.5, color: Colors.white),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// "Expected today" + the guest count, on the light canvas below the header.
class _ExpectedHeading extends ConsumerWidget {
  const _ExpectedHeading({required this.l});

  final _L l;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final expected = ref.watch(expectedTodayProvider);
    final count = expected.valueOrNull?.length;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(
            l.expectedToday,
            style: l.ar
                ? MiftahType.ar(size: 17, weight: FontWeight.w700)
                : MiftahType.title().copyWith(fontSize: 17),
          ),
          if (count != null)
            Text(l.guestCount(count), style: MiftahType.mono(size: 12.5)),
        ],
      ),
    );
  }
}

class _GateNavBar extends StatelessWidget {
  const _GateNavBar({
    required this.selectedIndex,
    required this.l,
    required this.onTap,
  });

  final int selectedIndex;
  final _L l;
  final ValueChanged<int> onTap;

  @override
  Widget build(BuildContext context) {
    final items = [
      (icon: Icons.people_outline, activeIcon: Icons.people, label: l.visitors),
      (
        icon: Icons.qr_code_scanner_outlined,
        activeIcon: Icons.qr_code_scanner,
        label: l.scan,
      ),
      (
        icon: Icons.approval_outlined,
        activeIcon: Icons.approval,
        label: l.approvals,
      ),
    ];

    return SafeArea(
      top: false,
      child: Container(
        height: 64,
        decoration: BoxDecoration(
          color: AppColors.primary,
          border: Border(
            top: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
          ),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: List.generate(items.length, (index) {
            final item = items[index];
            // The Scan destination pushes rather than selecting a tab, so it is
            // never "current" the way Visitors/Approvals are.
            final isSelected = index != 1 && index == selectedIndex;
            return Expanded(
              child: InkWell(
                onTap: () => onTap(index),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      isSelected ? item.activeIcon : item.icon,
                      size: 22,
                      color: isSelected
                          ? AppColors.accent
                          : Colors.white.withValues(alpha: 0.5),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      item.label,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 11,
                              fontWeight: isSelected
                                  ? FontWeight.w600
                                  : FontWeight.w400,
                              color: isSelected
                                  ? AppColors.accent
                                  : Colors.white.withValues(alpha: 0.5),
                            )
                          : GoogleFonts.plusJakartaSans(
                              fontSize: 10,
                              fontWeight: isSelected
                                  ? FontWeight.w600
                                  : FontWeight.w400,
                              letterSpacing: 0.4,
                              color: isSelected
                                  ? AppColors.accent
                                  : Colors.white.withValues(alpha: 0.5),
                            ),
                    ),
                  ],
                ),
              ),
            );
          }),
        ),
      ),
    );
  }
}

/// Appearance + language + sign-out, in a sheet reachable from the header's
/// settings icon — same pattern as the manager app's More screen.
class _SettingsSheet extends ConsumerWidget {
  const _SettingsSheet({required this.onSignOut, required this.onAccountDeleted});

  final Future<void> Function() onSignOut;
  final Future<void> Function() onAccountDeleted;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);

    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
          left: 16,
          right: 16,
          top: 14,
          bottom: MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: Container(
          decoration: BoxDecoration(
            color: m.surface,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: m.border),
          ),
          padding: const EdgeInsets.all(16),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: m.border,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              Text(
                l.ar ? l.settings : l.settings.toUpperCase(),
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 13,
                        color: m.textMuted,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 10,
                        letterSpacing: 2.4,
                        color: m.textMuted,
                      ),
              ),
              const SizedBox(height: 12),
              _AppearanceRow(l: l),
              const SizedBox(height: 12),
              _LanguageRow(l: l),
              const SizedBox(height: 14),
              // Privacy / Terms / Data deletion — the public pages every app
              // must link to.
              const LegalLinksList(dense: true),
              const SizedBox(height: 14),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: () {
                    Navigator.of(context).pop();
                    onSignOut();
                  },
                  icon: Icon(Icons.logout, size: 18, color: m.danger),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: m.danger,
                    side: BorderSide(color: m.danger.withValues(alpha: 0.5)),
                    padding: const EdgeInsets.symmetric(vertical: 13),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  label: Text(
                    l.ar ? l.signOut : l.signOut.toUpperCase(),
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 11.5,
                            fontWeight: FontWeight.w600,
                            letterSpacing: 2.0,
                          ),
                  ),
                ),
              ),
              const SizedBox(height: 6),
              // App Store Review Guideline 5.1.1(v): deletion starts in-app.
              DeleteAccountButton(onDeleted: onAccountDeleted),
            ],
          ),
        ),
      ),
    );
  }
}

class _AppearanceRow extends ConsumerWidget {
  const _AppearanceRow({required this.l});

  final _L l;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final mode = ref.watch(themeModeProvider);

    Widget option(String label, ThemeMode value) {
      final selected = mode == value;
      return Expanded(
        child: GestureDetector(
          onTap: () => ref.read(themeModeProvider.notifier).setMode(value),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            height: 34,
            margin: const EdgeInsets.symmetric(horizontal: 3),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(999),
              gradient: selected ? LegacyMiftahGradients.gold : null,
              border: selected ? null : Border.all(color: m.borderStrong),
            ),
            alignment: Alignment.center,
            child: Text(
              l.ar ? label : label.toUpperCase(),
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 12,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                      color: selected ? AppColors.primary : m.textSecondary,
                    )
                  : GoogleFonts.plusJakartaSans(
                      fontSize: 10,
                      letterSpacing: 1.6,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                      color: selected ? AppColors.primary : m.textSecondary,
                    ),
            ),
          ),
        ),
      );
    }

    return Row(
      children: [
        option(l.light, ThemeMode.light),
        option(l.dark, ThemeMode.dark),
        option(l.system, ThemeMode.system),
      ],
    );
  }
}

class _LanguageRow extends ConsumerWidget {
  const _LanguageRow({required this.l});

  final _L l;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final language = ref.watch(appLanguageProvider);

    Widget option({
      required bool selected,
      required VoidCallback onTap,
      required Widget child,
    }) {
      return Expanded(
        child: GestureDetector(
          onTap: onTap,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            height: 34,
            margin: const EdgeInsets.symmetric(horizontal: 3),
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(999),
              color: selected ? AppColors.accent : Colors.transparent,
              border: selected ? null : Border.all(color: m.borderStrong),
            ),
            child: child,
          ),
        ),
      );
    }

    return Row(
      children: [
        option(
          selected: language == AppLanguage.en,
          onTap: () => ref
              .read(appLanguageProvider.notifier)
              .setLanguage(AppLanguage.en),
          child: Text(
            'EN',
            style: GoogleFonts.plusJakartaSans(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              letterSpacing: 1.4,
              color: language == AppLanguage.en
                  ? AppColors.primary
                  : m.textSecondary,
            ),
          ),
        ),
        option(
          selected: language == AppLanguage.ar,
          onTap: () => ref
              .read(appLanguageProvider.notifier)
              .setLanguage(AppLanguage.ar),
          child: Text(
            'عربي',
            style: GoogleFonts.notoNaskhArabic(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: language == AppLanguage.ar
                  ? AppColors.primary
                  : m.textSecondary,
            ),
          ),
        ),
      ],
    );
  }
}

/// Today's expected visitors, grouped by property.
class _VisitorsTab extends ConsumerWidget {
  const _VisitorsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final expected = ref.watch(expectedTodayProvider);
    final l = _L(context.isAr);

    return RefreshIndicator(
      // Refreshes the postings alongside the board: a guard assigned mid-shift is
      // pulling precisely because they expect that to have changed, and refreshing
      // only the passes would leave "No properties assigned" on screen while the
      // gate they were just given fills up behind it.
      onRefresh: () {
        ref.invalidate(myPropertiesProvider);
        return ref.refresh(expectedTodayProvider.future);
      },
      child: expected.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => _Scrollable(
          child: ErrorState(
            message: l.visitorsLoadError,
            onRetry: () => ref.invalidate(expectedTodayProvider),
          ),
        ),
        data: (passes) {
          if (passes.isEmpty) return const _Scrollable(child: _NoVisitors());

          final groups = groupByProperty(passes);
          // A guard posted to one property — nearly all of them — gets no
          // headings: with only one group the heading separates nothing, and the
          // property is not in question at a gate you are standing at.
          final showHeadings = groups.length > 1;

          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(12, 4, 12, 96),
            itemCount: groups.length,
            itemBuilder: (context, index) {
              final group = groups[index];
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (showHeadings)
                    Padding(
                      padding: const EdgeInsetsDirectional.fromSTEB(
                        4,
                        12,
                        4,
                        8,
                      ),
                      child: Text(
                        propertyGroupLabel(
                          group.propertyId,
                          index,
                          propertyName: group.propertyName,
                        ),
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                                color: context.miftah.textMuted,
                              )
                            : GoogleFonts.plusJakartaSans(
                                fontSize: 13,
                                fontWeight: FontWeight.w700,
                                letterSpacing: 0.6,
                                color: context.miftah.textMuted,
                              ),
                      ),
                    ),
                  ...group.passes.map((pass) => _VisitorRow(pass: pass)),
                ],
              );
            },
          );
        },
      ),
    );
  }
}

/// One expected guest.
class _VisitorRow extends StatelessWidget {
  const _VisitorRow({required this.pass});

  final Map<String, dynamic> pass;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final name = passString(pass, 'guestName') ?? l.guestFallback;
    final unit = passString(pass, 'unitNumber');
    final vehicle = passString(pass, 'vehicleNumber');
    final window = formatWindow(
      passInstant(pass, 'validFrom'),
      passInstant(pass, 'validTo'),
    );

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
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
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  name,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 16.5,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.plusJakartaSans(
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                          color: m.textPrimary,
                        ),
                ),
              ),
              if (unit != null)
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 8,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: m.surfaceAlt,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: m.border),
                  ),
                  child: Text(
                    l.unitLabel(unit),
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 12.5,
                            fontWeight: FontWeight.w600,
                            color: m.textSecondary,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 13,
                            fontWeight: FontWeight.w700,
                            color: m.textSecondary,
                          ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Icon(Icons.schedule, size: 15, color: m.textMuted),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  window,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 13.5,
                          color: m.textSecondary,
                        )
                      : GoogleFonts.plusJakartaSans(
                          fontSize: 14,
                          color: m.textSecondary,
                        ),
                ),
              ),
            ],
          ),
          if (vehicle != null) ...[
            const SizedBox(height: 8),
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: m.successBg,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.directions_car,
                      size: 14,
                      color: AppColors.accentDark,
                    ),
                    const SizedBox(width: 5),
                    Text(
                      vehicle,
                      style: GoogleFonts.plusJakartaSans(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: AppColors.accentDark,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// The empty board, resolved to whichever kind of empty this actually is.
///
/// `GET /expected-today` answers `[]` both for a posted guard on a quiet day and
/// for a guard with no property assignments at all, so this used to hedge and
/// name both cases in one message. `GET /my-properties` now separates them: an
/// unposted guard is told plainly to go and ask, instead of being left to work a
/// shift wondering whether the silence is real.
///
/// The hedged copy survives as the fallback for exactly one case — the
/// assignments call itself failing. Knowing "nothing is expected" while not
/// knowing why is precisely the situation the old wording was written for, so it
/// is the honest thing to show rather than a guess in either direction.
class _NoVisitors extends ConsumerWidget {
  const _NoVisitors();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final properties = ref.watch(myPropertiesProvider);
    final l = _L(context.isAr);

    return properties.when(
      // Not a spinner: the board underneath has already resolved, and flashing a
      // loader onto a settled screen reads as a reload. The hedged copy is true
      // in both cases, so it holds until the answer lands.
      loading: () => _EmptyBoardHedged(l: l),
      error: (_, _) => _EmptyBoardHedged(l: l),
      data: (properties) => properties.isEmpty
          ? EmptyState(
              icon: Icons.location_off_outlined,
              title: l.noPropertiesTitle,
              subtitle: l.noPropertiesSubtitle,
            )
          : EmptyState(
              icon: Icons.event_available,
              title: l.noVisitorsTitle,
              subtitle: l.noVisitorsSubtitle,
            ),
    );
  }
}

/// The pre-`/my-properties` wording: correct but non-committal, for when the
/// assignments call has not answered (yet, or at all).
class _EmptyBoardHedged extends StatelessWidget {
  const _EmptyBoardHedged({required this.l});

  final _L l;

  @override
  Widget build(BuildContext context) {
    return EmptyState(
      icon: Icons.event_available,
      title: l.noVisitorsTitle,
      subtitle: l.noVisitorsHedgedSubtitle,
    );
  }
}

/// Keeps [RefreshIndicator] usable on a screen with nothing to scroll — a
/// non-scrolling child means the pull gesture never reaches the indicator, so an
/// empty or errored board could not be refreshed at all.
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

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get settings => ar ? 'الإعدادات' : 'Settings';
  String get scan => ar ? 'مسح الرمز' : 'Scan';
  String get approvals => ar ? 'الموافقات' : 'Approvals';
  String get walkIn => ar ? 'زيارة بدون تصريح' : 'Walk-in';
  String get visitors => ar ? 'الزوّار' : 'Visitors';
  String get expectedToday => ar ? 'المتوقعون اليوم' : 'Expected today';
  String get guardFallback => ar ? 'حارس' : 'Guard';
  String get guestFallback => ar ? 'زائر' : 'Guest';
  String get loadingPosting =>
      ar ? 'جارٍ تحميل موقع العمل...' : 'Loading posting…';
  String get postingUnavailable =>
      ar ? 'تعذّر تحميل موقع العمل' : 'Could not load posting';
  String get light => ar ? 'فاتح' : 'Light';
  String get dark => ar ? 'داكن' : 'Dark';
  String get system => ar ? 'تلقائي' : 'System';
  String get signOut => ar ? 'تسجيل الخروج' : 'Sign out';
  String get visitorsLoadError =>
      ar ? "تعذّر تحميل زوّار اليوم." : "Could not load today's visitors.";
  String get noPropertiesTitle =>
      ar ? 'لا توجد عقارات مسندة إليك' : 'No properties assigned';
  String get noPropertiesSubtitle => ar
      ? 'لم يتم تعيينك على بوابة بعد، لذا لن يظهر هنا أي زوّار.\n\n'
            'اطلب من مديرك تعيينك على أحد العقارات.'
      : 'You are not posted to a gate yet, so no visitors will '
            'appear here.\n\n'
            'Ask your manager to assign you to a property.';
  String get noVisitorsTitle =>
      ar ? 'لا يوجد زوّار متوقعون اليوم' : 'No visitors expected today';
  String get noVisitorsSubtitle => ar
      ? 'يظهر هنا الضيوف المحجوزون لبوابتك.'
      : 'Guests booked for your gate appear here.';
  String get noVisitorsHedgedSubtitle => ar
      ? 'يظهر هنا الضيوف المحجوزون لبوابتك.\n\n'
            'لم يظهر شيء طوال المناوبة؟ اطلب من مديرك التأكد من تعيينك على عقار.'
      : 'Guests booked for your gate appear here.\n\n'
            'Nothing all shift? Ask your manager to check that you are assigned '
            'to a property.';

  String unitLabel(String unit) => ar ? 'وحدة $unit' : 'Unit $unit';

  String propertyLine(List<Map<String, dynamic>> properties) {
    if (properties.isEmpty) {
      return ar ? 'غير مُعيَّن على عقار' : 'Not posted to a property';
    }
    if (properties.length == 1) {
      final name = properties.first['name'] as String? ?? '';
      return name.isEmpty ? (ar ? 'حارس أمن' : 'Security guard') : name;
    }
    return ar
        ? '${properties.length} عقارات مسندة'
        : '${properties.length} properties assigned';
  }

  // ── Added by the redesign (design screen 03) ──
  String get scanAPass => ar ? 'مسح تصريح' : 'Scan a pass';
  String onShiftLine(String posting) =>
      ar ? 'في الخدمة · $posting' : 'On shift · $posting';
  String guestCount(int n) =>
      ar ? '$n زائر' : '$n ${n == 1 ? 'guest' : 'guests'}';
}
