import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:singverse_mobile/app/design.dart';
import 'package:singverse_mobile/core/app_state.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:singverse_mobile/features/singing/works_page.dart';

import 'mobile_redesign_test.dart' show NativeFixture, workFixture;
import 'widget_test.dart' show FakeApi;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  late AppState state;
  late LocalStore store;
  late NativeFixture native;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    store = await LocalStore.open(
      factory: databaseFactoryFfiNoIsolate,
      path: inMemoryDatabasePath,
    );
    state = AppState(
      api: FakeApi(),
      store: store,
      preferences: await SharedPreferences.getInstance(),
      listenPlayer: false,
    )..instanceId = 'nas-a';
    native = NativeFixture(state)..install();
    native.works['draft'] = workFixture();
  });

  tearDown(() async {
    native.uninstall();
    state.dispose();
    await store.close();
  });

  Future<void> open(WidgetTester tester) async {
    await tester.binding.setSurfaceSize(const Size(390, 844));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(
        theme: singVerseTheme(Brightness.light),
        home: ReviewPage(state: state, id: 'draft'),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('review skips use real position and clamp to the recording', (
    tester,
  ) async {
    await open(tester);
    await tester.tap(find.byKey(const ValueKey('review-play')));
    await tester.pumpAndSettle();
    state.positionMs = 25000;
    native.emit();
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('review-forward')));
    await tester.pumpAndSettle();
    expect(state.positionMs, 29000);
    state.positionMs = 5000;
    native.emit();
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('review-backward')));
    await tester.pumpAndSettle();
    expect(state.positionMs, 0);
    expect(state.isPlaying, isTrue);
    expect(
      tester.widget<Waveform>(find.byType(Waveform)).values,
      native.works['draft']!['waveform'],
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('ended offline recording restarts from zero', (tester) async {
    await open(tester);
    await tester.tap(find.byKey(const ValueKey('review-play')));
    await tester.pumpAndSettle();
    state
      ..positionMs = 29000
      ..playbackState = 'ENDED'
      ..isPlaying = false;
    native.emit();
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('review-play')));
    await tester.pumpAndSettle();
    expect(state.positionMs, 0);
    expect(state.isPlaying, isTrue);
    expect(tester.takeException(), isNull);
  });

  testWidgets('failed mix keeps draft and offers a real render retry', (
    tester,
  ) async {
    await open(tester);
    await tester.tap(find.text('调整声音'));
    await tester.pumpAndSettle();
    await tester.drag(
      find.byWidgetPredicate((w) => w is Slider && w.max == 2).first,
      const Offset(35, 0),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('应用调整并试听').last);
    await tester.tap(find.text('应用调整并试听').last);
    await tester.pump();
    expect(native.calls.where((method) => method == 'render'), hasLength(1));
    state.singing = {
      'takeId': 'draft',
      'phase': 'PREVIEW',
      'error': '编码失败，原始录音已保留',
    };
    native.emit();
    await tester.pumpAndSettle();
    expect(native.works.containsKey('draft'), isTrue);
    expect(find.text('保存作品'), findsNothing);
    await tester.ensureVisible(find.text('重新处理并试听'));
    await tester.tap(find.text('重新处理并试听'));
    await tester.pump();
    expect(native.calls.where((method) => method == 'render'), hasLength(2));
    native.completeRender();
    await tester.pumpAndSettle();
    expect(find.text('保存作品'), findsOneWidget);
    expect(state.workId, 'draft');
    expect(tester.takeException(), isNull);
  });

  testWidgets('alignment panel returns changes to one real mix operation', (
    tester,
  ) async {
    await open(tester);
    await tester.tap(find.text('调整声音'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('声音不同步'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('延后 50 毫秒'));
    await tester.pumpAndSettle();
    expect(find.text('人声提前 50 毫秒'), findsOneWidget);
    await tester.tap(find.text('完成，返回声音调整'));
    await tester.pumpAndSettle();
    expect(find.text('人声提前 50 毫秒'), findsOneWidget);
    expect(native.calls.where((method) => method == 'render'), isEmpty);
    await tester.ensureVisible(find.text('应用调整并试听').last);
    await tester.tap(find.text('应用调整并试听').last);
    await tester.pump();
    expect(native.renderArgs!['shift'], -50);
    native.completeRender();
    await tester.pumpAndSettle();
    expect(find.text('保存作品'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('mix completed behind works waits for explicit replay', (
    tester,
  ) async {
    await open(tester);
    await tester.tap(find.text('调整声音'));
    await tester.pumpAndSettle();
    await tester.drag(
      find.byWidgetPredicate((w) => w is Slider && w.max == 2).first,
      const Offset(35, 0),
    );
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('应用调整并试听').last);
    await tester.tap(find.text('应用调整并试听').last);
    await tester.pump();
    expect(native.calls.where((method) => method == 'render'), hasLength(1));
    await tester.ensureVisible(find.text('查看作品'));
    await tester.tap(find.text('查看作品'));
    await tester.pumpAndSettle();
    expect(find.byType(WorksPage), findsOneWidget);
    native.completeRender();
    await tester.pumpAndSettle();
    expect(native.calls.where((method) => method == 'play'), isEmpty);
    expect(state.isPlaying, isFalse);
    expect(native.works['draft']!['hasPreview'], isTrue);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.text('保存作品'), findsOneWidget);
    expect(native.calls.where((method) => method == 'play'), isEmpty);
    await tester.ensureVisible(find.byKey(const ValueKey('review-play')));
    await tester.tap(find.byKey(const ValueKey('review-play')));
    await tester.pumpAndSettle();
    expect(state.workId, 'draft');
    expect(state.isPlaying, isTrue);
    expect(tester.takeException(), isNull);
  });
}
