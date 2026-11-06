import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:singverse_mobile/app/design.dart';
import 'package:singverse_mobile/core/app_state.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:singverse_mobile/core/models.dart';
import 'package:singverse_mobile/features/player/player_page.dart';
import 'package:singverse_mobile/features/singing/actions.dart';
import 'package:singverse_mobile/features/tv_control/tv_page.dart';

import 'mobile_redesign_test.dart' show NativeFixture;
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
    state =
        AppState(
            api: FakeApi()..loggedIn = true,
            store: store,
            preferences: await SharedPreferences.getInstance(),
            listenPlayer: false,
          )
          ..instanceId = 'nas-a'
          ..playbackInstanceId = 'nas-a'
          ..current = Song({'id': '1', 'title': '测试歌曲', 'mediaType': 'AUDIO'})
          ..playbackState = 'PLAYING'
          ..isPlaying = true
          ..durationMs = 240000
          ..backingMapped = true
          ..originalMapped = true
          ..audioRole = 'backing'
          ..tracks = [
            {'id': '0:0', 'type': 'audio', 'selected': true, 'channels': 2},
          ];
    native = NativeFixture(state)..install();
  });

  tearDown(() async {
    native.uninstall();
    state.dispose();
    await store.close();
  });

  Future<void> player(
    WidgetTester tester,
    Size size, {
    double scale = 1,
  }) async {
    await tester.binding.setSurfaceSize(size);
    tester.platformDispatcher.textScaleFactorTestValue = scale;
    addTearDown(() => tester.binding.setSurfaceSize(null));
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpWidget(MaterialApp(home: PlayerPage(state: state)));
    await tester.pumpAndSettle();
  }

  for (final scenario in [
    (false, const Size(390, 844), 1.0),
    (true, const Size(390, 844), 1.0),
    (false, const Size(844, 390), 1.0),
    (true, const Size(320, 568), 1.8),
  ]) {
    testWidgets('direct audio roles and playback work at $scenario', (
      tester,
    ) async {
      state.uiMode = scenario.$1 ? UiMode.senior : UiMode.standard;
      final semantics = tester.ensureSemantics();
      try {
        await player(tester, scenario.$2, scale: scenario.$3);

        final role = find.byKey(const ValueKey('player-role-original'));
        await tester.ensureVisible(role);
        await tester.tap(role);
        await tester.pumpAndSettle();
        expect(native.commands.last['action'], 'useRole');
        expect(native.commands.last['value'], 'original');
        expect(state.audioRole, 'original');
        expect(
          tester.getSize(role).height,
          greaterThanOrEqualTo(state.senior ? 56 : 48),
        );

        final play = find.byKey(const ValueKey('player-play'));
        await tester.ensureVisible(play);
        await tester.pumpAndSettle();
        final pause = find.bySemanticsLabel('暂停音乐');
        expect(pause, findsOneWidget);
        final playSemantics = tester.getSemantics(play).getSemanticsData();
        expect(playSemantics.label, '暂停音乐');
        expect(playSemantics.flagsCollection.isButton, true);
        expect(playSemantics.hasAction(SemanticsAction.tap), true);
        await tester.tap(play);
        await tester.pumpAndSettle();
        expect(native.commands.last['action'], 'pause');
        expect(find.bySemanticsLabel('继续播放'), findsOneWidget);
        expect(tester.takeException(), isNull);
      } finally {
        semantics.dispose();
      }
    });
  }

  testWidgets('landscape controls hide and return without changing playback', (
    tester,
  ) async {
    state.positionMs = 63000;
    await player(tester, const Size(844, 390));
    final play = find.byKey(const ValueKey('player-play'));
    expect(play.hitTestable(), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 3600));
    await tester.pump(const Duration(milliseconds: 200));
    expect(play.hitTestable(), findsNothing);
    expect(find.byKey(const ValueKey('landscape-controls')), findsOneWidget);
    await tester.tapAt(const Offset(420, 130));
    await tester.pumpAndSettle();
    expect(play.hitTestable(), findsOneWidget);
    expect(state.positionMs, 63000);
    expect(state.audioRole, 'backing');
    expect(state.isPlaying, true);
    expect(native.commands, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets('pause, accessibility and keyboard retain landscape controls', (
    tester,
  ) async {
    await player(tester, const Size(844, 390));
    await tester.tap(find.byKey(const ValueKey('player-play')));
    await tester.pump(const Duration(seconds: 8));
    expect(
      find.byKey(const ValueKey('player-play')).hitTestable(),
      findsOneWidget,
    );
    state.isPlaying = true;
    native.emit();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump(const Duration(seconds: 8));
    expect(
      find.byKey(const ValueKey('player-play')).hitTestable(),
      findsOneWidget,
    );
    tester.platformDispatcher.accessibilityFeaturesTestValue =
        const FakeAccessibilityFeatures(accessibleNavigation: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    await tester.tapAt(const Offset(420, 130));
    await tester.pump(const Duration(seconds: 8));
    expect(
      find.byKey(const ValueKey('player-play')).hitTestable(),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('holding the timeline or an open sheet prevents auto hide', (
    tester,
  ) async {
    await player(tester, const Size(844, 390));
    final slider = find.byKey(const ValueKey('player-progress'));
    final gesture = await tester.startGesture(tester.getCenter(slider));
    await gesture.moveBy(const Offset(30, 0));
    await tester.pump(const Duration(seconds: 6));
    expect(
      find.byKey(const ValueKey('player-play')).hitTestable(),
      findsOneWidget,
    );
    await gesture.up();
    await tester.pumpAndSettle();
    await tester.tap(find.text('调音'));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 6));
    expect(find.text('麦克风试音'), findsOneWidget);
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('player-play')).hitTestable(),
      findsOneWidget,
    );
    await tester.pump(const Duration(seconds: 4));
    expect(
      find.byKey(const ValueKey('player-play')).hitTestable(),
      findsNothing,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'hidden controls retain recording clock and stop across rotation',
    (tester) async {
      state.singing = {
        'phase': 'RECORDING',
        'takeId': 'active',
        'recordedMs': 65000,
      };
      state.positionMs = 65000;
      await player(tester, const Size(844, 390));
      await tester.pump(const Duration(seconds: 4));
      await tester.pump(const Duration(milliseconds: 200));
      expect(
        find.byKey(const ValueKey('player-play')).hitTestable(),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('player-record')).hitTestable(),
        findsOneWidget,
      );
      expect(find.text('正在录音 · 1:05').hitTestable(), findsOneWidget);
      state.singing = {...state.singing, 'recordedMs': 69000};
      native.emit();
      await tester.pump();
      expect(find.text('正在录音 · 1:09').hitTestable(), findsOneWidget);
      await tester.binding.setSurfaceSize(const Size(390, 844));
      await tester.pumpAndSettle();
      expect(state.recording, true);
      expect(state.positionMs, 65000);
      expect(state.audioRole, 'backing');
      expect(find.byKey(const ValueKey('player-record')), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('senior landscape has extended timeout and readable large type', (
    tester,
  ) async {
    state.uiMode = UiMode.senior;
    await player(tester, const Size(640, 360), scale: 1.5);
    await tester.pump(const Duration(seconds: 4));
    final play = find.byKey(const ValueKey('player-play'));
    expect(play.hitTestable(), findsOneWidget);
    expect(tester.getSize(play).height, greaterThanOrEqualTo(56));
    await tester.pump(const Duration(seconds: 3));
    expect(play.hitTestable(), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'short large-text landscape reserves readable lyrics and full video canvas',
    (tester) async {
      (state.api as FakeApi).textTracks = [
        {'format': 'lrc', 'text': '[00:00.00]这是一句可以完整换行的长歌词\n[00:10.00]下一句'},
      ];
      state.uiMode = UiMode.senior;
      await player(tester, const Size(568, 320), scale: 1.8);
      final viewport = find.byKey(const ValueKey('lyrics-viewport'));
      expect(tester.getSize(viewport).height, greaterThanOrEqualTo(80));
      await tester.ensureVisible(find.byKey(const ValueKey('player-record')));
      expect(
        find.byKey(const ValueKey('player-record')).hitTestable(),
        findsOneWidget,
      );
      state.current = Song({...state.current!.data, 'mediaType': 'VIDEO'});
      native.emit();
      await tester.pumpAndSettle();
      expect(
        tester.getSize(find.byKey(const ValueKey('player-video'))),
        const Size(568, 320),
      );
      expect(tester.getSize(viewport).height, greaterThanOrEqualTo(80));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'countdown holds controls and cancellation never starts the microphone',
    (tester) async {
      await player(tester, const Size(844, 390));
      state.countdown = 3;
      native.emit();
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 8));
      expect(find.text('倒数结束后开始录音'), findsOneWidget);
      expect(find.text('取消录音').hitTestable(), findsOneWidget);
      await tester.tap(find.text('取消录音'));
      await tester.pumpAndSettle();
      expect(state.countdown, 0);
      expect(native.commands.single['action'], 'cancelCountdown');
      expect(state.recording, false);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'large senior role buttons keep two-character labels on one line',
    (tester) async {
      state.uiMode = UiMode.senior;
      await player(tester, const Size(844, 390), scale: 1.8);
      final label = find.text('原唱');
      final choice = find.byKey(const ValueKey('player-role-original'));
      expect(
        tester.getSize(choice).width,
        greaterThanOrEqualTo(19 * 1.8 * 2 + 24),
      );
      expect(tester.getSize(label).height, lessThan(19 * 1.8 * 1.5));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'landscape video shows only the centered current lyric with seek enabled',
    (tester) async {
      (state.api as FakeApi).textTracks = [
        {
          'format': 'lrc',
          'text': '[00:00.00]这是一句很长的歌词仍应完整换行显示\n[00:10.00]下一句歌词',
        },
      ];
      state.current = Song({...state.current!.data, 'mediaType': 'VIDEO'});
      await player(tester, const Size(844, 390));
      expect(find.text('这是一句很长的歌词仍应完整换行显示'), findsOneWidget);
      expect(find.text('下一句歌词'), findsNothing);
      final current = tester.widget<Text>(
        find.byKey(const ValueKey('lyrics-line-0')),
      );
      expect(current.textAlign, TextAlign.center);
      expect(current.maxLines, isNull);
      state.positionMs = 14000;
      native.emit();
      await tester.pumpAndSettle();
      expect(find.text('这是一句很长的歌词仍应完整换行显示'), findsNothing);
      await tester.tap(find.text('下一句歌词'));
      await tester.pumpAndSettle();
      expect(native.commands.last, {'action': 'seek', 'value': 10000});
      await tester.pump(const Duration(seconds: 4));
      expect(
        find.byKey(const ValueKey('player-play')).hitTestable(),
        findsNothing,
      );
      final surface = find.byKey(const ValueKey('player-landscape-surface'));
      await tester.tapAt(tester.getTopLeft(surface) + const Offset(8, 8));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('player-play')).hitTestable(),
        findsOneWidget,
      );
      expect(native.commands, hasLength(1));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'blank video lyric viewport toggles controls while the current sentence seeks',
    (tester) async {
      (state.api as FakeApi).textTracks = [
        {'format': 'lrc', 'text': '[00:00.00]第一句\n[00:10.00]当前一句'},
      ];
      state.current = Song({...state.current!.data, 'mediaType': 'VIDEO'});
      state.positionMs = 14000;
      await player(tester, const Size(844, 390));
      await tester.pump(const Duration(seconds: 4));
      final play = find.byKey(const ValueKey('player-play'));
      expect(play.hitTestable(), findsNothing);
      final viewport = find.byKey(const ValueKey('lyrics-viewport'));
      Offset blankPoint() {
        final rect = tester.getRect(viewport);
        return Offset(rect.center.dx, rect.top + rect.height * .25);
      }

      await tester.tapAt(blankPoint());
      await tester.pumpAndSettle();
      expect(play.hitTestable(), findsOneWidget);
      expect(native.commands, isEmpty);
      expect(state.positionMs, 14000);
      await tester.tap(find.text('当前一句'));
      await tester.pumpAndSettle();
      expect(native.commands.single, {'action': 'seek', 'value': 10000});
      expect(play.hitTestable(), findsOneWidget);
      await tester.tapAt(blankPoint());
      await tester.pumpAndSettle();
      expect(play.hitTestable(), findsNothing);
      expect(native.commands, hasLength(1));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('unconfirmed audio opens choices directly at large senior type', (
    tester,
  ) async {
    state
      ..uiMode = UiMode.senior
      ..backingMapped = false
      ..originalMapped = false
      ..audioRole = '';
    await player(tester, const Size(320, 568), scale: 1.8);
    final role = find.byKey(const ValueKey('player-audio-settings'));
    await tester.ensureVisible(role);
    await tester.tap(role);
    await tester.pumpAndSettle();
    expect(find.text('音轨与播放设置'), findsOneWidget);
    await tester.tap(find.text('音轨 1'));
    await tester.pumpAndSettle();
    expect(native.commands.last['action'], 'track');
    expect(native.commands.last['value'], '0:0');
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'large recording timer remains readable while source controls lock',
    (tester) async {
      state
        ..uiMode = UiMode.senior
        ..singing = {
          'phase': 'RECORDING',
          'takeId': 'active',
          'recordedMs': 65000,
        };
      await player(tester, const Size(320, 568), scale: 1.8);
      final timer = find.byKey(const ValueKey('player-status'));
      await tester.ensureVisible(timer);
      expect(find.textContaining('正在录音 · 1:05'), findsWidgets);
      expect(tester.getSize(timer).height, greaterThan(40));
      for (final key in ['player-role-original', 'player-role-backing']) {
        final role = find.byKey(ValueKey(key));
        await tester.ensureVisible(role);
        expect(tester.widget<TextButton>(role).onPressed, isNull);
      }
      expect(
        tester
            .widget<FilledButton>(find.byKey(const ValueKey('player-play')))
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<FilledButton>(find.byKey(const ValueKey('player-record')))
            .onPressed,
        isNotNull,
      );
      expect(native.commands, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('microphone interruption stays visible and can be retried', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: singVerseTheme(Brightness.light),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => singingSettings(context, state),
              child: const Text('调音'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('调音'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('麦克风试音'));
    await tester.pumpAndSettle();
    state.singing = {'phase': 'IDLE', 'error': '麦克风未提供音频，请检查输入设备'};
    state.notifyListeners();
    await tester.pumpAndSettle();
    expect(find.text('麦克风未提供音频，请检查输入设备'), findsOneWidget);
    await tester.tap(find.text('开始试音'));
    await tester.pumpAndSettle();
    expect(native.calls, containsAllInOrder(['permission', 'test']));
    expect(tester.takeException(), isNull);
  });

  Future<void> television(
    WidgetTester tester,
    void Function(bool) complete,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: singVerseTheme(Brightness.light),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => uiTask(context, () async {
                complete(await prepareTvPlayback(context, state));
              }),
              child: const Text('进入电视'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('进入电视'));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'cancelling television entry keeps the active recording playing',
    (tester) async {
      state.singing = {'phase': 'RECORDING', 'takeId': 'active'};
      bool? ready;
      await television(tester, (value) => ready = value);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(ready, false);
      expect(state.recording, true);
      expect(state.isPlaying, true);
      expect(native.commands, isEmpty);
      expect(native.calls, isEmpty);
    },
  );

  testWidgets(
    'television entry retains the stable take before pausing playback',
    (tester) async {
      state.singing = {'phase': 'RECORDING', 'takeId': 'active'};
      bool? ready;
      await television(tester, (value) => ready = value);
      await tester.tap(find.text('结束并保留草稿'));
      await tester.pumpAndSettle();
      expect(ready, true);
      expect(state.takeId, 'active');
      expect(state.singingPhase, 'PREVIEW');
      expect(native.calls, ['end']);
      expect(native.commands.single['action'], 'pause');
      expect(state.isPlaying, false);
    },
  );

  testWidgets('television entry cancels countdown before pausing', (
    tester,
  ) async {
    state.countdown = 3;
    bool? ready;
    await television(tester, (value) => ready = value);
    await tester.tap(find.text('结束并保留草稿'));
    await tester.pumpAndSettle();
    expect(ready, true);
    expect(state.countdown, 0);
    expect(native.commands.map((c) => c['action']), [
      'cancelCountdown',
      'pause',
    ]);
    expect(native.calls, isEmpty);
  });

  void hlsRecordingPreparation(Completer<void> ready, {String? error}) {
    state
      ..mode = 'HLS'
      ..positionMs = 33000
      ..pitch = 2
      ..speed = .9
      ..channelMode = 'LEFT';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(AppState.player, (call) async {
          if (call.method == 'command') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            native.commands.add(args);
            if (args['action'] == 'seek') {
              state.playbackState = 'PREPARING';
              native.emit();
            } else if (args['action'] == 'record') {
              state
                ..playbackState = 'PAUSED'
                ..positionMs = 0
                ..countdown = 3;
              native.emit();
            }
          }
          if (call.method == 'snapshot') {
            return {
              'song': jsonEncode(state.current!.data),
              'instanceId': state.playbackInstanceId,
              'state': !ready.isCompleted
                  ? 'PREPARING'
                  : error == null
                  ? 'PAUSED'
                  : 'ERROR',
              'message': error ?? '',
            };
          }
          return null;
        });
  }

  testWidgets('HLS recording waits for readiness and locks repeated starts', (
    tester,
  ) async {
    final ready = Completer<void>();
    hlsRecordingPreparation(ready);
    await player(tester, const Size(390, 844));
    final record = find.byKey(const ValueKey('player-record'));
    await tester.ensureVisible(record);
    await tester.tap(record);
    await tester.pump(const Duration(milliseconds: 50));
    expect(native.commands, [
      {'action': 'seek', 'value': 0},
    ]);
    expect(tester.widget<FilledButton>(record).onPressed, isNull);
    await tester.tap(record);
    await tester.pump(const Duration(milliseconds: 150));
    expect(native.commands, hasLength(1));

    ready.complete();
    await tester.pump(const Duration(milliseconds: 150));
    await tester.pumpAndSettle();
    expect(native.commands, [
      {'action': 'seek', 'value': 0},
      {'action': 'record', 'value': true},
    ]);
    expect(state.countdown, 3);
    expect(state.pitch, 2);
    expect(state.speed, .9);
    expect(state.channelMode, 'LEFT');
    expect(tester.takeException(), isNull);
  });

  testWidgets('HLS preparation failure stays visible and never records', (
    tester,
  ) async {
    final ready = Completer<void>();
    hlsRecordingPreparation(ready, error: '转码资源准备失败');
    await player(tester, const Size(390, 844));
    final record = find.byKey(const ValueKey('player-record'));
    await tester.ensureVisible(record);
    await tester.tap(record);
    await tester.pump(const Duration(milliseconds: 50));
    ready.complete();
    state
      ..playbackState = 'ERROR'
      ..playbackError = '转码资源准备失败';
    native.emit();
    await tester.pump(const Duration(milliseconds: 150));
    await tester.pumpAndSettle();
    expect(native.commands, [
      {'action': 'seek', 'value': 0},
    ]);
    expect(state.countdown, 0);
    expect(find.text('转码资源准备失败'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
