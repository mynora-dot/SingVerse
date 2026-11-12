import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:singverse_mobile/app/design.dart';
import 'package:singverse_mobile/app/mini_player.dart';
import 'package:singverse_mobile/features/library/connection.dart';
import 'package:singverse_mobile/features/library/widgets.dart';
import 'package:singverse_mobile/core/app_state.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:singverse_mobile/core/models.dart';
import 'package:singverse_mobile/features/player/player_page.dart';
import 'package:singverse_mobile/features/singing/works_page.dart';
import 'package:singverse_mobile/features/singing/actions.dart';
import 'package:singverse_mobile/features/tv_control/tv_page.dart';
import 'package:singverse_mobile/main.dart';

import 'widget_test.dart' show FakeApi;

class CoverFixtureApi extends FakeApi {
  CoverFixtureApi(this.cover);
  final Uint8List cover;
  @override
  Future<Uint8List> bytes(String path) async => cover;
}

class NativeFixture {
  NativeFixture(this.state);
  final AppState state;
  final commands = <Json>[];
  final calls = <String>[];
  final works = <String, Json>{};
  String permission = 'granted';
  bool failOpen = false;
  bool failWorks = false;
  Completer<void>? pauseGate, renderReplyGate;
  Future<Json>? nextGet;
  Json? renderArgs;
  void emit() => state.notifyListeners();
  void install() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(AppState.player, (call) async {
      if (call.method == 'open') {
        if (failOpen) {
          throw PlatformException(code: 'offline', message: 'NAS 不可达');
        }
        final args = Map<String, dynamic>.from(call.arguments as Map);
        state.current = Song(jsonDecode(args['song'] as String) as Json);
        state.playbackInstanceId = args['instanceId'] as String;
        state.workId = '';
        state.positionMs = args['positionMs'] as int;
        state.isPlaying = args['autoplay'] == true;
        state.playbackState = state.isPlaying ? 'PLAYING' : 'PAUSED';
        state.durationMs = 240000;
        state.tracks = [
          {
            'id': '0:0',
            'type': 'audio',
            'selected': true,
            'channels': 2,
            'label': '2 声道',
          },
        ];
        state.backingMapped = true;
        state.originalMapped = true;
        state.audioRole = 'backing';
        emit();
      }
      if (call.method == 'snapshot') {
        return {
          'song': jsonEncode(state.current?.data ?? {}),
          'instanceId': state.playbackInstanceId,
          'state': state.playbackState,
          'isPlaying': state.isPlaying,
          'positionMs': state.positionMs,
          'durationMs': state.durationMs,
          'tracks': state.tracks,
          'karaoke': state.singing,
          'audioRole': state.audioRole,
          'backingMapped': state.backingMapped,
          'originalMapped': state.originalMapped,
          'workId': state.workId,
          'countdown': state.countdown,
        };
      }
      if (call.method == 'command') {
        final args = Map<String, dynamic>.from(call.arguments as Map);
        commands.add(args);
        if (args['action'] == 'pause' && pauseGate != null) {
          await pauseGate!.future;
        }
        switch (args['action']) {
          case 'play':
            state.isPlaying = true;
            state.playbackState = 'PLAYING';
          case 'pause':
            state.isPlaying = false;
            state.playbackState = 'PAUSED';
          case 'seek':
            state.positionMs = args['value'] as int;
          case 'record':
            state.countdown = 3;
          case 'cancelCountdown':
            state.countdown = 0;
          case 'range':
            final range = Map<String, dynamic>.from(args['value'] as Map);
            state.rangeA = range['a'] as int;
            state.rangeB = range['b'] as int;
          case 'useRole':
            state.audioRole = args['value'] as String;
        }
        emit();
      }
      return null;
    });
    messenger.setMockMethodCallHandler(AppState.karaoke, (call) async {
      calls.add(call.method);
      final args = Map<String, dynamic>.from(call.arguments as Map? ?? {});
      final id = args['id'] as String? ?? '';
      switch (call.method) {
        case 'permission':
          return permission;
        case 'get':
          final delayed = nextGet;
          nextGet = null;
          if (delayed != null) return jsonEncode(await delayed);
          return jsonEncode(works[id]);
        case 'works':
          if (failWorks) {
            throw PlatformException(code: 'storage', message: '本机存储暂不可用');
          }
          return jsonEncode(works.values.toList());
        case 'end':
          state.singing = {'phase': 'PREVIEW', 'takeId': state.takeId};
          emit();
        case 'save':
          works[id]!['status'] = 'SAVED';
          state.singing = {'phase': 'SAVED', 'takeId': id};
          emit();
          return jsonEncode(works[id]);
        case 'rename':
          works[id]!['name'] = args['name'];
        case 'delete':
          works.remove(id);
        case 'export':
          return true;
        case 'play':
          final work = works[id]!;
          state.workId = id;
          state.current = Song({
            ...work['song'] as Json,
            'title': work['name'],
          });
          state.durationMs = (work['durationMs'] as num).toInt();
          state.positionMs = 0;
          state.isPlaying = true;
          state.playbackState = 'PLAYING';
          emit();
        case 'render':
          renderArgs = args;
          state.singing = {'phase': 'RENDERING', 'takeId': id};
          emit();
          if (renderReplyGate != null) await renderReplyGate!.future;
      }
      return null;
    });
  }

  void completeRender() {
    final args = renderArgs!;
    works[args['id']]!.addAll({
      'hasPreview': true,
      'status': 'PREVIEW',
      'backingGain': args['backing'],
      'voiceGain': args['voice'],
      'shiftMs': args['shift'],
    });
    state.singing = {'phase': 'PREVIEW', 'takeId': args['id']};
    emit();
  }

  void uninstall() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(AppState.player, null);
    messenger.setMockMethodCallHandler(AppState.karaoke, null);
  }
}

