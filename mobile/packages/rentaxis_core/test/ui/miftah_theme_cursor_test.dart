import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  for (final platform in [TargetPlatform.android, TargetPlatform.iOS]) {
    for (final dark in [false, true]) {
      testWidgets(
          'the caret is brass, not ink (${platform.name}, '
          '${dark ? 'dark' : 'light'})', (tester) async {
        final theme = (dark ? MiftahTheme.dark : MiftahTheme.light)
            .copyWith(platform: platform);
        await tester.pumpWidget(MaterialApp(
          theme: theme,
          home: const Scaffold(body: TextField()),
        ));

        final editable = tester.widget<EditableText>(find.byType(EditableText));
        expect(editable.cursorColor, MiftahColors.brass);
      });
    }
  }
}
