import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../demo/wallet_demo.dart';

/// A gold scratch panel over [child]. Dragging a finger across it rubs the
/// cover away; once about half is gone (or [revealNow] is called) the rest
/// fades and [onRevealed] fires once.
class ScratchCard extends StatefulWidget {
  const ScratchCard({
    super.key,
    required this.child,
    required this.onRevealed,
    this.revealed = false,
    this.width = 280,
    this.height = 190,
    this.coverLabel = 'SCRATCH TO REVEAL',
  });

  final Widget child;
  final VoidCallback onRevealed;

  /// Start with the cover already gone (a card scratched earlier).
  final bool revealed;
  final double width;
  final double height;
  final String coverLabel;

  @override
  State<ScratchCard> createState() => ScratchCardState();
}

class ScratchCardState extends State<ScratchCard>
    with SingleTickerProviderStateMixin {
  static const _cols = 10;
  static const _rows = 6;
  static const _threshold = 0.5;
  static const _brush = 22.0;

  final _points = <Offset>[];
  final _cells = <int>{};
  late final AnimationController _fade = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 450),
    value: widget.revealed ? 0 : 1,
  );
  bool _revealed = false;

  @override
  void initState() {
    super.initState();
    _revealed = widget.revealed;
  }

  @override
  void dispose() {
    _fade.dispose();
    super.dispose();
  }

  double get progress => _cells.length / (_cols * _rows);

  Offset? _last;

  /// Rubs from the previous sample to [p]. Pan samples arrive sparsely on a
  /// fast swipe (and very sparsely from automation), so the gap is filled
  /// with points a half-brush apart and the stroke stays continuous.
  void _rub(Offset p, {bool continuous = true}) {
    if (_revealed) return;
    final from = continuous ? _last : null;
    final stamps = <Offset>[];
    if (from != null) {
      final gap = (p - from).distance;
      final steps = (gap / (_brush / 2)).ceil();
      for (var i = 1; i < steps; i++) {
        stamps.add(Offset.lerp(from, p, i / steps)!);
      }
    }
    stamps.add(p);
    _last = p;
    setState(() {
      for (final s in stamps) {
        _points.add(s);
        final cx = (s.dx / widget.width * _cols).floor().clamp(0, _cols - 1);
        final cy = (s.dy / widget.height * _rows).floor().clamp(0, _rows - 1);
        _cells.add(cy * _cols + cx);
      }
    });
    if (progress >= _threshold) revealNow();
  }

  /// Clears whatever cover is left and reports the reveal, once.
  void revealNow() {
    if (_revealed) return;
    _revealed = true;
    HapticFeedback.mediumImpact();
    _fade.reverse(from: 1).whenComplete(() {
      if (mounted) setState(() {});
    });
    widget.onRevealed();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: widget.width,
      height: widget.height,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(MiftahRadii.card),
        child: Stack(
          fit: StackFit.expand,
          children: [
            widget.child,
            if (_fade.value > 0)
              FadeTransition(
                opacity: _fade,
                child: GestureDetector(
                  key: const Key('scratch-cover'),
                  behavior: HitTestBehavior.opaque,
                  onPanStart: (d) => _rub(d.localPosition, continuous: false),
                  onPanUpdate: (d) => _rub(d.localPosition),
                  onTapDown: (d) => _rub(d.localPosition, continuous: false),
                  child: CustomPaint(
                    painter: _CoverPainter(
                      points: List.of(_points),
                      brush: _brush,
                      label: widget.coverLabel,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _CoverPainter extends CustomPainter {
  _CoverPainter({
    required this.points,
    required this.brush,
    required this.label,
  });

  final List<Offset> points;
  final double brush;
  final String label;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.saveLayer(rect, Paint());
    canvas.drawRect(
      rect,
      Paint()..shader = MiftahGradients.gold.createShader(rect),
    );

    // A faint diamond lattice so the rubbed-away patches read as "scratched".
    final lattice = Paint()
      ..color = MiftahColors.ink.withValues(alpha: 0.08)
      ..strokeWidth = 1;
    const step = 26.0;
    for (var x = -size.height; x < size.width; x += step) {
      canvas.drawLine(
        Offset(x, 0),
        Offset(x + size.height, size.height),
        lattice,
      );
      canvas.drawLine(
        Offset(x + size.height, 0),
        Offset(x, size.height),
        lattice,
      );
    }

    final star = TextPainter(
      text: TextSpan(
        text: '✦',
        style: TextStyle(
          fontSize: 34,
          color: MiftahColors.ink.withValues(alpha: 0.85),
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    star.paint(
      canvas,
      Offset((size.width - star.width) / 2, size.height / 2 - star.height - 2),
    );
    final text = TextPainter(
      text: TextSpan(
        text: label,
        style: MiftahType.sectionLabel(color: MiftahColors.ink),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    text.paint(
      canvas,
      Offset((size.width - text.width) / 2, size.height / 2 + 6),
    );

    final eraser = Paint()
      ..blendMode = BlendMode.clear
      ..style = PaintingStyle.fill;
    for (final p in points) {
      canvas.drawCircle(p, brush, eraser);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_CoverPainter old) =>
      old.points.length != points.length || old.label != label;
}

/// What a card hides: the cashback amount, the voucher, or the consolation.
class RewardFace extends StatelessWidget {
  const RewardFace({super.key, required this.reward, this.compact = false});

  final DemoReward reward;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final m = context.miftah;
    final l = _L(ar);
    final titleStyle = ar
        ? MiftahType.ar(size: compact ? 13 : 15, weight: FontWeight.w700)
        : MiftahType.cardTitle();
    final metaStyle = ar
        ? MiftahType.ar(size: compact ? 11 : 12, color: m.textMuted)
        : MiftahType.meta(color: m.textMuted);

    final Widget body = switch (reward.kind) {
      DemoRewardKind.cashback => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            Formatters.currency(reward.amount ?? 0),
            style: MiftahType.amount(
              size: compact ? 22 : 34,
              color: MiftahColors.successDeep,
            ),
          ),
          const SizedBox(height: 4),
          Text(l.cashback, style: titleStyle),
          if (!compact) ...[
            const SizedBox(height: 2),
            Text(l.addedToWallet, style: metaStyle),
          ],
        ],
      ),
      DemoRewardKind.voucher => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            reward.voucher!.brand(ar).toUpperCase(),
            style: MiftahType.sectionLabel(color: MiftahColors.brassDeep),
          ),
          const SizedBox(height: 6),
          Text(
            reward.voucher!.title(ar),
            textAlign: TextAlign.center,
            maxLines: compact ? 2 : 3,
            overflow: TextOverflow.ellipsis,
            style: titleStyle,
          ),
          if (!compact) ...[
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: MiftahColors.brassTint,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: MiftahColors.brassTintBorder),
              ),
              child: Text(
                reward.voucher!.code,
                style: MiftahType.mono(size: 14, color: MiftahColors.ink),
              ),
            ),
          ],
        ],
      ),
      DemoRewardKind.betterLuck => Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.sentiment_satisfied_alt_rounded,
            size: compact ? 28 : 40,
            color: m.textMuted,
          ),
          const SizedBox(height: 6),
          Text(l.betterLuck, style: titleStyle, textAlign: TextAlign.center),
          if (!compact) ...[
            const SizedBox(height: 2),
            Text(l.nextPaymentWins, style: metaStyle),
          ],
        ],
      ),
    };

    return Container(
      color: m.surface,
      padding: EdgeInsets.all(compact ? 10 : 16),
      alignment: Alignment.center,
      // A compact face sits in whatever tile the caller gives it (the receipt
      // card is 128×96), so it shrinks rather than overflows.
      child: compact ? FittedBox(fit: BoxFit.scaleDown, child: body) : body,
    );
  }
}

