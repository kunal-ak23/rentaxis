import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'pass_display.dart';

/// The branded image a renter sends when they share a visitor pass.
///
/// A guest arriving at a gate should not have to scroll a chat for a six-digit
/// code: the card carries the QR, the code, the place and the window in one
/// picture, over Miftah's ink-and-gold chrome so it is recognisable as coming
/// from the building rather than from a stranger.
///
/// Rendered off-screen and captured to PNG — see [renderPassShareCard]. It is
/// laid out at a fixed logical width so the output is identical on every
/// device, then captured at 3x for a crisp scan target.
class MiftahPassShareCard extends StatelessWidget {
  const MiftahPassShareCard({
    super.key,
    required this.pass,
    required this.propertyName,
    required this.unitIdentifier,
    required this.ar,
    required this.strings,
  });

  static const double width = 380;

  final Map<String, dynamic> pass;
  final String? propertyName;
  final String? unitIdentifier;
  final bool ar;
  final PassShareStrings strings;

  @override
  Widget build(BuildContext context) {
    final guest = passString(pass, 'guestName');
    final code = passString(pass, 'numericCode');
    final token = passString(pass, 'qrToken');
    final window = formatWindow(
      passInstant(pass, 'validFrom'),
      passInstant(pass, 'validTo'),
      ar: ar,
    );
    final place = [
      propertyName,
      unitIdentifier == null
          ? null
          : (ar ? 'وحدة $unitIdentifier' : 'Unit $unitIdentifier'),
    ].whereType<String>().join(' · ');

    return Directionality(
      textDirection: ar ? TextDirection.rtl : TextDirection.ltr,
      child: Container(
        width: width,
        color: MiftahColors.ink,
        padding: const EdgeInsets.fromLTRB(24, 26, 24, 22),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            // Arabic wordmark in عربي, English wordmark in EN — one script each.
            Image.asset(
              ar ? 'assets/logo_mark.png' : 'assets/logo_horizontal.png',
              height: ar ? 40 : 22,
              fit: BoxFit.contain,
              // A missing wordmark must not cost the guest their pass.
              errorBuilder: (_, _, _) => Text(
                'MIFTAH',
                style: MiftahType.sectionLabel(
                  color: MiftahColors.brassLight,
                ),
              ),
            ),
            const SizedBox(height: 18),
            Text(
              strings.gatePass,
              textAlign: TextAlign.center,
              style: ar
                  ? MiftahType.ar(
                      size: 12,
                      weight: FontWeight.w700,
                      color: MiftahColors.brassLight,
                    )
                  : MiftahType.sectionLabel(color: MiftahColors.brassLight),
            ),
            const SizedBox(height: 6),
            if (guest != null)
              Text(
                guest,
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: ar
                    ? MiftahType.ar(
                        size: 22,
                        weight: FontWeight.w700,
                        color: Colors.white,
                      )
                    : MiftahType.amount(size: 24, color: Colors.white),
              ),
            const SizedBox(height: 18),
            // QR always sits on white: scanners need the quiet zone and the
            // contrast, whatever the surrounding chrome.
            if (token != null)
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(MiftahRadii.hero),
                ),
                child: QrImageView(
                  data: token,
                  version: QrVersions.auto,
                  size: 232,
                  backgroundColor: Colors.white,
                  eyeStyle: const QrEyeStyle(
                    eyeShape: QrEyeShape.square,
                    color: MiftahColors.ink,
                  ),
                  dataModuleStyle: const QrDataModuleStyle(
                    dataModuleShape: QrDataModuleShape.square,
                    color: MiftahColors.ink,
                  ),
                ),
              ),
            if (code != null) ...[
              const SizedBox(height: 18),
              Text(
                strings.entryCode,
                style: ar
                    ? MiftahType.ar(
                        size: 11,
                        weight: FontWeight.w600,
                        color: const Color(0xFF8C86A0),
                      )
                    : MiftahType.sectionLabel(color: const Color(0xFF8C86A0)),
              ),
              const SizedBox(height: 6),
              // 22px at 0.24em — the gate-pass code setting from the handoff.
              Text(
                code,
                style: MiftahType.mono(
                  size: 22,
                  color: MiftahColors.brassLight,
                ).copyWith(letterSpacing: 5.28),
              ),
            ],
            const SizedBox(height: 18),
            _Divider(),
            if (place.isNotEmpty)
              _Row(label: strings.where, value: place, ar: ar),
            _Row(label: strings.when, value: window, ar: ar),
            const SizedBox(height: 14),
            Text(
              strings.instruction,
              textAlign: TextAlign.center,
              style: ar
                  ? MiftahType.ar(size: 11.5, color: const Color(0xFF8C86A0))
                  : MiftahType.body(size: 11.5, color: const Color(0xFF8C86A0)),
            ),
          ],
        ),
      ),
    );
  }
}

