import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import '../library/connection.dart';
import '../player/player_page.dart';

Future<bool> microphonePermission(BuildContext context, AppState state) async {
  final result = await state.sing('permission');
  if (result == 'granted') return true;
  if (!context.mounted) return false;
  await showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('需要麦克风才能录音'),
      content: Text(
        result == 'settings' ? '请在系统设置中允许麦克风。不开启也能继续练唱。' : '没有获得麦克风权限，仍可继续练唱。',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('继续练唱'),
        ),
        if (result == 'settings')
          FilledButton(
            onPressed: () {
              Navigator.pop(context);
              state.sing('settings');
            },
            child: const Text('打开设置'),
          ),
      ],
    ),
  );
  return false;
}

Future<bool> keepCurrentRecording(BuildContext context, AppState state) async {
  if (state.taking) {
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('先结束当前录音？'),
        content: const Text('已录下的声音会保留在“作品 · 草稿”，之后可以回听和保存。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('结束并保留草稿'),
          ),
        ],
      ),
    );
    if (yes != true) return false;
    if (state.countdown > 0) await state.command('cancelCountdown');
    if (state.recording) await state.endTake();
  }
  for (
    var i = 0;
    i < 80 && (state.taking || state.singingPhase == 'RENDERING');
    i++
  ) {
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
  if (state.taking || state.singingPhase == 'RENDERING') {
    throw const FormatException('录音仍在处理中，请稍后重试。');
  }
  return context.mounted;
}

Future<void> openSinging(BuildContext context, AppState state, Song song) =>
    uiTask(context, () async {
      final same =
          state.current?.id == song.id &&
          state.workId.isEmpty &&
          state.playbackInstanceId == state.instanceId &&
          !['ERROR', 'AUTH_REQUIRED', 'IDLE'].contains(state.playbackState);
      if (!same && !await keepCurrentRecording(context, state)) return;
      if (!context.mounted) return;
      if (!same && !state.authenticated) {
        await openConnection(context, state);
        if (!state.authenticated || !context.mounted) return;
      }
      if (same && !state.taking && !state.isPlaying) {
        if (state.playbackState == 'ENDED') await state.command('seek', 0);
        await state.command('play');
      }
      if (!context.mounted) return;
      await Navigator.push(
        context,
        MaterialPageRoute<void>(
          settings: const RouteSettings(name: 'player'),
          builder: (_) => PlayerPage(state: state, song: same ? null : song),
        ),
      );
    });

class _SoundValue extends StatelessWidget {
  const _SoundValue({required this.label, required this.value});
  final String label, value;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Text(
          label,
          style: Theme.of(context).textTheme.bodyMedium
              ?.copyWith(fontWeight: FontWeight.w500),
        ),
      ),
      const SizedBox(width: 12),
      Text(
        value,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
          fontFeatures: const [FontFeature.tabularFigures()],
        ),
      ),
    ],
  );
}

Future<void> singingSettings(
  BuildContext context,
  AppState state,
) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  builder: (context) => ListenableBuilder(
    listenable: state.performance,
    builder: (context, _) => SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          const SheetHeader('调音'),
          if (state.taking) const Text('录音参数已锁定，结束录音后可以调整。'),
          const SizedBox(height: 16),
          _SoundValue(
            label: '音调',
            value: '${state.pitch > 0 ? '+' : ''}${state.pitch} 半音',
          ),
          Semantics(
            label: '音调',
            child: Slider(
              key: const ValueKey('pitch-control'),
              semanticFormatterCallback: (v) => '${v.round()} 半音',
              value: state.pitch.toDouble().clamp(-6, 6),
              min: -6,
              max: 6,
              divisions: 12,
              onChanged: state.taking
                  ? null
                  : (v) => uiTask(
                      context,
                      () => state.command('pitch', v.round()),
                    ),
            ),
          ),
          const SizedBox(height: 12),
          _SoundValue(label: '速度', value: '${(state.speed * 100).round()}%'),
          Semantics(
            label: '速度',
            child: Slider(
              key: const ValueKey('speed-control'),
              semanticFormatterCallback: (v) => '${(v * 100).round()}%',
              value: state.speed.clamp(.6, 1.2),
              min: .6,
              max: 1.2,
              divisions: 12,
              onChanged: state.taking
                  ? null
                  : (v) => uiTask(context, () => state.command('speed', v)),
            ),
          ),
          const SizedBox(height: 12),
          _SoundValue(label: '播放音量', value: '${(state.volume * 100).round()}%'),
          Semantics(
            label: '播放音量',
            child: Slider(
              key: const ValueKey('volume-control'),
              semanticFormatterCallback: (v) => '${(v * 100).round()}%',
              value: state.volume.clamp(0, 1),
              onChanged: (v) =>
                  uiTask(context, () => state.command('volume', v)),
            ),
          ),
          Text(
            '这里调整听到的声音。作品的人声和伴奏比例在回听时调整。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 20),
          const Divider(),
          const SizedBox(height: 8),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.mic_none_rounded),
            title: const Text('麦克风试音'),
            subtitle: const Text('检查声音是否能录到，不保存录音'),
            onTap: state.taking
                ? null
                : () => showModalBottomSheet<void>(
                    context: context,
                    isScrollControlled: true,
                    useSafeArea: true,
                    builder: (_) => _MicrophoneTest(state: state),
                  ),
          ),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.headphones_rounded),
            title: const Text('音轨与播放设置'),
            subtitle: const Text('试听伴奏、声道、字幕和兼容播放'),
            trailing: const Icon(Icons.chevron_right_rounded),
            onTap: () => audioSettings(context, state),
          ),
        ],
      ),
    ),
  ),
);

