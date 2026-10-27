// Drives the real renter UI at a pace a Play reviewer can follow: every step is
// a tap, a typed field or a drag, followed by a short dwell on the result.
//
// ignore_for_file: avoid_print
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/screens/shell_screen.dart';

/// A sandbox login, read from the private defines file. Never printed.
class Account {
  const Account(this.name, this.email, this.password);
  final String name;
  final String email;
  final String password;
}

void note(String message) => print('[store-review] $message');

class Reviewer {
  Reviewer(this.tester, this.container);

  final WidgetTester tester;
  final ProviderContainer container;

  /// Holds the current screen on camera. The binding renders every frame the
  /// framework asks for (fullyLive), so animations play out while this waits.
  Future<void> dwell([double seconds = 1.5]) async {
    final end = DateTime.now().add(
      Duration(milliseconds: (seconds * 1000).round()),
    );
    while (DateTime.now().isBefore(end)) {
      await tester.pump(const Duration(milliseconds: 50));
    }
  }

  bool has(Finder finder) => finder.evaluate().isNotEmpty;

  Future<void> waitFor(Finder finder, String what, {int seconds = 30}) async {
    final end = DateTime.now().add(Duration(seconds: seconds));
    while (!has(finder)) {
      if (DateTime.now().isAfter(end)) fail('timed out waiting for $what');
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  Future<void> waitUntil(
    bool Function() ready,
    String what, {
    int seconds = 30,
  }) async {
    final end = DateTime.now().add(Duration(seconds: seconds));
    while (!ready()) {
      if (DateTime.now().isAfter(end)) fail('timed out waiting for $what');
      await tester.pump(const Duration(milliseconds: 100));
    }
  }

  /// Text matched without regard to case: badges and section labels render
  /// their copy upper-cased.
  Finder textCI(String text) => find.byWidgetPredicate(
    (w) => w is Text && (w.data ?? '').toLowerCase() == text.toLowerCase(),
  );

  Finder navItem(String label) => find.descendant(
    of: find.byType(MiftahNavBar),
    matching: find.text(label),
  );

  /// The vertical scrollable that fills most of the screen: the page itself,
  /// not a chip row or the promo carousel inside it.
  Finder mainScrollable() {
    Element? best;
    var bestArea = 0.0;
    for (final e
        in find
            .byWidgetPredicate(
              (w) =>
                  w is Scrollable &&
                  axisDirectionToAxis(w.axisDirection) == Axis.vertical,
            )
            .hitTestable()
            .evaluate()) {
      final box = e.renderObject;
      if (box is RenderBox && box.hasSize) {
        final area = box.size.width * box.size.height;
        if (area > bestArea) {
          bestArea = area;
          best = e;
        }
      }
    }
    if (best == null) fail('no vertical scrollable on screen');
    final target = best;
    return find.byElementPredicate((e) => e == target);
  }

  // Touches are dispatched straight into the hit-tested widgets rather than
  // through WidgetTester.tap/drag: the live test binding paints a crosshair
  // over every tester pointer, which would show up in the recordings.
  int _pointer = 1000;
  final _clock = Stopwatch()..start();

  HitTestResult _hitTest(Offset at) {
    final result = HitTestResult();
    tester.binding.hitTestInView(result, at, tester.view.viewId);
    return result;
  }

  void _dispatch(PointerEvent event, HitTestResult result) {
    for (final entry in result.path) {
      entry.target.handleEvent(event.transformed(entry.transform), entry);
    }
  }

  Future<void> touch(Offset at) async {
    final pointer = ++_pointer;
    final result = _hitTest(at);
    final view = tester.view.viewId;
    _dispatch(
      PointerDownEvent(
        pointer: pointer,
        position: at,
        viewId: view,
        timeStamp: _clock.elapsed,
      ),
      result,
    );
    await tester.pump(const Duration(milliseconds: 90));
    _dispatch(
      PointerUpEvent(
        pointer: pointer,
        position: at,
        viewId: view,
        timeStamp: _clock.elapsed,
      ),
      result,
    );
  }

  Future<void> _drag(Offset from, Offset by, Duration duration) async {
    const steps = 24;
    final pointer = ++_pointer;
    final result = _hitTest(from);
    final view = tester.view.viewId;
    var at = from;
    _dispatch(
      PointerDownEvent(
        pointer: pointer,
        position: at,
        viewId: view,
        timeStamp: _clock.elapsed,
      ),
      result,
    );
    final step = by / steps.toDouble();
    for (var i = 0; i < steps; i++) {
      await tester.pump(duration ~/ steps);
      at += step;
      _dispatch(
        PointerMoveEvent(
          pointer: pointer,
          position: at,
          delta: step,
          viewId: view,
          timeStamp: _clock.elapsed,
        ),
        result,
      );
    }
    await tester.pump(const Duration(milliseconds: 40));
    _dispatch(
      PointerUpEvent(
        pointer: pointer,
        position: at,
        viewId: view,
        timeStamp: _clock.elapsed,
      ),
      result,
    );
  }

  /// One finger-drag of the page, the way a person scrolls.
  Future<void> scroll(double dy, {double dwellAfter = 0.8}) async {
    final area = tester.getRect(mainScrollable());
    // Start low for an upward swipe and high for a downward one, so the whole
    // stroke stays inside the page.
    final from = Offset(
      area.center.dx,
      dy > 0 ? area.top + area.height * 0.75 : area.top + area.height * 0.25,
    );
    final travel = dy.abs().clamp(0, area.height * 0.5).toDouble();
    var remaining = dy.abs();
    while (remaining > 0) {
      final leg = remaining < travel ? remaining : travel;
      await _drag(
        from,
        Offset(0, dy > 0 ? -leg : leg),
        const Duration(milliseconds: 450),
      );
      remaining -= leg;
      await tester.pump(const Duration(milliseconds: 120));
    }
    await dwell(dwellAfter);
  }

  /// Scrolls until [finder] is built, then glides it into view.
  Future<void> reveal(
    Finder finder,
    String what, {
    double alignment = 0.4,
  }) async {
    for (var i = 0; i < 20 && !has(finder); i++) {
      await scroll(320, dwellAfter: 0.3);
    }
    if (!has(finder)) fail('could not scroll to $what');
    Scrollable.ensureVisible(
      tester.element(finder.first),
      alignment: alignment,
      duration: const Duration(milliseconds: 450),
      curve: Curves.easeInOut,
    );
    await dwell(0.9);
  }

  Future<void> tap(
    Finder finder,
    String what, {
    double dwellAfter = 1.4,
  }) async {
    await waitFor(finder, what);
    await tester.ensureVisible(finder.first);
    await tester.pump(const Duration(milliseconds: 250));
    await touch(tester.getCenter(finder.first));
    await dwell(dwellAfter);
  }

  /// Types into a field one character at a time — or, for a [secret], two at
  /// a time: an obscured field shows the character just typed whenever the
  /// text grows by exactly one, and frame by frame that would spell the
  /// password out in the recording.
  Future<void> type(
    Finder field,
    String text,
    String what, {
    bool secret = false,
  }) async {
    await tap(field, what, dwellAfter: 0.3);
    // Pinned to the element: a field found by its hint stops matching as soon
    // as the first character replaces the hint.
    final element = field.evaluate().first;
    final target = find.byElementPredicate((e) => e == element);
    final stride = secret ? 2 : 1;
    // An odd-length secret takes three characters first, so no step adds one.
    var i = secret && text.length.isOdd ? 3 : stride;
    for (; i <= text.length; i += stride) {
      await tester.enterText(target, text.substring(0, i));
      await tester.pump(Duration(milliseconds: 40 * stride));
    }
    if (secret && text.length < 3) await tester.enterText(target, text);
    await dwell(0.4);
  }

  void hideKeyboard() => FocusManager.instance.primaryFocus?.unfocus();

  /// The on-screen back control: an AppBar back arrow, the circle back
  /// button the profile header draws, or the arrow over a listing's photos.
  /// When it has scrolled away with the page, the page is scrolled back up.
  Future<void> back() async {
    Finder? control() {
      for (final candidate in [
        find.byType(BackButton),
        find.byTooltip('Back'),
        find.byTooltip('رجوع'),
        find.byIcon(Icons.arrow_back),
      ]) {
        final visible = candidate.hitTestable();
        if (has(visible)) return visible;
      }
      return null;
    }

    if (control() == null) {
      try {
        await scroll(-3000, dwellAfter: 0.6);
      } on TestFailure {
        // Nothing to scroll: the control is simply not there.
      }
    }
    final found = control();
    if (found == null) fail('no back control on screen');
    await tap(found, 'back');
  }

  Future<void> signIn(Account account) async {
    final fields = find.byType(TextFormField);
    await waitFor(fields, 'login form');
    await type(fields.at(0), account.email, 'email field');
    await type(fields.at(1), account.password, 'password field', secret: true);
    hideKeyboard();
    await dwell(0.6);
    await tap(find.text('SIGN IN'), 'sign in button', dwellAfter: 0.5);
    await waitUntil(
      () => container.read(authProvider).isAuthenticated,
      '${account.name} to sign in',
    );
    await waitFor(find.byType(ShellScreen), 'home');
    await dwell(2.5);
  }
}
