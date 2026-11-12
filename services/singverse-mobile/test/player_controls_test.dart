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
    Brightness brightness = Brightness.light,
  }) async {
    await tester.binding.setSurfaceSize(size);
    tester.platformDispatcher.textScaleFactorTestValue = scale;
    addTearDown(() => tester.binding.setSurfaceSize(null));
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpWidget(
      MaterialApp(
        theme: singVerseTheme(brightness, senior: state.senior),
        home: PlayerPage(state: state),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets(
    'portrait primary actions stay in the safe area before and after content scrolls',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.padding = const FakeViewPadding(top: 24, bottom: 24);
      tester.view.viewPadding = tester.view.padding;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPadding);
      addTearDown(tester.view.resetViewPadding);
      (state.api as FakeApi).textTracks = [
        {
          'format': 'lrc',
          'text': '[00:00.00]晨光落在窗前\n[00:10.00]把心事轻轻唱出来\n[00:20.00]熟悉的旋律还在',
        },
      ];
      state.positionMs = 12000;
      for (final brightness in Brightness.values) {
        for (final senior in [false, true]) {
          state.uiMode = senior ? UiMode.senior : UiMode.standard;
          for (final dimensions in [
            const Size(320, 568),
            const Size(390, 844),
          ]) {
            for (final scale in [1.0, 1.8]) {
              for (final mediaType in ['AUDIO', 'VIDEO']) {
                await tester.pumpWidget(const SizedBox.shrink());
                state.current = Song({
                  ...state.current!.data,
                  'mediaType': mediaType,
                });
                await player(
                  tester,
                  dimensions,
                  scale: scale,
                  brightness: brightness,
                );
                final label =
                    '$brightness senior=$senior $dimensions scale=$scale $mediaType';
                final play = find.byKey(const ValueKey('player-play'));
                final record = find.byKey(const ValueKey('player-record'));
                final initial = tester.getRect(play);
                final current = tester.getRect(
                  find.byKey(const ValueKey('lyrics-line-1')),
                );
                final viewport = tester.getRect(
                  find.byKey(const ValueKey('lyrics-viewport')),
                );
                final content = tester.getRect(
                  find.byKey(const ValueKey('player-content')),
                );
                expect(
                  current.top,
                  greaterThanOrEqualTo(viewport.top - 1),
                  reason: label,
                );
                expect(
                  current.bottom,
                  lessThanOrEqualTo(viewport.bottom + 1),
                  reason: label,
                );
                expect(
                  current.top,
                  greaterThanOrEqualTo(content.top - 1),
                  reason: label,
                );
                expect(
                  current.bottom,
                  lessThanOrEqualTo(content.bottom + 1),
                  reason: label,
                );
                for (final action in [play, record]) {
                  expect(action.hitTestable(), findsOneWidget, reason: label);
                  final rect = tester.getRect(action);
                  expect(rect.top, greaterThanOrEqualTo(24), reason: label);
                  expect(
                    rect.bottom,
                    lessThanOrEqualTo(dimensions.height - 24),
                    reason: label,
                  );
                  expect(rect.left, greaterThanOrEqualTo(0), reason: label);
                  expect(
                    rect.right,
                    lessThanOrEqualTo(dimensions.width),
                    reason: label,
                  );
                  expect(
                    rect.shortestSide,
                    greaterThanOrEqualTo(senior ? 56 : 48),
                    reason: label,
                  );
                }
                await tester.ensureVisible(find.text('更多'));
                await tester.pumpAndSettle();
                expect(tester.getRect(play), initial, reason: label);
                expect(play.hitTestable(), findsOneWidget, reason: label);
                expect(record.hitTestable(), findsOneWidget, reason: label);
                expect(tester.takeException(), isNull, reason: label);
              }
            }
          }
        }
      }
      expect(native.commands, isEmpty);
      expect(native.calls, isEmpty);
    },
  );

  testWidgets(
    'real phone metrics keep compact lyrics and a centered recording pill',
    (tester) async {
      const logicalSize = Size(360, 800);
      tester.view.devicePixelRatio = 3.5;
      tester.view.physicalSize = const Size(1260, 2800);
      tester.view.padding = const FakeViewPadding(top: 140);
      tester.view.viewPadding = tester.view.padding;
      await tester.binding.setSurfaceSize(logicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetPadding);
      addTearDown(tester.view.resetViewPadding);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      addTearDown(() => tester.binding.setSurfaceSize(null));
      (state.api as FakeApi).textTracks = [
        {
          'format': 'lrc',
          'text': '[00:00.00]晨光落在窗前\n[00:10.00]轻轻唱出心声\n[00:20.00]熟悉的旋律还在',
        },
      ];
      state.positionMs = 12000;
      for (final brightness in Brightness.values) {
        for (final senior in [false, true]) {
          for (final scale in [1.0, 1.8]) {
            await tester.pumpWidget(const SizedBox.shrink());
            state.uiMode = senior ? UiMode.senior : UiMode.standard;
            tester.platformDispatcher.textScaleFactorTestValue = scale;
            await tester.pumpWidget(
              MaterialApp(
                theme: singVerseTheme(brightness, senior: senior),
                home: PlayerPage(state: state),
              ),
            );
            await tester.pumpAndSettle();
            final reason = '$brightness senior=$senior scale=$scale';
            final page = tester.element(find.byType(PlayerPage));
            expect(MediaQuery.sizeOf(page), logicalSize, reason: reason);
            expect(MediaQuery.devicePixelRatioOf(page), 3.5, reason: reason);
            expect(MediaQuery.paddingOf(page).top, 40, reason: reason);

            final active = find.byKey(const ValueKey('lyrics-line-1'));
            final inactive = find.byKey(const ValueKey('lyrics-line-0'));
            expect(
              tester.widget<Text>(active).style?.fontSize,
              senior ? 32 : 18,
              reason: reason,
            );
            expect(
              tester.widget<Text>(inactive).style?.fontSize,
              senior ? 23 : 15,
              reason: reason,
            );
            expect(
              MediaQuery.textScalerOf(tester.element(active)).scale(18),
              closeTo(18 * scale, .001),
              reason: reason,
            );
            final lyricTarget = find.ancestor(
              of: active,
              matching: find.byType(InkWell),
            );
            expect(lyricTarget, findsOneWidget, reason: reason);
            expect(
              tester.getSize(lyricTarget).height,
              greaterThanOrEqualTo(senior ? 56 : 48),
              reason: reason,
            );

            final play = find.byKey(const ValueKey('player-play'));
            final record = find.byKey(const ValueKey('player-record'));
            final playRect = tester.getRect(play);
            final recordRect = tester.getRect(record);
            expect(play.hitTestable(), findsOneWidget, reason: reason);
            expect(record.hitTestable(), findsOneWidget, reason: reason);
            expect(
              recordRect.top,
              greaterThanOrEqualTo(playRect.bottom + 16 - .001),
              reason: reason,
            );
            expect(recordRect.left, greaterThanOrEqualTo(20), reason: reason);
            expect(recordRect.right, lessThanOrEqualTo(340), reason: reason);
            expect(recordRect.width, lessThan(320), reason: reason);
            expect(
              recordRect.width,
              greaterThanOrEqualTo(senior ? 184 : 160),
              reason: reason,
            );
            expect(recordRect.center.dx, closeTo(180, .001), reason: reason);
            expect(playRect.center.dx, closeTo(180, .001), reason: reason);
            for (final rect in [playRect, recordRect]) {
              expect(rect.top, greaterThanOrEqualTo(40), reason: reason);
              expect(rect.bottom, lessThanOrEqualTo(800), reason: reason);
              expect(
                rect.shortestSide,
                greaterThanOrEqualTo(senior ? 56 : 48),
                reason: reason,
              );
            }
            expect(tester.takeException(), isNull, reason: reason);
          }
        }
      }
      expect(native.commands, isEmpty);
      expect(native.calls, isEmpty);
    },
  );

  testWidgets(
    'player follows portrait themes and preserves playback on the dark landscape stage',
    (tester) async {
      state.positionMs = 63000;
      for (final brightness in Brightness.values) {
        await player(tester, const Size(390, 844), brightness: brightness);
        final primary = find.byKey(const ValueKey('player-primary-controls'));
        expect(Theme.of(tester.element(primary)).brightness, brightness);
        await tester.binding.setSurfaceSize(const Size(844, 390));
        await tester.pumpAndSettle();
        expect(
          Theme.of(
            tester.element(
              find.byKey(const ValueKey('player-landscape-surface')),
            ),
          ).brightness,
          Brightness.dark,
        );
        expect(state.positionMs, 63000);
        expect(state.audioRole, 'backing');
        expect(state.isPlaying, true);
        expect(tester.takeException(), isNull);
      }
      expect(native.commands, isEmpty);
      expect(native.calls, isEmpty);
    },
  );

  testWidgets(
    'selected practice range is announced on the timeline and its lyric boundaries',
    (tester) async {
      final semantics = tester.ensureSemantics();
      try {
        (state.api as FakeApi).textTracks = [
          {
            'format': 'lrc',
            'text': '[00:00.00]晨光落在窗前\n[00:10.00]把心事轻轻唱出来\n[00:20.00]熟悉的旋律还在',
          },
        ];
        state
          ..positionMs = 12000
          ..rangeA = 10000
          ..rangeB = 20000;
        await player(tester, const Size(390, 844));
        final timeline = tester.widget<Slider>(
          find.byKey(const ValueKey('player-progress')),
        );
        expect(
          timeline.semanticFormatterCallback!(12000),
          contains('选段 0:10 至 0:20'),
        );
        expect(find.bySemanticsLabel(RegExp('选段起止句')), findsOneWidget);
        await tester.tap(find.text('把心事轻轻唱出来'));
        await tester.pumpAndSettle();
        expect(native.commands.single, {'action': 'seek', 'value': 10000});
        expect(state.rangeA, 10000);
        expect(state.rangeB, 20000);
        expect(tester.takeException(), isNull);
      } finally {
        semantics.dispose();
      }
    },
  );

  testWidgets('explicit restart is a new playback intent rather than a seek', (
    tester,
  ) async {
    await player(tester, const Size(390, 844));
    final restart = find.text('重唱');
    await tester.ensureVisible(restart);
    await tester.tap(restart);
    await tester.pumpAndSettle();
    expect(native.commands, [
      {'action': 'restart', 'value': 0},
      {'action': 'play', 'value': null},
    ]);
  });

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

  for (final mapping in [
    (false, false),
    (false, true),
    (true, false),
    (true, true),
  ]) {
    for (final scenario in [
      (false, const Size(390, 844), 1.0),
      (true, const Size(390, 844), 1.0),
      (false, const Size(844, 390), 1.0),
      (true, const Size(320, 568), 1.8),
    ]) {
      testWidgets(
        'audio roles remain visible and honor mapping $mapping at $scenario',
        (tester) async {
          state
            ..uiMode = scenario.$1 ? UiMode.senior : UiMode.standard
            ..backingMapped = mapping.$1
            ..originalMapped = mapping.$2
            ..audioRole = '';
          await player(tester, scenario.$2, scale: scenario.$3);
          expect(find.text('选择声音'), findsNothing);
          for (final role in [
            ('original', '原唱', mapping.$2),
            ('backing', '伴奏', mapping.$1),
          ]) {
            final choice = find.byKey(ValueKey('player-role-${role.$1}'));
            expect(choice, findsOneWidget);
            expect(
              find.descendant(of: choice, matching: find.text(role.$2)),
              findsOneWidget,
            );
            await tester.ensureVisible(choice);
            await tester.pumpAndSettle();
            expect(choice.hitTestable(), findsOneWidget);
            expect(
              tester.getSize(choice).height,
              greaterThanOrEqualTo(state.senior ? 56 : 48),
            );
            final button = tester.widget<TextButton>(choice);
            final commandsBefore = native.commands.length;
            final previousRole = state.audioRole;
            if (role.$3) {
              expect(button.onPressed, isNotNull);
              await tester.tap(choice);
              await tester.pumpAndSettle();
              expect(native.commands.length, commandsBefore + 1);
              expect(native.commands.last, {
                'action': 'useRole',
                'value': role.$1,
              });
              expect(state.audioRole, role.$1);
            } else {
              expect(button.onPressed, isNull);
              await tester.tap(choice, warnIfMissed: false);
              await tester.pumpAndSettle();
              expect(native.commands.length, commandsBefore);
              expect(state.audioRole, previousRole);
            }
          }
          expect(native.calls, isEmpty);
          expect(tester.takeException(), isNull);
        },
      );
    }
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

  testWidgets(
    'unmapped roles stay disabled while more retains audio settings',
    (tester) async {
      state
        ..uiMode = UiMode.senior
        ..backingMapped = false
        ..originalMapped = false
        ..audioRole = '';
      await player(tester, const Size(320, 568), scale: 1.8);
      for (final role in ['original', 'backing']) {
        final choice = find.byKey(ValueKey('player-role-$role'));
        expect(choice, findsOneWidget);
        await tester.ensureVisible(choice);
        await tester.pumpAndSettle();
        expect(tester.widget<TextButton>(choice).onPressed, isNull);
        await tester.tap(choice, warnIfMissed: false);
        await tester.pumpAndSettle();
      }
      expect(native.commands, isEmpty);
      expect(find.text('选择声音'), findsNothing);
      final more = find.text('更多');
      await tester.ensureVisible(more);
      await tester.pumpAndSettle();
      expect(more.hitTestable(), findsOneWidget);
      await tester.tap(more);
      await tester.pumpAndSettle();
      final settings = find.widgetWithText(ListTile, '音轨与播放设置');
      await tester.ensureVisible(settings);
      await tester.pumpAndSettle();
      expect(settings.hitTestable(), findsOneWidget);
      await tester.tap(settings);
      await tester.pumpAndSettle();
      final track = find.text('音轨 1');
      await tester.ensureVisible(track);
      await tester.pumpAndSettle();
      await tester.tap(track);
      await tester.pumpAndSettle();
      expect(native.commands.last['action'], 'track');
      expect(native.commands.last['value'], '0:0');
      expect(native.commands.where((c) => c['action'] == 'useRole'), isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

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