/// The reveal moment after a payment, or when a card on the Rewards screen
/// is tapped. Scratching (or **Reveal**) credits the card through
/// [walletDemoProvider].
class ScratchCardSheet extends ConsumerStatefulWidget {
  const ScratchCardSheet({super.key, required this.rewardId});

  final String rewardId;

  static Future<void> show(BuildContext context, String rewardId) {
    return showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (_) => ScratchCardSheet(rewardId: rewardId),
    );
  }

  @override
  ConsumerState<ScratchCardSheet> createState() => _ScratchCardSheetState();
}

class _ScratchCardSheetState extends ConsumerState<ScratchCardSheet> {
  final _card = GlobalKey<ScratchCardState>();
  bool _revealed = false;

  void _onRevealed() {
    ref.read(walletDemoProvider.notifier).scratch(widget.rewardId);
    setState(() => _revealed = true);
  }

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final m = context.miftah;
    final l = _L(ar);
    final reward = ref.watch(
      walletDemoProvider.select((s) => s.reward(widget.rewardId)),
    );
    if (reward == null) return const SizedBox.shrink();
    final alreadyScratched = reward.scratched && !_revealed;

    final String headline;
    if (!reward.scratched) {
      headline = l.scratchTitle;
    } else {
      headline = switch (reward.kind) {
        DemoRewardKind.cashback => l.wonCashback(reward.amount ?? 0),
        DemoRewardKind.voucher => l.wonVoucher(reward.voucher!.brand(ar)),
        DemoRewardKind.betterLuck => l.noPrize,
      };
    }

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: const BorderRadius.vertical(
          top: Radius.circular(MiftahRadii.sheet),
        ),
      ),
      padding: EdgeInsets.fromLTRB(
        MiftahSpacing.page,
        14,
        MiftahSpacing.page,
        MediaQuery.viewInsetsOf(context).bottom + 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 42,
            height: 4,
            decoration: BoxDecoration(
              color: m.borderStrong,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 18),
          Text(
            headline,
            key: const Key('scratch-headline'),
            textAlign: TextAlign.center,
            style: ar
                ? MiftahType.ar(size: 18, weight: FontWeight.w700)
                : MiftahType.title(color: m.textPrimary),
          ),
          const SizedBox(height: 4),
          Text(
            reward.earnedFor(ar),
            textAlign: TextAlign.center,
            style: ar
                ? MiftahType.ar(size: 12, color: m.textMuted)
                : MiftahType.meta(color: m.textMuted),
          ),
          const SizedBox(height: 20),
          DecoratedBox(
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(MiftahRadii.card),
              boxShadow: MiftahShadows.raised,
              border: Border.all(color: m.border),
            ),
            child: ScratchCard(
              key: _card,
              revealed: alreadyScratched,
              coverLabel: l.scratchCover,
              onRevealed: _onRevealed,
              child: RewardFace(reward: reward),
            ),
          ),
          const SizedBox(height: 20),
          if (!reward.scratched)
            TextButton(
              key: const Key('scratch-reveal'),
              onPressed: () => _card.currentState?.revealNow(),
              child: Text(l.revealNow),
            )
          else ...[
            if (reward.kind == DemoRewardKind.voucher)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Text(
                  reward.voucher!.terms(ar),
                  textAlign: TextAlign.center,
                  style: ar
                      ? MiftahType.ar(size: 12, color: m.textSecondary)
                      : MiftahType.body(color: m.textSecondary),
                ),
              ),
            MiftahGoldButton(
              key: const Key('scratch-done'),
              label: switch (reward.kind) {
                DemoRewardKind.cashback => l.collect,
                DemoRewardKind.voucher => l.saveVoucher,
                DemoRewardKind.betterLuck => l.close,
              },
              onPressed: () => Navigator.of(context).pop(),
            ),
          ],
        ],
      ),
    );
  }
}

