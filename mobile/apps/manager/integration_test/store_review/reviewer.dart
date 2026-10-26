import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Drives the real app at a pace a Play reviewer can follow: every step is a
/// tap, a drag or typed text on screen, with a beat after it.
class Reviewer {
  Reviewer(this.tester);
  final WidgetTester tester;

  static const beat = Duration(milliseconds: 1500);

  ProviderContainer get container =>
      ProviderScope.containerOf(tester.element(find.byType(MaterialApp).first));

  /// Lets real time pass while frames keep rendering (animations, network).
  Future<void> pause([Duration d = beat]) async {
    final end = DateTime.now().add(d);
    while (DateTime.now().isBefore(end)) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  Future<void> waitFor(
    Finder finder, {
    Duration timeout = const Duration(seconds: 30),
    String? what,
  }) async {
    final end = DateTime.now().add(timeout);
    while (DateTime.now().isBefore(end)) {
      await tester.pump(const Duration(milliseconds: 200));
      if (finder.evaluate().isNotEmpty) return;
    }
    fail(
      'timed out waiting for ${what ?? finder.describeMatch(Plurality.one)}',
    );
  }

  Future<void> tap(Finder finder, {String? what, Duration after = beat}) async {
    await waitFor(finder, what: what);
    await tester.ensureVisible(finder.first);
    await pause(const Duration(milliseconds: 400));
    await _press([tester.getCenter(finder.first)], Duration.zero);
    await pause(after);
  }

  int _nextPointer = 900;

  /// Sends the touch as the device would. A tester.tap() would also work,
  /// but the test binding paints a crosshair at every test-sourced touch,
  /// and that would be filmed.
  Future<void> _press(List<Offset> path, Duration duration) async {
    final binding = tester.binding as LiveTestWidgetsFlutterBinding;
    binding.shouldPropagateDevicePointerEvents = true;
    final id = _nextPointer++;
    void send(PointerEvent e) => binding.handlePointerEventForSource(e);
    send(PointerDownEvent(pointer: id, position: path.first));
    await tester.pump(const Duration(milliseconds: 60));
    final step = path.length > 1
        ? duration ~/ (path.length - 1)
        : Duration.zero;
    for (var i = 1; i < path.length; i++) {
      send(
        PointerMoveEvent(
          pointer: id,
          position: path[i],
          delta: path[i] - path[i - 1],
        ),
      );
      await tester.pump(step);
    }
    send(PointerUpEvent(pointer: id, position: path.last));
    // The binding requires this back off by the end of the test.
    binding.shouldPropagateDevicePointerEvents = false;
    await tester.pump(const Duration(milliseconds: 60));
  }

  Future<void> _drag(Offset from, Offset by) async {
    const steps = 20;
    await _press([
      for (var i = 0; i <= steps; i++) from + by * (i / steps),
    ], const Duration(milliseconds: 700));
  }

  /// Types one character at a time, as a person would.
  Future<void> type(Finder field, String text) async {
    await tap(field, after: const Duration(milliseconds: 300));
    for (var i = 1; i <= text.length; i++) {
      await tester.enterText(field.first, text.substring(0, i));
      await tester.pump(const Duration(milliseconds: 45));
    }
    await pause(const Duration(milliseconds: 600));
  }

  Size get _screen => tester.view.physicalSize / tester.view.devicePixelRatio;

  /// A visible swipe up (content moves up) over the middle of the screen.
  Future<void> scrollDown({double fraction = 0.4}) async {
    final s = _screen;
    await _drag(
      Offset(s.width / 2, s.height * 0.7),
      Offset(0, -s.height * fraction),
    );
    await pause();
  }

  Future<void> scrollUp({double fraction = 0.4}) async {
    final s = _screen;
    await _drag(
      Offset(s.width / 2, s.height * 0.3),
      Offset(0, s.height * fraction),
    );
    await pause();
  }

  /// Android's back button.
  Future<void> back() async {
    await tester.binding.handlePopRoute();
    await pause();
  }

  /// Any instance of a widget class by name — for the screens' private cards.
  static Finder byClass(String name) =>
      find.byWidgetPredicate((w) => w.runtimeType.toString() == name);

  /// Text as shown, ignoring case — several labels render upper-cased.
  static Finder label(String text) => find.byWidgetPredicate(
    (w) =>
        w is Text &&
        (w.data ?? w.textSpan?.toPlainText())?.toLowerCase() ==
            text.toLowerCase(),
  );

  static Finder goldButton(String label) =>
      find.byWidgetPredicate((w) => w is GoldButton && w.label == label);

  // ---- session, off camera ----

  Future<void> resetToSignIn() async {
    final auth = container.read(authProvider);
    if (auth.isAuthenticated) await signOutQuietly();
    await container
        .read(appLanguageProvider.notifier)
        .setLanguage(AppLanguage.en);
    await container.read(themeModeProvider.notifier).setMode(ThemeMode.light);
    await waitFor(find.byType(TextFormField), what: 'the sign-in screen');
    await pause(const Duration(seconds: 1));
  }

  Future<void> signOutQuietly() async {
    await container.read(authProvider.notifier).logout();
    await pause(const Duration(milliseconds: 500));
    final ctx = tester.element(find.byType(Scaffold).first);
    GoRouter.of(ctx).go('/login');
    await pause(const Duration(seconds: 1));
  }

  // ---- on camera ----

  Future<void> signIn(String email, String password) async {
    final fields = find.byType(TextFormField);
    await type(fields.at(0), email);
    await type(fields.at(1), password);
    FocusManager.instance.primaryFocus?.unfocus();
    await pause(const Duration(milliseconds: 500));
    await tap(find.text('SIGN IN'));
    await waitFor(find.text('Today'), what: 'the dashboard');
    await pause();
  }
}
