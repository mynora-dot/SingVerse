import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import '../library/connection.dart';
import '../library/library_pages.dart';
import '../library/widgets.dart';
import '../singing/actions.dart';
import '../singing/works_page.dart';

Widget performancePage(AppState state) => state.recording || state.countdown > 0
    ? PlayerPage(state: state)
    : state.workId.isNotEmpty
    ? ReviewPage(state: state, id: state.workId)
    : state.pendingReviewId.isNotEmpty
    ? ReviewPage(state: state, id: state.pendingReviewId)
    : PlayerPage(state: state);

class PlayerPage extends StatefulWidget {
  const PlayerPage({super.key, required this.state, this.song});
  final AppState state;
  final Song? song;
  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  static const stageColor = Color(0xFF14161B);
  List<TextLine> lines = [];
  String? lyricsSong, lyricError, openError, recordingError;
  bool lyricsLoading = true,
      opening = false,
      actionBusy = false,
      allowPop = false;
  int lyricsGeneration = 0;
  String recordedTake = '', reviewed = '';
  double? drag;
  bool forcedLandscape = false;
  Timer? controlsTimer;
  bool controlsVisible = true, pointerHeld = false, keyboardNavigation = false;
  bool landscape = false, accessibleControls = false, wasPlaying = false;
  int openPanels = 0;
  final dockKey = GlobalKey();
  double dockHeight = 154, lastSubtitleInset = -1;
  bool measurePending = false;
  Brightness playerBrightness = Brightness.dark;

