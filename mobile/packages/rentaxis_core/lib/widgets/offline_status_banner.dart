import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';

/// A stable key for finding the global offline indicator in automated tests.
const offlineStatusBannerKey = Key('offline-status-banner');

/// Displays a persistent banner above [child] while the device has no active
/// network connection.
///
/// Connectivity describes the device's network interfaces. Individual API
/// calls must still handle timeouts and failures because Wi-Fi or mobile data
/// availability does not guarantee internet access.
class OfflineStatusBanner extends StatefulWidget {
  const OfflineStatusBanner({
    super.key,
    required this.child,
    this.connectivityChanges,
    this.checkConnectivity,
  });

  final Widget child;

  /// Injectable connectivity sources keep the widget deterministic in tests.
  final Stream<List<ConnectivityResult>>? connectivityChanges;
  final Future<List<ConnectivityResult>> Function()? checkConnectivity;

  @override
  State<OfflineStatusBanner> createState() => _OfflineStatusBannerState();
}

class _OfflineStatusBannerState extends State<OfflineStatusBanner> {
  final Connectivity _connectivity = Connectivity();
  StreamSubscription<List<ConnectivityResult>>? _subscription;
  bool _isOffline = false;
  bool _streamHasReported = false;

  @override
  void initState() {
    super.initState();
    _subscription =
        (widget.connectivityChanges ?? _connectivity.onConnectivityChanged).listen(
          (results) {
            _streamHasReported = true;
            _setConnectivity(results);
          },
          onError: (_) {
            // A platform-channel failure should not obscure the application UI.
          },
        );
    unawaited(_loadInitialConnectivity());
  }

  Future<void> _loadInitialConnectivity() async {
    try {
      final results =
          await (widget.checkConnectivity ?? _connectivity.checkConnectivity)();
      if (!_streamHasReported) {
        _setConnectivity(results);
      }
    } catch (_) {
      // Keep the app usable if connectivity status is temporarily unavailable.
    }
  }

  void _setConnectivity(List<ConnectivityResult> results) {
    if (!mounted) return;

    final isOffline =
        results.isEmpty ||
        results.every((result) => result == ConnectivityResult.none);
    if (isOffline == _isOffline) return;

    setState(() => _isOffline = isOffline);
  }

  @override
  void dispose() {
    unawaited(_subscription?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_isOffline) return widget.child;

    final colors = Theme.of(context).colorScheme;
    final isArabic = Directionality.of(context) == TextDirection.rtl;
    final message = isArabic
        ? 'أنت غير متصل بالإنترنت. قد لا تتوفر بعض الميزات.'
        : 'You’re offline. Some features may be unavailable.';

    return ColoredBox(
      color: colors.error,
      child: SafeArea(
        bottom: false,
        child: Column(
          children: [
            Material(
              key: offlineStatusBannerKey,
              color: colors.error,
              child: Semantics(
                container: true,
                liveRegion: true,
                label: message,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 10,
                  ),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        Icons.wifi_off_rounded,
                        color: colors.onError,
                        size: 20,
                      ),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          message,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.labelLarge
                              ?.copyWith(
                                color: colors.onError,
                                fontWeight: FontWeight.w600,
                              ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            Expanded(child: widget.child),
          ],
        ),
      ),
    );
  }
}