class _L {
  _L(this.ar);
  final bool ar;

  String get cashback => ar ? 'كاش باك' : 'Cashback';
  String get addedToWallet =>
      ar ? 'أُضيف إلى رصيد محفظتك' : 'Added to your wallet balance';
  String get betterLuck =>
      ar ? 'حظاً أوفر المرة القادمة' : 'Better luck next time';
  String get nextPaymentWins => ar
      ? 'كل دفعة إلكترونية تمنحك بطاقة جديدة'
      : 'Every online payment earns a new card';
  String get scratchTitle =>
      ar ? 'اكشط لتكشف مكافأتك' : 'Scratch to reveal your reward';
  String get scratchCover => ar ? 'اكشط هنا' : 'SCRATCH TO REVEAL';
  String wonCashback(num amount) => ar
      ? 'ربحت ${Formatters.currency(amount)} كاش باك!'
      : 'You won ${Formatters.currency(amount)} cashback!';
  String wonVoucher(String brand) =>
      ar ? 'ربحت قسيمة من $brand!' : 'You won a $brand voucher!';
  String get noPrize => ar ? 'لا جائزة هذه المرة' : 'No prize this time';
  String get revealNow => ar ? 'اكشف الآن' : 'Reveal now';
  String get collect => ar ? 'تحصيل' : 'Collect';
  String get saveVoucher => ar ? 'حفظ القسيمة' : 'Save voucher';
  String get close => ar ? 'إغلاق' : 'Close';
}
