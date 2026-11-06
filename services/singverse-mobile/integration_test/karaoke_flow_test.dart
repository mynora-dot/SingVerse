import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:singverse_mobile/main.dart' as app;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('real NAS singing, microphone, background capture, mix and local work', (
    tester,
  ) async {
    await app.main();
    Future<void> wait(
      bool Function() condition,
      String description, {
      int seconds = 30,
      String Function()? diagnostics,
    }) async {
      final end = DateTime.now().add(Duration(seconds: seconds));
      while (!condition() && DateTime.now().isBefore(end)) {
        await tester.pump(const Duration(milliseconds: 200));
      }
      expect(
        condition(),
        isTrue,
        reason: diagnostics == null
            ? description
            : '$description: ${diagnostics()}',
      );
    }

    Future<void> shot(String name) async {
      // Host runner captures the complete emulator display, including AndroidView and system UI.
      // ignore: avoid_print
      print('KARAOKE_SCREEN:$name');
      await tester.pump(const Duration(seconds: 2));
    }

    Future<void> tapText(String text) async {
      final candidates = find.byWidgetPredicate(
        (widget) => widget is Text && widget.data == text,
      );
      if (text == '保存作品' && candidates.evaluate().isEmpty) {
        await tester.scrollUntilVisible(
          candidates,
          240,
          scrollable: find.byType(Scrollable).first,
          maxScrolls: 20,
        );
      }
      await wait(() => candidates.evaluate().isNotEmpty, 'visible text: $text');
      final finder = candidates.last;
      await tester.ensureVisible(finder);
      await tester.pump(const Duration(milliseconds: 250));
      await tester.tap(finder);
      for (var frame = 0; frame < 5; frame++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
    }

    Future<void> setSlider(String key, double value) async {
      final finder = find.byKey(ValueKey(key));
      await tester.ensureVisible(finder);
      await tester.pump(const Duration(milliseconds: 250));
      final slider = tester.widget<Slider>(finder);
      final rect = tester.getRect(finder);
      final theme = SliderTheme.of(tester.element(finder));
      final track = (theme.trackShape ?? const RoundedRectSliderTrackShape())
          .getPreferredRect(
            parentBox: tester.renderObject<RenderBox>(finder),
            offset: rect.topLeft,
            sliderTheme: theme,
            isEnabled: slider.onChanged != null,
            isDiscrete: slider.divisions != null,
          );
      final fraction = (value - slider.min) / (slider.max - slider.min);
      await tester.tapAt(
        Offset(track.left + track.width * fraction, track.center.dy),
      );
      await tester.pump(const Duration(milliseconds: 500));
    }

    await tester.pump(const Duration(seconds: 2));
    final state = tester
        .widget<app.SingVerseApp>(find.byType(app.SingVerseApp))
        .state;
    await wait(() => state.initialized, 'application initialized');
    // The visual suite intentionally persists senior mode. Use the real settings
    // control to establish this suite's standard-mode starting point.
    if (state.senior) {
      await tester.tap(find.byKey(const Key('open-settings')));
      await tester.pump(const Duration(milliseconds: 500));
      await tapText('长辈模式');
      await tester.binding.handlePopRoute();
      await tester.pump(const Duration(milliseconds: 500));
      expect(state.senior, isFalse);
    }
    if (!state.authenticated) {
      await tapText(state.origin.isEmpty ? '连接 NAS' : '登录 NAS');
      await tester.pump(const Duration(milliseconds: 500));
      if (state.origin.isEmpty) {
        await tester.enterText(
          find.byType(TextField).first,
          const String.fromEnvironment(
            'TEST_NAS',
            defaultValue: 'http://10.0.2.2:18181',
          ),
        );
        await tapText('连接曲库');
        await wait(() => !state.busy, 'server validation');
      }
      await tester.enterText(find.byType(TextField).last, 'admin');
      await tapText('登录');
      await wait(() => state.authenticated, 'isolated administrator login');
    }
    await tester.pump(const Duration(seconds: 2));
    await shot('01-home');
    await tester.enterText(find.byType(TextField).first, '晨光');
    await tester.pump(const Duration(seconds: 2));
    await shot('02-search');
    await tapText('晨光练习');
    await tester.pump(const Duration(seconds: 1));
    await shot('03-player-opening');
    await wait(
      () =>
          state.current != null &&
          state.tracks.any((t) => t['type'] == 'audio'),
      'real decoded audio track',
    );
    await tester.pump(const Duration(seconds: 1));
    await shot('04-practice');
    await tapText('调音');
    await tapText('麦克风试音');
    // Exercise the actual Android denial flow before granting the disposable test package.
    // ignore: avoid_print
    print('KARAOKE_DENY_MIC');
    await tapText('开始试音');
    await wait(
      () => find.text('需要麦克风才能录音').evaluate().isNotEmpty,
      'microphone denial explanation',
    );
    await tapText('继续练唱');
    expect(state.singingPhase, isNot('TESTING'));
    await wait(
      () => state.isPlaying && state.positionMs > 500,
      'practice works with microphone denied',
    );
    await shot('18-denied-microphone-practice');
    await tester.binding.handlePopRoute();
    await tester.pump(const Duration(seconds: 1));
    await tapText('麦克风试音');
    await state.command('pause');
    // Permission is granted only on this dedicated test package, never on the product package.
    // ignore: avoid_print
    print('KARAOKE_MIC_GRANT');
    await tester.pump(const Duration(seconds: 2));
    await tapText('开始试音');
    await wait(
      () => state.singingPhase == 'TESTING',
      'AudioRecord test started',
    );
    // ignore: avoid_print
    print('KARAOKE_HOST_VOICE');
    var peak = 0.0;
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 200));
      final level = (state.singing['level'] as num? ?? 0).toDouble();
      if (level > peak) peak = level;
    }
    // ignore: avoid_print
    print('KARAOKE_HOST_MIC_PEAK:$peak');
    await shot('05-microphone');
    await tapText('结束试音');
    await tapText('关闭');
    await tester.binding.handlePopRoute();
    await state.command('play');
    await wait(
      () => state.isPlaying && state.positionMs > 1000,
      'Media3 actually plays the NAS source',
    );
    await shot('06-practice');
    await tapText('调音');
    await tester.pump(const Duration(milliseconds: 300));
    await setSlider('pitch-control', 2);
    await setSlider('speed-control', .9);
    await wait(
      () => state.pitch == 2 && (state.speed - .9).abs() < .01,
      'real pitch and speed controls',
      diagnostics: () => 'pitch=${state.pitch}, speed=${state.speed}',
    );
    await tapText('音轨与播放设置');
    await tapText('左声道');
    await tapText('设为伴奏');
    await tapText('确认标记');
    await wait(
      () =>
          state.backingMapped &&
          state.pitch == 2 &&
          (state.speed - .9).abs() < .01,
      'confirmed backing and real pitch/speed settings',
      diagnostics: () =>
          'backingMapped=${state.backingMapped}, channel=${state.channelMode}, '
          'pitch=${state.pitch}, speed=${state.speed}, tracks=${state.tracks}, '
          'message=${state.playbackError}',
    );
    await shot('07-settings');
    await tester.binding.handlePopRoute();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.binding.handlePopRoute();
    await tester.pump(const Duration(milliseconds: 500));
    final original = find.byKey(const ValueKey('player-role-original'));
    await tester.ensureVisible(original);
    await tester.tap(original);
    await wait(
      () =>
          state.channelMode == 'STEREO' &&
          state.audioRole != 'backing' &&
          state.isPlaying &&
          state.pitch == 2 &&
          (state.speed - .9).abs() < .01,
      'original source leaves the local backing channel and keeps tuning',
      diagnostics: () =>
          '${state.channelMode}; role=${state.audioRole}; '
          'pitch=${state.pitch}; speed=${state.speed}; ${state.playbackError}',
    );
    final backing = find.byKey(const ValueKey('player-role-backing'));
    await tester.ensureVisible(backing);
    await tester.tap(backing);
    await wait(
      () =>
          state.channelMode == 'LEFT' &&
          state.audioRole == 'backing' &&
          state.isPlaying &&
          state.pitch == 2 &&
          (state.speed - .9).abs() < .01,
      'backing button restores the saved channel and keeps tuning',
      diagnostics: () =>
          '${state.channelMode}; role=${state.audioRole}; '
          'pitch=${state.pitch}; speed=${state.speed}; ${state.playbackError}',
    );
    await tapText('开始录音');
    await wait(() => state.countdown > 0, 'native countdown');
    await shot('08-countdown');
    await wait(
      () => state.recording && (state.singing['recordedMs'] as num? ?? 0) > 500,
      'native microphone recording',
      diagnostics: () =>
          'phase=${state.singingPhase}; singing=${state.singing}; '
          'countdown=${state.countdown}; playback=${state.playbackState}; '
          'position=${state.positionMs}; ${state.playbackError}',
    );
    // ignore: avoid_print
    print('KARAOKE_HOST_VOICE');
    await tester.pump(const Duration(seconds: 4));
    await shot('09-recording');
    // ignore: avoid_print
    print('KARAOKE_RETURN_NOTIFICATION');
    await tester.pump(const Duration(seconds: 5));
    expect(state.recording, isTrue);
    expect(find.text('结束录音'), findsOneWidget);
    final before = (state.singing['recordedMs'] as num).toInt();
    // ignore: avoid_print
    print('KARAOKE_LOCK');
    await tester.pump(const Duration(seconds: 6));
    await wait(
      () =>
          state.recording &&
          (state.singing['recordedMs'] as num).toInt() > before + 3000,
      'microphone continues under lock screen',
    );
    await tapText('结束录音');
    await wait(
      () => state.singingPhase == 'PREVIEW',
      'capture finalization and real AAC mix',
      seconds: 60,
    );
    await tester.pump(const Duration(seconds: 2));
    final id = state.takeId;
    var work = await state.sing('get', {'id': id}) as Map;
    await wait(() => state.singingPhase != 'RENDERING', 'AAC render complete');
    work = await state.sing('get', {'id': id}) as Map;
    expect(
      work['hasPreview'],
      isTrue,
      reason: 'a playable native encoded mix exists',
    );
    expect((work['voiceFrames'] as num), greaterThan(48000));
    expect((work['backingFrames'] as num), greaterThan(48000));
    // ignore: avoid_print
    print(
      'KARAOKE_METRICS:${{
        for (final key in ['frames', 'voiceFrames', 'backingFrames', 'voiceOffsetFrames', 'timestampAligned']) key: work[key],
      }}',
    );
    await shot('10-review');
    await tapText('播放录音');
    await wait(
      () => state.workId == id && state.isPlaying,
      'local AAC mix playback',
    );
    await tester.pump(const Duration(seconds: 2));
    final savedName = '模拟器唱录验收 ${id.substring(0, 4)}';
    await tester.tap(find.byKey(const Key('review-more')));
    await tester.pump(const Duration(milliseconds: 300));
    await tapText('修改名称');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.enterText(find.byType(TextField).last, savedName);
    await tapText('完成');
    await tapText('保存作品');
    await tester.pump(const Duration(seconds: 2));
    work = await state.sing('get', {'id': id}) as Map;
    expect(work['status'], 'SAVED');
    final repeated =
        await state.sing('save', {'id': id, 'name': '模拟器唱录验收'}) as Map;
    expect(repeated['id'], id);
    await shot('11-saved');
    // ignore: avoid_print
    print('KARAOKE_WORK:$id');
    await tester.pump(const Duration(seconds: 3));
    // Real system export is driven by the host runner while the Android document picker is visible.
    // ignore: avoid_print
    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump(const Duration(seconds: 1));
    // ignore: avoid_print
    print('KARAOKE_EXPORT');
    final exported = await state.sing('export', {'id': id});
    expect(exported, true);
    // Confirm replay does not rely on an available NAS.
    await state.command('pause');
    await state.api.configure('http://10.0.2.2:1');
    await state.sing('play', {'id': id});
    await wait(
      () => state.isPlaying && state.positionMs > 500,
      'offline saved work replay',
    );
    await shot('12-offline-replay');
    await state.command('pause');
    await state.api.configure(
      const String.fromEnvironment(
        'TEST_NAS',
        defaultValue: 'http://10.0.2.2:18181',
      ),
    );
    await state.login('admin');
    final navigator = tester.state<NavigatorState>(
      find.byType(Navigator).first,
    );
    navigator.popUntil((route) => route.isFirst);
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.byKey(const Key('nav-works')));
    await tester.pump(const Duration(seconds: 1));
    await shot('15-works');
    await tapText(savedName);
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.byKey(const Key('review-more')));
    await tester.pump(const Duration(milliseconds: 300));
    await tapText('修改名称');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.enterText(find.byType(TextField).last, '$savedName 已改名');
    await tapText('完成');
    await tester.pump(const Duration(seconds: 1));
    expect(
      (await state.sing('get', {'id': id}) as Map)['name'],
      '$savedName 已改名',
    );
    await tester.tap(find.byKey(const Key('review-more')));
    await tester.pump(const Duration(milliseconds: 300));
    await tapText('删除录音');
    await tester.pump(const Duration(milliseconds: 500));
    await tapText('删除录音');
    await tester.pump(const Duration(seconds: 1));
    await state.refreshWorks();
    expect(state.works.any((w) => w['id'] == id), isFalse);
    navigator.popUntil((route) => route.isFirst);
    await tester.pump(const Duration(seconds: 1));
    await tester.tap(find.byKey(const Key('nav-songs')));
    await tester.pump(const Duration(seconds: 1));
    final library =
        await state.get('/api/v1/mobile/library/songs?size=100') as Map;
    final videoSong = (library['items'] as List).cast<Map>().firstWhere(
      (s) => s['mediaType'] == 'VIDEO',
    );
    await tester.enterText(
      find.byType(TextField).first,
      videoSong['title'] as String,
    );
    await tester.pump(const Duration(seconds: 2));
    await tapText(videoSong['title'] as String);
    await wait(
      () =>
          state.tracks.where((t) => t['type'] == 'audio').length >= 2 &&
          ['PAUSED', 'PLAYING'].contains(state.playbackState),
      'real multiple audio tracks ready',
    );
    await wait(
      () => state.isPlaying && state.positionMs > 1000,
      'real video playback',
    );
    await shot('16-video-practice');
    await tapText('调音');
    await tapText('音轨与播放设置');
    await shot('17-multiple-tracks');
    await tester.binding.handlePopRoute();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.binding.handlePopRoute();
    await tester.pump(const Duration(milliseconds: 500));
    await state.command('pause');
    expect(tester.takeException(), isNull);
    // ignore: avoid_print
    print('KARAOKE_UI_FLOW_PASS:$id');
    expect(
      peak,
      greaterThan(0.00001),
      reason: 'host microphone must deliver actual nonzero samples; test injection cannot satisfy this',
    );
  });
}