Future<void> audioSettings(
  BuildContext context,
  AppState state,
) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  builder: (context) => ListenableBuilder(
    listenable: state.performance,
    builder: (context, _) {
      final audio = state.tracks.where((t) => t['type'] == 'audio').toList();
      final selected = audio.where((t) => t['selected'] == true).firstOrNull;
      final stereo = selected?['channels'] == 2;
      Future<void> mark(String role) => uiTask(context, () async {
        final local =
            audio.length == 1 && ['LEFT', 'RIGHT'].contains(state.channelMode);
        final yes = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: Text(role == 'backing' ? '标记为伴奏？' : '标记为原唱？'),
            content: Text(
              local
                  ? '声道标记只保存在此手机，对当前素材版本生效。'
                  : '这会更新 NAS 曲库的音轨标记，其他设备也会使用此标记。',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('取消'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('确认标记'),
              ),
            ],
          ),
        );
        if (yes == true) await state.command('role', role);
      });
      return SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            const SheetHeader('音轨与播放设置'),
            Text(state.taking ? '录音中不能切换音轨或声道。' : '先试听：有人唱的是原唱，只有音乐的是伴奏。'),
            const SizedBox(height: 12),
            if (audio.isEmpty) const Notice('歌曲准备好后，会在这里显示音轨。'),
            for (var i = 0; i < audio.length; i++)
              ListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 12),
                minTileHeight: state.senior ? 76 : 64,
                selected: audio[i]['selected'] == true,
                selectedTileColor: Theme.of(context)
                    .colorScheme
                    .primaryContainer,
                selectedColor: Theme.of(context).colorScheme.primary,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                leading: Icon(
                  audio[i]['selected'] == true
                      ? Icons.check_circle_rounded
                      : Icons.circle_outlined,
                  size: 20,
                ),
                title: Text('音轨 ${i + 1}'),
                subtitle: Text(audio[i]['label'] as String? ?? '未命名音轨'),
                onTap: state.taking
                    ? null
                    : () => uiTask(
                        context,
                        () => state.command('track', audio[i]['id']),
                      ),
              ),
            if (audio.isNotEmpty)
              TextButton.icon(
                onPressed: state.taking
                    ? null
                    : () => uiTask(
                        context,
                        () => state.command(state.isPlaying ? 'pause' : 'play'),
                      ),
                icon: Icon(
                  state.isPlaying
                      ? Icons.pause_rounded
                      : Icons.play_arrow_rounded,
                ),
                label: Text(state.isPlaying ? '暂停试听' : '播放当前音轨'),
              ),
            if (stereo) ...[
              const SizedBox(height: 12),
              const Text('声道'),
              Wrap(
                spacing: 8,
                children: [
                  for (final item in [
                    ('STEREO', '立体声'),
                    ('LEFT', '左声道'),
                    ('RIGHT', '右声道'),
                  ])
                    ChoiceChip(
                      label: Text(item.$2),
                      selected: state.channelMode == item.$1,
                      onSelected: state.taking
                          ? null
                          : (_) => uiTask(
                              context,
                              () => state.command('channel', item.$1),
                            ),
                    ),
                ],
              ),
            ],
            if (audio.length > 1 || stereo)
              Wrap(
                spacing: 8,
                children: [
                  OutlinedButton(
                    onPressed: state.taking ? null : () => mark('backing'),
                    child: const Text('设为伴奏'),
                  ),
                  OutlinedButton(
                    onPressed: state.taking ? null : () => mark('original'),
                    child: const Text('设为原唱'),
                  ),
                ],
              ),
            const SizedBox(height: 16),
            Text(
              '声音输出：${state.outputRoute}',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            if (state.tracks.any((t) => t['type'] == 'text')) ...[
              const SizedBox(height: 12),
              const Text('视频字幕'),
              for (final track in state.tracks.where(
                (t) => t['type'] == 'text',
              ))
                ListTile(
                  title: Text(track['label'] as String? ?? '字幕'),
                  trailing: track['selected'] == true
                      ? const Icon(Icons.check_rounded)
                      : null,
                  onTap: () => uiTask(
                    context,
                    () => state.command('track', track['id']),
                  ),
                ),
              TextButton(
                onPressed: () =>
                    uiTask(context, () => state.command('subtitles', false)),
                child: const Text('关闭视频字幕'),
              ),
            ],
            if (state.playbackError.isNotEmpty) Notice(state.playbackError),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: state.taking || state.current == null
                  ? null
                  : () => uiTask(context, () async {
                      final song = state.current!;
                      await state.play(
                        song,
                        force: true,
                        start: state.positionMs,
                        autoplay: state.isPlaying,
                      );
                    }),
              child: const Text('尝试兼容播放'),
            ),
          ],
        ),
      );
    },
  ),
);