Json workFixture({
  String id = 'draft',
  String status = 'PREVIEW',
  bool preview = true,
}) => {
  'id': id,
  'instanceId': 'nas-a',
  'name': '我的测试录音',
  'status': status,
  'hasPreview': preview,
  'song': {
    'id': '1',
    'title': '晴天',
    'artists': ['样本歌手'],
    'mediaType': 'AUDIO',
  },
  'durationMs': 29000,
  'frames': 1392000,
  'complete': false,
  'waveform': List.generate(
    96,
    (i) => .01 + .08 * ((i * 7) % 13) / 13 + .08 * math.sin(i * .22).abs(),
  ),
  'backingGain': .6,
  'voiceGain': 1.1,
  'shiftMs': -100,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  late Uint8List fixtureCover;
  setUpAll(() async {
    final bytes = await File('test/fixtures/fonts/UiCapture.ttf').readAsBytes();
    for (final family in ['UiCapture', 'Roboto', 'Ahem']) {
      await (FontLoader(
        family,
      )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
    }
    await (FontLoader(
      'MaterialIcons',
    )..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'))).load();
    // Original, deterministic artwork exercises real PNG decoding without NAS.
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.scale(4);
    const bounds = Rect.fromLTWH(0, 0, 128, 128);
    canvas.drawRect(
      bounds,
      Paint()
        ..shader = ui.Gradient.linear(Offset.zero, const Offset(128, 128), [
          const Color(0xFF18235A),
          const Color(0xFF6C85FF),
        ]),
    );
    canvas.drawCircle(
      const Offset(88, 38),
      23,
      Paint()..color = const Color(0xFFFFC9A8),
    );
    canvas.drawPath(
      Path()
        ..moveTo(0, 88)
        ..quadraticBezierTo(70, 44, 128, 89)
        ..lineTo(128, 128)
        ..lineTo(0, 128)
        ..close(),
      Paint()..color = const Color(0xFF14214B),
    );
    final picture = recorder.endRecording();
    final image = await picture.toImage(512, 512);
    fixtureCover = (await image.toByteData(format: ui.ImageByteFormat.png))!
        .buffer
        .asUint8List();
    image.dispose();
    picture.dispose();
  });
  late AppState state;
  late LocalStore store;
  late FakeApi api;
  late NativeFixture native;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    store = await LocalStore.open(
      factory: databaseFactoryFfiNoIsolate,
      path: inMemoryDatabasePath,
    );
    api = CoverFixtureApi(fixtureCover)..loggedIn = true;
    api.rows = List.generate(
      30,
      (i) => {
        'id': '${i + 1}',
        'title': i == 0 ? '晴天' : '测试歌曲 ${i + 1}',
        'artists': ['样本歌手'],
        'mediaType': 'AUDIO',
        'version': 'ACCOMPANIMENT',
        if (i % 3 != 2) 'coverPath': '/api/v1/test-artwork/$i',
      },
    );
    state =
        AppState(
            api: api,
            store: store,
            preferences: await SharedPreferences.getInstance(),
            listenPlayer: false,
          )
          ..initialized = true
          ..authenticated = true
          ..origin = 'http://nas.local:18080'
          ..instanceId = 'nas-a';
    native = NativeFixture(state)..install();
  });
  tearDown(() async {
    native.uninstall();
    state.dispose();
    await store.close();
  });
  test('theme text remains legible on filled controls and surfaces', () {
    for (final brightness in Brightness.values) {
      final theme = singVerseTheme(brightness);
      final scheme = theme.colorScheme;
      for (final colors in [
        (scheme.onSurface, scheme.surface),
        (scheme.onSurfaceVariant, theme.scaffoldBackgroundColor),
        (scheme.onSurfaceVariant, scheme.secondaryContainer),
        (scheme.onPrimary, scheme.primary),
        (scheme.onPrimaryContainer, scheme.primaryContainer),
      ]) {
        final foreground = colors.$1.computeLuminance();
        final background = colors.$2.computeLuminance();
        final contrast =
            (math.max(foreground, background) + .05) /
            (math.min(foreground, background) + .05);
        expect(
          contrast,
          greaterThanOrEqualTo(4.5),
          reason: '$brightness $colors',
        );
      }
    }
  });
  Future<void> size(WidgetTester tester, Size dimensions) async {
    await tester.binding.setSurfaceSize(dimensions);
    addTearDown(() => tester.binding.setSurfaceSize(null));
  }

  Future<void> app(WidgetTester tester) async {
    await tester.pumpWidget(SingVerseApp(state: state));
    await tester.pumpAndSettle();
  }

  Future<void> section(WidgetTester tester, String name) async {
    final target = find.byKey(Key('nav-$name'));
    await tester.ensureVisible(target);
    await tester.pumpAndSettle();
    await tester.tap(target);
    await tester.pumpAndSettle();
    expect(
      find.descendant(
        of: find.byType(AppBar).first,
        matching: find.text(
          {'songs': '点歌', 'frequent': '常唱', 'works': '作品'}[name]!,
        ),
      ),
      findsOneWidget,
    );
  }

  Future<void> review(WidgetTester tester, Json work) async {
    native.works[work['id']] = work;
    await tester.pumpWidget(
      MaterialApp(
        theme: singVerseTheme(Brightness.light, senior: state.senior),
        darkTheme: singVerseTheme(Brightness.dark, senior: state.senior),
        themeMode: state.themeMode,
        home: ReviewPage(state: state, id: work['id'] as String),
      ),
    );
    await tester.pumpAndSettle();
  }

  test(
    'mode defaults and persistence preserve NAS records and current playback',
    () async {
      expect(state.uiMode, UiMode.standard);
      expect(state.themeMode, ThemeMode.system);
      final song = Song(api.rows.first);
      await store.favorite('nas-a', song, true);
      await store.played('nas-a', song, 42123, newPlay: true);
      await state.refreshPersonal();
      state.current = song;
      state.positionMs = 42123;
      state.singing = {
        'phase': 'RECORDING',
        'takeId': 'current',
        'recordedMs': 12345,
      };
      await state.setUiMode(UiMode.senior);
      expect(state.current, same(song));
      expect(state.positionMs, 42123);
      expect(state.recording, isTrue);
      expect(state.singing['recordedMs'], 12345);
      expect(state.favorites.single.id, '1');
      final restored = AppState(
        api: api,
        store: store,
        preferences: state.preferences,
        listenPlayer: false,
      )..instanceId = 'nas-a';
      await restored.refreshPersonal();
      expect(restored.senior, isTrue);
      expect(restored.history.single.data['positionMs'], 42123);
      restored.dispose();
      state.works = [
        workFixture(),
        {...workFixture(id: 'other'), 'instanceId': 'nas-b'},
      ];
      expect(state.visibleWorks.map((w) => w['id']), ['draft']);
    },
  );
  test('pending review belongs to the current song and NAS', () {
    state.current = Song(api.rows.first);
    state.playbackInstanceId = 'nas-a';
    state.singing = {
      'phase': 'PREVIEW',
      'takeId': 'draft',
      'songId': '2',
      'instanceId': 'nas-a',
    };
    expect(state.pendingReviewId, isEmpty);
    expect(performancePage(state), isA<PlayerPage>());
    state.singing['songId'] = '1';
    expect(state.pendingReviewId, 'draft');
    expect(performancePage(state), isA<ReviewPage>());
    state.singing['instanceId'] = 'nas-b';
    expect(state.pendingReviewId, isEmpty);
  });
  testWidgets('changing activity can cancel or end and retain a recording', (
    tester,
  ) async {
    state.singing = {'phase': 'RECORDING', 'takeId': 'draft'};
    bool? result;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async {
                result = await keepCurrentRecording(context, state);
              },
              child: const Text('切换场景'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('切换场景'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('取消'));
    await tester.pumpAndSettle();
    expect(result, false);
    expect(state.recording, isTrue);
    expect(native.calls, isNot(contains('end')));
    await tester.tap(find.text('切换场景'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('结束并保留草稿'));
    await tester.pumpAndSettle();
    expect(result, true);
    expect(state.singingPhase, 'PREVIEW');
    expect(native.calls.where((c) => c == 'end'), hasLength(1));
    expect(tester.takeException(), isNull);
  });
  testWidgets('library keeps search and scroll when switching main tabs', (
    tester,
  ) async {
    await size(tester, const Size(390, 844));
    for (final senior in [false, true]) {
      await state.setUiMode(senior ? UiMode.senior : UiMode.standard);
      await app(tester);
      await section(tester, 'songs');
      await tester.enterText(find.byType(TextField).first, '晴');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      await tester.drag(
        find.byType(CustomScrollView).first,
        const Offset(0, -400),
      );
      await tester.pumpAndSettle();
      final before = tester
          .state<ScrollableState>(find.byType(Scrollable).first)
          .position
          .pixels;
      expect(before, greaterThan(0));
      await section(tester, 'frequent');
      await section(tester, 'songs');
      expect(
        tester
            .state<ScrollableState>(find.byType(Scrollable).first)
            .position
            .pixels,
        before,
      );
      await tester.drag(
        find.byType(CustomScrollView).first,
        const Offset(0, 1000),
      );
      await tester.pumpAndSettle();
      expect(
        tester.widget<TextField>(find.byType(TextField).first).controller!.text,
        '晴',
      );
      expect(tester.takeException(), isNull);
    }
  });
  testWidgets(
    'one song tap opens practice without details preparation or microphone',
    (tester) async {
      await size(tester, const Size(390, 844));
      await app(tester);
      await tester.tap(find.text('晴天'));
      await tester.pumpAndSettle();
      expect(find.byType(PlayerPage), findsOneWidget);
      expect(find.text('练唱 · 麦克风未开启'), findsOneWidget);
      expect(find.text('演唱准备'), findsNothing);
      expect(find.text('歌曲详情'), findsNothing);
      expect(state.isPlaying, isTrue);
      expect(native.calls, isNot(contains('permission')));
      expect(native.calls, isNot(contains('test')));
      expect(native.commands.where((c) => c['action'] == 'record'), isEmpty);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('song open failure stays in player and can retry', (
    tester,
  ) async {
    native.failOpen = true;
    await size(tester, const Size(390, 844));
    await app(tester);
    await tester.tap(find.text('晴天'));
    await tester.pumpAndSettle();
    expect(find.textContaining('NAS 不可达'), findsOneWidget);
    expect(find.text('重试播放'), findsOneWidget);
    native.failOpen = false;
    await tester.tap(find.text('重试播放'));
    await tester.pumpAndSettle();
    expect(state.isPlaying, isTrue);
    expect(find.textContaining('NAS 不可达'), findsNothing);
    expect(tester.takeException(), isNull);
  });
  testWidgets('lyrics seek normally and range selection is an explicit tool', (
    tester,
  ) async {
    api.textTracks = [
      {
        'format': 'lrc',
        'text': '[00:00.00]第一句很长的歌词需要完整换行而不是省略显示\n[00:10.00]第二句\n[00:20.00]第三句',
      },
    ];
    await size(tester, const Size(390, 844));
    await app(tester);
    await tester.tap(find.text('晴天'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('第二句'));
    await tester.tap(find.text('第二句'));
    await tester.pumpAndSettle();
    expect(native.commands.last['action'], 'seek');
    expect(native.commands.last['value'], 10000);
    expect(native.commands.where((c) => c['action'] == 'range'), isEmpty);
    await tester.tap(find.text('选段'));
    await tester.pumpAndSettle();
    expect(find.text('选段练唱'), findsOneWidget);
    await tester.tap(find.text('循环练唱这一段'));
    await tester.pumpAndSettle();
    expect(native.commands.where((c) => c['action'] == 'range'), hasLength(1));
    expect(tester.takeException(), isNull);
  });
  testWidgets('permission denial keeps practice available and never records', (
    tester,
  ) async {
    native.permission = 'denied';
    await size(tester, const Size(390, 844));
    await app(tester);
    await tester.tap(find.text('晴天'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('开始录音'));
    await tester.pumpAndSettle();
    expect(find.text('需要麦克风才能录音'), findsOneWidget);
    await tester.tap(find.text('继续练唱'));
    await tester.pumpAndSettle();
    expect(state.isPlaying, isTrue);
    expect(native.commands.where((c) => c['action'] == 'record'), isEmpty);
    expect(tester.takeException(), isNull);
  });
  testWidgets('recording ends in retained draft then saves and exports', (
    tester,
  ) async {
    await size(tester, const Size(390, 844));
    await app(tester);
    await tester.tap(find.text('晴天'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('开始录音'));
    await tester.pumpAndSettle();
    expect(state.countdown, 3);
    native.works['draft'] = workFixture();
    state.countdown = 0;
    state.singing = {
      'phase': 'RECORDING',
      'takeId': 'draft',
      'recordedMs': 29000,
      'level': .2,
    };
    native.emit();
    await tester.pumpAndSettle();
    expect(find.text('结束录音'), findsOneWidget);
    await tester.tap(find.text('结束录音'));
    await tester.pumpAndSettle();
    expect(find.text('录音草稿'), findsOneWidget);
    expect(native.works.containsKey('draft'), isTrue);
    await tester.ensureVisible(find.text('保存作品'));
    await tester.tap(find.text('保存作品'));
    await tester.pumpAndSettle();
    expect(find.text('作品回听'), findsOneWidget);
    expect(native.works['draft']!['status'], 'SAVED');
    await tester.ensureVisible(find.text('导出音频'));
    await tester.tap(find.text('导出音频'));
    await tester.pumpAndSettle();
    expect(native.calls, contains('export'));
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'draft adjustment generates once and gates saving until finished',
    (tester) async {
      await size(tester, const Size(390, 844));
      await review(tester, workFixture());
      expect(
        tester.getBottomLeft(find.byKey(const Key('review-save'))).dy,
        lessThanOrEqualTo(844),
      );
      await tester.tap(find.text('调整声音'));
      await tester.pumpAndSettle();
      expect(find.text('人声 110%'), findsOneWidget);
      expect(find.text('伴奏 60%'), findsOneWidget);
      await tester.drag(
        find.byWidgetPredicate((w) => w is Slider && w.max == 2).first,
        const Offset(40, 0),
      );
      await tester.pumpAndSettle();
      expect(find.text('保存作品'), findsNothing);
      await tester.ensureVisible(find.text('应用调整并试听').last);
      await tester.tap(find.text('应用调整并试听').last);
      await tester.pump();
      expect(native.calls.where((c) => c == 'render'), hasLength(1));
      expect(find.text('正在处理录音，请稍候…'), findsOneWidget);
      native.completeRender();
      await tester.pumpAndSettle();
      expect(find.text('保存作品'), findsOneWidget);
      expect(state.workId, 'draft');
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('opening an older draft cannot interrupt an active recording', (
    tester,
  ) async {
    state.singing = {'phase': 'RECORDING', 'takeId': 'new-take'};
    await review(tester, workFixture(preview: false));
    expect(native.commands.where((c) => c['action'] == 'pause'), isEmpty);
    expect(native.calls.where((c) => c == 'render'), isEmpty);
    expect(state.recording, isTrue);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'render locks before awaits and ignores stale metadata and unrelated completion',
    (tester) async {
      await size(tester, const Size(390, 844));
      await review(tester, workFixture());
      final oldGet = Completer<Json>();
      native.nextGet = oldGet.future;
      state.singing = {'phase': 'PREVIEW', 'takeId': 'draft'};
      native.emit();
      await tester.pump();
      await tester.tap(find.text('调整声音'));
      await tester.pumpAndSettle();
      await tester.drag(
        find.byWidgetPredicate((w) => w is Slider && w.max == 2).first,
        const Offset(40, 0),
      );
      await tester.tap(find.text('完成'));
      await tester.pumpAndSettle();
      native.pauseGate = Completer<void>();
      native.renderReplyGate = Completer<void>();
      final apply = tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '应用调整并试听'))
          .onPressed!;
      apply();
      apply();
      await tester.pump();
      oldGet.complete(workFixture());
      await tester.pump();
      expect(
        native.commands.where((c) => c['action'] == 'pause'),
        hasLength(1),
      );
      expect(native.calls.where((c) => c == 'render'), isEmpty);
      expect(native.calls.where((c) => c == 'play'), isEmpty);
      expect(find.text('保存作品'), findsNothing);
      native.pauseGate!.complete();
      await tester.pump();
      expect(native.calls.where((c) => c == 'render'), hasLength(1));
      state.singing = {'phase': 'PREVIEW', 'takeId': 'different-take'};
      native.emit();
      await tester.pump();
      expect(find.text('保存作品'), findsNothing);
      expect(native.calls.where((c) => c == 'play'), isEmpty);
      native.completeRender();
      await tester.pump();
      // The matching begin/end events arrived, but native has not acknowledged yet.
      expect(find.text('保存作品'), findsNothing);
      expect(native.calls.where((c) => c == 'play'), isEmpty);
      native.renderReplyGate!.complete();
      await tester.pumpAndSettle();
      expect(native.calls.where((c) => c == 'render'), hasLength(1));
      expect(native.calls.where((c) => c == 'play'), hasLength(1));
      expect(find.text('保存作品'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'review reopened during native render refreshes completed mix values',
    (tester) async {
      state.singing = {'phase': 'RENDERING', 'takeId': 'draft'};
      await tester.binding.setSurfaceSize(const Size(390, 844));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      // Avoid pumpAndSettle while the real processing indicator is active.
      native.works['draft'] = workFixture();
      await tester.pumpWidget(
        MaterialApp(
          theme: singVerseTheme(Brightness.light),
          home: ReviewPage(state: state, id: 'draft'),
        ),
      );
      await tester.pump();
      native.works['draft']!.addAll({
        'voiceGain': .8,
        'backingGain': .9,
        'shiftMs': 200,
      });
      state.singing = {'phase': 'PREVIEW', 'takeId': 'draft'};
      native.emit();
      await tester.pumpAndSettle();
      await tester.tap(find.text('调整声音'));
      await tester.pumpAndSettle();
      expect(find.text('人声 80%'), findsOneWidget);
      expect(find.text('伴奏 90%'), findsOneWidget);
      expect(find.text('人声延后 200 毫秒'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('missing recording does not promise resumable draft editing', (
    tester,
  ) async {
    await review(tester, workFixture(status: 'MISSING', preview: false));
    expect(find.text('录音文件缺失'), findsOneWidget);
    expect(find.text('录音草稿'), findsNothing);
    expect(find.textContaining('返回后仍可在作品中继续处理草稿'), findsNothing);
    expect(find.text('保存作品'), findsNothing);
    expect(find.text('导出音频'), findsNothing);
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('review-play')))
          .onPressed,
      isNull,
    );
    expect(tester.takeException(), isNull);
  });
  testWidgets('saved work plays offline and deletion requires confirmation', (
    tester,
  ) async {
    state.authenticated = false;
    api.offline = true;
    await review(tester, workFixture(status: 'SAVED'));
    await tester.tap(find.text('播放录音'));
    await tester.pumpAndSettle();
    expect(state.workId, 'draft');
    await tester.tap(find.byKey(const Key('review-more')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除录音'));
    await tester.pumpAndSettle();
    expect(native.calls, isNot(contains('delete')));
    await tester.tap(find.text('保留'));
    await tester.pumpAndSettle();
    expect(native.works.containsKey('draft'), isTrue);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'works failure retains records and missing files have a separate group',
    (tester) async {
      state.nativeEnabled = true;
      native.works['saved'] = workFixture(id: 'saved', status: 'SAVED');
      native.works['missing'] = {
        ...workFixture(id: 'missing', status: 'MISSING'),
        'name': '音频缺失的作品',
      };
      await state.refreshWorks();
      native.failWorks = true;
      await tester.pumpWidget(
        MaterialApp(
          theme: singVerseTheme(Brightness.light),
          home: WorksPage(state: state),
        ),
      );
      await tester.pumpAndSettle();
      expect(state.visibleWorks, hasLength(2));
      expect(find.textContaining('暂时无法读取本机录音'), findsOneWidget);
      expect(find.text('已保存作品'), findsOneWidget);
      expect(find.text('音频文件缺失'), findsOneWidget);
      expect(find.text('留下你的声音'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('works list builds lazily and search includes retained drafts', (
    tester,
  ) async {
    state.works = List.generate(
      200,
      (index) => {
        ...workFixture(
          id: 'work-$index',
          status: index == 199 ? 'PREVIEW' : 'SAVED',
        ),
        'name': '录音 $index',
      },
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: singVerseTheme(Brightness.light),
        home: WorksPage(state: state),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('录音 198'), findsNothing);
    expect(find.text('录音 199'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '录音 198');
    await tester.pumpAndSettle();
    expect(find.text('录音 198'), findsWidgets);
    expect(find.text('继续完成'), findsNothing);
    expect(tester.takeException(), isNull);
  });
  testWidgets('filters group dimensions and omit zero-song tags', (
    tester,
  ) async {
    api.tags = [
      {
        'id': 'lang',
        'name': '国语',
        'dimension': 'LANGUAGE',
        'active': true,
        'count': 2,
      },
      {
        'id': 'decade',
        'name': '1990 年代',
        'dimension': 'DECADE',
        'active': true,
        'count': 1,
      },
      {
        'id': 'empty',
        'name': '零歌曲分类',
        'dimension': 'GENRE',
        'active': true,
        'count': 0,
      },
    ];
    await app(tester);
    await tester.tap(find.byTooltip('筛选歌曲'));
    await tester.pumpAndSettle();
    expect(find.text('语种'), findsOneWidget);
    expect(find.text('年代'), findsOneWidget);
    expect(find.textContaining('零歌曲分类'), findsNothing);
    await tester.tap(find.text('国语 · 2'));
    await tester.pump();
    await tester.tap(find.text('应用筛选 (1)'));
    await tester.pumpAndSettle();
    expect(find.text('国语'), findsOneWidget);
    expect(api.requests.any((p) => p.contains('tagIds=lang')), isTrue);
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'artist browsing renders rows and mini player reserves page space',
    (tester) async {
      api.browseRows = [
        {'id': 'artist', 'name': '样本歌手', 'count': 3},
      ];
      state.current = Song(api.rows.first);
      await size(tester, const Size(390, 844));
      await app(tester);
      await tester.tap(find.text('歌手'));
      await tester.pumpAndSettle();
      expect(find.text('样本歌手'), findsOneWidget);
      final mini = tester.getRect(find.byType(MiniPlayer));
      final page = tester.getRect(find.byType(Scaffold).last);
      expect(page.bottom, lessThanOrEqualTo(mini.top));
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('logged-in NAS page shows status instead of password form', (
    tester,
  ) async {
    await app(tester);
    await section(tester, 'frequent');
    await tester.tap(find.byKey(const Key('open-settings')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('NAS 连接与账号'));
    await tester.pumpAndSettle();
    expect(find.text('已登录家庭曲库'), findsOneWidget);
    expect(find.text('管理员密码'), findsNothing);
    expect(find.text('修改 NAS 地址'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('senior toggle persists and exposes distinct large row actions', (
    tester,
  ) async {
    await size(tester, const Size(390, 844));
    await app(tester);
    await section(tester, 'frequent');
    await tester.tap(find.byKey(const Key('open-settings')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('长辈模式'));
    await tester.pumpAndSettle();
    expect(state.senior, isTrue);
    expect(state.preferences.getString('uiMode'), 'senior');
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('open-settings')), findsOneWidget);
    await section(tester, 'songs');
    expect(find.text('收藏'), findsWidgets);
    expect(
      tester.getSize(find.widgetWithText(FilledButton, '开始练唱').first).height,
      greaterThanOrEqualTo(56),
    );
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'audio video and senior players adapt to landscape small screens and large text',
    (tester) async {
      for (final senior in [false, true]) {
        await state.setUiMode(senior ? UiMode.senior : UiMode.standard);
        for (final mediaType in ['AUDIO', 'VIDEO']) {
          state.current = Song({...api.rows.first, 'mediaType': mediaType});
          state.playbackState = 'PAUSED';
          state.durationMs = 240000;
          for (final dimensions in [
            const Size(360, 800),
            const Size(844, 390),
            const Size(320, 568),
          ]) {
            await tester.binding.setSurfaceSize(dimensions);
            tester.platformDispatcher.textScaleFactorTestValue =
                dimensions.width == 320 ? 1.8 : 1;
            await tester.pumpWidget(
              MaterialApp(
                home: PlayerPage(
                  key: ValueKey('$senior:$mediaType:$dimensions'),
                  state: state,
                ),
              ),
            );
            await tester.pumpAndSettle();
            expect(find.text('开始录音'), findsOneWidget);
            expect(
              tester.takeException(),
              isNull,
              reason: '$senior $mediaType $dimensions',
            );
          }
        }
      }
      tester.platformDispatcher.clearTextScaleFactorTestValue();
      await tester.binding.setSurfaceSize(null);
    },
  );
  testWidgets('senior favorite label saves without starting practice', (
    tester,
  ) async {
    await state.setUiMode(UiMode.senior);
    await size(tester, const Size(390, 844));
    await app(tester);
    await section(tester, 'songs');
    await tester.tap(find.text('收藏').first);
    await tester.pumpAndSettle();
    expect(state.favorites.single.id, '1');
    expect(state.current, isNull);
    expect(find.text('已收藏'), findsOneWidget);
    expect(find.byType(PlayerPage), findsNothing);
    expect(tester.takeException(), isNull);
  });
  testWidgets('entering TV cancels pending local autoplay', (tester) async {
    state.current = Song(api.rows.first);
    state.playbackState = 'PREPARING';
    state.isPlaying = false;
    bool? ready;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async {
                ready = await prepareTvPlayback(context, state);
              },
              child: const Text('进入电视'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('进入电视'));
    await tester.pumpAndSettle();
    expect(ready, true);
    expect(native.commands.single['action'], 'pause');
    expect(state.isPlaying, false);
    expect(tester.takeException(), isNull);
  });
  testWidgets('entering TV cancels a late song detail before native open', (
    tester,
  ) async {
    await size(tester, const Size(390, 844));
    await app(tester);
    api.deferred = true;
    final pendingPlay = state.play(Song(api.rows.first));
    expect(api.pending, hasLength(1));
    final context = tester.element(find.byKey(const Key('nav-songs')));
    expect(await prepareTvPlayback(context, state), isTrue);
    api.pending.single.complete(api.rows.first);
    await pendingPlay;
    await tester.pumpAndSettle();
    expect(state.current, isNull);
    expect(state.isPlaying, isFalse);
    expect(state.history, isEmpty);
    expect(native.calls, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'portrait video and long lyrics fit together at all viewport sizes',
    (tester) async {
      api.textTracks = [
        {
          'format': 'lrc',
          'text': '[00:00.00]这是一句很长很长的歌词需要换行完整显示不能被省略\n[00:10.00]下一句也应当可以看到\n[00:20.00]第三句',
        },
      ];
      state.current = Song({
        ...api.rows.first,
        'mediaType': 'VIDEO',
        'title': '很长的歌名需要允许换行并通过详情查看完整内容',
      });
      state.videoAspect = 9 / 16;
      state.playbackState = 'PAUSED';
      state.durationMs = 240000;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      addTearDown(() => tester.binding.setSurfaceSize(null));
      for (final senior in [false, true]) {
        await state.setUiMode(senior ? UiMode.senior : UiMode.standard);
        for (final dimensions in [
          const Size(390, 844),
          const Size(844, 390),
          const Size(320, 568),
        ]) {
          await tester.binding.setSurfaceSize(dimensions);
          tester.platformDispatcher.textScaleFactorTestValue =
              dimensions.width == 320 ? 1.8 : 1;
          await tester.pumpWidget(
            MaterialApp(
              home: PlayerPage(
                key: ValueKey('$senior:$dimensions'),
                state: state,
              ),
            ),
          );
          await tester.pumpAndSettle();
          expect(find.byType(LyricsView), findsOneWidget);
          expect(find.textContaining('这是一句很长很长的歌词'), findsOneWidget);
          expect(tester.takeException(), isNull, reason: '$senior $dimensions');
        }
      }
    },
  );
  testWidgets(
    'safe areas preserve navigation and review actions at large type',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPadding);
      addTearDown(tester.view.resetViewPadding);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      addTearDown(() => tester.binding.setSurfaceSize(null));
      for (final senior in [false, true]) {
        for (final viewport in [
          (const Size(320, 568), const EdgeInsets.only(top: 32, bottom: 24)),
          (
            const Size(568, 320),
            const EdgeInsets.only(left: 32, right: 40, bottom: 24),
          ),
        ]) {
          await tester.pumpWidget(const SizedBox.shrink());
          await tester.binding.setSurfaceSize(viewport.$1);
          final padding = viewport.$2;
          tester.view.padding = FakeViewPadding(
            left: padding.left,
            top: padding.top,
            right: padding.right,
            bottom: padding.bottom,
          );
          tester.view.viewPadding = tester.view.padding;
          state.current = null;
          state.workId = '';
          state.singing = {};
          state.isPlaying = false;
          await state.setUiMode(senior ? UiMode.senior : UiMode.standard);
          final label = '$senior ${viewport.$1}';
          void insideSafeArea(Finder target) {
            final rect = tester.getRect(target);
            expect(
              rect.left,
              greaterThanOrEqualTo(padding.left),
              reason: label,
            );
            expect(rect.top, greaterThanOrEqualTo(padding.top), reason: label);
            expect(
              rect.right,
              lessThanOrEqualTo(viewport.$1.width - padding.right),
              reason: label,
            );
            expect(
              rect.bottom,
              lessThanOrEqualTo(viewport.$1.height - padding.bottom),
              reason: label,
            );
          }

          await app(tester);
          insideSafeArea(find.byKey(const Key('open-settings')));
          for (final tab in ['frequent', 'works', 'songs']) {
            await section(tester, tab);
            insideSafeArea(find.byKey(Key('nav-$tab')));
            expect(tester.takeException(), isNull, reason: '$label $tab');
          }
          state.current = Song(api.rows.first);
          state.playbackInstanceId = 'nas-a';
          state.playbackState = 'PAUSED';
          native.emit();
          await tester.pumpAndSettle();
          insideSafeArea(find.byKey(const Key('mini-player')));
          final resume = find.byTooltip('继续播放');
          insideSafeArea(resume);
          await tester.tap(resume);
          await tester.pumpAndSettle();
          expect(state.isPlaying, isTrue);
          if (senior) {
            expect(
              find.descendant(
                of: find.byKey(const Key('mini-player')),
                matching: find.text('暂停'),
              ),
              findsOneWidget,
            );
          }
          expect(tester.takeException(), isNull, reason: '$label mini player');
          final id = 'safe-$senior-${viewport.$1.width}';
          await review(tester, workFixture(id: id));
          final play = find.byKey(const Key('review-play'));
          await tester.scrollUntilVisible(
            play,
            160,
            scrollable: find.byType(Scrollable).first,
          );
          await tester.pumpAndSettle();
          insideSafeArea(play);
          await tester.tap(play);
          await tester.pumpAndSettle();
          expect(state.workId, id);
          expect(state.isPlaying, isTrue);
          expect(tester.takeException(), isNull, reason: '$label play');
          final save = find.byKey(const Key('review-save'));
          await tester.scrollUntilVisible(
            save,
            160,
            scrollable: find.byType(Scrollable).first,
          );
          await tester.pumpAndSettle();
          insideSafeArea(save);
          await tester.tap(save);
          await tester.pumpAndSettle();
          expect(native.works[id]!['status'], 'SAVED');
          expect(find.text('导出音频'), findsOneWidget);
          expect(tester.takeException(), isNull, reason: '$label save');
        }
      }
    },
  );
  testWidgets(
    'mini player shows playback state and retains recording priority',
    (tester) async {
      await size(tester, const Size(390, 844));
      state.current = Song(api.rows.first);
      state.playbackInstanceId = 'nas-a';
      state.positionMs = 12000;
      await app(tester);
      expect(find.byKey(const Key('mini-player')), findsOneWidget);
      for (final status in [
        ('PREPARING', false, '正在准备'),
        ('BUFFERING', false, '正在缓冲'),
        ('ERROR', false, '播放失败'),
        ('AUTH_REQUIRED', false, '登录失效'),
        ('PLAYING', true, '播放中'),
        ('PAUSED', false, '已暂停'),
      ]) {
        state.playbackState = status.$1;
        state.isPlaying = status.$2;
        native.emit();
        await tester.pumpAndSettle();
        expect(find.text('${status.$3} · 0:12'), findsOneWidget);
        expect(tester.takeException(), isNull, reason: status.$1);
      }
      await state.setUiMode(UiMode.senior);
      await tester.pumpAndSettle();
      expect(find.text('继续'), findsOneWidget);
      await tester.tap(find.byTooltip('继续播放'));
      await tester.pumpAndSettle();
      expect(state.isPlaying, isTrue);
      expect(find.text('播放中 · 0:12'), findsOneWidget);
      expect(find.text('暂停'), findsOneWidget);
      await tester.tap(find.byTooltip('暂停播放'));
      await tester.pumpAndSettle();
      expect(state.isPlaying, isFalse);
      expect(find.text('已暂停 · 0:12'), findsOneWidget);
      state.playbackState = 'ERROR';
      state.singing = {'phase': 'RECORDING', 'recordedMs': 9000};
      native.emit();
      await tester.pumpAndSettle();
      expect(find.text('正在录音 · 0:09'), findsOneWidget);
      expect(find.textContaining('播放失败'), findsNothing);
      state.singing = {'phase': 'IDLE'};
      state.countdown = 3;
      native.emit();
      await tester.pumpAndSettle();
      expect(find.text('录音倒数 3'), findsOneWidget);
      state.countdown = 0;
      state.singing = {
        'phase': 'PREVIEW',
        'takeId': 'draft',
        'songId': '1',
        'instanceId': 'nas-a',
      };
      native.emit();
      await tester.pumpAndSettle();
      expect(find.text('录音已保留 · 点击回听'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'recording notification returns from an older work to the active take',
    (tester) async {
      await size(tester, const Size(390, 844));
      native.works['older'] = workFixture(id: 'older', status: 'SAVED');
      state.works = native.works.values.toList();
      state.current = Song(api.rows.first);
      state.playbackInstanceId = 'nas-a';
      state.singing = {
        'phase': 'RECORDING',
        'takeId': 'active-take',
        'recordedMs': 19000,
      };
      await app(tester);
      await section(tester, 'works');
      await tester.tap(find.text(native.works['older']!['name'] as String));
      await tester.pumpAndSettle();
      expect(tester.widget<ReviewPage>(find.byType(ReviewPage)).id, 'older');
      state.stageRequests++;
      native.emit();
      await tester.pumpAndSettle();
      expect(find.byType(PlayerPage), findsOneWidget);
      expect(state.takeId, 'active-take');
      expect(state.recording, isTrue);
      expect(native.calls.where((method) => method == 'end'), isEmpty);
      expect(native.calls.where((method) => method == 'play'), isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('mini player ends the actual take and opens its retained draft', (
    tester,
  ) async {
    await size(tester, const Size(390, 844));
    native.works['mini-take'] = workFixture(id: 'mini-take');
    state.current = Song(api.rows.first);
    state.playbackInstanceId = 'nas-a';
    state.singing = {
      'phase': 'RECORDING',
      'takeId': 'mini-take',
      'recordedMs': 19000,
    };
    await app(tester);
    final mini = find.byKey(const Key('mini-player'));
    expect(
      find.descendant(of: mini, matching: find.text('正在录音 · 0:19')),
      findsOneWidget,
    );
    await tester.tap(find.descendant(of: mini, matching: find.text('结束')));
    await tester.pumpAndSettle();
    expect(native.calls.where((method) => method == 'end'), hasLength(1));
    expect(state.recording, isFalse);
    expect(tester.widget<ReviewPage>(find.byType(ReviewPage)).id, 'mini-take');
    expect(native.works['mini-take']!['status'], 'PREVIEW');
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'player semantics survives lyric progress roles and recording transitions',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        addTearDown(() => tester.binding.setSurfaceSize(null));
        api.textTracks = [
          {
            'format': 'lrc',
            'text': List.generate(
              50,
              (i) => '[00:${i.toString().padLeft(2, '0')}.00]这句歌词需要换行完整显示 $i',
            ).join('\n'),
          },
        ];
        state
          ..current = Song(api.rows.first)
          ..playbackInstanceId = 'nas-a'
          ..playbackState = 'PLAYING'
          ..isPlaying = true
          ..durationMs = 240000
          ..backingMapped = true
          ..originalMapped = true
          ..audioRole = 'backing'
          ..tracks = [
            {'id': '0:0', 'type': 'audio', 'selected': true, 'channels': 2},
          ];
        for (final dimensions in [const Size(390, 844), const Size(844, 390)]) {
          await tester.binding.setSurfaceSize(dimensions);
          state
            ..countdown = 0
            ..singing = {}
            ..playbackError = ''
            ..positionMs = 0;
          await tester.pumpWidget(
            MaterialApp(
              home: PlayerPage(key: ValueKey(dimensions), state: state),
            ),
          );
          await tester.pumpAndSettle();
          for (final role in ['original', 'backing']) {
            final target = find.byKey(ValueKey('player-role-$role'));
            await tester.ensureVisible(target);
            await tester.tap(target);
            await tester.pumpAndSettle();
            expect(state.audioRole, role);
            expect(tester.takeException(), isNull, reason: '$dimensions $role');
          }
          for (final position in [800, 1000, 1200, 2000, 2200]) {
            state.positionMs = position;
            native.emit();
            await tester.pump(const Duration(milliseconds: 100));
            expect(
              tester.takeException(),
              isNull,
              reason: '$dimensions $position',
            );
          }
          final tuning = find.text('调音');
          await tester.ensureVisible(tuning);
          await tester.tap(tuning);
          await tester.pump(const Duration(milliseconds: 300));
          final audioSettings = find.text('音轨与播放设置');
          await tester.ensureVisible(audioSettings);
          await tester.pumpAndSettle();
          await tester.tap(audioSettings);
          await tester.pump(const Duration(milliseconds: 300));
          state.positionMs = 15000;
          native.emit();
          await tester.pump(const Duration(milliseconds: 16));
          await tester.binding.handlePopRoute();
          await tester.pump(const Duration(milliseconds: 300));
          await tester.binding.handlePopRoute();
          await tester.pump(const Duration(milliseconds: 16));
          final record = find.byKey(const Key('player-record'));
          state
            ..countdown = 3
            ..positionMs = 33000;
          native.emit();
          await tester.pump(const Duration(milliseconds: 16));
          state
            ..countdown = 0
            ..singing = {'phase': 'IDLE'}
            ..playbackError = '请先从歌曲开头重新准备兼容播放';
          native.emit();
          for (var frame = 0; frame < 20; frame++) {
            await tester.pump(const Duration(milliseconds: 16));
            expect(
              tester.takeException(),
              isNull,
              reason: '$dimensions countdown error frame=$frame',
            );
          }
          expect(find.text('请先从歌曲开头重新准备兼容播放'), findsOneWidget);
          state.playbackError = '';
          native.emit();
          await tester.pumpAndSettle();
          final progress = find.byKey(const Key('player-progress'));
          SemanticsData progressSemantics() {
            final values = <SemanticsData>[];
            void visit(SemanticsNode node) {
              final data = node.getSemanticsData();
              if (data.value.isNotEmpty) values.add(data);
              node.visitChildren((child) {
                visit(child);
                return true;
              });
            }

            visit(tester.getSemantics(progress));
            expect(values, hasLength(1));
            return values.single;
          }

          await tester.ensureVisible(progress);
          await tester.pumpAndSettle();
          expect(progressSemantics().value, durationLabel(state.positionMs));
          expect(
            progressSemantics().hasAction(SemanticsAction.increase),
            isTrue,
          );
          await tester.ensureVisible(record);
          await tester.tap(record);
          await tester.pumpAndSettle();
          expect(state.countdown, 3);
          for (final countdown in [2, 1, 0]) {
            state.countdown = countdown;
            state.positionMs += 1000;
            if (countdown == 0) {
              state.singing = {
                'phase': 'RECORDING',
                'takeId': 'semantics-take',
                'recordedMs': 1000,
                'level': .1,
              };
            }
            native.emit();
            await tester.pump(const Duration(milliseconds: 100));
            final play = find.byKey(const Key('player-play'));
            expect(
              tester
                  .getSemantics(play)
                  .getSemanticsData()
                  .hasAction(SemanticsAction.tap),
              isFalse,
            );
            expect(
              tester
                  .getSemantics(record)
                  .getSemanticsData()
                  .hasAction(SemanticsAction.tap),
              isTrue,
            );
            expect(
              progressSemantics().hasAction(SemanticsAction.increase),
              isFalse,
            );
            expect(
              tester.takeException(),
              isNull,
              reason: '$dimensions countdown=$countdown',
            );
          }
          state.singing = {'phase': 'IDLE'};
          native.emit();
          await tester.pumpAndSettle();
          await tester.ensureVisible(progress);
          await tester.pumpAndSettle();
          expect(
            progressSemantics().hasAction(SemanticsAction.increase),
            isTrue,
          );
          expect(
            tester
                .getSemantics(find.byKey(const Key('player-play')))
                .getSemanticsData()
                .hasAction(SemanticsAction.tap),
            isTrue,
          );
          expect(tester.takeException(), isNull, reason: '$dimensions enabled');
        }
      } finally {
        semantics.dispose();
      }
    },
  );
  testWidgets(
    'active wrapped lyrics fit inside the viewport after seeking and resizing',
    (tester) async {
      await size(tester, const Size(390, 844));
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      const lines = [
        TextLine(0, 10000, '这句歌词需要换行完整显示'),
        TextLine(10000, 20000, '中间一行'),
        TextLine(20000, null, '这句歌词需要换行完整显示'),
      ];
      for (final senior in [false, true]) {
        for (final step in [
          (400.0, 0),
          (400.0, 20000),
          (280.0, 20000),
          (280.0, 0),
        ]) {
          int? seek;
          await tester.pumpWidget(
            MaterialApp(
              theme: singVerseTheme(Brightness.dark, senior: senior),
              home: Scaffold(
                body: Center(
                  child: SizedBox(
                    width: 320,
                    height: step.$1,
                    child: LyricsView(
                      key: ValueKey(senior),
                      lines: lines,
                      positionMs: step.$2,
                      senior: senior,
                      enabled: true,
                      onSeek: (value) => seek = value,
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final active = find.byKey(
            ValueKey('lyrics-line-${step.$2 == 0 ? 0 : 2}'),
          );
          final text = tester.getRect(active);
          final viewport = tester.getRect(
            find.byKey(const Key('lyrics-viewport')),
          );
          final label = '$senior height=${step.$1} position=${step.$2}';
          final paragraph = tester.renderObject<RenderParagraph>(active);
          final lineTops = paragraph
              .getBoxesForSelection(
                TextSelection(
                  baseOffset: 0,
                  extentOffset: paragraph.text.toPlainText().length,
                ),
              )
              .map((box) => box.top)
              .toSet();
          expect(
            lineTops.length,
            greaterThanOrEqualTo(2),
            reason: 'fixture必须实际换成多行：$label',
          );
          expect(
            text.height,
            lessThanOrEqualTo(viewport.height),
            reason: label,
          );
          expect(text.left, greaterThanOrEqualTo(viewport.left), reason: label);
          expect(text.right, lessThanOrEqualTo(viewport.right), reason: label);
          expect(text.top, greaterThanOrEqualTo(viewport.top), reason: label);
          expect(
            text.bottom,
            lessThanOrEqualTo(viewport.bottom),
            reason: label,
          );
          await tester.tap(active);
          await tester.pumpAndSettle();
          expect(seek, step.$2);
          expect(tester.takeException(), isNull, reason: label);
        }
      }
    },
  );
  testWidgets(
    'playing wrapped lyrics stay visible during continuous position updates',
    (tester) async {
      await size(tester, const Size(390, 844));
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      const phrases = [
        '晨光落在窗前',
        '把心事轻轻唱出来',
        '熟悉的旋律还在',
        '每一次开口都值得期待',
        '让声音慢慢盛开',
      ];
      final lines = List.generate(
        15,
        (index) => TextLine(
          index * 6000,
          (index + 1) * 6000,
          phrases[index % phrases.length],
        ),
      );
      for (final scale in [1.0, 1.8]) {
        tester.platformDispatcher.textScaleFactorTestValue = scale;
        for (final senior in [false, true]) {
          state.positionMs = 0;
          await tester.pumpWidget(
            MaterialApp(
              theme: singVerseTheme(Brightness.dark, senior: senior),
              home: Scaffold(
                body: Center(
                  child: SizedBox(
                    width: 390,
                    height: 240,
                    child: ListenableBuilder(
                      listenable: state,
                      builder: (context, _) => LyricsView(
                        key: ValueKey(senior),
                        lines: lines,
                        positionMs: state.positionMs,
                        senior: senior,
                        enabled: true,
                        onSeek: (_) {},
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          for (var tick = 0; tick < 330; tick++) {
            state.positionMs = tick * 80;
            native.emit();
            await tester.pump(const Duration(milliseconds: 16));
            final activeIndex = state.positionMs ~/ 6000;
            // Allow the 240ms standard-mode follow animation to finish while
            // native progress keeps rebuilding the same active lyric. Large type
            // and senior mode must reveal the whole line on the next frame.
            if (tick % 75 >= (senior || scale > 1.3 ? 1 : 17)) {
              final active = find.byKey(ValueKey('lyrics-line-$activeIndex'));
              expect(active, findsOneWidget);
              final text = tester.getRect(active);
              final viewport = tester.getRect(
                find.byKey(const Key('lyrics-viewport')),
              );
              final label =
                  'scale=$scale senior=$senior tick=$tick active=$activeIndex';
              if (scale == 1.8 && activeIndex == 3) {
                final paragraph = tester.renderObject<RenderParagraph>(active);
                final lineTops = paragraph
                    .getBoxesForSelection(
                      TextSelection(
                        baseOffset: 0,
                        extentOffset: paragraph.text.toPlainText().length,
                      ),
                    )
                    .map((box) => box.top)
                    .toSet();
                expect(
                  lineTops.length,
                  greaterThanOrEqualTo(2),
                  reason: '活动歌词必须实际换行：$label',
                );
              }
              expect(
                text.height,
                lessThanOrEqualTo(viewport.height),
                reason: label,
              );
              expect(
                text.top,
                greaterThanOrEqualTo(viewport.top),
                reason: label,
              );
              expect(
                text.bottom,
                lessThanOrEqualTo(viewport.bottom),
                reason: label,
              );
            }
            expect(
              tester.takeException(),
              isNull,
              reason: 'scale=$scale $senior tick=$tick',
            );
          }
        }
      }
    },
  );
  testWidgets(
    'main tabs and review remain usable in both themes at large type',
    (tester) async {
      await size(tester, const Size(320, 568));
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      for (final senior in [false, true]) {
        await state.setUiMode(senior ? UiMode.senior : UiMode.standard);
        for (final theme in [ThemeMode.light, ThemeMode.dark]) {
          await state.setTheme(theme);
          await app(tester);
          for (final tab in ['frequent', 'works', 'songs']) {
            await section(tester, tab);
            expect(
              tester.takeException(),
              isNull,
              reason: '$senior $theme $tab',
            );
          }
          await review(tester, workFixture());
          await tester.scrollUntilVisible(
            find.text('保存作品'),
            160,
            scrollable: find.byType(Scrollable).first,
          );
          expect(
            tester.getSize(find.widgetWithText(FilledButton, '保存作品')).height,
            greaterThanOrEqualTo(senior ? 56 : 48),
          );
          expect(
            tester.takeException(),
            isNull,
            reason: '$senior $theme review',
          );
        }
      }
    },
  );

  testWidgets(
    'settings and NAS editing remain reachable without interrupting playback',
    (tester) async {
      addTearDown(() => tester.binding.setSurfaceSize(null));
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      addTearDown(tester.view.resetViewInsets);
      addTearDown(tester.view.resetDevicePixelRatio);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      for (final senior in [false, true]) {
        for (final theme in [ThemeMode.light, ThemeMode.dark]) {
          for (final dimensions in [
            const Size(320, 568),
            const Size(390, 844),
            const Size(844, 390),
          ]) {
            await tester.pumpWidget(const SizedBox.shrink());
            tester.view.resetViewInsets();
            await tester.binding.setSurfaceSize(dimensions);
            state
              ..uiMode = senior ? UiMode.senior : UiMode.standard
              ..themeMode = theme
              ..current = Song(api.rows.first)
              ..positionMs = 35000
              ..isPlaying = true
              ..playbackState = 'PLAYING'
              ..origin = 'http://family-music-library.home.local:18080';
            await app(tester);
            for (final key in ['open-tv', 'open-settings']) {
              final button = find.byKey(Key(key));
              expect(button.hitTestable(), findsOneWidget);
              expect(
                tester.getSize(button).height,
                greaterThanOrEqualTo(senior ? 56 : 48),
              );
            }
            await tester.tap(find.byKey(const Key('open-settings')));
            await tester.pumpAndSettle();
            final option = find.text('深色');
            await tester.scrollUntilVisible(
              option,
              120,
              scrollable: find.byType(Scrollable).first,
            );
            await tester.pumpAndSettle();
            expect(option.hitTestable(), findsOneWidget);
            await tester.tap(option);
            await tester.pumpAndSettle();
            expect(state.themeMode, ThemeMode.dark);
            await state.setTheme(theme);
            await tester.pumpAndSettle();
            final nas = find.text('NAS 连接与账号');
            await tester.scrollUntilVisible(
              nas,
              -120,
              scrollable: find.byType(Scrollable).first,
            );
            await tester.pumpAndSettle();
            await tester.tap(nas);
            await tester.pumpAndSettle();
            expect(
              find.byKey(const ValueKey('nas-status-card')),
              findsOneWidget,
            );
            final change = find.text('修改 NAS 地址');
            await tester.scrollUntilVisible(
              change,
              120,
              scrollable: find.byType(Scrollable).first,
            );
            await tester.pumpAndSettle();
            await tester.tap(change);
            await tester.pumpAndSettle();
            final address = find.byKey(const ValueKey('nas-address-field'));
            await tester.scrollUntilVisible(
              address,
              -120,
              scrollable: find.byType(Scrollable).first,
            );
            await tester.pumpAndSettle();
            await Scrollable.ensureVisible(
              tester.element(address),
              alignment: 0.5,
            );
            await tester.pumpAndSettle();
            expect(address.hitTestable(), findsOneWidget);
            await tester.tap(address);
            await tester.pumpAndSettle();
            expect(
              tester
                  .widget<EditableText>(find.byType(EditableText))
                  .focusNode
                  .hasFocus,
              isTrue,
            );
            // Editing alone must neither replace the NAS nor disturb playback.
            tester.view.viewInsets = const FakeViewPadding(bottom: 160);
            await tester.pumpAndSettle();
            final submit = find.byKey(const ValueKey('nas-submit'));
            await tester.scrollUntilVisible(
              submit,
              120,
              scrollable: find.byType(Scrollable).first,
            );
            await tester.pumpAndSettle();
            expect(submit.hitTestable(), findsOneWidget);
            expect(
              tester.getSize(submit).height,
              greaterThanOrEqualTo(senior ? 56 : 48),
            );
            expect(state.positionMs, 35000);
            expect(state.isPlaying, true);
            expect(state.playbackState, 'PLAYING');
            expect(
              state.origin,
              'http://family-music-library.home.local:18080',
            );
            expect(
              tester.takeException(),
              isNull,
              reason: '$senior $theme $dimensions',
            );
          }
        }
      }
      expect(native.commands, isEmpty);
      expect(native.calls, isEmpty);
    },
  );

  testWidgets(
    'phone density preserves compact type scaling safe area and playback',
    (tester) async {
      tester.view.devicePixelRatio = 3.5;
      tester.view.physicalSize = const Size(1260, 2800);
      tester.view.padding = const FakeViewPadding(top: 140);
      tester.view.viewPadding = const FakeViewPadding(top: 140);
      tester.platformDispatcher.textScaleFactorTestValue = 1;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetPadding);
      addTearDown(tester.view.resetViewPadding);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      state
        ..current = Song(api.rows.first)
        ..positionMs = 35000
        ..isPlaying = true
        ..playbackState = 'PLAYING';
      await app(tester);
      BuildContext pageContext() => tester.element(find.byType(AppBar).first);
      final media = MediaQuery.of(pageContext());
      expect(media.size, const Size(360, 800));
      expect(media.devicePixelRatio, 3.5);
      expect(media.padding.top, 40);
      expect(media.textScaler.scale(14), 14);
      expect(Theme.of(pageContext()).textTheme.headlineSmall!.fontSize, 20);
      expect(Theme.of(pageContext()).textTheme.bodyMedium!.fontSize, 14);
      final songTitle = find.descendant(
        of: find.byType(SongRow).first,
        matching: find.text('晴天'),
      );
      expect(tester.widget<Text>(songTitle).style!.fontSize, 14);
      for (final key in ['open-tv', 'open-settings', 'nav-songs']) {
        final button = find.byKey(Key(key));
        expect(button.hitTestable(), findsOneWidget);
        expect(tester.getSize(button).height, greaterThanOrEqualTo(48));
        expect(tester.getTopLeft(button).dy, greaterThanOrEqualTo(40));
      }

      // The same live app responds to width changes, not physical pixel count.
      tester.view.physicalSize = const Size(1365, 2954);
      await tester.pumpAndSettle();
      expect(MediaQuery.sizeOf(pageContext()).width, 390);
      expect(Theme.of(pageContext()).textTheme.headlineSmall!.fontSize, 22);
      expect(tester.widget<Text>(songTitle).style!.fontSize, 15);
      tester.view.physicalSize = const Size(1260, 2800);
      await state.setTheme(ThemeMode.dark);
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      await tester.pumpAndSettle();
      expect(Theme.of(pageContext()).brightness, Brightness.dark);
      expect(Theme.of(pageContext()).textTheme.headlineSmall!.fontSize, 20);
      expect(
        MediaQuery.textScalerOf(pageContext()).scale(14),
        closeTo(25.2, .01),
      );
      await state.setUiMode(UiMode.senior);
      await tester.pumpAndSettle();
      expect(Theme.of(pageContext()).textTheme.headlineSmall!.fontSize, 26);
      final settings = find.byKey(const Key('open-settings'));
      expect(settings.hitTestable(), findsOneWidget);
      expect(tester.getSize(settings).height, greaterThanOrEqualTo(56));
      expect(state.positionMs, 35000);
      expect(state.isPlaying, isTrue);
      expect(native.commands, isEmpty);
      expect(native.calls, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('capture refined app screens with controlled fixtures', (
    tester,
  ) async {
    if (!const bool.fromEnvironment('CAPTURE_APP_UI')) return;
    final round = const String.fromEnvironment(
      'UI_ROUND',
      defaultValue: 'round-1',
    );
    final directory = Directory('../../tmp/mobile-ui-unslop/$round')
      ..createSync(recursive: true);
    await size(tester, const Size(390, 844));
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final boundary = GlobalKey();
    Future<void> capture(String name) async {
      await tester.runAsync(() async {
        final context = boundary.currentContext!;
        await precacheImage(MemoryImage(fixtureCover), context);
        for (final size in [48, 60, 64, 80]) {
          await precacheImage(
            ResizeImage(
              MemoryImage(fixtureCover),
              width: (size * MediaQuery.devicePixelRatioOf(context)).ceil(),
            ),
            context,
          );
        }
      });
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        final render =
            boundary.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final picture = await render.toImage(pixelRatio: 2);
        final bytes = await picture.toByteData(format: ui.ImageByteFormat.png);
        await File('${directory.path}/$name.png')
            .writeAsBytes(bytes!.buffer.asUint8List());
        picture.dispose();
      });
      expect(tester.takeException(), isNull, reason: name);
    }

    await store.favorite('nas-a', Song(api.rows.first), true);
    await state.refreshPersonal();
    native.works['draft'] = {...workFixture(), 'name': '晴天 · 练唱草稿'};
    native.works['saved'] = {
      ...workFixture(id: 'saved', status: 'SAVED'),
      'name': '晴天 · 我的演唱',
    };
    state.works = native.works.values.toList();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: SingVerseApp(state: state),
      ),
    );
    await tester.pumpAndSettle();
    await capture('01-library');
    await state.setTheme(ThemeMode.dark);
    await tester.pumpAndSettle();
    await capture('02-library-dark');
    await state.setTheme(ThemeMode.light);
    await section(tester, 'frequent');
    await capture('03-frequent');
    await section(tester, 'works');
    await capture('04-works');
    await tester.tap(find.text('晴天 · 练唱草稿'));
    await tester.pumpAndSettle();
    await capture('05-review');
    await tester.tap(find.text('调整声音'));
    await tester.pumpAndSettle();
    await capture('06-adjustment');
    final nav = tester.state<NavigatorState>(find.byType(Navigator).first);
    nav.popUntil((route) => route.isFirst);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('open-settings')));
    await tester.pumpAndSettle();
    await capture('07-settings');
    await tester.tap(find.text('NAS 连接与账号'));
    await tester.pumpAndSettle();
    await capture('08-nas-connected');
    nav.popUntil((route) => route.isFirst);
    await tester.pumpAndSettle();
    await state.setUiMode(UiMode.senior);
    await section(tester, 'songs');
    await capture('09-senior-library');
    await tester.binding.setSurfaceSize(const Size(320, 568));
    tester.platformDispatcher.textScaleFactorTestValue = 1.8;
    await tester.pumpAndSettle();
    await capture('10-small-senior');
    tester.platformDispatcher.clearTextScaleFactorTestValue();
    await tester.binding.setSurfaceSize(const Size(390, 844));
    await state.setUiMode(UiMode.standard);
    state.authenticated = false;
    nav.push(
      MaterialPageRoute<void>(builder: (_) => ConnectionPage(state: state)),
    );
    await tester.pumpAndSettle();
    await capture('11-nas-login');
  });

  testWidgets('capture refined player screens with controlled fixtures', (
    tester,
  ) async {
    if (!const bool.fromEnvironment('CAPTURE_PLAYER_UI')) return;
    final directory = Directory('../../tmp/mobile-player-unslop')
      ..createSync(recursive: true);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final scenarios = [
      (
        '01-dark-audio',
        Brightness.dark,
        const Size(390, 844),
        'AUDIO',
        false,
        1.0,
        true,
        true,
      ),
      (
        '02-light-audio',
        Brightness.light,
        const Size(390, 844),
        'AUDIO',
        false,
        1.0,
        true,
        true,
      ),
      (
        '03-dark-mv',
        Brightness.dark,
        const Size(390, 844),
        'VIDEO',
        false,
        1.0,
        true,
        true,
      ),
      (
        '04-light-mv',
        Brightness.light,
        const Size(390, 844),
        'VIDEO',
        false,
        1.0,
        true,
        true,
      ),
      (
        '05-landscape',
        Brightness.light,
        const Size(844, 390),
        'VIDEO',
        false,
        1.0,
        true,
        true,
      ),
      (
        '06-small-large',
        Brightness.dark,
        const Size(320, 568),
        'VIDEO',
        true,
        1.8,
        true,
        false,
      ),
      (
        '07-no-artwork-or-lyrics',
        Brightness.light,
        const Size(390, 844),
        'AUDIO',
        false,
        1.0,
        false,
        false,
      ),
      (
        '08-selected-range',
        Brightness.light,
        const Size(390, 844),
        'AUDIO',
        false,
        1.0,
        true,
        true,
      ),
    ];
    for (final sample in scenarios) {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.binding.setSurfaceSize(sample.$3);
      tester.platformDispatcher.textScaleFactorTestValue = sample.$6;
      api.textTracks = sample.$7
          ? [
              {
                'format': 'lrc',
                'text': '[00:00.00]晨光落在窗前\n[00:10.00]把心事轻轻唱出来\n[00:20.00]熟悉的旋律还在\n[00:30.00]每一次开口都值得期待',
              },
            ]
          : [];
      state
        ..current = Song({
          'id': 'capture-player',
          'title': '晴天',
          'artists': ['样本歌手'],
          'mediaType': sample.$4,
          if (sample.$8) 'coverPath': '/api/v1/test-artwork/player',
        })
        ..uiMode = sample.$5 ? UiMode.senior : UiMode.standard
        ..positionMs = 12000
        ..durationMs = 240000
        ..isPlaying = false
        ..playbackState = 'PAUSED'
        ..rangeA = sample.$1 == '08-selected-range' ? 10000 : 0
        ..rangeB = sample.$1 == '08-selected-range' ? 30000 : 0
        ..backingMapped = true
        ..originalMapped = true
        ..audioRole = 'backing'
        ..tracks = [
          {'id': '0:0', 'type': 'audio', 'selected': true, 'channels': 2},
        ];
      final boundary = GlobalKey();
      await tester.pumpWidget(
        RepaintBoundary(
          key: boundary,
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: singVerseTheme(sample.$2, senior: state.senior),
            home: PlayerPage(state: state),
          ),
        ),
      );
      if (sample.$8) {
        await tester.runAsync(
          () => precacheImage(
            MemoryImage(fixtureCover),
            boundary.currentContext!,
          ),
        );
      }
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        final render =
            boundary.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final picture = await render.toImage(pixelRatio: 2);
        final bytes = await picture.toByteData(format: ui.ImageByteFormat.png);
        await File('${directory.path}/${sample.$1}.png')
            .writeAsBytes(bytes!.buffer.asUint8List());
        picture.dispose();
      });
      expect(tester.takeException(), isNull, reason: sample.$1);
    }
  });

  testWidgets('capture redesign reference screens with controlled fixtures', (
    tester,
  ) async {
    if (!const bool.fromEnvironment('CAPTURE_UI')) return;
    await size(tester, const Size(390, 844));
    final boundary = GlobalKey();
    Future<void> capture(String name) async {
      await tester.runAsync(
        () =>
            precacheImage(MemoryImage(fixtureCover), boundary.currentContext!),
      );
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        final render =
            boundary.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final image = await render.toImage(pixelRatio: 2);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        final directory = Directory('../../tmp/mobile清晰舞台预览')
          ..createSync(recursive: true);
        await File('${directory.path}/$name.png')
            .writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
    }

    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: SingVerseApp(state: state),
      ),
    );
    await tester.pumpAndSettle();
    await capture('01-标准点歌');
    expect(
      find
          .byType(RawImage)
          .evaluate()
          .any((element) => (element.widget as RawImage).image != null),
      isTrue,
      reason: '有封面fixture必须解码，不能把空白Image当成封面截图',
    );
    await tester.tap(find.text('晴天'));
    await tester.pumpAndSettle();
    await capture('02-标准播放器');
    await tester.tap(find.text('调音'));
    await tester.pumpAndSettle();
    await capture('03-调音');
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('收起演唱'));
    await tester.pumpAndSettle();
    await state.setUiMode(UiMode.senior);
    await tester.pumpAndSettle();
    await capture('04-长辈主导航');
    await section(tester, 'songs');
    await capture('05-长辈点歌');
    await tester.tap(find.text('开始练唱').first);
    await tester.pumpAndSettle();
    await capture('06-长辈播放器');
    await tester.tap(find.byTooltip('收起演唱'));
    await tester.pumpAndSettle();
    await state.setUiMode(UiMode.standard);
    await tester.pumpAndSettle();
    api.textTracks = [
      {
        'format': 'lrc',
        'text': '[00:00.00]晨光落在窗前\n[00:10.00]把心事轻轻唱出来\n[00:20.00]熟悉的旋律还在\n[00:30.00]每一次开口都值得期待',
      },
    ];
    await tester.tap(find.text('晴天').first);
    await tester.pumpAndSettle();
    await capture('07-同步歌词');
    await tester.tap(find.byTooltip('收起演唱'));
    await tester.pumpAndSettle();
    state.current = Song({...api.rows.first, 'mediaType': 'VIDEO'});
    native.emit();
    await tester.tap(find.byKey(const Key('mini-player')));
    await tester.pumpAndSettle();
    await tester.binding.setSurfaceSize(const Size(844, 390));
    await tester.pumpAndSettle();
    await capture('08-横屏视频与歌词');
    state.isPlaying = true;
    native.emit();
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
    await capture('14-横屏视频隐藏控制');
    await tester.tapAt(
      tester.getTopLeft(
            find.byKey(const ValueKey('player-landscape-surface')),
          ) +
          const Offset(8, 8),
    );
    await tester.pumpAndSettle();
    state.singing = {
      'phase': 'RECORDING',
      'takeId': 'capture-live',
      'recordedMs': 42000,
    };
    native.emit();
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
    await capture('15-横屏录音控制隐藏');
    state.singing = {};
    state.current = Song({...api.rows.first, 'mediaType': 'AUDIO'});
    state.isPlaying = false;
    native.emit();
    await tester.pumpAndSettle();
    await capture('16-横屏同步歌词');
    await state.setUiMode(UiMode.senior);
    tester.platformDispatcher.textScaleFactorTestValue = 1.4;
    await tester.pumpAndSettle();
    await capture('17-横屏长辈大字号');
    tester.platformDispatcher.clearTextScaleFactorTestValue();
    await state.setUiMode(UiMode.standard);
    api.textTracks = [];
    state.current = Song({...api.rows[1], 'mediaType': 'AUDIO'});
    native.emit();
    await tester.pumpAndSettle();
    await capture('18-横屏无歌词音频');
    await tester.binding.setSurfaceSize(const Size(390, 844));
    await tester.pumpAndSettle();
    native.works['draft'] = workFixture();
    final navigator = tester.state<NavigatorState>(
      find.byType(Navigator).first,
    );
    navigator.push(
      MaterialPageRoute<void>(
        settings: const RouteSettings(name: 'review'),
        builder: (_) => ReviewPage(state: state, id: 'draft'),
      ),
    );
    await tester.pumpAndSettle();
    await capture('09-草稿回听');
    await tester.ensureVisible(find.text('调整声音'));
    await tester.tap(find.text('调整声音'));
    await tester.pumpAndSettle();
    await capture('10-调整声音');
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
    navigator.popUntil((route) => route.isFirst);
    await tester.pumpAndSettle();
    await state.setTheme(ThemeMode.dark);
    await tester.pumpAndSettle();
    await capture('11-深色点歌');
    await state.setTheme(ThemeMode.light);
    await store.favorite('nas-a', Song(api.rows.first), true);
    native.works['saved'] = {
      ...workFixture(id: 'saved', status: 'SAVED'),
      'name': '晴天 · 我的版本',
    };
    state.works = native.works.values.toList();
    await state.refreshPersonal();
    await section(tester, 'frequent');
    await capture('12-常唱收藏');
    await section(tester, 'works');
    await capture('13-作品列表');
    expect(tester.takeException(), isNull);
  });
}