  void measureChrome(double canvasHeight) {
    if (measurePending) return;
    measurePending = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      measurePending = false;
      if (!mounted) return;
      final measured = dockKey.currentContext?.size?.height ?? dockHeight;
      if (landscape && (measured - dockHeight).abs() > 1) {
        setState(() => dockHeight = measured);
      }
      final inset = landscape && controlsVisible
          ? ((measured + 12) / canvasHeight).clamp(.08, .65)
          : .08;
      if ((lastSubtitleInset - inset).abs() > .005) {
        lastSubtitleInset = inset;
        if (widget.state.nativeEnabled) {
          unawaited(widget.state.command('subtitleInset', inset));
        }
      }
    });
  }

  void updateLayout(bool wide) {
    if (landscape == wide) return;
    landscape = wide;
    controlsVisible = true;
    keyboardNavigation = false;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      unawaited(
        SystemChrome.setEnabledSystemUIMode(
          wide ? SystemUiMode.immersiveSticky : SystemUiMode.edgeToEdge,
        ),
      );
      armControls();
    });
  }

  bool get keepControls =>
      !widget.state.isPlaying ||
      widget.state.countdown > 0 ||
      opening ||
      actionBusy ||
      drag != null ||
      pointerHeld ||
      keyboardNavigation ||
      accessibleControls ||
      openPanels > 0 ||
      widget.state.playbackError.isNotEmpty ||
      recordingError != null;

  void armControls() {
    controlsTimer?.cancel();
    if (!mounted || !landscape || !controlsVisible || keepControls) return;
    controlsTimer = Timer(
      Duration(milliseconds: widget.state.senior ? 6000 : 3500),
      () {
        if (!mounted ||
            keepControls ||
            ModalRoute.of(context)?.isCurrent != true) {
          return;
        }
        setState(() => controlsVisible = false);
      },
    );
  }

  void revealControls() {
    if (!mounted) return;
    setState(() => controlsVisible = true);
    armControls();
  }

  void toggleControls() {
    if (keepControls) {
      revealControls();
      return;
    }
    setState(() => controlsVisible = !controlsVisible);
    armControls();
  }

  Future<void> panel(Future<void> Function() action) async {
    openPanels++;
    revealControls();
    try {
      await action();
    } finally {
      openPanels--;
      armControls();
    }
  }

  ThemeData get _theme => singVerseTheme(
    playerBrightness,
    senior: widget.state.senior,
    compact: MediaQuery.sizeOf(context).width < 375,
  );

  Color get pageColor => _theme.brightness == Brightness.dark
      ? stageColor
      : const Color(0xFFFAFAFC);

  Color get recordingColor => _theme.brightness == Brightness.dark
      ? const Color(0xFFFF8794)
      : const Color(0xFFAC263C);

  bool get largeControls =>
      widget.state.senior || MediaQuery.textScalerOf(context).scale(16) > 22;

  @override
  void initState() {
    super.initState();
    reviewed = widget.state.takeId;
    if (widget.state.recording) recordedTake = widget.state.takeId;
    widget.state.performance.addListener(watch);
    if (widget.song != null) {
      opening = true;
      unawaited(prepare());
    } else {
      unawaited(loadLyrics(widget.state.current));
    }
  }

  @override
  void dispose() {
    lyricsGeneration++;
    controlsTimer?.cancel();
    unawaited(SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge));
    if (widget.state.nativeEnabled) {
      unawaited(widget.state.command('subtitleInset', .08));
    }
    widget.state.performance.removeListener(watch);
    if (forcedLandscape) {
      unawaited(
        SystemChrome.setPreferredOrientations(DeviceOrientation.values),
      );
    }
    super.dispose();
  }

  Future<void> prepare({bool compatible = false, bool retry = false}) async {
    final song = widget.song ?? widget.state.current;
    if (song == null) return;
    if (mounted) {
      setState(() {
        opening = true;
        openError = null;
      });
    }
    try {
      await widget.state.play(
        song,
        force: compatible,
        reuseAttempt: retry,
        start: (song.data['positionMs'] as num? ?? 0).toInt(),
      );
      if (mounted) await loadLyrics(widget.state.current ?? song);
    } catch (e) {
      if (mounted) setState(() => openError = e.toString());
    } finally {
      if (mounted) setState(() => opening = false);
    }
  }

  Future<void> loadLyrics(Song? song) async {
    if (song == null || widget.state.workId.isNotEmpty) {
      if (mounted) setState(() => lyricsLoading = false);
      return;
    }
    final generation = ++lyricsGeneration;
    lyricsSong = song.id;
    if (mounted) {
      setState(() {
        lyricsLoading = true;
        lyricError = null;
        lines = [];
      });
    }
    try {
      final data = await widget.state.get(
        '/api/v1/mobile/library/songs/${Uri.encodeComponent(song.id)}/text-tracks',
      ) as Json;
      final usable = (data['items'] as List)
          .cast<Json>()
          .map((i) => parseText(i['format'] as String, i['text'] as String))
          .where((l) => l.isNotEmpty)
          .firstOrNull;
      if (mounted && generation == lyricsGeneration) {
        setState(() => lines = usable ?? []);
      }
    } catch (_) {
      if (mounted && generation == lyricsGeneration) {
        setState(() => lyricError = '歌词暂不可读');
      }
    } finally {
      if (mounted && generation == lyricsGeneration) {
        setState(() => lyricsLoading = false);
      }
    }
  }

  void watch() {
    final state = widget.state;
    if (wasPlaying != state.isPlaying) {
      wasPlaying = state.isPlaying;
      controlsVisible = true;
      armControls();
    } else if (keepControls) {
      controlsVisible = true;
      controlsTimer?.cancel();
    } else if (controlsVisible && !(controlsTimer?.isActive ?? false)) {
      armControls();
    }
    if (!opening && state.current?.id != lyricsSong && state.workId.isEmpty) {
      unawaited(loadLyrics(state.current));
    }
    if (state.recording) recordedTake = state.takeId;
    if (state.countdown == 0 &&
        recordedTake.isNotEmpty &&
        state.takeId == recordedTake &&
        reviewed != state.takeId &&
        ['FINALIZING', 'PREVIEW', 'FAILED'].contains(state.singingPhase)) {
      final id = state.takeId;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted &&
            state.takeId == id &&
            reviewed != id &&
            ModalRoute.of(context)?.isCurrent == true) {
          reviewed = id;
          Navigator.pushReplacement(
            context,
            MaterialPageRoute<void>(
              settings: const RouteSettings(name: 'review'),
              builder: (_) => ReviewPage(state: state, id: id),
            ),
          );
        }
      });
    }
  }

  Future<void> close() async {
    if (widget.state.countdown > 0) {
      await widget.state.command('cancelCountdown');
    }
    if (!mounted) return;
    if (widget.state.recording) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('仍在录音，可通过底部播放器或通知返回。')));
    }
    setState(() => allowPop = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.pop(context);
    });
  }

  Future<void> record() => uiTask(context, () async {
    if (actionBusy) return;
    setState(() {
      actionBusy = true;
      recordingError = null;
    });
    controlsTimer?.cancel();
    final state = widget.state;
    try {
      if (state.countdown > 0) {
        await state.command('cancelCountdown');
        return;
      }
      if (state.recording) {
        await state.endTake();
        return;
      }
      if (await confirmRecording(context, state)) {
        reviewed = state.takeId;
        await state.command('record', true);
      }
    } catch (e) {
      if (mounted) {
        setState(
          () => recordingError = e is FormatException
              ? e.message
              : e is PlatformException
              ? e.message ?? '录音准备失败，请重试。'
              : e.toString(),
        );
      }
    } finally {
      if (mounted) {
        setState(() => actionBusy = false);
        armControls();
      }
    }
  });
  Future<void> orientation() async {
    forcedLandscape = true;
    await SystemChrome.setPreferredOrientations(
      MediaQuery.orientationOf(context) == Orientation.landscape
          ? [DeviceOrientation.portraitUp]
          : [DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight],
    );
  }

  Future<void> range() async {
    final state = widget.state;
    if (state.durationMs <= 0 || state.taking) return;
    final duration = state.durationMs.toDouble();
    var values = RangeValues(
      state.rangeA.toDouble().clamp(0, duration),
      state.rangeB > state.rangeA
          ? state.rangeB.toDouble().clamp(0, duration)
          : duration,
    );
    int? startLine;
    bool selectingEnd = false;
    await panel(
      () => showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        useSafeArea: true,
        builder: (context) => StatefulBuilder(
          builder: (context, update) => SizedBox(
            height: MediaQuery.sizeOf(context).height * .82,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const SheetHeader('选段练唱', done: '取消'),
                  Expanded(
                    child: ListView(
                      children: [
                        Text(
                          '起点 ${durationLabel(values.start.round())}  —  终点 ${durationLabel(values.end.round())}',
                        ),
                        RangeSlider(
                          values: values,
                          max: duration,
                          labels: RangeLabels(
                            durationLabel(values.start.round()),
                            durationLabel(values.end.round()),
                          ),
                          onChanged: (v) => update(() => values = v),
                        ),
                        Wrap(
                          spacing: 8,
                          children: [
                            TextButton(
                              onPressed: () => update(
                                () => values = RangeValues(
                                  state.positionMs.toDouble().clamp(
                                    0,
                                    values.end,
                                  ),
                                  values.end,
                                ),
                              ),
                              child: const Text('当前位置设为起点'),
                            ),
                            TextButton(
                              onPressed: () => update(
                                () => values = RangeValues(
                                  values.start,
                                  state.positionMs.toDouble().clamp(
                                    values.start,
                                    duration,
                                  ),
                                ),
                              ),
                              child: const Text('当前位置设为终点'),
                            ),
                          ],
                        ),
                        if (lines.isNotEmpty) ...[
                          const Divider(),
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                            child: Text(
                              selectingEnd ? '再点一句作为结束句' : '按歌词选段：先点起句，再点结束句',
                            ),
                          ),
                          for (var i = 0; i < lines.length; i++)
                            ListTile(
                              contentPadding: const EdgeInsets.symmetric(
                                horizontal: 8,
                              ),
                              selected:
                                  lines[i].startMs >= values.start &&
                                  lines[i].startMs < values.end,
                              leading: Text(durationLabel(lines[i].startMs)),
                              title: Text(lines[i].text),
                              onTap: () => update(() {
                                if (!selectingEnd ||
                                    startLine == null ||
                                    i < startLine!) {
                                  startLine = i;
                                  selectingEnd = true;
                                  values = RangeValues(
                                    lines[i].startMs.toDouble().clamp(
                                      0,
                                      duration,
                                    ),
                                    (lines[i].endMs ??
                                            (i + 1 < lines.length
                                                ? lines[i + 1].startMs
                                                : state.durationMs))
                                        .toDouble()
                                        .clamp(
                                          lines[i].startMs.toDouble().clamp(
                                            0,
                                            duration,
                                          ),
                                          duration,
                                        ),
                                  );
                                } else {
                                  values = RangeValues(
                                    values.start,
                                    (lines[i].endMs ??
                                            (i + 1 < lines.length
                                                ? lines[i + 1].startMs
                                                : state.durationMs))
                                        .toDouble()
                                        .clamp(values.start, duration),
                                  );
                                  selectingEnd = false;
                                }
                              }),
                            ),
                        ],
                      ],
                    ),
                  ),
                  const SizedBox(height: 8),
                  FilledButton(
                    onPressed: values.end <= values.start
                        ? null
                        : () => uiTask(context, () async {
                            await state.command('range', {
                              'a': values.start.round(),
                              'b': values.end.round(),
                            });
                            await state.command('seek', values.start.round());
                            if (context.mounted) Navigator.pop(context);
                          }),
                    child: const Text('循环练唱这一段'),
                  ),
                  TextButton(
                    onPressed: () => uiTask(context, () async {
                      await state.command('range', {'a': 0, 'b': 0});
                      if (context.mounted) Navigator.pop(context);
                    }),
                    child: const Text('恢复整首练唱'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> more() => panel(
    () => showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (context) => ListenableBuilder(
        listenable: widget.state.performance,
        builder: (context, _) => SingleChildScrollView(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SheetHeader('更多功能', done: '关闭'),
              ListTile(
                leading: const Icon(Icons.repeat_rounded),
                title: const Text('选段练唱'),
                onTap: widget.state.taking
                    ? null
                    : () {
                        Navigator.pop(context);
                        range();
                      },
              ),
              SwitchListTile(
                value: widget.state.loop,
                title: const Text('单曲循环'),
                onChanged: widget.state.taking
                    ? null
                    : (v) => uiTask(
                        context,
                        () => widget.state.command('loop', v),
                      ),
              ),
              ListTile(
                leading: const Icon(Icons.headphones_rounded),
                title: const Text('音轨与播放设置'),
                onTap: () => panel(() => audioSettings(context, widget.state)),
              ),
            ],
          ),
        ),
      ),
    ),
  );

  Widget header(Song song, {bool compact = false, bool dense = false}) {
    final state = widget.state;
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 8, 8, 8),
      child: Row(
        children: [
          IconButton(
            tooltip: '收起演唱',
            onPressed: close,
            icon: const Icon(Icons.keyboard_arrow_down_rounded, size: 30),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  song.title,
                  maxLines: compact || dense ? 1 : 2,
                  overflow: TextOverflow.ellipsis,
                  style: _theme.textTheme.titleMedium,
                ),
                if (!compact && !dense)
                  Text(
                    song.artist.isEmpty ? '手机练唱' : song.artist,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: _theme.textTheme.bodySmall?.copyWith(
                      color: _theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
              ],
            ),
          ),
          IconButton(
            tooltip: state.isFavorite(song) ? '取消收藏' : '收藏歌曲',
            onPressed: () => uiTask(context, () => state.favorite(song)),
            icon: Icon(
              state.isFavorite(song)
                  ? Icons.favorite_rounded
                  : Icons.favorite_border_rounded,
              color: state.isFavorite(song) ? coral : null,
            ),
          ),
          if (compact)
            IconButton(
              tooltip: '退出横屏',
              onPressed: orientation,
              icon: const Icon(Icons.fullscreen_exit_rounded),
            ),
          IconButton(
            tooltip: '歌曲信息',
            onPressed: () => panel(() => showSong(context, state, song)),
            icon: const Icon(Icons.info_outline_rounded),
          ),
        ],
      ),
    );
  }

  Widget video() => ColoredBox(
    key: const ValueKey('player-video'),
    color: Colors.black,
    child: widget.state.nativeEnabled
        ? const AndroidView(viewType: 'singverse/video')
        : const Center(
            child: Icon(
              Icons.music_video_rounded,
              size: 64,
              color: Colors.white54,
            ),
          ),
  );

  Widget lyrics({bool currentOnly = false}) => LyricsView(
    currentOnly: currentOnly,
    onBackgroundTap: currentOnly ? toggleControls : null,
    lines: lines,
    positionMs: widget.state.positionMs,
    senior: widget.state.senior,
    enabled: !widget.state.taking,
    rangeA: widget.state.rangeA,
    rangeB: widget.state.rangeB,
    onSeek: (ms) => uiTask(context, () => widget.state.command('seek', ms)),
  );

  Widget media(Song song, {required bool landscape}) {
    final state = widget.state;
    if (song.mediaType == 'VIDEO') {
      Widget frame(Widget child) => Stack(
        children: [
          Positioned.fill(child: child),
          Positioned(
            bottom: 8,
            right: 8,
            child: IconButton.filledTonal(
              tooltip: landscape ? '退出横屏' : '全屏视频',
              style: IconButton.styleFrom(
                backgroundColor: const Color(0xB314161B),
                foregroundColor: Colors.white,
              ),
              onPressed: orientation,
              icon: Icon(
                landscape
                    ? Icons.fullscreen_exit_rounded
                    : Icons.fullscreen_rounded,
              ),
            ),
          ),
        ],
      );
      Widget roundedFrame() => ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: frame(video()),
      );
      if (lines.isEmpty) {
        return Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
          child: roundedFrame(),
        );
      }
      return LayoutBuilder(
        builder: (context, size) {
          final readingHeight =
              MediaQuery.textScalerOf(context)
                      .scale(_lyricFontSize(context, state.senior)) *
                  2.8 +
              48;
          final pictureHeight = largeControls
              ? math.max(48.0, size.maxHeight - readingHeight)
              : size.maxHeight * .48;
          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                child: SizedBox(
                  height: ((size.maxWidth - 40) / state.videoAspect).clamp(
                    0.0,
                    math.max(0.0, math.min(pictureHeight, size.maxHeight - 8)),
                  ),
                  child: roundedFrame(),
                ),
              ),
              Expanded(child: lyrics()),
            ],
          );
        },
      );
    }
    if (lines.isNotEmpty) {
      return LayoutBuilder(
        builder: (context, size) => Column(
          children: [
            if ((song.cover?.isNotEmpty ?? false) && size.maxHeight >= 340) ...[
              const SizedBox(height: 16),
              Artwork(
                state: state,
                path: song.cover,
                label: song.title,
                size: largeControls ? 64 : 96,
              ),
              const SizedBox(height: 8),
            ],
            Expanded(child: lyrics()),
          ],
        ),
      );
    }
    return LayoutBuilder(
      builder: (context, size) => SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: size.maxHeight),
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Artwork(
                    state: state,
                    path: song.cover,
                    label: song.title,
                    size: (size.maxHeight - 112).clamp(
                      80.0,
                      (size.maxWidth - 48).clamp(80.0, 176.0),
                    ),
                  ),
                  const SizedBox(height: 24),
                  Text(
                    lyricsLoading ? '正在读取歌词…' : lyricError ?? '暂无独立歌词',
                    textAlign: TextAlign.center,
                    style: _theme.textTheme.bodyMedium?.copyWith(
                      color: _theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  if (lyricError != null)
                    TextButton(
                      onPressed: () => loadLyrics(song),
                      child: const Text('重试歌词'),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  bool get ready =>
      !opening &&
      openError == null &&
      ['PLAYING', 'PAUSED', 'ENDED'].contains(widget.state.playbackState) &&
      widget.state.tracks.any((t) => t['type'] == 'audio');

  Widget recordingStatus() {
    final state = widget.state;
    return Text(
      key: const ValueKey('player-status'),
      state.recording
          ? '正在录音 · ${durationLabel((state.singing['recordedMs'] as num? ?? 0).toInt())}'
          : state.countdown > 0
          ? '准备录音 · ${state.countdown}'
          : state.singingPhase == 'FINALIZING'
          ? '正在保留录音…'
          : state.rangeB > state.rangeA
          ? '选段 ${durationLabel(state.rangeA)} — ${durationLabel(state.rangeB)}'
          : '练唱 · 麦克风未开启',
      style: _theme.textTheme.bodySmall?.copyWith(
        color: state.taking
            ? recordingColor
            : state.rangeB > state.rangeA
            ? _theme.colorScheme.primary
            : _theme.colorScheme.onSurfaceVariant,
        fontWeight: state.taking ? FontWeight.w600 : FontWeight.w400,
      ),
    );
  }

  Widget progress() {
    final state = widget.state;
    final scheme = _theme.colorScheme;
    final timeStyle = _theme.textTheme.bodySmall?.copyWith(
      color: scheme.onSurfaceVariant,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    return Row(
      children: [
        Text(
          durationLabel((drag ?? state.positionMs).round()),
          style: timeStyle,
        ),
        Expanded(
          child: SliderTheme(
            data: _theme.sliderTheme.copyWith(
              trackHeight: 3,
              trackShape: _PracticeTrack(
                start: state.rangeA,
                end: state.rangeB,
                duration: state.durationMs,
              ),
            ),
            child: SizedBox(
              height: state.senior ? 56 : 48,
              child: Semantics(
                container: true,
                child: Slider(
                  key: const ValueKey('player-progress'),
                  semanticFormatterCallback: (v) => state.rangeB > state.rangeA
                      ? '${durationLabel(v.round())}，选段 ${durationLabel(state.rangeA)} 至 ${durationLabel(state.rangeB)}'
                      : durationLabel(v.round()),
                  value: (drag ?? state.positionMs.toDouble()).clamp(
                    0,
                    state.durationMs > 0 ? state.durationMs.toDouble() : 1,
                  ),
                  max: state.durationMs > 0 ? state.durationMs.toDouble() : 1,
                  onChangeStart: ready && !state.taking
                      ? (v) {
                          controlsTimer?.cancel();
                          setState(() => drag = v);
                        }
                      : null,
                  onChanged: ready && !state.taking
                      ? (v) => setState(() => drag = v)
                      : null,
                  onChangeEnd: ready && !state.taking
                      ? (v) {
                          setState(() => drag = null);
                          uiTask(
                            context,
                            () => state.command('seek', v.round()),
                          );
                          armControls();
                        }
                      : null,
                ),
              ),
            ),
          ),
        ),
        Text(durationLabel(state.durationMs), style: timeStyle),
      ],
    );
  }

  double roleWidth({bool compact = false}) {
    final font = widget.state.senior
        ? 19.0
        : compact
        ? 13.0
        : 14.0;
    return 2 * (MediaQuery.textScalerOf(context).scale(font) * 2 + 32);
  }

  Widget roles({bool compact = false}) {
    final state = widget.state;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final role in [
          ('original', '原唱', state.originalMapped),
          ('backing', '伴奏', state.backingMapped),
        ])
          Expanded(
            child: Semantics(
              selected: role.$3 && state.audioRole == role.$1,
              child: Tooltip(
                message: role.$3 ? role.$2 : '${role.$2}暂不可用',
                child: TextButton(
                  key: ValueKey('player-role-${role.$1}'),
                  style: TextButton.styleFrom(
                    minimumSize: Size(48, state.senior ? 56 : 48),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    textStyle: _theme.textTheme.labelLarge?.copyWith(
                      fontSize: state.senior
                          ? 19
                          : compact
                          ? 13
                          : 14,
                      fontWeight: role.$3 && state.audioRole == role.$1
                          ? FontWeight.w500
                          : FontWeight.w400,
                    ),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(8),
                    ),
                    backgroundColor: Colors.transparent,
                    foregroundColor: role.$3 && state.audioRole == role.$1
                        ? _theme.colorScheme.primary
                        : _theme.colorScheme.onSurfaceVariant,
                    disabledForegroundColor: _theme.colorScheme.onSurface
                        .withValues(alpha: .38),
                  ),
                  onPressed: ready && !state.taking && role.$3
                      ? () => uiTask(
                          context,
                          () => state.command('useRole', role.$1),
                        )
                      : null,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(role.$2, maxLines: 1, softWrap: false),
                      const SizedBox(height: 4),
                      SizedBox(
                        width: 20,
                        height: 2,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: role.$3 && state.audioRole == role.$1
                                ? _theme.colorScheme.primary
                                : Colors.transparent,
                            borderRadius: BorderRadius.circular(1),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget playButton({bool text = false, bool compact = false}) {
    final state = widget.state;
    final label = state.isPlaying ? '暂停音乐' : '继续播放';
    return FilledButton.tonal(
      key: const ValueKey('player-play'),
      style: FilledButton.styleFrom(
        minimumSize: Size(
          state.senior
              ? 56
              : compact
              ? 48
              : 56,
          state.senior
              ? 56
              : compact
              ? 48
              : 56,
        ),
        padding: text
            ? const EdgeInsets.symmetric(horizontal: 8, vertical: 12)
            : EdgeInsets.zero,
        backgroundColor: _theme.brightness == Brightness.dark
            ? const Color(0xFFF3F5FA)
            : _theme.colorScheme.primary,
        foregroundColor: _theme.brightness == Brightness.dark
            ? stageColor
            : _theme.colorScheme.onPrimary,
        disabledBackgroundColor: _theme.colorScheme.onSurface.withValues(
          alpha: .12,
        ),
        disabledForegroundColor: _theme.colorScheme.onSurface.withValues(
          alpha: .38,
        ),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(text ? 12 : 32),
        ),
      ),
      onPressed: ready && !state.taking
          ? () => uiTask(
              context,
              () => state.command(state.isPlaying ? 'pause' : 'play'),
            )
          : null,
      child: Semantics(
        label: label,
        excludeSemantics: true,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              state.isPlaying ? Icons.pause_rounded : Icons.play_arrow_rounded,
              size: 28,
            ),
            if (text) ...[
              const SizedBox(width: 8),
              Flexible(child: Text(state.isPlaying ? '暂停' : '继续')),
            ],
          ],
        ),
      ),
    );
  }

  Widget recordButton({bool compact = false, Key? key}) {
    final state = widget.state;
    final enabled =
        !actionBusy && (ready || state.recording || state.countdown > 0);
    return FilledButton.icon(
      key: key ?? const ValueKey('player-record'),
      style: FilledButton.styleFrom(
        minimumSize: Size(48, state.senior ? 56 : 48),
        padding: EdgeInsets.symmetric(
          horizontal: compact ? 12 : 20,
          vertical: 12,
        ),
        textStyle: _theme.textTheme.labelLarge?.copyWith(
          fontSize: state.senior
              ? 19
              : compact
              ? 13
              : 14,
          fontWeight: FontWeight.w500,
        ),
        backgroundColor: state.taking
            ? coral
            : _theme.colorScheme.onSurface.withValues(alpha: .04),
        foregroundColor: state.taking
            ? const Color(0xFF410009)
            : _theme.colorScheme.onSurface,
        disabledBackgroundColor: _theme.colorScheme.onSurface.withValues(
          alpha: .05,
        ),
        disabledForegroundColor: _theme.colorScheme.onSurface.withValues(
          alpha: .38,
        ),
        side: state.taking
            ? BorderSide.none
            : BorderSide(
                color: _theme.colorScheme.onSurface.withValues(alpha: .10),
              ),
        shape: const StadiumBorder(),
      ),
      onPressed: enabled ? record : null,
      icon: Icon(
        state.recording
            ? Icons.stop_rounded
            : state.countdown > 0
            ? Icons.close_rounded
            : Icons.mic_none_rounded,
        size: 18,
        color: enabled && !state.taking ? recordingColor : null,
      ),
      label: Text(
        state.recording
            ? '结束录音'
            : state.countdown > 0
            ? '取消倒数'
            : '开始录音',
      ),
    );
  }

  Future<void> restart() => uiTask(context, () async {
    final state = widget.state;
    await state.command(
      'restart',
      state.rangeB > state.rangeA ? state.rangeA : 0,
    );
    await state.command('play');
  });

  Widget tools({bool compact = false, bool includeRestart = true}) {
    final state = widget.state;
    return LayoutBuilder(
      builder: (context, size) {
        final items = <(IconData, String, VoidCallback?)>[
          (
            Icons.tune_rounded,
            '调音',
            !state.taking
                ? () => panel(() => singingSettings(context, state))
                : null,
          ),
          if (includeRestart)
            (
              Icons.replay_rounded,
              '重唱',
              ready && !state.taking ? restart : null,
            ),
          (Icons.repeat_rounded, '选段', !state.taking ? range : null),
          (Icons.more_horiz_rounded, '更多', !state.taking ? more : null),
        ];
        final columns =
            size.maxWidth < 320 &&
                MediaQuery.textScalerOf(context).scale(16) > 22
            ? 2
            : items.length;
        return Wrap(
          children: [
            for (final tool in items)
              SizedBox(
                width: size.maxWidth / columns,
                child: TextButton(
                  style: TextButton.styleFrom(
                    foregroundColor: _theme.colorScheme.onSurfaceVariant,
                    minimumSize: Size(48, state.senior ? 56 : 48),
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    textStyle: _theme.textTheme.labelLarge?.copyWith(
                      fontSize: state.senior
                          ? 17
                          : compact
                          ? 12
                          : 13,
                    ),
                  ),
                  onPressed: tool.$3,
                  child: compact
                      ? Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(tool.$1, size: 18),
                            const SizedBox(width: 8),
                            Flexible(child: Text(tool.$2)),
                          ],
                        )
                      : Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(tool.$1, size: 20),
                            const SizedBox(height: 4),
                            Text(tool.$2),
                          ],
                        ),
                ),
              ),
          ],
        );
      },
    );
  }

  Widget controls({bool compact = false}) {
    final state = widget.state;
    final large = MediaQuery.textScalerOf(context).scale(16) > 22;
    final primaryFirst = compact && (large || state.senior);
    if (!compact) {
      return DecoratedBox(
        key: const ValueKey('player-primary-controls'),
        decoration: BoxDecoration(
          color: pageColor,
          border: Border(
            top: BorderSide(
              color: _theme.colorScheme.onSurface.withValues(alpha: .08),
            ),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              progress(),
              const SizedBox(height: 4),
              Align(
                child: largeControls
                    ? SizedBox(
                        width: double.infinity,
                        child: playButton(text: true),
                      )
                    : SizedBox(width: 56, height: 56, child: playButton()),
              ),
              const SizedBox(height: 16),
              Align(
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    minWidth: state.senior ? 184 : 160,
                  ),
                  child: recordButton(),
                ),
              ),
            ],
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 4, 24, 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (!primaryFirst) ...[progress(), const SizedBox(height: 8)],
          LayoutBuilder(
            builder: (context, size) {
              final choiceWidth = roleWidth(compact: true)
                  .clamp(0.0, size.maxWidth);
              final primary = Wrap(
                spacing: 12,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  playButton(compact: true),
                  SizedBox(width: choiceWidth, child: roles(compact: true)),
                  if (!state.recording) recordButton(compact: true),
                ],
              );
              if (!state.senior && !large && size.maxWidth >= 680) {
                return Row(
                  children: [
                    primary,
                    const Spacer(),
                    SizedBox(width: 284, child: tools(compact: true)),
                  ],
                );
              }
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  primary,
                  if (primaryFirst) progress(),
                  const SizedBox(height: 8),
                  Align(
                    alignment: Alignment.centerRight,
                    child: SizedBox(
                      width: size.maxWidth.clamp(
                        0.0,
                        state.senior ? 500.0 : 380.0,
                      ),
                      child: tools(compact: true),
                    ),
                  ),
                ],
              );
            },
          ),
          if (state.singing['clipped'] == true) const Text('声音偏大，请稍微远离麦克风'),
        ],
      ),
    );
  }

  Widget practiceTools() {
    final scheme = _theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (!widget.state.taking) ...[
            recordingStatus(),
            const SizedBox(height: 12),
          ],
          Row(
            children: [
              if (largeControls)
                Icon(
                  Icons.headphones_rounded,
                  size: 24,
                  color: scheme.onSurfaceVariant,
                )
              else
                Text(
                  '演唱声音',
                  style: _theme.textTheme.bodySmall?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              const SizedBox(width: 12),
              Expanded(
                child: Align(
                  alignment: Alignment.centerRight,
                  child: SizedBox(
                    width: largeControls ? double.infinity : roleWidth(),
                    child: roles(),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          tools(),
          if (widget.state.singing['clipped'] == true)
            Text('声音偏大，请稍微远离麦克风', style: _theme.textTheme.bodySmall),
        ],
      ),
    );
  }

  Widget portrait(Song song, BoxConstraints size) => Stack(
    children: [
      Positioned.fill(
        child: Column(
          children: [
            header(song, dense: size.maxHeight < 680),
            if (widget.state.taking)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: recordingStatus(),
                ),
              ),
            Expanded(
              child: LayoutBuilder(
                builder: (context, available) {
                  // Only the content scrolls. Playback and the explicit microphone
                  // action stay in the safe area, including at large text sizes.
                  final mediaHeight = math.max(
                    math.min(
                      available.maxHeight,
                      largeControls ? 280.0 : 240.0,
                    ),
                    available.maxHeight - (largeControls ? 208.0 : 172.0),
                  );
                  return SingleChildScrollView(
                    key: const ValueKey('player-content'),
                    child: Column(
                      children: [
                        SizedBox(
                          height: mediaHeight,
                          child: media(song, landscape: false),
                        ),
                        status(song),
                        practiceTools(),
                      ],
                    ),
                  );
                },
              ),
            ),
            controls(),
          ],
        ),
      ),
      if (widget.state.countdown > 0) Positioned.fill(child: countdown()),
    ],
  );

  Widget status(Song song) {
    final state = widget.state;
    final failure =
        openError ??
        (['ERROR', 'AUTH_REQUIRED'].contains(state.playbackState)
            ? state.playbackError
            : null);
    if (failure != null) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(failure, maxLines: 3, overflow: TextOverflow.ellipsis),
            Wrap(
              spacing: 12,
              alignment: WrapAlignment.center,
              children: [
                TextButton(
                  onPressed: () async {
                    if (!state.authenticated) {
                      await openConnection(context, state);
                      if (!mounted || !state.authenticated) return;
                    }
                    await prepare(retry: true);
                  },
                  child: Text(state.authenticated ? '重试播放' : '登录后重试'),
                ),
                if (state.authenticated)
                  TextButton(
                    onPressed: () => prepare(compatible: true, retry: true),
                    child: const Text('兼容播放'),
                  ),
              ],
            ),
          ],
        ),
      );
    }
    if (opening || ['PREPARING', 'BUFFERING'].contains(state.playbackState)) {
      return const Padding(
        padding: EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            LinearProgressIndicator(),
            SizedBox(height: 8),
            Text('正在准备歌曲…'),
          ],
        ),
      );
    }
    final message = state.playbackError.isNotEmpty
        ? state.playbackError
        : recordingError;
    if (message != null && message.isNotEmpty) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
        child: Text(message, textAlign: TextAlign.center),
      );
    }
    return const SizedBox.shrink();
  }

  Widget countdown() => ColoredBox(
    color: const Color(0xDB0C1017),
    child: DefaultTextStyle.merge(
      style: const TextStyle(color: Colors.white),
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('准备好，跟着音乐唱'),
              Text(
                '${widget.state.countdown}',
                style: const TextStyle(
                  fontSize: 76,
                  fontWeight: FontWeight.w500,
                ),
              ),
              const Text('倒数结束后开始录音'),
              const SizedBox(height: 16),
              OutlinedButton(
                style: OutlinedButton.styleFrom(
                  foregroundColor: Colors.white,
                  backgroundColor: Colors.white12,
                ),
                onPressed: record,
                child: const Text('取消录音'),
              ),
            ],
          ),
        ),
      ),
    ),
  );

  Widget immersiveMedia(Song song) {
    final state = widget.state;
    final top = controlsVisible
        ? (state.recording ? 122.0 : 70.0)
        : state.recording
        ? 70.0
        : 12.0;
    final bottom = controlsVisible ? dockHeight + 8 : 12.0;
    if (song.mediaType == 'VIDEO') {
      return Stack(
        children: [
          Positioned.fill(child: video()),
          // Own the hit surface above AndroidView. A detector around a platform
          // view can lose taps to its native gesture recognizer on real devices.
          Positioned.fill(
            child: GestureDetector(
              key: const ValueKey('player-video-tap-layer'),
              behavior: HitTestBehavior.opaque,
              onTap: toggleControls,
              child: const ColoredBox(color: Colors.transparent),
            ),
          ),
          if (lines.isNotEmpty)
            Positioned.fill(
              child: Padding(
                padding: EdgeInsets.fromLTRB(48, top, 48, bottom),
                child: lyrics(currentOnly: true),
              ),
            ),
        ],
      );
    }
    return ColoredBox(
      color: stageColor,
      child: Padding(
        padding: EdgeInsets.fromLTRB(40, top, 40, bottom),
        child: lines.isNotEmpty
            ? lyrics()
            : LayoutBuilder(
                builder: (context, size) => SingleChildScrollView(
                  child: ConstrainedBox(
                    constraints: BoxConstraints(minHeight: size.maxHeight),
                    child: Center(
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Artwork(
                            state: state,
                            path: song.cover,
                            label: song.title,
                            size: size.maxHeight.clamp(64.0, 200.0),
                          ),
                          const SizedBox(width: 24),
                          Flexible(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  song.title,
                                  style: _theme.textTheme.titleLarge,
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  lyricsLoading
                                      ? '正在读取歌词…'
                                      : lyricError ?? '没有独立歌词，听着旋律轻松跟唱',
                                  style: _theme.textTheme.bodySmall?.copyWith(
                                    color: Colors.white60,
                                  ),
                                ),
                                if (lyricError != null)
                                  TextButton(
                                    onPressed: () => loadLyrics(song),
                                    child: const Text('重试歌词'),
                                  ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
      ),
    );
  }

  Widget chrome(Widget child) => IgnorePointer(
    ignoring: !controlsVisible,
    child: ExcludeSemantics(
      excluding: !controlsVisible,
      child: AnimatedOpacity(
        opacity: controlsVisible ? 1 : 0,
        duration: MediaQuery.disableAnimationsOf(context)
            ? Duration.zero
            : const Duration(milliseconds: 180),
        child: child,
      ),
    ),
  );

  Widget immersive(Song song, BoxConstraints size) {
    measureChrome(size.maxHeight);
    // Keep a real lyric reading area even on a short screen with large type.
    // Extra controls scroll inside their floating surface rather than shrinking lyrics to zero.
    final textScale = MediaQuery.textScalerOf(context).scale(28);
    final readingHeight = (textScale * 2.7).clamp(80.0, size.maxHeight * .45);
    final topClearance = widget.state.recording ? 122.0 : 70.0;
    final maxDockHeight = (size.maxHeight - topClearance - readingHeight - 8)
        .clamp(72.0, size.maxHeight * .65);
    return Listener(
      onPointerDown: (_) {
        pointerHeld = true;
        keyboardNavigation = false;
        controlsTimer?.cancel();
      },
      onPointerUp: (_) {
        pointerHeld = false;
        armControls();
      },
      onPointerCancel: (_) {
        pointerHeld = false;
        armControls();
      },
      child: Focus(
        autofocus: true,
        onKeyEvent: (_, event) {
          if (event is KeyDownEvent) {
            keyboardNavigation = true;
            revealControls();
          }
          return KeyEventResult.ignored;
        },
        child: Stack(
          children: [
            Positioned.fill(
              child: SizedBox.expand(
                key: const ValueKey('player-landscape-surface'),
                child: song.mediaType == 'VIDEO'
                    ? immersiveMedia(song)
                    : GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: toggleControls,
                        child: immersiveMedia(song),
                      ),
              ),
            ),
            // Accessible users can always summon controls without finding a gesture.
            Positioned.fill(
              child: Semantics(
                container: true,
                label: controlsVisible ? '隐藏播放控制' : '显示播放控制',
                button: true,
                onTap: revealControls,
                child: const IgnorePointer(child: SizedBox.expand()),
              ),
            ),
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: chrome(
                DecoratedBox(
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [Color(0xD9090C11), Color(0x00090C11)],
                    ),
                  ),
                  child: header(song, compact: true),
                ),
              ),
            ),
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: chrome(
                ConstrainedBox(
                  key: dockKey,
                  constraints: BoxConstraints(maxHeight: maxDockHeight),
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: stageColor.withValues(
                        alpha: widget.state.reduceTransparency ? 1 : .96,
                      ),
                      border: Border(
                        top: BorderSide(
                          color: Colors.white.withValues(alpha: .08),
                        ),
                      ),
                    ),
                    child: SingleChildScrollView(
                      key: const ValueKey('landscape-controls'),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [status(song), controls(compact: true)],
                      ),
                    ),
                  ),
                ),
              ),
            ),
            if (widget.state.recording)
              Positioned(
                top: controlsVisible ? 70 : 10,
                right: 16,
                child: ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: size.maxWidth - 32),
                  child: DecoratedBox(
                    key: const ValueKey('landscape-recording'),
                    decoration: BoxDecoration(
                      color: const Color(0xF219141D),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: coral.withValues(alpha: .3)),
                    ),
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(12, 4, 4, 4),
                      child: Wrap(
                        spacing: 12,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          recordingStatus(),
                          recordButton(
                            key: const ValueKey('player-record'),
                            compact: true,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            if (widget.state.countdown > 0) Positioned.fill(child: countdown()),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, bounds) {
      playerBrightness = bounds.maxWidth > bounds.maxHeight
          ? Brightness.dark
          : Theme.of(context).brightness;
      return Theme(
        data: _theme,
        child: Builder(
          builder: (context) => ListenableBuilder(
            listenable: widget.state.performance,
            builder: (context, _) {
              final state = widget.state;
              final song = opening || openError != null
                  ? widget.song ?? state.current
                  : state.current ?? widget.song;
              return PopScope(
                canPop: allowPop,
                onPopInvokedWithResult: (didPop, result) {
                  if (!didPop) close();
                },
                child: Scaffold(
                  backgroundColor: pageColor,
                  appBar: AppBar(
                    toolbarHeight: 0,
                    backgroundColor: pageColor,
                    systemOverlayStyle: _theme.brightness == Brightness.dark
                        ? SystemUiOverlayStyle.light
                        : SystemUiOverlayStyle.dark,
                  ),
                  body: SafeArea(
                    child: song == null
                        ? Notice('还没有正在演唱的歌曲。', action: close, label: '返回曲库')
                        : LayoutBuilder(
                            builder: (context, size) {
                              final wide = size.maxWidth > size.maxHeight;
                              accessibleControls =
                                  MediaQuery.accessibleNavigationOf(context) ||
                                  MediaQuery.viewInsetsOf(context).bottom > 0;
                              if (accessibleControls) {
                                controlsVisible = true;
                                controlsTimer?.cancel();
                              }
                              updateLayout(wide);
                              if (wide) return immersive(song, size);
                              measureChrome(size.maxHeight);
                              return portrait(song, size);
                            },
                          ),
                  ),
                ),
              );
            },
          ),
        ),
      );
    },
  );
}

void _paintPracticeBracket(
  Canvas canvas,
  Offset center, {
  required bool start,
  required Color color,
  required double height,
}) {
  final cap = start ? 4.0 : -4.0;
  canvas.drawPath(
    Path()
      ..moveTo(center.dx + cap, center.dy - height / 2)
      ..lineTo(center.dx, center.dy - height / 2)
      ..lineTo(center.dx, center.dy + height / 2)
      ..lineTo(center.dx + cap, center.dy + height / 2),
    Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.round,
  );
}

class _PracticeTrack extends RoundedRectSliderTrackShape {
  const _PracticeTrack({
    required this.start,
    required this.end,
    required this.duration,
  });
  final int start, end, duration;

  @override
  void paint(
    PaintingContext context,
    Offset offset, {
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required Animation<double> enableAnimation,
    required TextDirection textDirection,
    required Offset thumbCenter,
    Offset? secondaryOffset,
    bool isDiscrete = false,
    bool isEnabled = false,
    double additionalActiveTrackHeight = 2,
  }) {
    super.paint(
      context,
      offset,
      parentBox: parentBox,
      sliderTheme: sliderTheme,
      enableAnimation: enableAnimation,
      textDirection: textDirection,
      thumbCenter: thumbCenter,
      secondaryOffset: secondaryOffset,
      isDiscrete: isDiscrete,
      isEnabled: isEnabled,
      additionalActiveTrackHeight: additionalActiveTrackHeight,
    );
    if (duration <= 0 || end <= start) return;
    final rect = getPreferredRect(
      parentBox: parentBox,
      offset: offset,
      sliderTheme: sliderTheme,
      isEnabled: isEnabled,
      isDiscrete: isDiscrete,
    );
    final color = sliderTheme.activeTrackColor ?? berry;
    for (final boundary in [(start, true), (end, false)]) {
      final fraction = (boundary.$1 / duration).clamp(0.0, 1.0);
      final rtl = textDirection == TextDirection.rtl;
      _paintPracticeBracket(
        context.canvas,
        Offset(
          rect.left + rect.width * (rtl ? 1 - fraction : fraction),
          rect.center.dy,
        ),
        start: rtl ? !boundary.$2 : boundary.$2,
        color: color,
        height: 12,
      );
    }
  }
}

class _LyricRangePainter extends CustomPainter {
  const _LyricRangePainter({
    required this.start,
    required this.end,
    required this.color,
  });
  final bool start, end;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    if (start) {
      _paintPracticeBracket(
        canvas,
        Offset(-12, size.height / 2),
        start: true,
        color: color,
        height: 32,
      );
    }
    if (end) {
      _paintPracticeBracket(
        canvas,
        Offset(size.width + 12, size.height / 2),
        start: false,
        color: color,
        height: 32,
      );
    }
  }

  @override
  bool shouldRepaint(_LyricRangePainter oldDelegate) =>
      start != oldDelegate.start ||
      end != oldDelegate.end ||
      color != oldDelegate.color;
}

