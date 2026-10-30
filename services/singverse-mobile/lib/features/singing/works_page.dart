import 'dart:async';

import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import 'actions.dart';

class Waveform extends StatelessWidget {
  const Waveform({super.key, required this.values, this.color = mint});
  final List<double> values;
  final Color color;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: 72,
    width: double.infinity,
    child: CustomPaint(painter: _WavePainter(values, color)),
  );
}

class _WavePainter extends CustomPainter {
  _WavePainter(this.values, this.color);
  final List<double> values;
  final Color color;
  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..color = color
      ..strokeWidth = 2.5
      ..strokeCap = StrokeCap.round;
    if (values.isEmpty) {
      p.color = color.withValues(alpha: .25);
      canvas.drawLine(
        Offset(0, size.height / 2),
        Offset(size.width, size.height / 2),
        p,
      );
      return;
    }
    for (var i = 0; i < values.length; i++) {
      final x = (i + .5) * size.width / values.length;
      final h = (values[i] * 4).clamp(.02, 1) * size.height * .45;
      canvas.drawLine(
        Offset(x, size.height / 2 - h),
        Offset(x, size.height / 2 + h),
        p,
      );
    }
  }

  @override
  bool shouldRepaint(_WavePainter old) =>
      old.values != values || old.color != color;
}

class WorksPage extends StatefulWidget {
  const WorksPage({super.key, required this.state});
  final AppState state;
  @override
  State<WorksPage> createState() => _WorksPageState();
}

class _WorksPageState extends State<WorksPage> {
  bool pending = false;
  @override
  void initState() {
    super.initState();
    unawaited(widget.state.refreshWorks());
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state,
    builder: (context, _) {
      final rows = widget.state.works
          .where(
            (w) => pending ? w['status'] != 'SAVED' : w['status'] == 'SAVED',
          )
          .toList();
      return Scaffold(
        appBar: AppBar(title: const Text('我的作品'), centerTitle: true),
        body: RefreshIndicator(
          onRefresh: widget.state.refreshWorks,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 12, 24, 120),
            children: [
              Text(
                '每一次开口，都值得留下。',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 20),
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: false, label: Text('已保存作品')),
                  ButtonSegment(value: true, label: Text('待处理录音')),
                ],
                selected: {pending},
                onSelectionChanged: (s) => setState(() => pending = s.first),
              ),
              const SizedBox(height: 20),
              if (rows.isEmpty)
                Notice(
                  pending
                      ? '没有待处理录音。中断或未保存的录音会留在这里。'
                      : '还没有作品，选一首拿手歌，开始第一次录唱吧。',
                ),
              ...rows.map(
                (work) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Card(
                    child: ListTile(
                      contentPadding: const EdgeInsets.all(16),
                      leading: CircleAvatar(
                        backgroundColor: coral.withValues(alpha: .12),
                        foregroundColor: coral,
                        child: const Icon(Icons.mic_rounded),
                      ),
                      title: Text(
                        work['name'] as String? ?? '我的演唱',
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                      subtitle: Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: Text(
                          '${durationLabel((work['durationMs'] as num? ?? 0).toInt())} · ${work['complete'] == true ? '完整演唱' : '演唱片段'}${work['status'] == 'MISSING' ? ' · 文件丢失' : ''}',
                        ),
                      ),
                      trailing: const Icon(Icons.chevron_right_rounded),
                      onTap: () async {
                        await Navigator.push(
                          context,
                          MaterialPageRoute<void>(
                            settings: const RouteSettings(name: 'review'),
                            builder: (_) => ReviewPage(
                              state: widget.state,
                              id: work['id'] as String,
                            ),
                          ),
                        );
                        await widget.state.refreshWorks();
                      },
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      );
    },
  );
}

class ReviewPage extends StatefulWidget {
  const ReviewPage({super.key, required this.state, required this.id});
  final AppState state;
  final String id;
  @override
  State<ReviewPage> createState() => _ReviewPageState();
}

class _ReviewPageState extends State<ReviewPage> {
  Json? data;
  String? error;
  double backing = .7, voice = 1;
  int shift = 0;
  bool autoRendered = false, saving = false, dirty = false;
  String keyState = '';
  final name = TextEditingController();
  @override
  void initState() {
    super.initState();
    widget.state.addListener(changed);
    unawaited(load());
  }

  void changed() {
    final key = '${widget.state.takeId}:${widget.state.singingPhase}';
    if (key == keyState) return;
    keyState = key;
    if (widget.state.takeId == widget.id &&
        ['PREVIEW', 'SAVED', 'FAILED'].contains(widget.state.singingPhase)) {
      unawaited(load());
    }
  }