Future<bool> confirmRecording(BuildContext context, AppState state) async {
  if (state.audioRole != 'backing' &&
      ![
        'ACCOMPANIMENT',
        'INSTRUMENTAL',
      ].contains(state.current?.data['version'])) {
    if (!context.mounted) return false;
    final choice = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('录音前听一下伴奏'),
        content: Text(
          state.audioRole == 'original'
              ? '当前播放的是原唱，录下的作品也会包含原唱。可以先切换到伴奏，或继续跟原唱录音。'
              : '当前音轨的用途尚未确认。如果含有原唱，录下的作品也会包含原唱。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, 'select'),
            child: Text(state.backingMapped ? '切换到伴奏' : '试听并选择伴奏'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, 'record'),
            child: const Text('使用当前声音录音'),
          ),
        ],
      ),
    );
    if (!context.mounted) return false;
    if (choice == 'select') {
      if (state.backingMapped) {
        await state.command('useRole', 'backing');
      } else {
        await audioSettings(context, state);
      }
      return false;
    }
    if (choice != 'record') return false;
  }
  if (!context.mounted || !await microphonePermission(context, state)) {
    return false;
  }
  await state.sing('notifications');
  if (state.mode == 'HLS') {
    final songId = state.current?.id;
    final instanceId = state.playbackInstanceId;
    // HLS opened at a later position may no longer contain the recording start.
    // The existing seek command renegotiates from zero and preserves settings.
    await state.command('seek', 0);
    final deadline = DateTime.now().add(const Duration(seconds: 30));
    while (context.mounted) {
      final snapshot = await AppState.player.invokeMapMethod<String, dynamic>(
        'snapshot',
      );
      if (!context.mounted) return false;
      if (snapshot == null) {
        throw const FormatException('无法确认歌曲是否准备完成，请重试。');
      }
      final song = jsonDecode(snapshot['song'] as String) as Json;
      if (song['id'] != songId ||
          snapshot['instanceId'] != instanceId ||
          (snapshot['workId'] as String? ?? '').isNotEmpty ||
          state.taking) {
        return false;
      }
      final status = snapshot['state'];
      if (status == 'ERROR' || status == 'AUTH_REQUIRED') {
        throw FormatException(
          (snapshot['message'] as String?)?.isNotEmpty == true
              ? snapshot['message'] as String
              : '歌曲准备失败，请重试。',
        );
      }
      if (status == 'PLAYING' || status == 'PAUSED') return true;
      if (DateTime.now().isAfter(deadline)) {
        throw const FormatException('歌曲仍在准备中，请稍后再开始录音。');
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    return false;
  }
  return context.mounted;
}

class _MicrophoneTest extends StatefulWidget {
  const _MicrophoneTest({required this.state});
  final AppState state;
  @override
  State<_MicrophoneTest> createState() => _MicrophoneTestState();
}

class _MicrophoneTestState extends State<_MicrophoneTest> {
  bool started = false, busy = false;
  @override
  void dispose() {
    if (started && widget.state.singingPhase == 'TESTING') {
      unawaited(widget.state.sing('test', {'start': false}));
    }
    super.dispose();
  }

  Future<void> toggle() => uiTask(context, () async {
    if (busy) return;
    setState(() => busy = true);
    try {
      if (widget.state.singingPhase == 'TESTING') {
        await widget.state.sing('test', {'start': false});
        started = false;
      } else if (await microphonePermission(context, widget.state) && mounted) {
        await widget.state.sing('test', {'start': true});
        started = true;
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  });
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state.performance,
    builder: (context, _) => SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          const SheetHeader('麦克风试音', done: '关闭'),
          const SizedBox(height: 12),
          const Text('说一句话，看看音量条是否变化。试音不会保存声音，也不提供实时耳返。'),
          const SizedBox(height: 24),
          LinearProgressIndicator(
            value: ((widget.state.singing['level'] as num? ?? 0).toDouble() * 6)
                .clamp(0, 1),
          ),
          if ((widget.state.singing['error'] as String? ?? '').isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Semantics(
                liveRegion: true,
                child: Text(
                  widget.state.singing['error'] as String,
                  style: Theme.of(context).textTheme.bodyMedium
                      ?.copyWith(color: Theme.of(context).colorScheme.error),
                ),
              ),
            ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: busy || widget.state.taking ? null : toggle,
            child: Text(
              widget.state.singingPhase == 'TESTING' ? '结束试音' : '开始试音',
            ),
          ),
        ],
      ),
    ),
  );
}
