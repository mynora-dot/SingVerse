import 'dart:async';

import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import '../library/widgets.dart';
import '../player/player_page.dart';
import 'actions.dart';

class PreparePage extends StatefulWidget {
  const PreparePage({super.key, required this.state});
  final AppState state;
  @override
  State<PreparePage> createState() => _PreparePageState();
}

class _PreparePageState extends State<PreparePage> {
  String lyrics = '正在检查歌词…';
  @override
  void initState() {
    super.initState();
    checkText();
  }

  Future<void> checkText() async {
    try {
      final result = await widget.state.get(
        '/api/v1/mobile/library/songs/${widget.state.current!.id}/text-tracks',
      ) as Json;
      if (mounted) {
        setState(
          () => lyrics = (result['items'] as List).isEmpty
              ? '无独立歌词 · 可观看视频字幕'
              : '同步歌词已就绪',
        );
      }
    } catch (_) {
      if (mounted) setState(() => lyrics = '歌词暂时不可读 · 可继续练唱');
    }
  }

  @override
  void dispose() {
    if (widget.state.singingPhase == 'TESTING') {
      unawaited(widget.state.sing('test', {'start': false}));
    }
    super.dispose();
  }

  Future<void> start(bool record) => uiTask(context, () async {
    final state = widget.state;
    if (record && !await confirmRecording(context, state)) return;
    if (state.singingPhase == 'TESTING') {
      await state.sing('test', {'start': false});
    }
    await state.command(record ? 'record' : 'play', record ? true : null);
    if (mounted) {
      await Navigator.push(
        context,
        MaterialPageRoute<void>(
          settings: const RouteSettings(name: 'player'),
          builder: (_) => PlayerPage(state: state, fromPreparation: true),
        ),
      );
    }
  });
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state,
    builder: (context, _) {
      final state = widget.state, song = state.current;
      final ready =
          ['PAUSED', 'PLAYING'].contains(state.playbackState) &&
          state.tracks.any((t) => t['type'] == 'audio');
      final meter = ((state.singing['level'] as num? ?? 0).toDouble() * 6)
          .clamp(0.0, 1.0);
      return Scaffold(
        appBar: AppBar(title: const Text('演唱准备'), centerTitle: true),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 12, 24, 28),
            children: [
              if (song != null)
                Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Artwork(
                      state: state,
                      path: song.cover,
                      size: 88,
                      label: song.title,
                    ),
                    const SizedBox(width: 18),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            song.title,
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                            style: Theme.of(context).textTheme.headlineSmall,
                          ),
                          const SizedBox(height: 5),
                          Text(song.artist),
                          const SizedBox(height: 5),
                          Text(
                            '${song.format} · ${song.version}',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              const SizedBox(height: 28),
              Text(
                '先准备好，再尽情唱。',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 8),
              Text(
                '练唱不用开麦，录唱会把你的声音和伴奏一起留下。',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 24),
              Card(
                child: Column(
                  children: [
                    ListTile(
                      leading: const Icon(Icons.queue_music_rounded),
                      title: const Text('伴奏与导唱'),
                      subtitle: Text(
                        state.backingMapped
                            ? '已确认伴奏，可随时切换导唱练习'
                            : '请试听并确认音轨，不会自动消除原唱',
                      ),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      onTap: () => singingSettings(context, state),
                    ),
                    const Divider(height: 1, indent: 56),
                    ListTile(
                      leading: const Icon(Icons.lyrics_outlined),
                      title: const Text('歌词'),
                      subtitle: Text(lyrics),
                    ),
                    const Divider(height: 1, indent: 56),
                    ListTile(
                      leading: const Icon(Icons.headphones_outlined),
                      title: const Text('声音输出'),
                      subtitle: Text(
                        state.outputRoute.isEmpty
                            ? '等待设备报告'
                            : state.outputRoute,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 18),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(18),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(Icons.mic_none_rounded),
                          const SizedBox(width: 10),
                          const Expanded(
                            child: Text(
                              '试试你的声音',
                              style: TextStyle(fontWeight: FontWeight.w700),
                            ),
                          ),
                          TextButton(
                            onPressed: ready
                                ? () => uiTask(context, () async {
                                    if (state.singingPhase == 'TESTING') {
                                      await state.sing('test', {
                                        'start': false,
                                      });
                                    } else if (await microphonePermission(
                                      context,
                                      state,
                                    )) {
                                      await state.sing('test', {'start': true});
                                    }
                                  })
                                : null,
                            child: Text(
                              state.singingPhase == 'TESTING' ? '结束试音' : '开始试音',
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      LinearProgressIndicator(
                        value: meter,
                        minHeight: 6,
                        borderRadius: BorderRadius.circular(8),
                        backgroundColor: mint.withValues(alpha: .12),
                        color: state.singing['clipped'] == true ? coral : mint,
                      ),
                      const SizedBox(height: 12),
                      Text(
                        state.singingPhase == 'TESTING'
                            ? '正在采集 · ${state.singing['input']}'
                            : '只在试音和录唱时开启麦克风，不做实时耳返。',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                '可用空间 · ${((state.singing['freeBytes'] as num? ?? 0) / 1073741824).toStringAsFixed(1)} GB',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              if (state.playbackError.isNotEmpty) Notice(state.playbackError),
              if (!ready && state.playbackError.isEmpty)
                const Notice('正在准备演唱素材…'),
              const SizedBox(height: 24),
              SizedBox(
                height: 58,
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: coral,
                    foregroundColor: Colors.white,
                  ),
                  onPressed: ready && !state.taking ? () => start(true) : null,
                  icon: const Icon(Icons.mic_rounded),
                  label: const Text(
                    '开始录唱',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton(
                onPressed: ready ? () => start(false) : null,
                child: const Text('仅练唱 · 不开启麦克风'),
              ),
            ],
          ),
        ),
      );
    },
  );
}
