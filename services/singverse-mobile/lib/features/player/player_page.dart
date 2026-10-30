import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import '../library/widgets.dart';
import '../singing/actions.dart';
import '../singing/works_page.dart';
import '../tv_control/tv_page.dart';

Widget performancePage(AppState state) => state.workId.isNotEmpty
    ? ReviewPage(state: state, id: state.workId)
    : PlayerPage(state: state);

class PlayerPage extends StatefulWidget {
  const PlayerPage({
    super.key,
    required this.state,
    this.fromPreparation = false,
  });
  final AppState state;
  final bool fromPreparation;
  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  final lyricScroll = ScrollController();
  List<TextLine> lines = [];
  String? lyricError, songId;
  bool loading = true, expandedVideo = false, guiding = false;
  int lastLine = -1;
  String reviewed = '';
  int? selectA;
  double? drag;
  double extent = 92;
  @override
  void initState() {
    super.initState();
    if (!widget.state.recording &&
        (widget.fromPreparation ||
            widget.state.countdown > 0 ||
            widget.state.isPlaying)) {
      reviewed = widget.state.takeId;
    }
    widget.state.addListener(watch);
    unawaited(loadLyrics());
  }

  @override
  void dispose() {
    widget.state.removeListener(watch);
    lyricScroll.dispose();
    super.dispose();
  }

  Future<void> loadLyrics() async {
    final song = widget.state.current;
    if (song == null) return;
    songId = song.id;
    try {
      final data = await widget.state.get(
        '/api/v1/mobile/library/songs/${song.id}/text-tracks',
      ) as Json;
      final items = (data['items'] as List).cast<Json>();
      final usable = items
          .map((i) => parseText(i['format'] as String, i['text'] as String))
          .where((l) => l.isNotEmpty)
          .firstOrNull;
      if (mounted) {
        setState(() {
          lines = usable ?? [];
          loading = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          loading = false;
          lyricError = '歌词暂时不可读，可继续观看视频或跟唱。';
        });
      }
    }
  }

