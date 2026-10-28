import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:singverse_mobile/main.dart';

void main() {
  testWidgets('shows the welcome screen with Material 3', (tester) async {
    await tester.pumpWidget(const SingVerseApp());
    expect(find.text('SingVerse'), findsOneWidget);
    expect(find.text('Welcome to SingVerse'), findsOneWidget);
    final context = tester.element(find.text('SingVerse'));
    expect(Theme.of(context).useMaterial3, isTrue);
    expect(tester.takeException(), isNull);
  });
}
