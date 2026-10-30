import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:singverse_mobile/main.dart' as app;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'installed app large fonts, long names, native video and sheets',
    (tester) async {
      await app.main();
      Future<void> frames([int count = 10]) async {
        for (var i = 0; i < count; i++) {
          await tester.pump(const Duration(milliseconds: 200));
        }
      }

      Future<void> wait(bool Function() condition, String reason) async {
        final end = DateTime.now().add(const Duration(seconds: 30));
        while (!condition() && DateTime.now().isBefore(end)) {
          await frames(1);
        }
        expect(condition(), true, reason: reason);
      }

      Future<void> tap(String text) async {
        final candidates = find.byWidgetPredicate(
          (w) => w is Text && w.data == text,
        );
        if (candidates.evaluate().isEmpty &&
            ['仅练唱 · 不开启麦克风', '调音', '减少透明效果'].contains(text)) {
          await tester.scrollUntilVisible(
            candidates,
            240,
            scrollable: find.byType(Scrollable).first,
            maxScrolls: 20,
          );
        }
        await wait(() => candidates.evaluate().isNotEmpty, text);
        await tester.ensureVisible(candidates.last);
        await frames(1);
        await tester.tap(candidates.last);
        await frames(4);
      }

      Future<void> event(String value) async {
        // ignore: avoid_print
        print(value);
        await frames();
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
      final library =
          await state.get('/api/v1/mobile/library/songs?size=100') as Map;
      final song = (library['items'] as List).cast<Map>().firstWhere(
        (s) =>
            s['mediaType'] == 'VIDEO' &&
            (s['title'] as String).startsWith('视觉验收'),
      );
      final longTitle = song['title'] as String;
      try {
        await tap('找首拿手歌');
        await tester.enterText(find.byType(TextField).first, '视觉验收');
        await frames();
        await tap(longTitle);
        await event('KARAOKE_SCREEN:21-long-title-detail');
        await tap('开始演唱');
        await wait(
          () =>
              ['PAUSED', 'PLAYING'].contains(state.playbackState) &&
              state.tracks.any((t) => t['type'] == 'audio'),
          'decoded video ready',
        );
        await event('KARAOKE_LARGE_FONT');
        await event('KARAOKE_SCREEN:22-large-font-preparation');
        await tap('仅练唱 · 不开启麦克风');
        await wait(
          () => state.isPlaying && state.positionMs > 1000,
          'video actually plays',
        );
        await event('KARAOKE_SCREEN:23-large-font-stage');
        expect(tester.takeException(), isNull);
        await event('KARAOKE_NORMAL_FONT');
        await tester.ensureVisible(find.byTooltip('展开视频'));
        await frames(1);
        await tester.tap(find.byTooltip('展开视频'));
        await frames();
        await event('KARAOKE_LANDSCAPE');
        await event('KARAOKE_SCREEN:24-landscape-video');
        await tap('调音');
        await event('KARAOKE_SCREEN:25-landscape-sheet');
        await tester.binding.handlePopRoute();
        await frames();
        await event('KARAOKE_PORTRAIT');
        await state.command('pause');
        final navigator = tester.state<NavigatorState>(
          find.byType(Navigator).first,
        );
        navigator.popUntil((route) => route.isFirst);
        await frames();
        await tap('我的');
        await tap('减少透明效果');
        expect(state.reduceTransparency, true);
        await event('KARAOKE_SCREEN:26-reduced-transparency');
        await tap('减少透明效果');
        expect(state.reduceTransparency, false);
        expect(tester.takeException(), isNull);
        // ignore: avoid_print
        print('KARAOKE_UI_FLOW_PASS:visual');
      } finally {
        await state.command('pause');
      }
    },
  );
}
