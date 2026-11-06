import 'package:flutter/material.dart';

import '../core/app_state.dart';
import '../core/models.dart';
import '../features/library/widgets.dart';
import '../features/player/player_page.dart';
import '../features/singing/works_page.dart';
import 'design.dart';

class MiniPlayer extends StatelessWidget {
  const MiniPlayer({super.key, required this.state, this.navigator});
  final AppState state;
  final NavigatorState? navigator;
  @override
  Widget build(BuildContext context) {
    if (state.current == null) return const SizedBox.shrink();
    final playbackLabel = switch (state.playbackState) {
      'PREPARING' => '正在准备',
      'BUFFERING' => '正在缓冲',
      'ERROR' => '播放失败',
      'AUTH_REQUIRED' => '登录失效',
      'ENDED' => '播放结束',
      _ => state.isPlaying ? '播放中' : '已暂停',
    };
    void open() => (navigator ?? Navigator.of(context)).push(
      MaterialPageRoute<void>(
        settings: RouteSettings(
          name: state.workId.isNotEmpty || state.pendingReviewId.isNotEmpty
              ? 'review'
              : 'player',
        ),
        builder: (_) => performancePage(state),
      ),
    );
    final showState = [
      'PREPARING',
      'BUFFERING',
      'ERROR',
      'AUTH_REQUIRED',
    ].contains(state.playbackState);
    final actionLabel = showState
        ? '查看播放状态'
        : state.isPlaying
        ? '暂停播放'
        : '继续播放';
    void playbackAction() => showState
        ? open()
        : uiTask(
            context,
            () => state.command(state.isPlaying ? 'pause' : 'play'),
          );
    return SafeArea(
      top: false,
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 4, 8, 4),
        child: Glass(
          state: state,
          radius: 14,
          child: InkWell(
            key: const Key('mini-player'),
            onTap: open,
            child: Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 9,
                      vertical: 4,
                    ),
                    child: Row(
                      children: [
                        Artwork(
                          state: state,
                          path: state.current!.cover,
                          label: state.current!.title,
                          size: state.senior ? 48 : 38,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                state.current!.title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: state.senior
                                    ? Theme.of(context).textTheme.titleMedium
                                    : Theme.of(context).textTheme.bodySmall
                                          ?.copyWith(
                                            fontWeight: FontWeight.w600,
                                          ),
                              ),
                              Text(
                                state.recording
                                    ? '正在录音 · ${durationLabel((state.singing['recordedMs'] as num? ?? 0).toInt())}'
                                    : state.countdown > 0
                                    ? '录音倒数 ${state.countdown}'
                                    : state.singingPhase == 'FINALIZING'
                                    ? '正在保留录音'
                                    : state.pendingReviewId.isNotEmpty
                                    ? '录音已保留 · 点击回听'
                                    : state.workId.isNotEmpty
                                    ? '返回作品回听'
                                    : '$playbackLabel · ${durationLabel(state.positionMs)}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: Theme.of(context).textTheme.bodySmall
                                    ?.copyWith(
                                      color: state.taking
                                          ? Theme.of(context).colorScheme.error
                                          : Theme.of(context)
                                                .colorScheme
                                                .onSurfaceVariant,
                                    ),
                              ),
                            ],
                          ),
                        ),
                        if (state.recording)
                          TextButton(
                            onPressed: () => uiTask(context, () async {
                              final id = state.takeId;
                              await state.endTake();
                              if (!context.mounted) return;
                              await (navigator ?? Navigator.of(context)).push(
                                MaterialPageRoute<void>(
                                  settings: const RouteSettings(name: 'review'),
                                  builder: (_) =>
                                      ReviewPage(state: state, id: id),
                                ),
                              );
                            }),
                            child: const Text('结束'),
                          )
                        else if (state.taking)
                          TextButton(onPressed: open, child: const Text('查看'))
                        else if (state.senior)
                          Tooltip(
                            message: actionLabel,
                            child: TextButton(
                              onPressed: playbackAction,
                              child: Text(
                                showState
                                    ? '查看'
                                    : state.isPlaying
                                    ? '暂停'
                                    : '继续',
                              ),
                            ),
                          )
                        else
                          IconButton(
                            tooltip: actionLabel,
                            onPressed: playbackAction,
                            icon: Icon(
                              state.isPlaying
                                  ? Icons.pause_rounded
                                  : Icons.play_arrow_rounded,
                            ),
                          ),
                      ],
                    ),
                  ),
                  ExcludeSemantics(
                    child: LinearProgressIndicator(
                      minHeight: 2,
                      value: state.durationMs > 0
                          ? (state.positionMs / state.durationMs).clamp(0, 1)
                          : 0,
                      color: state.recording
                          ? Theme.of(context).colorScheme.error
                          : null,
                      backgroundColor: Colors.transparent,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
