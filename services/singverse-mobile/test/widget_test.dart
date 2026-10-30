import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:singverse_mobile/core/api.dart';
import 'package:singverse_mobile/core/app_state.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:singverse_mobile/core/models.dart';
import 'package:singverse_mobile/main.dart';

class FakeApi implements NasApi {
  String origin = '';
  String loginState = 'authenticated';
  final requests = <String>[];
  final pending = <Completer<dynamic>>[];
  bool deferred = false;
  bool loggedIn = false;
  List<Json> rows = [];
  bool offline = false;
  ApiFailure? loginFailure;
  @override
  Future<String> restore() async => origin;
  @override
  Future<void> configure(String value) async {
    origin = value;
  }

  @override
  Future<void> forget() async {}
  @override
  Future<Uint8List> bytes(String path) async => Uint8List(0);
  @override
  Future<dynamic> inspect(String origin, {String? invitation}) async {
    if (offline) throw ApiFailure(0, 'NAS 不可达');
    return {'service': 'singverse', 'apiVersion': 1, 'instanceId': 'nas-a'};
  }

  @override
  Future<dynamic> request(
    String path, {
    String method = 'GET',
    Object? body,
  }) async {
    requests.add(path);
    if (path.endsWith('/auth/session')) {
      if (!loggedIn) throw ApiFailure(401, '请登录');
      return {'username': 'admin', 'defaultPassword': true};
    }
    if (path.endsWith('/auth/login')) {
      if (loginFailure != null) throw loginFailure!;
      loggedIn = loginState == 'authenticated';
      return {'state': loginState};
    }
    if (path.endsWith('/mfa/verify')) {
      loggedIn = true;
      return {'state': 'authenticated'};
    }
    if (deferred) {
      final next = Completer<dynamic>();
      pending.add(next);
      return next.future;
    }
    return {'items': rows, 'totalElements': rows.length};
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  late LocalStore store;
  late AppState state;
  late FakeApi api;
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    store = await LocalStore.open(
      factory: databaseFactoryFfi,
      path: inMemoryDatabasePath,
    );
    api = FakeApi();
    state = AppState(
      api: api,
      store: store,
      preferences: await SharedPreferences.getInstance(),
      listenPlayer: false,
    );
  });
  tearDown(() async {
    state.dispose();
    await store.close();
  });
  testWidgets('disconnected shell exposes two tabs and scanner without NAS', (
    tester,
  ) async {
    await state.initialize();
    await tester.pumpWidget(SingVerseApp(state: state));
    await tester.pumpAndSettle();
    expect(find.text('曲库'), findsNWidgets(2));
    expect(find.text('我的'), findsOneWidget);
    expect(find.text('扫码点歌'), findsWidgets);
    expect(find.text('录音'), findsNothing);
    await tester.tap(find.text('我的'));
    await tester.pumpAndSettle();
    expect(find.text('常唱收藏'), findsOneWidget);
    expect(find.text('减少透明效果'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('recording notification request opens the singing stage', (
    tester,
  ) async {
    await state.initialize();
    state.current = Song({
      'id': 'notification-fixture',
      'title': '测试录唱',
      'artists': ['样本歌手'],
    });
    state.playbackState = 'PAUSED';
    await tester.pumpWidget(SingVerseApp(state: state));
    await tester.pumpAndSettle();
    state.stageRequests++;
    state.notifyListeners();
    await tester.pumpAndSettle();
    expect(find.text('练唱模式 · 麦克风未开启'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('new take countdown does not reopen an older pending draft', (
    tester,
  ) async {
    await state.initialize();
    state.current = Song({'id': 'countdown-fixture', 'title': '倒数测试'});
    state.playbackState = 'PAUSED';
    state.singing = {'phase': 'PREVIEW', 'takeId': 'old-draft'};
    state.countdown = 3;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          AppState.karaoke,
          (call) async => '{"id":"new-take","name":"新片段","status":"PREVIEW","hasPreview":true,"song":{},"waveform":[]}',
        );
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(AppState.karaoke, null),
    );
    await tester.pumpWidget(SingVerseApp(state: state));
    await tester.pumpAndSettle();
    state.stageRequests++;
    state.notifyListeners();
    await tester.pumpAndSettle();
    state.notifyListeners();
    await tester.pumpAndSettle();
    expect(find.text('留下这次演唱'), findsNothing);
    state.countdown = 0;
    state.singing = {'phase': 'RECORDING', 'takeId': 'new-take'};
    state.notifyListeners();
    await tester.pumpAndSettle();
    expect(find.text('留下这次演唱'), findsNothing);
    state.singing = {'phase': 'PREVIEW', 'takeId': 'new-take'};
    state.notifyListeners();
    await tester.pumpAndSettle();
    expect(find.text('留下这次演唱'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('themes and large text preserve readable shell', (tester) async {
    await state.initialize();
    state.themeMode = ThemeMode.dark;
    state.reduceTransparency = true;
    tester.platformDispatcher.textScaleFactorTestValue = 1.8;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.binding.setSurfaceSize(const Size(390, 844));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(SingVerseApp(state: state));
    await tester.pumpAndSettle();
    final context = tester.element(find.text('我的'));
    expect(Theme.of(context).brightness, Brightness.dark);
    expect(tester.takeException(), isNull);
  });
  testWidgets('connected library and player render in light and dark themes', (
    tester,
  ) async {
    const fontPath = String.fromEnvironment('CAPTURE_FONT');
    if (fontPath.isNotEmpty) {
      await tester.runAsync(() async {
        final loader = FontLoader('Roboto')
          ..addFont(
            File(fontPath)
                .readAsBytes()
                .then((bytes) => ByteData.sublistView(bytes)),
          );
        await loader.load();
        final icons = FontLoader('MaterialIcons')
          ..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'));
        await icons.load();
      });
    }
    await tester.binding.setSurfaceSize(const Size(390, 844));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    api.rows = [
      {
        'id': '1',
        'title': '晴天',
        'artists': ['周杰伦'],
        'format': 'FLAC',
        'mediaType': 'AUDIO',
        'version': 'ORIGINAL',
      },
      {
        'id': '2',
        'title': '富士山下',
        'artists': ['陈奕迅'],
        'format': 'MKV',
        'mediaType': 'VIDEO',
        'version': 'KARAOKE',
      },
      {
        'id': '3',
        'title': '慢慢喜欢你',
        'artists': ['莫文蔚'],
        'format': 'MP4',
        'mediaType': 'VIDEO',
        'version': 'ORIGINAL',
      },
    ];
    state.initialized = true;
    state.authenticated = true;
    state.origin = 'http://nas.local:18080';
    state.instanceId = 'nas-a';
    state.current = Song(api.rows.first);
    state.playbackState = 'PAUSED';
    state.positionMs = 42000;
    state.durationMs = 269000;
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: SingVerseApp(state: state),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('今天，\n想唱哪首？'), findsOneWidget);
    expect(find.text('家庭歌单'), findsOneWidget);
    expect(tester.takeException(), isNull);
    if (const bool.fromEnvironment('CAPTURE_UI')) {
      await tester.runAsync(() async {
        final image =
            await (boundary.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary)
                .toImage(pixelRatio: 2);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        await File('/tmp/singverse-mobile-light.png')
            .writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
    }
    await state.setTheme(ThemeMode.dark);
    await tester.pumpAndSettle();
    if (const bool.fromEnvironment('CAPTURE_UI')) {
      await tester.runAsync(() async {
        final image =
            await (boundary.currentContext!.findRenderObject()!
                    as RenderRepaintBoundary)
                .toImage(pixelRatio: 2);
        final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
        await File('/tmp/singverse-mobile-dark.png')
            .writeAsBytes(bytes!.buffer.asUint8List());
        image.dispose();
      });
    }
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('返回演唱'));
    await tester.pumpAndSettle();
    expect(find.text('练唱模式 · 麦克风未开启'), findsOneWidget);
    expect(find.text('4:29'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.binding.setSurfaceSize(const Size(844, 390));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
  test(
    'known NAS personal data remains available when NAS is offline',
    () async {
      api.origin = 'http://nas.local';
      api.offline = true;
      await state.preferences.setString(
        'server-instance:http://nas.local',
        'nas-a',
      );
      await store.favorite('nas-a', Song({'id': '1', 'title': '晴天'}), true);
      await state.initialize();
      expect(state.authenticated, isFalse);
      expect(state.favorites.single.title, '晴天');
      expect(state.error, contains('NAS 不可达'));
    },
  );
  test(
    'login rate limiting preserves unauthenticated state and retry delay',
    () async {
      api.loginFailure = ApiFailure(429, '请求过于频繁', retryAfter: '60');
      await state.login('example');
      expect(state.authenticated, isFalse);
      expect(state.retryAfter, '60');
      expect(state.error, contains('请求过于频繁'));
    },
  );
  test('MFA grants no native access before successful factor', () async {
    state.origin = 'http://nas.local';
    state.instanceId = 'nas-a';
    api.loginState = 'mfaRequired';
    await state.login('example');
    expect(state.authenticated, isFalse);
    expect(state.mfaRequired, isTrue);
    await state.verify('123456');
    expect(state.authenticated, isTrue);
    expect(state.mfaRequired, isFalse);
  });
  test('late search response cannot replace newer query', () async {
    api.deferred = true;
    final query = SongQuery(state);
    final first = query.load();
    query.query = '新关键词';
    final second = query.load();
    api.pending[1].complete({
      'items': [
        {'id': 'new', 'title': '新结果'},
      ],
      'totalElements': 1,
    });
    await second;
    api.pending[0].complete({
      'items': [
        {'id': 'old', 'title': '旧结果'},
      ],
      'totalElements': 1,
    });
    await first;
    expect(query.items.single.id, 'new');
    query.dispose();
  });
  test('favorites and real play history are isolated by NAS', () async {
    final song = Song({'id': 'same-id', 'title': '晴天'});
    await store.favorite('nas-a', song, true);
    expect(await store.history('nas-a'), isEmpty);
    expect(await store.favorites('nas-b'), isEmpty);
    await store.played('nas-a', song, 12000, newPlay: true);
    expect((await store.history('nas-a')).single.data['positionMs'], 12000);
    await store.favorite('nas-a', song, false);
    expect(await store.favorites('nas-a'), isEmpty);
    expect(await store.history('nas-a'), hasLength(1));
  });
  test('invitation accepts only TV party URLs without login credentials', () {
    final token = 'a' * 43;
    expect(
      TvInvite.parse('http://nas.local:18080/party/#invite=$token').origin,
      'http://nas.local:18080',
    );
    for (final value in [
      'javascript:alert(1)',
      'http://nas.local/admin/#invite=$token',
      'http://admin:secret@nas.local/party/#invite=$token',
      'http://nas.local/party/#invite=bad',
    ]) {
      expect(() => TvInvite.parse(value), throwsFormatException);
    }
    expect(
      () => normalizeOrigin('http://nas.local/admin/'),
      throwsFormatException,
    );
  });
  test('text timelines use media time and preserve timed gaps', () {
    final lrc = parseText('lrc', '[offset:200]\n[00:01.20][00:03.000]一句歌词');
    expect(lrc.map((line) => line.startMs), [1000, 2800]);
    final srt = parseText(
      'srt',
      '1\n00:00:01,000 --> 00:00:02,500\n第一句\n\n2\n00:00:04,000 --> 00:00:05,000\n第二句',
    );
    expect(srt, hasLength(2));
    expect(srt.first.endMs, 2500);
    expect(srt.last.startMs, 4000);
  });
}
