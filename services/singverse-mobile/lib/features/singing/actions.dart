import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import 'prepare_page.dart';

Future<bool> microphonePermission(BuildContext context, AppState state) async {
  final result = await state.sing('permission');
  if (result == 'granted') return true;
  if (!context.mounted) return false;
  await showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('需要麦克风才能录唱'),
      content: Text(
        result == 'settings'
            ? '请在系统设置中允许麦克风。你仍然可以仅练唱。'
            : '没有获得麦克风权限。你仍然可以仅练唱，稍后再授权。',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('仅练唱'),
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

Future<void> openPreparation(BuildContext context, AppState state, Song song) =>
    uiTask(context, () async {
      if (state.taking || state.singingPhase == 'PREVIEW') {
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('保留当前录音？'),
            content: const Text('切换歌曲前结束当前录唱，片段会保留在“我的 · 待处理录音”。'),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('继续当前演唱'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('保留并选新歌'),
              ),
            ],
          ),
        );
        if (confirmed != true) return;
        if (state.countdown > 0) await state.command('cancelCountdown');
        if (state.recording) await state.endTake();
        for (var i = 0; i < 50 && state.singingPhase == 'FINALIZING'; i++) {
          await Future<void>.delayed(const Duration(milliseconds: 100));
        }
      }
      await state.play(song, autoplay: false);
      if (context.mounted) {
        await Navigator.push(
          context,
          MaterialPageRoute<void>(
            settings: const RouteSettings(name: 'prepare'),
            builder: (_) => PreparePage(state: state),
          ),
        );
      }
    });

Future<void> singingSettings(
  BuildContext context,
  AppState state,
) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  showDragHandle: true,
  builder: (context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) {
      final frozen = state.taking;
      return SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('调音与伴奏', style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 8),
              Text(
                frozen ? '录音参数已锁定。结束这段录音后可调整。' : '先试听，再确认哪一轨是伴奏或导唱。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 20),
              Row(
                children: [
                  const Text('移调'),
                  const Spacer(),
                  Text(
                    '${state.pitch > 0 ? '+' : ''}${state.pitch} 半音',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ],
              ),
              Slider(
                value: state.pitch.toDouble(),
                min: -6,
                max: 6,
                divisions: 12,
                onChanged: frozen
                    ? null
                    : (v) => state.command('pitch', v.toInt()),
              ),
              Row(
                children: [
                  const Text('速度'),
                  const Spacer(),
                  Text('${(state.speed * 100).round()}%'),
                ],
              ),
              Slider(
                value: state.speed.clamp(.6, 1.2),
                min: .6,
                max: 1.2,
                divisions: 12,
                onChanged: frozen ? null : (v) => state.command('speed', v),
              ),
              const Text('播放音量'),
              Slider(
                value: state.volume,
                onChanged: (v) => state.command('volume', v),
              ),
              const Text(
                '只调节你听到的音量，成品比例在回听时调整。',
                style: TextStyle(fontSize: 12),
              ),
              const SizedBox(height: 16),
              ...state.tracks
                  .where((t) => t['type'] == 'audio')
                  .map(
                    (track) => ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(
                        track['selected'] == true
                            ? Icons.radio_button_checked
                            : Icons.radio_button_off,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                      title: Text(track['label'] as String),
                      onTap: frozen
                          ? null
                          : () => uiTask(
                              context,
                              () => state.command('track', track['id']),
                            ),
                    ),
                  ),
              if (state.tracks.where((t) => t['type'] == 'audio').isEmpty)
                const Notice('正在识别音轨；请先等待歌曲准备完成。'),
              const SizedBox(height: 8),
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
                      onSelected: frozen
                          ? null
                          : (_) => uiTask(
                              context,
                              () => state.command('channel', item.$1),
                            ),
                    ),
                ],
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: frozen
                          ? null
                          : () => uiTask(
                              context,
                              () => state.command('role', 'backing'),
                            ),
                      child: const Text('当前设为伴奏'),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: OutlinedButton(
                      onPressed: frozen
                          ? null
                          : () => uiTask(
                              context,
                              () => state.command('role', 'original'),
                            ),
                      child: const Text('当前设为导唱'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              const Text('输出设备', style: TextStyle(fontWeight: FontWeight.w600)),
              Text(
                state.outputRoute.isEmpty ? '系统默认输出' : state.outputRoute,
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const SizedBox(height: 16),
              OutlinedButton(
                onPressed: frozen
                    ? null
                    : () => uiTask(context, () async {
                        final song = state.current;
                        if (song != null) {
                          Navigator.pop(context);
                          await state.play(song, force: true, autoplay: false);
                        }
                      }),
                child: const Text('重新准备兼容播放'),
              ),
            ],
          ),
        ),
      );
    },
  ),
);

Future<bool> confirmRecording(BuildContext context, AppState state) async {
  if (!await microphonePermission(context, state)) return false;
  await state.sing('notifications');
  if (state.backingMapped) return true;
  if (!context.mounted) return false;
  return await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('确认录唱素材'),
          content: const Text(
            '当前音轨还没有确认为伴奏。如果含有原唱，成品也会包含原唱。可以先在“伴奏与导唱”中试听并标记。',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('先选伴奏'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('就用当前音轨录唱'),
            ),
          ],
        ),
      ) ??
      false;
}
