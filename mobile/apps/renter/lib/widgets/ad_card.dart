import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The height an [AdCard] occupies at a given text scale. The carousel needs
/// this before it builds a card, so it lives here rather than inside the
/// widget. Clamped at 1.5x: past that the copy is clipped rather than allowed
/// to push the strip to half the screen.
double adCardHeight(BuildContext context) =>
    140.0 * MediaQuery.textScalerOf(context).scale(1).clamp(1.0, 1.5);

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
        // The card has a fixed height (clamped at 1.5x text scale) but the
        // copy keeps scaling. The text block takes whatever is left after the
        // CTA and lays out at its natural size inside a clipping OverflowBox:
        // when it fits it renders exactly as an unconstrained Column would (no
        // line is ever cut early); when it doesn't, the bottom is clipped
        // instead of throwing "RenderFlex overflowed by N pixels on the
        // bottom".
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Flexible(
              child: ClipRect(
                child: OverflowBox(
                  alignment: isAr ? Alignment.topRight : Alignment.topLeft,
                  minHeight: 0,
                  maxHeight: double.infinity,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (eyebrow.isNotEmpty)
                        Text(
                          eyebrow.toUpperCase(),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: MiftahType.sectionLabel(
                            color: hasImage
                                ? MiftahColors.brassPale
                                : MiftahColors.warning,
                          ),
                        ),
                      const SizedBox(height: 3),
                      Text(
                        ad.title(isAr),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: MiftahType.cardTitle(color: onFill)
                            .copyWith(fontSize: 18, height: 1.1),
                      ),
                      // When the eyebrow is showing the subtitle, the business
                      // name has nowhere else to appear — and on a card with no
                      // artwork the renter has no other clue who is offering
                      // this. The admin panel's preview already renders this
                      // line; the widget was the side that was missing it.
                      if (!hasImage
                          && ad.subtitle(isAr) != null
                          && ad.business.name(isAr).isNotEmpty) ...[
                        const SizedBox(height: 3),
                        Text(
                          ad.business.name(isAr),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: MiftahType.body(
                              size: 12, color: MiftahColors.textSecondary),
                        ),
                      ],
                    ],
                  ),
                ),
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
