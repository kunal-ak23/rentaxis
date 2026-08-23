import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The card's height before text scaling.
///
/// 160, not the 140 this shipped with. A card carrying the business-name line
/// needs 146pt in Latin and 155pt in Arabic at default text scale — measured
/// against the real faces, with a two-line headline and the CTA pill showing —
/// and Arabic is the taller script by a wide margin: its fallback face runs
/// about 1.9em a line against Plus Jakarta's 1.55em. At 140 the last line was
/// cut through its glyphs on every phone, which reads as a rendering fault
/// rather than as truncation. 160 leaves the Arabic card whole with headroom
/// for a system Arabic face taller than the one measured.
const _cardHeight = 160.0;

/// Past this the card stops growing and the copy yields a line instead — see
/// [_CopyFit]. Letting it grow with the scaler would hand half the home screen
/// to an ad.
const _maxHeightScale = 1.5;

/// Space between the lines of the copy block.
const _copyGap = 3.0;

/// The height an [AdCard] occupies at a given text scale. The carousel needs
/// this before it builds a card, so it lives here rather than inside the
/// widget. Clamped at 1.5x: past that the card holds still and the copy block
/// drops its optional lines rather than pushing the strip to half the screen.
double adCardHeight(BuildContext context) =>
    _cardHeight *
    MediaQuery.textScalerOf(context).scale(1).clamp(1.0, _maxHeightScale);

/// One promotion card.
///
/// Photo-hero when the ad has artwork — full-bleed image, darkened so white
/// copy stays legible. Split colour card when it does not, filled with the
/// ad's accent colour. Both share the same copy block and CTA pill.
class AdCard extends StatelessWidget {
  const AdCard({super.key, required this.ad, required this.onTap});

  final PromoAd ad;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final hasImage = ad.hasImage;
    // brassTint, not surfaceAlt. surfaceAlt (#F4F2F9) differs from the home
    // canvas (#F6F5FA) by six across all three channels combined, so a card
    // with neither artwork nor an accent colour was an invisible rectangle on
    // the home screen. brassTint is the token's documented chip/badge fill and
    // reads as deliberate. The border gives it an edge either way, including
    // when a client picks an accent close to the canvas.
    final fill = ad.accentColor ?? MiftahColors.brassTint;
    final onFill = hasImage ? Colors.white : MiftahColors.textPrimary;
    final eyebrow = ad.subtitle(isAr) ?? ad.business.name(isAr);
    final ctaLabel = ad.ctaLabel(isAr);
    final businessName = ad.business.name(isAr);
    // When the eyebrow is showing the subtitle, the business name has nowhere
    // else to appear — and on a card with no artwork the renter has no other
    // clue who is offering this. The admin panel's preview already renders this
    // line; the widget was the side that was missing it.
    final wantsBusinessName =
        !hasImage && ad.subtitle(isAr) != null && businessName.isNotEmpty;

    // Held as locals because the fit pass below has to measure the very styles
    // the Text widgets render with — a style that drifts between the two is a
    // sliced line.
    final eyebrowStyle = MiftahType.sectionLabel(
      color: hasImage ? MiftahColors.brassPale : MiftahColors.warning,
    );
    final titleStyle =
        MiftahType.cardTitle(color: onFill).copyWith(fontSize: 18, height: 1.1);
    final businessStyle =
        MiftahType.body(size: 12, color: MiftahColors.textSecondary);