  void watch() {
    final state = widget.state;
    if (state.countdown == 0 &&
        state.takeId.isNotEmpty &&
        reviewed != state.takeId &&
        ['FINALIZING', 'PREVIEW'].contains(state.singingPhase)) {
      reviewed = state.takeId;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && ModalRoute.of(context)?.isCurrent == true) {
          Navigator.push(
            context,
            MaterialPageRoute<void>(
              settings: const RouteSettings(name: 'review'),
              builder: (_) => ReviewPage(state: state, id: reviewed),
            ),
          );
        }
      });
    }
    final index = lines.lastIndexWhere((l) => l.startMs <= state.positionMs);
    if (index != lastLine && index >= 0) {
      lastLine = index;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && lyricScroll.hasClients) {
          lyricScroll.animateTo(
            (index * extent).clamp(0.0, lyricScroll.position.maxScrollExtent),
            duration: const Duration(milliseconds: 260),
            curve: Curves.easeOutCubic,
          );
        }
      });
    }
  }

  Future<void> close() async {
    final state = widget.state;
    if (state.recording) {
      final end = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('结束这次录唱？'),
          content: const Text('保留已经唱过的片段，接下来可以回听和保存。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('继续录唱'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('结束并回听'),
            ),
          ],
        ),
      );
      if (end == true) await state.endTake();
      return;
    }
    if (state.countdown > 0) await state.command('cancelCountdown');
    if (!mounted) return;
    if (state.singingPhase == 'PREVIEW' && state.takeId.isNotEmpty) {
      final action = await showDialog<String>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('这段演唱还没有保存'),
          content: const Text('可以保留为待处理录音，或先回听再保存。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, 'cancel'),
              child: const Text('继续演唱'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(context, 'keep'),
              child: const Text('保留待处理'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, 'review'),
              child: const Text('回听并保存'),
            ),
          ],
        ),
      );
      if (action == 'review' && mounted) {
        await Navigator.push(
          context,
          MaterialPageRoute<void>(
            settings: const RouteSettings(name: 'review'),
            builder: (_) => ReviewPage(state: state, id: state.takeId),
          ),
        );
        return;
      }
      if (action != 'keep') return;
    }
    if (mounted) Navigator.pop(context);
  }

  Future<void> record() => uiTask(context, () async {
    final state = widget.state;
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
  });
  Future<void> range() async {
    final state = widget.state;
    if (state.durationMs <= 0 || state.taking) return;
    var values = RangeValues(
      state.rangeA.toDouble(),
      state.rangeB > state.rangeA
          ? state.rangeB.toDouble()
          : state.durationMs.toDouble(),
    );
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (context) => StatefulBuilder(
        builder: (context, update) => SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  '把难的一段，多唱几遍。',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 16),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('A ${durationLabel(values.start.round())}'),
                    Text('B ${durationLabel(values.end.round())}'),
                  ],
                ),
                RangeSlider(
                  values: values,
                  max: state.durationMs.toDouble(),
                  onChanged: (v) => update(() => values = v),
                ),
                const SizedBox(height: 12),
                FilledButton(
                  onPressed: () => uiTask(context, () async {
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
                  onPressed: () async {
                    await state.command('range', {'a': 0, 'b': 0});
                    if (context.mounted) Navigator.pop(context);
                  },
                  child: const Text('恢复整首演唱'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget fullVideo(AppState state, Song song) => SafeArea(
    child: Stack(
      children: [
        const Positioned.fill(child: AndroidView(viewType: 'singverse/video')),
        Positioned(
          top: 0,
          left: 0,
          right: 0,
          child: ColoredBox(
            color: Colors.black54,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Row(
                children: [
                  IconButton(
                    tooltip: '收起视频',
                    onPressed: () => setState(() => expandedVideo = false),
                    icon: const Icon(Icons.fullscreen_exit_rounded),
                  ),
                  Expanded(
                    child: Text(
                      song.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 18,
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Text(
                    state.recording
                        ? '● 已录 ${durationLabel((state.singing['recordedMs'] as num? ?? 0).toInt())}'
                        : state.countdown > 0
                        ? '准备开唱'
                        : '练唱 · 不开麦',
                    style: TextStyle(
                      color: state.recording ? coral : Colors.white70,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        Positioned(
          bottom: 0,
          left: 0,
          right: 0,
          child: ColoredBox(
            color: Colors.black54,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 10),
              child: Row(
                children: [
                  IconButton(
                    tooltip: state.isPlaying ? '暂停' : '播放',
                    onPressed: state.taking
                        ? null
                        : () => uiTask(
                            context,
                            () => state.command(
                              state.isPlaying ? 'pause' : 'play',
                            ),
                          ),
                    icon: Icon(
                      state.isPlaying
                          ? Icons.pause_rounded
                          : Icons.play_arrow_rounded,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Text(
                    '${durationLabel(state.positionMs)} / ${durationLabel(state.durationMs)}',
                  ),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: () => singingSettings(context, state),
                    icon: const Icon(Icons.tune_rounded),
                    label: const Text('调音'),
                  ),
                  const SizedBox(width: 16),
                  FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: coral,
                      foregroundColor: Colors.white,
                    ),
                    onPressed: record,
                    icon: Icon(
                      state.recording ? Icons.stop_rounded : Icons.mic_rounded,
                    ),
                    label: Text(
                      state.recording
                          ? '结束录唱'
                          : state.countdown > 0
                          ? '取消倒数'
                          : '开始录唱',
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        if (state.countdown > 0)
          Center(
            child: Text(
              '${state.countdown}',
              style: const TextStyle(
                color: Colors.white,
                fontSize: 100,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) => Theme(
    data: singVerseTheme(Brightness.dark),
    child: ListenableBuilder(
      listenable: widget.state,
      builder: (context, _) {
        final state = widget.state, song = state.current;
        if (song == null) {
          return Scaffold(
            appBar: AppBar(title: const Text('演唱舞台')),
            body: const Notice('选一首喜欢的歌，开始演唱。'),
          );
        }
        extent = MediaQuery.textScalerOf(context).scale(26) * 2.5 + 16;
        final level = ((state.singing['level'] as num? ?? 0).toDouble() * 8)
            .clamp(0.0, 1.0);
        final recording = state.recording;
        final active = lines.lastIndexWhere(
          (l) =>
              l.startMs <= state.positionMs &&
              (l.endMs == null || state.positionMs < l.endMs!),
        );
        final video = song.mediaType == 'VIDEO';
        final ready = ![
          'PREPARING',
          'ERROR',
          'AUTH_REQUIRED',
        ].contains(state.playbackState);
        return PopScope(
          canPop: false,
          onPopInvokedWithResult: (didPop, result) {
            if (!didPop) {
              if (expandedVideo &&
                  video &&
                  MediaQuery.orientationOf(context) == Orientation.landscape) {
                setState(() => expandedVideo = false);
              } else {
                close();
              }
            }
          },
          child: Scaffold(
            backgroundColor: const Color(0xFF101B1B),
            appBar: AppBar(
              toolbarHeight: 0,
              backgroundColor: const Color(0xFF101B1B),
              systemOverlayStyle: SystemUiOverlayStyle.light,
            ),
            body:
                video &&
                    expandedVideo &&
                    MediaQuery.orientationOf(context) == Orientation.landscape
                ? fullVideo(state, song)
                : Stack(
                    children: [
                      Positioned.fill(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: RadialGradient(
                              center: const Alignment(.5, -.7),
                              radius: 1.3,
                              colors: [
                                recording
                                    ? const Color(0xFF44252E)
                                    : const Color(0xFF254A43),
                                const Color(0xFF101819),
                              ],
                            ),
                          ),
                        ),
                      ),
                      SafeArea(
                        child: LayoutBuilder(
                          builder: (context, constraints) {
                            final videoExtra = video
                                ? (((constraints.maxWidth - 48) /
                                              (expandedVideo ? 1.6 : 16 / 7)) -
                                          160)
                                      .clamp(0.0, 10000.0)
                                : 0.0;
                            final minimum =
                                videoExtra +
                                720.0 +
                                (MediaQuery.textScalerOf(context).scale(26) -
                                        26) *
                                    8;
                            return SingleChildScrollView(
                              child: SizedBox(
                                height: constraints.maxHeight < minimum
                                    ? minimum
                                    : constraints.maxHeight,
                                child: Column(
                                  children: [
                                    Padding(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 12,
                                        vertical: 6,
                                      ),
                                      child: Row(
                                        children: [
                                          IconButton(
                                            tooltip: '收起演唱',
                                            onPressed: close,
                                            icon: const Icon(
                                              Icons.keyboard_arrow_down_rounded,
                                              color: Colors.white,
                                            ),
                                          ),
                                          Expanded(
                                            child: Column(
                                              children: [
                                                Text(
                                                  recording
                                                      ? '正在录唱'
                                                      : state.countdown > 0
                                                      ? '准备开唱'
                                                      : '正在练唱',
                                                  style: TextStyle(
                                                    color: recording
                                                        ? coral
                                                        : mint,
                                                    fontSize: 12,
                                                    fontWeight: FontWeight.w700,
                                                  ),
                                                ),
                                                const SizedBox(height: 3),
                                                Text(
                                                  song.title,
                                                  maxLines: 1,
                                                  overflow:
                                                      TextOverflow.ellipsis,
                                                  style: const TextStyle(
                                                    color: Colors.white,
                                                    fontSize: 18,
                                                    fontWeight: FontWeight.w700,
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                          IconButton(
                                            tooltip: '扫码点歌',
                                            onPressed: () =>
                                                openTv(context, state),
                                            icon: const Icon(
                                              Icons.qr_code_scanner_rounded,
                                              color: Colors.white70,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                    Padding(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 24,
                                      ),
                                      child: Row(
                                        children: [
                                          Expanded(
                                            child: Text(
                                              song.artist,
                                              maxLines: 1,
                                              overflow: TextOverflow.ellipsis,
                                              style: const TextStyle(
                                                color: Colors.white54,
                                                fontSize: 13,
                                              ),
                                            ),
                                          ),
                                          if (recording)
                                            Text(
                                              '● 已录 ${durationLabel((state.singing['recordedMs'] as num? ?? 0).toInt())}',
                                              style: const TextStyle(
                                                color: coral,
                                                fontSize: 12,
                                              ),
                                            ),
                                          if (!recording)
                                            const Text(
                                              '手机本机',
                                              style: TextStyle(
                                                color: Colors.white38,
                                                fontSize: 12,
                                              ),
                                            ),
                                        ],
                                      ),
                                    ),
                                    const SizedBox(height: 14),
                                    if (video)
                                      Padding(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: 24,
                                        ),
                                        child: ClipRRect(
                                          borderRadius: BorderRadius.circular(
                                            18,
                                          ),
                                          child: AspectRatio(
                                            aspectRatio: expandedVideo
                                                ? 16 / 10
                                                : 16 / 7,
                                            child: const AndroidView(
                                              viewType: 'singverse/video',
                                            ),
                                          ),
                                        ),
                                      ),
                                    if (video)
                                      Align(
                                        alignment: Alignment.centerRight,
                                        child: Padding(
                                          padding: const EdgeInsets.only(
                                            right: 18,
                                          ),
                                          child: IconButton(
                                            tooltip: '展开视频',
                                            onPressed: () => setState(
                                              () => expandedVideo =
                                                  !expandedVideo,
                                            ),
                                            icon: Icon(
                                              expandedVideo
                                                  ? Icons
                                                        .fullscreen_exit_rounded
                                                  : Icons.fullscreen_rounded,
                                              color: Colors.white54,
                                            ),
                                          ),
                                        ),
                                      ),
                                    Expanded(
                                      child: loading
                                          ? const Center(
                                              child:
                                                  CircularProgressIndicator(),
                                            )
                                          : lines.isEmpty
                                          ? Center(
                                              child: Padding(
                                                padding: const EdgeInsets.all(
                                                  28,
                                                ),
                                                child: Column(
                                                  mainAxisSize:
                                                      MainAxisSize.min,
                                                  children: [
                                                    if (!video)
                                                      Artwork(
                                                        state: state,
                                                        path: song.cover,
                                                        size: 160,
                                                        label: song.title,
                                                      ),
                                                    const SizedBox(height: 24),
                                                    Text(
                                                      lyricError ??
                                                          (video
                                                              ? '跟着画面，一起唱。'
                                                              : '跟着旋律，自在开唱。'),
                                                      textAlign:
                                                          TextAlign.center,
                                                      style: const TextStyle(
                                                        color: Colors.white,
                                                        fontSize: 24,
                                                        fontWeight:
                                                            FontWeight.w600,
                                                      ),
                                                    ),
                                                    const SizedBox(height: 12),
                                                    const Text(
                                                      '当前素材没有可用的独立歌词',
                                                      style: TextStyle(
                                                        color: Colors.white38,
                                                        fontSize: 13,
                                                      ),
                                                    ),
                                                  ],
                                                ),
                                              ),
                                            )
                                          : LayoutBuilder(
                                              builder: (context, size) => ListView.builder(
                                                controller: lyricScroll,
                                                padding: EdgeInsets.fromLTRB(
                                                  28,
                                                  size.maxHeight * .28,
                                                  28,
                                                  size.maxHeight * .48,
                                                ),
                                                itemExtent: extent,
                                                itemCount: lines.length,
                                                itemBuilder: (context, index) => GestureDetector(
                                                  onTap: state.taking
                                                      ? null
                                                      : () => uiTask(context, () async {
                                                          if (selectA == null) {
                                                            setState(
                                                              () => selectA =
                                                                  index,
                                                            );
                                                            ScaffoldMessenger.of(
                                                              context,
                                                            ).showSnackBar(
                                                              const SnackBar(
                                                                content: Text(
                                                                  '已选起句，再点一句作为结束。',
                                                                ),
                                                              ),
                                                            );
                                                          } else {
                                                            final a =
                                                                lines[selectA!]
                                                                    .startMs;
                                                            final b =
                                                                lines[index]
                                                                    .endMs ??
                                                                (index + 1 <
                                                                        lines
                                                                            .length
                                                                    ? lines[index +
                                                                              1]
                                                                          .startMs
                                                                    : state
                                                                          .durationMs);
                                                            if (b > a) {
                                                              await state
                                                                  .command(
                                                                    'range',
                                                                    {
                                                                      'a': a,
                                                                      'b': b,
                                                                    },
                                                                  );
                                                              await state
                                                                  .command(
                                                                    'seek',
                                                                    a,
                                                                  );
                                                            }
                                                            setState(
                                                              () => selectA =
                                                                  null,
                                                            );
                                                          }
                                                        }),
                                                  child: Align(
                                                    alignment:
                                                        Alignment.centerLeft,
                                                    child:
                                                        AnimatedDefaultTextStyle(
                                                          duration:
                                                              const Duration(
                                                                milliseconds:
                                                                    200,
                                                              ),
                                                          style: TextStyle(
                                                            color:
                                                                index == active
                                                                ? Colors.white
                                                                : index ==
                                                                      selectA
                                                                ? mint
                                                                : Colors.white
                                                                      .withValues(
                                                                        alpha:
                                                                            .30,
                                                                      ),
                                                            fontSize:
                                                                index == active
                                                                ? 30
                                                                : 25,
                                                            height: 1.4,
                                                            fontWeight:
                                                                FontWeight.w700,
                                                          ),
                                                          child: Text(
                                                            lines[index].text,
                                                            maxLines: 2,
                                                            overflow:
                                                                TextOverflow
                                                                    .ellipsis,
                                                          ),
                                                        ),
                                                  ),
                                                ),
                                              ),
                                            ),
                                    ),
                                    Padding(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 26,
                                      ),
                                      child: Column(
                                        children: [
                                          if (state.playbackError.isNotEmpty)
                                            Text(
                                              state.playbackError,
                                              style: const TextStyle(
                                                color: coral,
                                                fontSize: 12,
                                              ),
                                            ),
                                          Row(
                                            children: [
                                              Text(
                                                durationLabel(state.positionMs),
                                                style: const TextStyle(
                                                  color: Colors.white54,
                                                  fontSize: 11,
                                                ),
                                              ),
                                              Expanded(
                                                child: Slider(
                                                  value:
                                                      (drag ??
                                                              state.positionMs
                                                                  .toDouble())
                                                          .clamp(
                                                            0.0,
                                                            state.durationMs > 0
                                                                ? state
                                                                      .durationMs
                                                                      .toDouble()
                                                                : 1.0,
                                                          ),
                                                  max: state.durationMs > 0
                                                      ? state.durationMs
                                                            .toDouble()
                                                      : 1.0,
                                                  onChanged:
                                                      state.taking ||
                                                          !ready ||
                                                          state.durationMs == 0
                                                      ? null
                                                      : (v) => setState(
                                                          () => drag = v,
                                                        ),
                                                  onChangeEnd: (v) {
                                                    setState(() => drag = null);
                                                    uiTask(
                                                      context,
                                                      () => state.command(
                                                        'seek',
                                                        v.round(),
                                                      ),
                                                    );
                                                  },
                                                ),
                                              ),
                                              Text(
                                                state.durationMs > 0
                                                    ? durationLabel(
                                                        state.durationMs,
                                                      )
                                                    : '--:--',
                                                style: const TextStyle(
                                                  color: Colors.white54,
                                                  fontSize: 11,
                                                ),
                                              ),
                                            ],
                                          ),
                                          if (recording)
                                            Padding(
                                              padding: const EdgeInsets.only(
                                                bottom: 12,
                                              ),
                                              child: Row(
                                                children: [
                                                  const Icon(
                                                    Icons.mic_rounded,
                                                    color: coral,
                                                    size: 15,
                                                  ),
                                                  const SizedBox(width: 8),
                                                  Expanded(
                                                    child: LinearProgressIndicator(
                                                      value: level,
                                                      backgroundColor:
                                                          Colors.white12,
                                                      color:
                                                          state.singing['clipped'] ==
                                                              true
                                                          ? Colors.orange
                                                          : coral,
                                                      borderRadius:
                                                          BorderRadius.circular(
                                                            8,
                                                          ),
                                                      minHeight: 4,
                                                    ),
                                                  ),
                                                  const SizedBox(width: 8),
                                                  Text(
                                                    state.singing['clipped'] ==
                                                            true
                                                        ? '靠近太大声'
                                                        : '人声采集中',
                                                    style: const TextStyle(
                                                      color: Colors.white54,
                                                      fontSize: 10,
                                                    ),
                                                  ),
                                                ],
                                              ),
                                            ),
                                          Row(
                                            mainAxisAlignment:
                                                MainAxisAlignment.spaceBetween,
                                            children: [
                                              _tool(
                                                guiding
                                                    ? Icons.record_voice_over
                                                    : Icons.headphones_outlined,
                                                guiding ? '导唱' : '伴奏',
                                                () async {
                                                  if (!state.originalMapped ||
                                                      !state.backingMapped) {
                                                    await singingSettings(
                                                      context,
                                                      state,
                                                    );
                                                    return;
                                                  }
                                                  await uiTask(
                                                    context,
                                                    () => state.command(
                                                      'useRole',
                                                      guiding
                                                          ? 'backing'
                                                          : 'original',
                                                    ),
                                                  );
                                                  setState(
                                                    () => guiding = !guiding,
                                                  );
                                                },
                                                enabled: !state.taking,
                                              ),
                                              _tool(
                                                Icons.tune_rounded,
                                                '调音',
                                                () => singingSettings(
                                                  context,
                                                  state,
                                                ),
                                              ),
                                              _tool(
                                                Icons.repeat_rounded,
                                                state.rangeB > state.rangeA
                                                    ? '选段中'
                                                    : '选段',
                                                range,
                                                enabled: !state.taking,
                                              ),
                                              _tool(
                                                state.isPlaying
                                                    ? Icons.pause_rounded
                                                    : Icons.play_arrow_rounded,
                                                recording
                                                    ? '结束片段'
                                                    : state.isPlaying
                                                    ? '暂停'
                                                    : '继续',
                                                () => uiTask(
                                                  context,
                                                  () => state.command(
                                                    'pause' ==
                                                            (state.isPlaying
                                                                ? 'pause'
                                                                : 'play')
                                                        ? 'pause'
                                                        : 'play',
                                                  ),
                                                ),
                                                enabled:
                                                    ready &&
                                                    state.countdown == 0,
                                              ),
                                            ],
                                          ),
                                          const SizedBox(height: 18),
                                          SizedBox(
                                            height: 56,
                                            width: double.infinity,
                                            child: FilledButton.icon(
                                              style: FilledButton.styleFrom(
                                                backgroundColor: coral,
                                                foregroundColor: Colors.white,
                                                shape: RoundedRectangleBorder(
                                                  borderRadius:
                                                      BorderRadius.circular(18),
                                                ),
                                              ),
                                              onPressed:
                                                  ready ||
                                                      recording ||
                                                      state.countdown > 0
                                                  ? record
                                                  : null,
                                              icon: Icon(
                                                recording
                                                    ? Icons.stop_rounded
                                                    : Icons.mic_rounded,
                                              ),
                                              label: Text(
                                                recording
                                                    ? '结束录唱 · 去回听'
                                                    : state.countdown > 0
                                                    ? '取消倒数'
                                                    : '开始录唱',
                                                style: const TextStyle(
                                                  fontSize: 17,
                                                  fontWeight: FontWeight.w700,
                                                ),
                                              ),
                                            ),
                                          ),
                                          const SizedBox(height: 10),
                                          Text(
                                            recording
                                                ? '声音留在此手机，不自动上传'
                                                : '练唱模式 · 麦克风未开启',
                                            style: const TextStyle(
                                              color: Colors.white38,
                                              fontSize: 11,
                                            ),
                                          ),
                                          const SizedBox(height: 14),
                                        ],
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                      ),
                      if (state.countdown > 0)
                        Positioned.fill(
                          child: IgnorePointer(
                            child: BackdropFilter(
                              filter: ImageFilter.blur(sigmaX: 6, sigmaY: 6),
                              child: ColoredBox(
                                color: Colors.black.withValues(alpha: .35),
                                child: Center(
                                  child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Text(
                                        '${state.countdown}',
                                        style: const TextStyle(
                                          fontSize: 104,
                                          fontWeight: FontWeight.w700,
                                          color: Colors.white,
                                        ),
                                      ),
                                      const SizedBox(height: 12),
                                      const Text(
                                        '准备，把故事唱出来。',
                                        style: TextStyle(
                                          color: Colors.white70,
                                          fontSize: 18,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
          ),
        );
      },
    ),
  );
  Widget _tool(
    IconData icon,
    String title,
    FutureOr<void> Function() tap, {
    bool enabled = true,
  }) => SizedBox(
    width: 68,
    child: TextButton(
      onPressed: enabled ? tap : null,
      style: TextButton.styleFrom(
        foregroundColor: Colors.white70,
        disabledForegroundColor: Colors.white24,
        padding: const EdgeInsets.symmetric(vertical: 4),
      ),
      child: Column(
        children: [
          Icon(icon, size: 24),
          const SizedBox(height: 5),
          Text(title, style: const TextStyle(fontSize: 11)),
        ],
      ),
    ),
  );
}
