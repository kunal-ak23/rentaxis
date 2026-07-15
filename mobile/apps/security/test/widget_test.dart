import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:security/app.dart';

void main() {
  testWidgets('SecurityApp smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: SecurityApp()),
    );
    await tester.pumpAndSettle();
  });
}