    return InkWell(
      borderRadius: BorderRadius.circular(MiftahRadii.card),
      onTap: onTap,
      child: Container(
        key: const Key('ad-card-surface'),
        decoration: BoxDecoration(
          color: hasImage ? null : fill,
          border: hasImage
              ? null
              : Border.all(color: MiftahColors.brassTintBorder),
          borderRadius: BorderRadius.circular(MiftahRadii.card),
          image: hasImage
              ? DecorationImage(
                  image: CachedNetworkImageProvider(ad.backgroundImageUrl!),
                  fit: BoxFit.cover,
                  colorFilter: ColorFilter.mode(
                    Colors.black.withValues(alpha: 0.35),
                    BlendMode.darken,
                  ),
                )
              : null,
        ),
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Flexible(
              // The card's height is fixed before it knows what copy it holds,
              // so the block asks how much room it was given and renders what
              // fits: a line is either whole or not asked for. The clipping
              // OverflowBox below stays as the last resort — it keeps a card
              // that still doesn't fit (a scaler past the clamp, a headline in
              // a face taller than any measured here) from throwing
              // "RenderFlex overflowed by N pixels on the bottom".
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final fit = _CopyFit.measure(
                    context,
                    available: constraints.maxHeight,
                    maxWidth: constraints.maxWidth,
                    eyebrow: eyebrow.isEmpty ? null : eyebrow.toUpperCase(),
                    eyebrowStyle: eyebrowStyle,
                    title: ad.title(isAr),
                    titleStyle: titleStyle,
                    businessName: wantsBusinessName ? businessName : null,
                    businessStyle: businessStyle,
                  );

                  final lines = <Widget>[
                    if (fit.showEyebrow)
                      Text(
                        eyebrow.toUpperCase(),
                        // One line, always. The eyebrow is a tracked uppercase
                        // label; a second line of it is not a label any more,
                        // and the fixed height cannot promise one on top of the
                        // headline and the business name.
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: eyebrowStyle,
                      ),
                    Text(
                      ad.title(isAr),
                      maxLines: fit.titleMaxLines,
                      overflow: TextOverflow.ellipsis,
                      style: titleStyle,
                    ),
                    if (fit.showBusinessName)
                      Text(
                        businessName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: businessStyle,
                      ),
                  ];

                  return ClipRect(
                    child: OverflowBox(
                      alignment: isAr ? Alignment.topRight : Alignment.topLeft,
                      minHeight: 0,
                      maxHeight: double.infinity,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          for (var i = 0; i < lines.length; i++) ...[
                            if (i > 0) const SizedBox(height: _copyGap),
                            lines[i],
                          ],
                        ],
                      ),
                    ),
                  );
                },
              ),
            ),
            if (ctaLabel.isNotEmpty) ...[
              const SizedBox(height: 6),
              Container(
                key: const Key('ad-card-cta'),
                padding:
                    const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                decoration: BoxDecoration(
                  color: hasImage ? MiftahColors.surface : MiftahColors.ink,
                  borderRadius: BorderRadius.circular(MiftahRadii.pill),
                ),
                child: Text(
                  isAr ? '← $ctaLabel' : '$ctaLabel →',
                  style: MiftahType.badge(
                    color: hasImage ? MiftahColors.ink : MiftahColors.surface,
                  ).copyWith(fontSize: 11),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// How much of the copy the card has room for, given the height left after the
/// CTA pill has taken its share.
///
/// The card gives up its optional copy in a fixed order — the business name,
/// then the headline's second line, then the eyebrow — because that is the
/// order the renter can afford to lose it in. A line is either rendered whole
/// or not asked for; nothing here is cut halfway down its glyphs.
class _CopyFit {
  const _CopyFit({
    required this.showEyebrow,
    required this.titleMaxLines,
    required this.showBusinessName,
  });

  final bool showEyebrow;
  final int titleMaxLines;
  final bool showBusinessName;

  static _CopyFit measure(
    BuildContext context, {
    required double available,
    required double maxWidth,
    required String? eyebrow,
    required TextStyle eyebrowStyle,
    required String title,
    required TextStyle titleStyle,
    required String? businessName,
    required TextStyle businessStyle,
  }) {
    final defaults = DefaultTextStyle.of(context);
    final scaler = MediaQuery.textScalerOf(context);
    final direction = Directionality.of(context);
    final heightBehavior = defaults.textHeightBehavior ??
        DefaultTextHeightBehavior.maybeOf(context);

    double lineBox(String text, TextStyle style, int maxLines) {
      // Merged and scaled exactly as `Text` will merge and scale it, or the
      // measurement is of a style nothing renders.
      final resolved = style.inherit ? defaults.style.merge(style) : style;
      final painter = TextPainter(
        text: TextSpan(text: text, style: resolved),
        maxLines: maxLines,
        ellipsis: '…',
        textScaler: scaler,
        textDirection: direction,
        textHeightBehavior: heightBehavior,
      )..layout(maxWidth: maxWidth);
      final height = painter.height;
      painter.dispose();
      return height;
    }

    final eyebrowHeight =
        eyebrow == null ? 0.0 : lineBox(eyebrow, eyebrowStyle, 1);
    final businessHeight =
        businessName == null ? 0.0 : lineBox(businessName, businessStyle, 1);

    var showEyebrow = eyebrow != null;
    var titleMaxLines = 2;
    var showBusinessName = businessName != null;
    var titleHeight = lineBox(title, titleStyle, titleMaxLines);

    double total() =>
        (showEyebrow ? eyebrowHeight + _copyGap : 0.0) +
        titleHeight +
        (showBusinessName ? _copyGap + businessHeight : 0.0);

    // The order things are given up in is the order the renter can afford to
    // lose them: the business name, then the headline's second line, then the
    // eyebrow. The headline is the offer, so it is the last thing standing.
    if (total() > available) showBusinessName = false;
    if (total() > available) {
      titleMaxLines = 1;
      titleHeight = lineBox(title, titleStyle, titleMaxLines);
    }
    if (total() > available) showEyebrow = false;

    return _CopyFit(
      showEyebrow: showEyebrow,
      titleMaxLines: titleMaxLines,
      showBusinessName: showBusinessName,
    );
  }
}
