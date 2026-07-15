import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../gatepass/pass_display.dart';
import '../providers/gate_pass_provider.dart';
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        title: Text(_tab == 0 ? 'Expected today' : 'Approvals'),
        backgroundColor: AppColors.surface,
        surfaceTintColor: Colors.transparent,
        actions: [
          IconButton(
            tooltip: 'Log out',
            icon: const Icon(Icons.logout, color: AppColors.textMuted),
            onPressed: () => ref.read(authProvider.notifier).logout(),
          ),
        ],
      ),
      body: SafeArea(
        child: _tab == 0 ? const _VisitorsTab() : const ApprovalsView(),
      ),
      // The gate's primary action, sized to be hit without looking at the phone.
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/scan'),
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        icon: const Icon(Icons.qr_code_scanner, size: 26),
        label: const Text(
          'Scan',
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: _onDestinationSelected,
        backgroundColor: AppColors.surface,
        indicatorColor: AppColors.accentLight,
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.people_outline),
            selectedIcon: Icon(Icons.people),
            label: 'Visitors',
          ),
          NavigationDestination(
            icon: Icon(Icons.qr_code_scanner),
            label: 'Scan',
          ),
          NavigationDestination(
            icon: Icon(Icons.approval_outlined),
            selectedIcon: Icon(Icons.approval),
            label: 'Approvals',
          ),
        ],
      ),
    );
  }
}

/// Today's expected visitors, grouped by property.
class _VisitorsTab extends ConsumerWidget {
  const _VisitorsTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final expected = ref.watch(expectedTodayProvider);

    return RefreshIndicator(
      onRefresh: () => ref.refresh(expectedTodayProvider.future),
      child: expected.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => _Scrollable(
          child: ErrorState(
            message: "Could not load today's visitors.",
            onRetry: () => ref.invalidate(expectedTodayProvider),
          ),
        ),
        data: (passes) {
          if (passes.isEmpty) return const _Scrollable(child: _NoVisitors());

          final groups = groupByProperty(passes);
          // A guard posted to one property — nearly all of them — gets no
          // headings: with only one group the heading separates nothing, and the
          // label it would carry is an id fragment, not a name.
          final showHeadings = groups.length > 1;

          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 96),
            itemCount: groups.length,
            itemBuilder: (context, index) {
              final group = groups[index];
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (showHeadings)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(4, 12, 4, 8),
                      child: Text(
                        propertyGroupLabel(group.propertyId, index),
                        style: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 0.6,
                          color: AppColors.textMuted,
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
    final name = passString(pass, 'guestName') ?? 'Guest';
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
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.border),
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
                  style: const TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                    color: AppColors.textPrimary,
                  ),
                ),
              ),
              if (unit != null)
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: AppColors.surface2,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: AppColors.border),
                  ),
                  child: Text(
                    'Unit $unit',
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: AppColors.textSecondary,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              const Icon(Icons.schedule, size: 15, color: AppColors.textMuted),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  window,
                  style: const TextStyle(
                    fontSize: 14,
                    color: AppColors.textSecondary,
                  ),
                ),
              ),
            ],
          ),
          if (vehicle != null) ...[
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerLeft,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  color: AppColors.accentLight,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.directions_car,
                        size: 14, color: AppColors.accentDark),
                    const SizedBox(width: 5),
                    Text(
                      vehicle,
                      style: const TextStyle(
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

/// The empty board.
///
/// **Deliberately covers two situations at once**, because the app cannot tell
/// them apart: `GET /expected-today` answers `[]` both for a posted guard with a
/// quiet day and for a guard with no property assignments at all. There is no
/// guard-facing endpoint that reports the assignments (`/guards/{id}/properties`
/// is manager-only), so "no visitors" and "you are posted nowhere" are the same
/// response on the wire. The copy therefore names the second case explicitly —
/// an unposted guard who is only told "no visitors today" will wait out a whole
/// shift before anyone discovers the app was never going to show them anything.
class _NoVisitors extends StatelessWidget {
  const _NoVisitors();

  @override
  Widget build(BuildContext context) {
    return const EmptyState(
      icon: Icons.event_available,
      title: 'No visitors expected today',
      subtitle: 'Guests booked for your gate appear here.\n\n'
          'Nothing all shift? Ask your manager to check that you are assigned '
          'to a property.',
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