class _Divider extends StatelessWidget {
  @override
  Widget build(BuildContext context) =>
      Container(height: 1, color: Colors.white.withValues(alpha: 0.1));
}

class _Row extends StatelessWidget {
  const _Row({required this.label, required this.value, required this.ar});

  final String label;
  final String value;
  final bool ar;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 9),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: ar
                ? MiftahType.ar(size: 12, color: const Color(0xFF8C86A0))
                : MiftahType.body(size: 12, color: const Color(0xFF8C86A0)),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: ar
                  ? MiftahType.ar(
                      size: 13,
                      weight: FontWeight.w700,
                      color: Colors.white,
                    )
                  : MiftahType.body(
                      size: 13,
                      color: Colors.white,
                    ).copyWith(fontWeight: FontWeight.w700),
            ),
          ),
        ],
      ),
    );
  }
}

/// EN/AR labels for the card. Passed in rather than read from a screen's `_L`
/// so the card can be rendered from anywhere that has a pass.
class PassShareStrings {
  const PassShareStrings(this.ar);

  final bool ar;

  String get gatePass => ar ? 'تصريح دخول' : 'GATE PASS';
  String get entryCode => ar ? 'رمز الدخول' : 'ENTRY CODE';
  String get where => ar ? 'المكان' : 'Where';
  String get when => ar ? 'الوقت' : 'When';
  String get instruction => ar
      ? 'أظهر رمز QR عند البوابة، أو أعطِ الحارس رمز الدخول.'
      : 'Show this QR at the gate, or give the guard the entry code.';
}

/// Renders [card] off-screen and writes it to a PNG in the app's temp
/// directory, returning the file.
///
/// The card is mounted in an Overlay because `toImage` needs a boundary that
/// has actually been laid out and painted — an `Offstage` subtree never paints,
/// so it cannot be captured. It is inserted at almost-zero opacity for the two
/// frames it takes to paint, then removed.
///
/// Returns null if anything goes wrong; callers fall back to sharing text
/// alone rather than failing the share outright.
Future<XFileLike?> renderPassShareCard(
  BuildContext context,
  Widget card, {
  double pixelRatio = 3,
}) async {
  final overlay = Overlay.maybeOf(context);
  if (overlay == null) return null;

  final boundaryKey = GlobalKey();
  final entry = OverlayEntry(
    builder: (_) => Positioned(
      left: 0,
      top: 0,
      child: IgnorePointer(
        // Not Opacity(0): RenderOpacity skips painting entirely at zero alpha,
        // and an unpainted boundary cannot be captured.
        child: Opacity(
          opacity: 0.004,
          child: RepaintBoundary(key: boundaryKey, child: card),
        ),
      ),
    ),
  );

  overlay.insert(entry);
  try {
    // Two frames: one to lay out, one to be sure the paint landed.
    await WidgetsBinding.instance.endOfFrame;
    await WidgetsBinding.instance.endOfFrame;

    final object = boundaryKey.currentContext?.findRenderObject();
    if (object is! RenderRepaintBoundary) return null;

    final image = await object.toImage(pixelRatio: pixelRatio);
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    if (data == null) return null;

    final bytes = data.buffer.asUint8List();
    return _writeTemp(bytes);
  } catch (_) {
    return null;
  } finally {
    entry.remove();
  }
}

Future<XFileLike?> _writeTemp(Uint8List bytes) async {
  try {
    // systemTemp is the app sandbox's tmp on both platforms — no extra
    // dependency, and the OS reclaims it.
    final dir = await Directory.systemTemp.createTemp('miftah_pass');
    final file = File('${dir.path}/miftah-gate-pass.png');
    await file.writeAsBytes(bytes, flush: true);
    return XFileLike(file.path);
  } catch (_) {
    return null;
  }
}

/// Thin holder so this file does not depend on share_plus; the provider that
/// actually shares converts it.
class XFileLike {
  const XFileLike(this.path);

  final String path;
}
