import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:singverse_mobile/features/player/player_page.dart';
import 'package:singverse_mobile/main.dart' as app;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('installed app large fonts, long names, native video and sheets', (
    tester,
  ) async {
    await app.main();
    Future<void> frames([int count = 10]) async {
      for (var i = 0; i < count; i++) {
        await tester.pump(const Duration(milliseconds: 200));
      }
    }

    Future<void> wait(
      bool Function() condition,
      String reason, {
      String Function()? details,
    }) async {
      final end = DateTime.now().add(const Duration(seconds: 30));
      while (!condition() && DateTime.now().isBefore(end)) {
        await frames(1);
      }
      expect(condition(), true, reason: '$reason ${details?.call() ?? ''}');
    }

    Future<void> tap(String text) async {
      final candidates = find.byWidgetPredicate(
        (w) => w is Text && w.data == text,
      );
      if (candidates.evaluate().isEmpty &&
          ['调音', '减少透明效果', '长辈模式'].contains(text)) {
        await tester.scrollUntilVisible(
          candidates,
          240,
          scrollable: find.byType(Scrollable).first,
          maxScrolls: 20,
        );
      }
      await wait(() => candidates.evaluate().isNotEmpty, text);
      final landscapeAction =
          text == '调音' &&
          find
              .byKey(const ValueKey('player-landscape-surface'))
              .evaluate()
              .isNotEmpty;
      if (!landscapeAction) {
        await tester.ensureVisible(candidates.last);
        await frames(1);
      }
      expect(candidates.last.hitTestable(), findsOneWidget);
      await tester.tap(candidates.last.hitTestable());
      await frames(4);
    }

    Future<void> event(String value) async {
      await frames();
      // ignore: avoid_print
      print(value);
      await frames();
    }

    Future<void> revealLandscapeControls() async {
      final surface = find.byKey(const ValueKey('player-landscape-surface'));
      expect(surface, findsOneWidget);
      if (find.byTooltip('退出横屏').hitTestable().evaluate().isEmpty) {
        await tester.tapAt(tester.getTopLeft(surface) + const Offset(8, 8));
        await tester.pump();
      }
      expect(find.byTooltip('退出横屏').hitTestable(), findsOneWidget);
    }

    await frames();
    final state = tester
        .widget<app.SingVerseApp>(find.byType(app.SingVerseApp))
        .state;
    await wait(() => state.initialized, 'initialized');
    await state.connect(
      const String.fromEnvironment(
        'TEST_NAS',
        defaultValue: 'http://10.0.2.2:18181',
      ),
    );
    if (!state.authenticated) {
      await state.login('admin');
    }
    expect(state.authenticated, true, reason: 'isolated NAS authenticated');
    await frames();
    await wait(
      () => find.byType(TextField).evaluate().isNotEmpty,
      'library search ready',
    );
    final library =
        await state.get('/api/v1/mobile/library/songs?size=100') as Map;
    final song = (library['items'] as List).cast<Map>().firstWhere(
      (s) =>
          s['mediaType'] == 'VIDEO' &&
          (s['title'] as String).startsWith('视觉验收'),
    );
    final longTitle = song['title'] as String;
    try {
      await tester.enterText(find.byType(TextField).first, '视觉验收');
      await frames();
      await tap(longTitle);
      await event('KARAOKE_SCREEN:21-long-title-player');
      await wait(
        () =>
            ['PAUSED', 'PLAYING'].contains(state.playbackState) &&
            state.tracks.any((t) => t['type'] == 'audio'),
        'decoded video ready',
        details: () =>
            '${state.playbackState}; ${state.playbackError}; tracks=${state.tracks.length}',
      );
      await event('KARAOKE_LARGE_FONT');
      await event('KARAOKE_SCREEN:22-large-font-player');
      await wait(
        () => state.isPlaying && state.positionMs > 1000,
        'video actually plays',
      );
      await state.command('seek', 18000);
      await wait(
        () => state.isPlaying && state.positionMs >= 18000,
        'wrapped lyric position prepared',
      );
      await state.command('pause');
      await frames();
      final lyrics = tester.widget<LyricsView>(find.byType(LyricsView));
      final active = lyrics.lines.lastIndexWhere(
        (line) => line.startMs <= lyrics.positionMs,
      );
      expect(lyrics.lines[active].text, '每一次开口都值得期待');
      final viewport = tester.getRect(
        find.byKey(const ValueKey('lyrics-viewport')),
      );
      final text = tester.getRect(find.byKey(ValueKey('lyrics-line-$active')));
      expect(text.height, lessThanOrEqualTo(viewport.height));
      expect(text.top, greaterThanOrEqualTo(viewport.top - 1));
      expect(text.bottom, lessThanOrEqualTo(viewport.bottom + 1));
      await event('KARAOKE_SCREEN:23-large-font-stage');
      expect(tester.takeException(), isNull);
      await state.command('play');
      await event('KARAOKE_NORMAL_FONT');
      await tester.ensureVisible(find.byTooltip('全屏视频'));
      await frames(1);
      await tester.tap(find.byTooltip('全屏视频'));
      await frames();
      await event('KARAOKE_LANDSCAPE');
      await event('KARAOKE_SCREEN:24-landscape-video');
      await revealLandscapeControls();
      await tap('调音');
      expect(find.byKey(const ValueKey('pitch-control')), findsOneWidget);
      await event('KARAOKE_SCREEN:25-landscape-sheet');
      await tester.binding.handlePopRoute();
      await frames(2);
      await revealLandscapeControls();
      await tester.tap(find.byTooltip('退出横屏'));
      await event('KARAOKE_PORTRAIT');
      await state.command('pause');
      final navigator = tester.state<NavigatorState>(
        find.byType(Navigator).first,
      );
      navigator.popUntil((route) => route.isFirst);
      await frames();
      await tester.tap(find.byKey(const Key('open-settings')));
      await frames();
      await tap('减少透明效果');
      expect(state.reduceTransparency, true);
      await event('KARAOKE_SCREEN:26-reduced-transparency');
      await tap('减少透明效果');
      expect(state.reduceTransparency, false);
      await tap('长辈模式');
      expect(state.senior, true);
      await event('KARAOKE_SCREEN:27-senior-settings');
      await tester.binding.handlePopRoute();
      await frames();
      await event('KARAOKE_SCREEN:28-senior-navigation');
      await tester.tap(find.byKey(const Key('nav-songs')));
      await frames();
      await event('KARAOKE_SCREEN:29-senior-library');
      expect(tester.takeException(), isNull);
      // ignore: avoid_print
      print('KARAOKE_UI_FLOW_PASS:visual');
    } finally {
      await state.command('pause');
    }
  });
}
