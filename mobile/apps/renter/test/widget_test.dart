import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:renter/app.dart';

void main() {
  testWidgets('RenterApp smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: RenterApp()),
    );
    await tester.pumpAndSettle();
  });
}