  Future<void> load() async {
    try {
      var meta = await widget.state.sing('get', {'id': widget.id}) as Json;
      for (
        var i = 0;
        i < 50 && ['RECORDING', 'FINALIZING'].contains(meta['status']);
        i++
      ) {
        await Future<void>.delayed(const Duration(milliseconds: 100));
        meta = await widget.state.sing('get', {'id': widget.id}) as Json;
      }
      if (!mounted) return;
      setState(() {
        data = meta;
        error = null;
        if (name.text.isEmpty) name.text = meta['name'] as String? ?? '我的演唱';
      });
      if (meta['status'] == 'PREVIEW' &&
          meta['hasPreview'] != true &&
          !autoRendered) {
        autoRendered = true;
        await render();
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    }
  }

  Future<void> render() => uiTask(context, () async {
    await widget.state.command('pause');
    await widget.state.sing('render', {
      'id': widget.id,
      'backing': backing,
      'voice': voice,
      'shift': shift,
    });
    setState(() => dirty = false);
  });
  @override
  void dispose() {
    widget.state.removeListener(changed);
    name.dispose();
    super.dispose();
  }

  bool allowLeave = false;
  void leavePage() {
    setState(() => allowLeave = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) Navigator.pop(context);
    });
  }

  Future<void> closeDraft() async {
    if (data?['status'] == 'SAVED') {
      leavePage();
      return;
    }
    final choice = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('这段演唱还没有保存'),
        content: const Text('保留为待处理录音，之后仍可回听、混音并保存。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('继续回听'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, 'discard'),
            child: const Text('丢弃录音'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, 'keep'),
            child: const Text('保留片段'),
          ),
        ],
      ),
    );
    if (!mounted) return;
    if (choice == 'discard') await remove();
    if (choice == 'keep' && mounted) leavePage();
  }

  Future<void> remove() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除这段作品？'),
        content: const Text('只删除此手机中的作品和录音素材，不会删除 NAS 原曲。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('保留'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认删除', style: TextStyle(color: coral)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await uiTask(context, () async {
      await widget.state.sing('delete', {'id': widget.id});
      await widget.state.refreshWorks();
      if (mounted) leavePage();
    });
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state,
    builder: (context, _) {
      final state = widget.state, work = data;
      final saved = work?['status'] == 'SAVED';
      final working =
          state.takeId == widget.id &&
          ['FINALIZING', 'RENDERING'].contains(state.singingPhase);
      final ready = work?['hasPreview'] == true || saved;
      final waveform = (work?['waveform'] as List? ?? [])
          .map((v) => (v as num).toDouble())
          .toList();
      return PopScope(
        canPop: saved || allowLeave,
        onPopInvokedWithResult: (didPop, result) {
          if (!didPop) closeDraft();
        },
        child: Scaffold(
          appBar: AppBar(
            title: Text(saved ? '作品回听' : '留下这次演唱'),
            centerTitle: true,
            actions: [
              if (work != null && !working)
                IconButton(
                  tooltip: '删除作品',
                  onPressed: remove,
                  icon: const Icon(Icons.delete_outline),
                ),
            ],
          ),
          body: SafeArea(
            child: work == null
                ? error != null
                      ? Notice(error!, action: load)
                      : const Center(child: CircularProgressIndicator())
                : ListView(
                    padding: const EdgeInsets.fromLTRB(24, 12, 24, 32),
                    children: [
                      Container(
                        padding: const EdgeInsets.all(24),
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(24),
                          gradient: LinearGradient(
                            colors: [
                              coral.withValues(alpha: .13),
                              const Color(0xFFF9E9DD),
                            ],
                          ),
                        ),
                        child: Column(
                          children: [
                            const Icon(
                              Icons.mic_rounded,
                              size: 38,
                              color: coral,
                            ),
                            const SizedBox(height: 16),
                            Text(
                              (work['song'] as Json?)?['title'] as String? ??
                                  '我的演唱',
                              style: Theme.of(context).textTheme.headlineSmall,
                              textAlign: TextAlign.center,
                            ),
                            const SizedBox(height: 8),
                            Text(
                              '${work['complete'] == true ? '完整演唱' : '演唱片段'} · ${durationLabel((work['durationMs'] as num? ?? 0).toInt())}',
                            ),
                            const SizedBox(height: 16),
                            Waveform(values: waveform, color: coral),
                          ],
                        ),
                      ),
                      if ((work['interruption'] as String? ?? '').isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Text(
                            work['interruption'] as String,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ),
                      if (waveform.isNotEmpty &&
                          waveform.every((v) => v < .001))
                        const Notice('这段录音未检测到明显人声，请检查麦克风后试听。'),
                      const SizedBox(height: 20),
                      FilledButton.icon(
                        onPressed: !ready || working
                            ? null
                            : () => uiTask(context, () async {
                                if (state.workId == widget.id &&
                                    state.isPlaying) {
                                  await state.command('pause');
                                } else {
                                  await state.sing('play', {'id': widget.id});
                                }
                              }),
                        icon: Icon(
                          state.workId == widget.id && state.isPlaying
                              ? Icons.pause_rounded
                              : Icons.play_arrow_rounded,
                        ),
                        label: Text(
                          state.workId == widget.id && state.isPlaying
                              ? '暂停回听'
                              : '试听混音',
                        ),
                      ),
                      if (state.workId == widget.id)
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: Text(
                            '${durationLabel(state.positionMs)} / ${durationLabel(state.durationMs)}',
                            textAlign: TextAlign.center,
                          ),
                        ),
                      if (!saved) ...[
                        const SizedBox(height: 24),
                        const Text(
                          '让你的声音，刚刚好。',
                          style: TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            const Text('人声'),
                            const Spacer(),
                            Text('${(voice * 100).round()}%'),
                          ],
                        ),
                        Slider(
                          value: voice,
                          min: 0,
                          max: 2,
                          onChanged: working
                              ? null
                              : (v) => setState(() {
                                  voice = v;
                                  dirty = true;
                                }),
                        ),
                        Row(
                          children: [
                            const Text('伴奏'),
                            const Spacer(),
                            Text('${(backing * 100).round()}%'),
                          ],
                        ),
                        Slider(
                          value: backing,
                          min: 0,
                          max: 2,
                          onChanged: working
                              ? null
                              : (v) => setState(() {
                                  backing = v;
                                  dirty = true;
                                }),
                        ),
                        Row(
                          children: [
                            const Text('人声对齐'),
                            const Spacer(),
                            Text('${shift > 0 ? '+' : ''}$shift ms'),
                          ],
                        ),
                        Slider(
                          value: shift.toDouble(),
                          min: -1000,
                          max: 1000,
                          divisions: 200,
                          onChanged: working
                              ? null
                              : (v) => setState(() {
                                  shift = v.round();
                                  dirty = true;
                                }),
                        ),
                        const Text(
                          '负值提前人声，正值延后。与歌词显示偏移无关。',
                          style: TextStyle(fontSize: 12),
                        ),
                        const SizedBox(height: 12),
                        OutlinedButton(
                          onPressed: working ? null : render,
                          child: Text(working ? '正在生成混音…' : '生成新的试听混音'),
                        ),
                      ],
                      if (working)
                        const Padding(
                          padding: EdgeInsets.all(20),
                          child: Center(child: CircularProgressIndicator()),
                        ),
                      if (state.takeId == widget.id &&
                          (state.singing['error'] as String? ?? '').isNotEmpty)
                        Notice(state.singing['error'] as String),
                      if (error != null) Notice(error!),
                      const SizedBox(height: 24),
                      TextField(
                        controller: name,
                        maxLength: 120,
                        decoration: const InputDecoration(labelText: '作品名称'),
                      ),
                      const SizedBox(height: 12),
                      FilledButton(
                        onPressed: working || saving || !ready || dirty
                            ? null
                            : () => uiTask(context, () async {
                                setState(() => saving = true);
                                try {
                                  await state.sing(saved ? 'rename' : 'save', {
                                    'id': widget.id,
                                    'name': name.text,
                                  });
                                  await state.refreshWorks();
                                  await load();
                                  if (context.mounted) {
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      SnackBar(
                                        content: Text(
                                          saved ? '名称已更新' : '作品已保存到此手机',
                                        ),
                                      ),
                                    );
                                  }
                                } finally {
                                  if (mounted) setState(() => saving = false);
                                }
                              }),
                        child: Text(
                          saving
                              ? '保存中…'
                              : saved
                              ? '保存名称'
                              : '保存作品到手机',
                        ),
                      ),
                      if (dirty)
                        const Padding(
                          padding: EdgeInsets.all(8),
                          child: Text(
                            '请先生成新的试听混音，再保存作品。',
                            textAlign: TextAlign.center,
                          ),
                        ),
                      TextButton.icon(
                        onPressed: working
                            ? null
                            : () => openPreparation(
                                context,
                                state,
                                Song(
                                  Map<String, dynamic>.from(
                                    work['song'] as Map,
                                  ),
                                ),
                              ),
                        icon: const Icon(Icons.replay_rounded),
                        label: const Text('重新演唱'),
                      ),
                      if (saved)
                        TextButton.icon(
                          onPressed: () => uiTask(context, () async {
                            await state.sing('export', {'id': widget.id});
                          }),
                          icon: const Icon(Icons.ios_share_rounded),
                          label: const Text('导出 M4A'),
                        ),
                      if (!saved)
                        TextButton(
                          onPressed: working
                              ? null
                              : () async {
                                  await closeDraft();
                                },
                          child: const Text('稍后处理 · 录音会保留在此手机'),
                        ),
                    ],
                  ),
          ),
        ),
      );
    },
  );
}