double _lyricFontSize(BuildContext context, bool senior, {bool active = true}) {
  if (senior) return active ? 32 : 23;
  final narrow = MediaQuery.sizeOf(context).width < 375;
  return active ? (narrow ? 18 : 20) : (narrow ? 15 : 16);
}

class LyricsView extends StatefulWidget {
  const LyricsView({
    super.key,
    required this.lines,
    required this.positionMs,
    required this.senior,
    required this.enabled,
    required this.onSeek,
    this.currentOnly = false,
    this.onBackgroundTap,
    this.rangeA = 0,
    this.rangeB = 0,
  });
  final bool currentOnly;
  final VoidCallback? onBackgroundTap;
  final List<TextLine> lines;
  final int positionMs;
  final int rangeA, rangeB;
  final bool senior, enabled;
  final ValueChanged<int> onSeek;
  @override
  State<LyricsView> createState() => _LyricsViewState();
}

class _LyricsViewState extends State<LyricsView> {
  final controller = ScrollController();
  int lastActive = -2;
  double lastWidth = 0, lastHeight = 0, lastScale = 0;
  DateTime manualUntil = DateTime.fromMillisecondsSinceEpoch(0);
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, size) {
      final scaler = MediaQuery.textScalerOf(context);
      final scheme = Theme.of(context).colorScheme;
      final hasRange = widget.rangeB > widget.rangeA;
      final rangeStart = hasRange
          ? widget.lines.lastIndexWhere((line) => line.startMs <= widget.rangeA)
          : -1;
      final rangeEnd = hasRange
          ? widget.lines.lastIndexWhere((line) => line.startMs < widget.rangeB)
          : -1;
      final active = widget.lines.lastIndexWhere(
        (line) => line.startMs <= widget.positionMs,
      );
      if (widget.currentOnly) {
        if (widget.lines.isEmpty) return const SizedBox.shrink();
        final index = active < 0 ? 0 : active;
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          excludeFromSemantics: true,
          onTap: widget.onBackgroundTap,
          child: SingleChildScrollView(
            key: const ValueKey('lyrics-viewport'),
            child: ConstrainedBox(
              constraints: BoxConstraints(minHeight: size.maxHeight),
              child: Align(
                alignment: Alignment.bottomCenter,
                child: Semantics(
                  button: widget.enabled,
                  selected: true,
                  child: InkWell(
                    onTap: widget.enabled
                        ? () => widget.onSeek(
                            widget.lines[index].startMs.clamp(0, 1 << 53),
                          )
                        : null,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 12,
                      ),
                      child: Text(
                        key: ValueKey('lyrics-line-$index'),
                        widget.lines[index].text,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: widget.senior ? 26 : 20,
                          height: 1.45,
                          fontWeight: FontWeight.w600,
                          shadows: const [
                            Shadow(
                              color: Colors.black,
                              blurRadius: 6,
                              offset: Offset(0, 2),
                            ),
                            Shadow(color: Colors.black87, blurRadius: 18),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
      }
      TextStyle styleFor(int index) => TextStyle(
        fontSize: _lyricFontSize(
          context,
          widget.senior,
          active: index == active,
        ),
        height: 1.4,
        fontWeight: index == active
            ? (widget.senior ? FontWeight.w600 : FontWeight.w500)
            : FontWeight.w400,
      );
      final heights = <double>[];
      final textHeights = <double>[];
      for (var index = 0; index < widget.lines.length; index++) {
        final painter = TextPainter(
          text: TextSpan(
            text: widget.lines[index].text.isEmpty
                ? ' '
                : widget.lines[index].text,
            style: styleFor(index),
          ),
          textDirection: Directionality.of(context),
          textScaler: scaler,
        )..layout(maxWidth: (size.maxWidth - 48).clamp(1, double.infinity));
        final padding = widget.senior
            ? (index == active ? 36 : 28)
            : (index == active ? 24 : 20);
        heights.add(
          math.max(widget.senior ? 56 : 48, painter.height + padding),
        );
        textHeights.add(painter.height);
        painter.dispose();
      }
      final topPadding = size.maxHeight * .26;
      if (active != lastActive ||
          size.maxWidth != lastWidth ||
          size.maxHeight != lastHeight ||
          scaler.scale(_lyricFontSize(context, widget.senior)) != lastScale) {
        lastActive = active;
        lastWidth = size.maxWidth;
        lastHeight = size.maxHeight;
        lastScale = scaler.scale(_lyricFontSize(context, widget.senior));
        final offset = heights
            .take(active < 0 ? 0 : active)
            .fold<double>(0, (sum, h) => sum + h);
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted ||
              !controller.hasClients ||
              DateTime.now().isBefore(manualUntil)) {
            return;
          }
          final activeHeight = active < 0 ? 0.0 : heights[active];
          final textHeight = active < 0 ? 0.0 : textHeights[active];
          final target =
              (active < 0
                      ? 0.0
                      : topPadding +
                            offset +
                            (textHeight > size.maxHeight
                                ? (activeHeight - textHeight) / 2
                                : activeHeight / 2 - size.maxHeight / 2))
                  .clamp(0.0, controller.position.maxScrollExtent);
          if (widget.senior ||
              scaler.scale(16) > 23 ||
              MediaQuery.disableAnimationsOf(context)) {
            controller.jumpTo(target);
          } else {
            controller.animateTo(
              target,
              duration: const Duration(milliseconds: 240),
              curve: Curves.easeOutCubic,
            );
          }
        });
      }
      return NotificationListener<ScrollStartNotification>(
        onNotification: (notice) {
          if (notice.dragDetails != null) {
            manualUntil = DateTime.now().add(const Duration(seconds: 4));
          }
          return false;
        },
        child: ListView.builder(
          key: const ValueKey('lyrics-viewport'),
          controller: controller,
          padding: EdgeInsets.fromLTRB(
            24,
            topPadding,
            24,
            size.maxHeight * .58,
          ),
          itemCount: widget.lines.length,
          itemExtentBuilder: (index, dimensions) => heights[index],
          itemBuilder: (context, index) => Semantics(
            button: widget.enabled,
            selected: index == active,
            label: index == rangeStart && index == rangeEnd
                ? '选段起止句'
                : index == rangeStart
                ? '选段起句'
                : index == rangeEnd
                ? '选段结束句'
                : null,
            child: InkWell(
              onTap: widget.enabled
                  ? () {
                      manualUntil = DateTime.fromMillisecondsSinceEpoch(0);
                      widget.onSeek(
                        widget.lines[index].startMs.clamp(0, 1 << 53),
                      );
                    }
                  : null,
              child: CustomPaint(
                painter: _LyricRangePainter(
                  start: index == rangeStart,
                  end: index == rangeEnd,
                  color: scheme.primary,
                ),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    key: ValueKey('lyrics-line-$index'),
                    widget.lines[index].text,
                    style: styleFor(index).copyWith(
                      color: index == active
                          ? scheme.primary
                          : scheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    },
  );
}
