import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import '../library/widgets.dart';
import 'actions.dart';

String _recordingError(Object error) => error is PlatformException
    ? error.message ?? '暂时无法处理录音，请重试。'
    : error is FormatException
    ? error.message
    : error.toString();

class Waveform extends StatelessWidget {
  const Waveform({
    super.key,
    required this.values,
    this.color = berry,
    this.progress = 0,
  });
  final List<double> values;
  final Color color;
  final double progress;
  @override
  Widget build(BuildContext context) => SizedBox(
    height: 80,
    width: double.infinity,
    child: ExcludeSemantics(
      child: CustomPaint(painter: _WavePainter(values, color, progress)),
    ),
  );
}

class _WavePainter extends CustomPainter {
  _WavePainter(this.values, this.color, this.progress);
  final List<double> values;
  final Color color;
  final double progress;
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
      p.color = color.withValues(
        alpha: (i + .5) / values.length <= progress ? 1 : .28,
      );
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
      old.values != values || old.color != color || old.progress != progress;
}

class WorksPage extends StatefulWidget {
  const WorksPage({super.key, required this.state, this.embedded = false});
  final AppState state;
  final bool embedded;
  @override
  State<WorksPage> createState() => _WorksPageState();
}

class _WorksPageState extends State<WorksPage> {
  final search = TextEditingController();
  @override
  void initState() {
    super.initState();
    unawaited(widget.state.refreshWorks());
  }

  @override
  void dispose() {
    search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state.performance,
    builder: (context, _) {
      final state = widget.state;
      final works = state.visibleWorks.where((work) {
        final name = work['name'] as String? ?? '我的演唱';
        return name.toLowerCase().contains(search.text.toLowerCase());
      }).toList();
      final drafts = works.where((w) => w['status'] != 'SAVED').toList();
      final saved = works.where((w) => w['status'] == 'SAVED').toList();
      Future<void> open(Json work) async {
        await Navigator.push(
          context,
          MaterialPageRoute<void>(
            settings: const RouteSettings(name: 'review'),
            builder: (_) => ReviewPage(state: state, id: work['id'] as String),
          ),
        );
        await state.refreshWorks();
      }

      final missing = drafts.where((w) => w['status'] == 'MISSING').toList();
      drafts.removeWhere((w) => w['status'] == 'MISSING');
      Widget section(String title, List<Json> rows, {bool draft = false}) =>
          SliverMainAxisGroup(
            slivers: [
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.only(top: 24, bottom: 8),
                  child: SectionTitle(
                    title,
                    trailing: Text(
                      '${rows.length}',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                        fontFeatures: const [FontFeature.tabularFigures()],
                      ),
                    ),
                  ),
                ),
              ),
              SliverList.builder(
                itemCount: rows.length,
                itemBuilder: (_, index) => _WorkRow(
                  senior: state.senior,
                  work: rows[index],
                  draft: draft,
                  onTap: () => open(rows[index]),
                ),
              ),
            ],
          );
      final body = RefreshIndicator(
        onRefresh: state.refreshWorks,
        child: CustomScrollView(
          key: const PageStorageKey('works-scroll'),
          physics: const AlwaysScrollableScrollPhysics(),
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
              sliver: SliverMainAxisGroup(
                slivers: [
                  SliverToBoxAdapter(
                    child: TextField(
                      controller: search,
                      onChanged: (_) => setState(() {}),
                      decoration: InputDecoration(
                        prefixIcon: const Icon(Icons.search_rounded),
                        hintText: '搜索作品和草稿',
                        suffixIcon: search.text.isEmpty
                            ? null
                            : IconButton(
                                tooltip: '清除搜索',
                                onPressed: () => setState(search.clear),
                                icon: const Icon(Icons.cancel_rounded),
                              ),
                      ),
                    ),
                  ),
                  if (state.worksLoading)
                    const SliverToBoxAdapter(
                      child: Padding(
                        padding: EdgeInsets.only(top: 20),
                        child: LinearProgressIndicator(),
                      ),
                    ),
                  if (state.worksError != null)
                    SliverToBoxAdapter(
                      child: Notice(
                        '暂时无法读取本机录音。${state.worksError}',
                        action: state.refreshWorks,
                      ),
                    ),
                  if (drafts.isNotEmpty) section('继续完成', drafts, draft: true),
                  if (saved.isNotEmpty) section('已保存作品', saved),
                  if (missing.isNotEmpty)
                    section('音频文件缺失', missing, draft: true),
                  if (works.isEmpty &&
                      !state.worksLoading &&
                      state.worksError == null)
                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 44),
                        child: Column(
                          children: [
                            Icon(
                              search.text.isEmpty
                                  ? Icons.mic_none_rounded
                                  : Icons.search_off_rounded,
                              size: 40,
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurfaceVariant,
                            ),
                            const SizedBox(height: 16),
                            Text(
                              search.text.isEmpty ? '留下你的声音' : '没有找到录音',
                              style: Theme.of(context).textTheme.titleLarge,
                            ),
                            const SizedBox(height: 8),
                            Text(
                              search.text.isEmpty
                                  ? '练唱时开始录音，草稿会自动保存在这里'
                                  : '试试歌曲名或作品的其他名称。',
                              textAlign: TextAlign.center,
                              style: Theme.of(context).textTheme.bodyMedium
                                  ?.copyWith(
                                    color: Theme.of(context)
                                        .colorScheme
                                        .onSurfaceVariant,
                                  ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  if (works.isNotEmpty)
                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.only(top: 24),
                        child: Text(
                          '仅保存在此手机 · 离线也能回听',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(
                                color: Theme.of(context)
                                    .colorScheme
                                    .onSurfaceVariant,
                              ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      );
      return widget.embedded
          ? body
          : Scaffold(
              appBar: AppBar(title: const Text('作品')),
              body: body,
            );
    },
  );
}

class _WorkRow extends StatelessWidget {
  const _WorkRow({
    required this.senior,
    required this.work,
    required this.draft,
    required this.onTap,
  });
  final Json work;
  final bool senior;
  final bool draft;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final missing = work['status'] == 'MISSING';
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Container(
          padding: EdgeInsets.symmetric(vertical: senior ? 16 : 12),
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(color: scheme.outlineVariant, width: .6),
            ),
          ),
          child: Row(
            children: [
              Container(
                width: senior ? 56 : 44,
                height: senior ? 56 : 44,
                decoration: BoxDecoration(
                  color: scheme.onSurface.withValues(alpha: .05),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  missing
                      ? Icons.file_present_outlined
                      : Icons.graphic_eq_rounded,
                  size: 20,
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      work['name'] as String? ?? '我的演唱',
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        fontWeight: senior ? FontWeight.w600 : FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      [
                        durationLabel(
                          (work['durationMs'] as num? ?? 0).toInt(),
                        ),
                        if (work['createdAt'] is num)
                          _dateLabel((work['createdAt'] as num).toInt()),
                        if (missing)
                          '音频文件缺失'
                        else if (draft)
                          '待回听与保存'
                        else
                          work['complete'] == true ? '完整演唱' : '演唱片段',
                      ].join(' · '),
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: missing ? scheme.error : scheme.onSurfaceVariant,
                        fontFeatures: const [FontFeature.tabularFigures()],
                      ),
                    ),
                  ],
                ),
              ),
              if (!senior)
                Icon(
                  Icons.chevron_right_rounded,
                  color: scheme.onSurfaceVariant,
                )
              else
                Padding(
                  padding: const EdgeInsets.only(left: 8),
                  child: Text(
                    '回听',
                    style: TextStyle(color: scheme.onSurfaceVariant),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

String _dateLabel(int milliseconds) {
  final date = DateTime.fromMillisecondsSinceEpoch(milliseconds);
  final today = DateUtils.dateOnly(DateTime.now());
  final day = DateUtils.dateOnly(date);
  final difference = today.difference(day).inDays;
  if (difference == 0) return '今天';
  if (difference == 1) return '昨天';
  return '${date.month} 月 ${date.day} 日';
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
  int shift = 0, loadGeneration = 0, renderGeneration = 0;
  bool renderSubmitted = false,
      renderAcknowledged = false,
      renderStarted = false,
      renderEnded = false,
      readingRender = false;
  bool initializedMix = false,
      autoRendered = false,
      saving = false,
      dirty = false,
      rendering = false,
      renderFailed = false,
      playAfterRender = false;
  String keyState = '';
  double? drag;
  bool get working =>
      rendering ||
      saving ||
      (widget.state.takeId == widget.id &&
          ['FINALIZING', 'RENDERING'].contains(widget.state.singingPhase));
  @override
  void initState() {
    super.initState();
    widget.state.performance.addListener(changed);
    unawaited(load());
  }

  @override
  void dispose() {
    loadGeneration++;
    renderGeneration++;
    widget.state.performance.removeListener(changed);
    super.dispose();
  }

  void changed() {
    final state = widget.state;
    final matching = state.takeId == widget.id;
    if (rendering && renderSubmitted && matching) {
      if (state.singingPhase == 'RENDERING') renderStarted = true;
      if (renderStarted && ['PREVIEW', 'FAILED'].contains(state.singingPhase)) {
        renderEnded = true;
        unawaited(finishRender(renderGeneration));
      }
    }
    final key = '${state.takeId}:${state.singingPhase}';
    if (key == keyState) return;
    keyState = key;
    if (!rendering &&
        matching &&
        ['PREVIEW', 'SAVED', 'FAILED'].contains(state.singingPhase)) {
      unawaited(load());
    }
  }

  Future<void> load() async {
    if (rendering) return;
    final ticket = ++loadGeneration;
    try {
      var meta = await widget.state.sing('get', {'id': widget.id}) as Json;
      for (
        var i = 0;
        i < 80 && ['RECORDING', 'FINALIZING'].contains(meta['status']);
        i++
      ) {
        await Future<void>.delayed(const Duration(milliseconds: 100));
        if (!mounted || ticket != loadGeneration) return;
        meta = await widget.state.sing('get', {'id': widget.id}) as Json;
      }
      if (!mounted || ticket != loadGeneration) return;
      final renderError = widget.state.takeId == widget.id
          ? widget.state.singing['error'] as String? ?? ''
          : '';
      setState(() {
        data = meta;
        error = renderError.isEmpty ? null : renderError;
        renderFailed = renderError.isNotEmpty && meta['status'] == 'PREVIEW';
        if (!initializedMix || !dirty) {
          backing = (meta['backingGain'] as num? ?? .7).toDouble();
          voice = (meta['voiceGain'] as num? ?? 1).toDouble();
          shift = (meta['shiftMs'] as num? ?? 0).toInt();
          initializedMix = true;
        }
      });
      if (meta['status'] == 'PREVIEW' &&
          meta['hasPreview'] != true &&
          !autoRendered &&
          !widget.state.taking &&
          widget.state.singingPhase != 'RENDERING') {
        autoRendered = true;
        await render();
      }
    } catch (e) {
      if (mounted && ticket == loadGeneration) {
        setState(() {
          error = _recordingError(e);
        });
      }
    }
  }

  Future<void> finishRender(int operation) async {
    final state = widget.state;
    if (!mounted ||
        operation != renderGeneration ||
        !rendering ||
        !renderAcknowledged ||
        !renderStarted ||
        !renderEnded ||
        readingRender ||
        state.takeId != widget.id) {
      return;
    }
    readingRender = true;
    final ticket = ++loadGeneration;
    try {
      final meta = await state.sing('get', {'id': widget.id}) as Json;
      if (!mounted ||
          ticket != loadGeneration ||
          operation != renderGeneration) {
        return;
      }
      final renderError = state.takeId == widget.id
          ? state.singing['error'] as String? ?? ''
          : '当前录音已改变，请重新打开草稿。';
      final succeeded = renderError.isEmpty && meta['hasPreview'] == true;
      final listen =
          playAfterRender &&
          succeeded &&
          ModalRoute.of(context)?.isCurrent == true;
      setState(() {
        data = meta;
        rendering = false;
        readingRender = false;
        playAfterRender = false;
        dirty = !succeeded;
        renderFailed = !succeeded;
        error = renderError.isEmpty
            ? succeeded
                  ? null
                  : '未能生成试听，原始录音仍保留。'
            : renderError;
        if (succeeded) {
          backing = (meta['backingGain'] as num? ?? backing).toDouble();
          voice = (meta['voiceGain'] as num? ?? voice).toDouble();
          shift = (meta['shiftMs'] as num? ?? shift).toInt();
          initializedMix = true;
        }
      });
      if (listen) await state.sing('play', {'id': widget.id});
    } catch (e) {
      if (mounted && operation == renderGeneration) {
        setState(() {
          rendering = false;
          readingRender = false;
          playAfterRender = false;
          dirty = true;
          renderFailed = true;
          error = _recordingError(e);
        });
      }
    }
  }

  Future<void> render({bool listen = false}) => uiTask(context, () async {
    if (working) return;
    final operation = ++renderGeneration;
    // Lock before any confirmation/pause await; stale metadata cannot finish it.
    loadGeneration++;
    setState(() {
      rendering = true;
      renderSubmitted = false;
      renderAcknowledged = false;
      renderStarted = false;
      renderEnded = false;
      readingRender = false;
      playAfterRender = listen;
      error = null;
      renderFailed = false;
    });
    try {
      if (!await keepCurrentRecording(context, widget.state)) {
        if (mounted && operation == renderGeneration) {
          setState(() {
            rendering = false;
            playAfterRender = false;
          });
        }
        return;
      }
      if (!mounted || operation != renderGeneration) return;
      await widget.state.command('pause');
      if (!mounted || operation != renderGeneration) return;
      renderSubmitted = true;
      await widget.state.sing('render', {
        'id': widget.id,
        'backing': backing,
        'voice': voice,
        'shift': shift,
      });
      if (!mounted || operation != renderGeneration) return;
      renderAcknowledged = true;
      // Native begin/end events can arrive before the method reply.
      await finishRender(operation);
    } catch (e) {
      if (mounted && operation == renderGeneration) {
        setState(() {
          rendering = false;
          readingRender = false;
          playAfterRender = false;
          renderFailed = true;
          dirty = true;
          error = _recordingError(e);
        });
      }
      rethrow;
    }
  });
  Future<void> play() => uiTask(context, () async {
    final state = widget.state;
    if (working || !await keepCurrentRecording(context, state)) return;
    if (state.workId == widget.id) {
      if (!state.isPlaying && state.playbackState == 'ENDED') {
        await state.command('seek', 0);
      }
      await state.command(state.isPlaying ? 'pause' : 'play');
    } else {
      await state.sing('play', {'id': widget.id});
    }
  });
  Future<void> seek(int position) => uiTask(context, () async {
    if (working || !await keepCurrentRecording(context, widget.state)) return;
    if (widget.state.workId != widget.id) {
      await widget.state.sing('play', {'id': widget.id});
    }
    await widget.state.command('seek', position);
  });
  Future<void> rename() async {
    if (working) return;
    final value = await showDialog<String>(
      context: context,
      builder: (_) =>
          _RenameDialog(initial: data?['name'] as String? ?? '我的演唱'),
    );
    if (value == null || !mounted) return;
    await uiTask(context, () async {
      if (!await keepCurrentRecording(context, widget.state)) return;
      await widget.state.sing('rename', {'id': widget.id, 'name': value});
      await load();
      await widget.state.refreshWorks();
    });
  }

  Future<void> save() => uiTask(context, () async {
    if (working || dirty || data == null) return;
    setState(() => saving = true);
    try {
      await widget.state.sing('save', {
        'id': widget.id,
        'name': data!['name'] as String? ?? '我的演唱',
      });
      await load();
      await widget.state.refreshWorks();
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('作品已保存到此手机')));
      }
    } finally {
      if (mounted) setState(() => saving = false);
    }
  });
  Future<void> export() => uiTask(context, () async {
    if (working || !await keepCurrentRecording(context, widget.state)) return;
    final result = await widget.state.sing('export', {'id': widget.id});
    if (mounted && result == true) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('音频已导出')));
    }
  });
  Future<void> remove() async {
    if (working) return;
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除这段录音？'),
        content: const Text('会删除此手机上的作品及录音素材，无法恢复。NAS 原曲不受影响。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('保留'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(
              '删除录音',
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        ],
      ),
    );
    if (yes != true || !mounted) return;
    await uiTask(context, () async {
      if (!await keepCurrentRecording(context, widget.state)) return;
      await widget.state.sing('delete', {'id': widget.id});
      await widget.state.refreshWorks();
      if (mounted) Navigator.pop(context);
    });
  }

  Future<void> adjustSound() => showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (sheetContext) => StatefulBuilder(
      builder: (sheetContext, update) {
        void change(VoidCallback action) {
          setState(() {
            action();
            dirty = true;
          });
          update(() {});
        }

        return SingleChildScrollView(
          padding: EdgeInsets.fromLTRB(
            24,
            4,
            24,
            24 + MediaQuery.viewInsetsOf(sheetContext).bottom,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SheetHeader('调整声音'),
              const SizedBox(height: 20),
              Text(
                '人声 ${(voice * 100).round()}%',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              Slider(
                semanticFormatterCallback: (v) => '人声 ${(v * 100).round()}%',
                value: voice.clamp(0, 2),
                max: 2,
                onChanged: working ? null : (v) => change(() => voice = v),
              ),
              const SizedBox(height: 12),
              Text(
                '伴奏 ${(backing * 100).round()}%',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              Slider(
                semanticFormatterCallback: (v) => '伴奏 ${(v * 100).round()}%',
                value: backing.clamp(0, 2),
                max: 2,
                onChanged: working ? null : (v) => change(() => backing = v),
              ),
              const SizedBox(height: 12),
              ListTile(
                contentPadding: EdgeInsets.zero,
                minTileHeight: widget.state.senior ? 80 : 64,
                leading: const Icon(Icons.schedule_rounded),
                title: const Text('声音不同步'),
                subtitle: Text(_alignmentLabel),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: working
                    ? null
                    : () async {
                        await adjustAlignment();
                        if (sheetContext.mounted) update(() {});
                      },
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: dirty && !working
                    ? () {
                        Navigator.pop(sheetContext);
                        unawaited(render(listen: true));
                      }
                    : null,
                child: const Text('应用调整并试听'),
              ),
              const SizedBox(height: 8),
              Text(
                '调整后重新生成试听，确认满意再保存。原始录音会保留。',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        );
      },
    ),
  );

  String get _alignmentLabel =>
      shift == 0 ? '原始对齐' : '人声${shift < 0 ? '提前' : '延后'} ${shift.abs()} 毫秒';

  Future<void> adjustAlignment() => showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (sheetContext) => StatefulBuilder(
      builder: (sheetContext, update) {
        void change(int value) {
          setState(() {
            shift = value.clamp(-1000, 1000);
            dirty = true;
          });
          update(() {});
        }

        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 4, 24, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              const SheetHeader('声音不同步'),
              const SizedBox(height: 16),
              const Text('如果人声比音乐慢，就让人声提前一点；反过来则延后。'),
              const SizedBox(height: 20),
              Text(
                _alignmentLabel,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              Slider(
                key: const ValueKey('review-alignment'),
                value: shift.toDouble().clamp(-1000, 1000),
                min: -1000,
                max: 1000,
                divisions: 200,
                semanticFormatterCallback: (v) => v == 0
                    ? '原始对齐'
                    : '人声${v < 0 ? '提前' : '延后'} ${v.abs().round()} 毫秒',
                onChanged: working ? null : (v) => change(v.round()),
              ),
              Wrap(
                alignment: WrapAlignment.spaceBetween,
                spacing: 12,
                runSpacing: 8,
                children: [
                  OutlinedButton(
                    onPressed: working ? null : () => change(shift - 50),
                    child: const Text('提前 50 毫秒'),
                  ),
                  OutlinedButton(
                    onPressed: working ? null : () => change(shift + 50),
                    child: const Text('延后 50 毫秒'),
                  ),
                ],
              ),
              TextButton(
                onPressed: working ? null : () => change(0),
                child: const Text('恢复原始对齐'),
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () => Navigator.pop(sheetContext),
                child: const Text('完成，返回声音调整'),
              ),
            ],
          ),
        );
      },
    ),
  );

  Future<void> showWorks() => Navigator.push<void>(
    context,
    MaterialPageRoute<void>(
      settings: const RouteSettings(name: 'works'),
      builder: (_) => WorksPage(state: widget.state),
    ),
  );

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state.performance,
    builder: (context, _) {
      final state = widget.state, work = data;
      final saved = work?['status'] == 'SAVED';
      final missing = work?['status'] == 'MISSING';
      final ready = !missing && (work?['hasPreview'] == true || saved);
      final active = state.workId == widget.id;
      final duration = active && state.durationMs > 0
          ? state.durationMs
          : (work?['durationMs'] as num? ?? 0).toInt();
      final position = active ? state.positionMs : 0;
      final waveform = (work?['waveform'] as List? ?? [])
          .map((v) => (v as num).toDouble())
          .toList();
      final song = work?['song'] is Map
          ? Song(Map<String, dynamic>.from(work!['song'] as Map))
          : null;
      final scheme = Theme.of(context).colorScheme;
      final text = Theme.of(context).textTheme;
      final currentPosition = (drag ?? position.toDouble()).clamp(
        0.0,
        duration > 0 ? duration.toDouble() : 1.0,
      );
      final canListen = ready && !working;
      final canRender = !working && (work?['frames'] as num? ?? 1) > 0;
      final playLabel = active && state.isPlaying
          ? '暂停回听'
          : state.senior
          ? '听录音'
          : '播放录音';
      final note = missing
          ? '音频文件缺失'
          : saved
          ? '已保存在此手机'
          : (work?['interruption'] as String? ?? '').isNotEmpty
          ? '录音已中断，已有内容已保留'
          : '草稿已自动保留';
      return Scaffold(
        appBar: AppBar(
          title: Text(
            missing
                ? '录音文件缺失'
                : saved
                ? '作品回听'
                : '录音草稿',
          ),
          actions: [
            if (work != null)
              PopupMenuButton<String>(
                key: const ValueKey('review-more'),
                tooltip: '更多操作',
                enabled: !working,
                icon: const Icon(Icons.more_horiz_rounded),
                onSelected: (action) {
                  if (action == 'rename') unawaited(rename());
                  if (action == 'delete') unawaited(remove());
                  if (action == 'sing' && song != null) {
                    unawaited(openSinging(context, state, song));
                  }
                },
                itemBuilder: (_) => [
                  PopupMenuItem(
                    value: 'rename',
                    height: state.senior ? 64 : 48,
                    child: const Text('修改名称'),
                  ),
                  if (song != null)
                    PopupMenuItem(
                      value: 'sing',
                      height: state.senior ? 64 : 48,
                      child: const Text('重新演唱'),
                    ),
                  PopupMenuItem(
                    value: 'delete',
                    height: state.senior ? 64 : 48,
                    child: Text('删除录音', style: TextStyle(color: scheme.error)),
                  ),
                ],
              ),
          ],
        ),
        body: SafeArea(
          top: false,
          child: ContentWidth(
            child: work == null
                ? error != null
                      ? Notice(error!, action: load)
                      : const Center(child: CircularProgressIndicator())
                : ListView(
                    key: ValueKey('review-scroll:${widget.id}'),
                    padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              work['name'] as String? ?? '我的演唱',
                              style: text.titleLarge?.copyWith(
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                          IconButton(
                            tooltip: '修改录音名称',
                            onPressed: working ? null : rename,
                            icon: const Icon(Icons.edit_outlined, size: 20),
                          ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        '$note · ${durationLabel((work['durationMs'] as num? ?? 0).toInt())}',
                        style: text.bodySmall?.copyWith(
                          color: missing
                              ? scheme.error
                              : scheme.onSurfaceVariant,
                        ),
                      ),
                      if (song != null) ...[
                        const SizedBox(height: 16),
                        Row(
                          children: [
                            Artwork(
                              state: state,
                              path: song.cover,
                              revision: song.data['revision']?.toString(),
                              size: state.senior ? 60 : 48,
                            ),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    song.title,
                                    style: text.bodyMedium?.copyWith(
                                      fontWeight: FontWeight.w500,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    '${song.artist.isEmpty ? '歌手未知' : song.artist} · 我的演唱',
                                    style: text.bodySmall?.copyWith(
                                      color: scheme.onSurfaceVariant,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ],
                      const SizedBox(height: 20),
                      DecoratedBox(
                        decoration: BoxDecoration(
                          color: scheme.surface,
                          borderRadius: BorderRadius.circular(16),
                          border: Border.all(
                            color: scheme.onSurface.withValues(alpha: .08),
                          ),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(16, 20, 16, 16),
                          child: Column(
                            children: [
                              Waveform(
                                values: waveform,
                                color: scheme.primary,
                                progress: duration > 0
                                    ? currentPosition / duration
                                    : 0,
                              ),
                              const SizedBox(height: 8),
                              Slider(
                                key: const ValueKey('review-progress'),
                                semanticFormatterCallback: (v) =>
                                    durationLabel(v.round()),
                                value: currentPosition,
                                max: duration > 0 ? duration.toDouble() : 1,
                                onChanged: canListen && duration > 0
                                    ? (v) => setState(() => drag = v)
                                    : null,
                                onChangeEnd: canListen && duration > 0
                                    ? (v) {
                                        setState(() => drag = null);
                                        unawaited(seek(v.round()));
                                      }
                                    : null,
                              ),
                              Row(
                                mainAxisAlignment:
                                    MainAxisAlignment.spaceBetween,
                                children: [
                                  Text(
                                    durationLabel(currentPosition.round()),
                                    style: text.bodySmall?.copyWith(
                                      color: scheme.onSurfaceVariant,
                                      fontFeatures: const [
                                        FontFeature.tabularFigures(),
                                      ],
                                    ),
                                  ),
                                  Text(
                                    durationLabel(duration),
                                    style: text.bodySmall?.copyWith(
                                      color: scheme.onSurfaceVariant,
                                      fontFeatures: const [
                                        FontFeature.tabularFigures(),
                                      ],
                                    ),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 16),
                              Row(
                                mainAxisAlignment:
                                    MainAxisAlignment.spaceEvenly,
                                children: [
                                  Expanded(
                                    child: _ReviewSkip(
                                      forward: false,
                                      senior: state.senior,
                                      onPressed: canListen
                                          ? () => seek(
                                              (position - 15000).clamp(
                                                0,
                                                duration,
                                              ),
                                            )
                                          : null,
                                    ),
                                  ),
                                  Expanded(
                                    child: GestureDetector(
                                      onTap: canListen ? play : null,
                                      behavior: HitTestBehavior.opaque,
                                      child: Semantics(
                                        button: true,
                                        enabled: canListen,
                                        label: playLabel,
                                        excludeSemantics: true,
                                        onTap: canListen ? play : null,
                                        child: Column(
                                          mainAxisSize: MainAxisSize.min,
                                          children: [
                                            FilledButton(
                                              key: const ValueKey(
                                                'review-play',
                                              ),
                                              style: FilledButton.styleFrom(
                                                minimumSize: Size.square(64),
                                                padding: EdgeInsets.zero,
                                                shape: const CircleBorder(),
                                                backgroundColor:
                                                    Theme.of(context)
                                                            .brightness ==
                                                        Brightness.dark
                                                    ? const Color(0xFFF3F5FA)
                                                    : scheme.primary,
                                                foregroundColor:
                                                    Theme.of(context)
                                                            .brightness ==
                                                        Brightness.dark
                                                    ? scheme.surface
                                                    : scheme.onPrimary,
                                              ),
                                              onPressed: canListen
                                                  ? play
                                                  : null,
                                              child: Icon(
                                                active && state.isPlaying
                                                    ? Icons.pause_rounded
                                                    : Icons.play_arrow_rounded,
                                                size: 30,
                                              ),
                                            ),
                                            const SizedBox(height: 4),
                                            Text(
                                              playLabel,
                                              textAlign: TextAlign.center,
                                              style: text.bodySmall,
                                            ),
                                          ],
                                        ),
                                      ),
                                    ),
                                  ),
                                  Expanded(
                                    child: _ReviewSkip(
                                      forward: true,
                                      senior: state.senior,
                                      onPressed: canListen
                                          ? () => seek(
                                              (position + 15000).clamp(
                                                0,
                                                duration,
                                              ),
                                            )
                                          : null,
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      if (missing)
                        const Notice('本机音频文件已缺失。作品信息仍保留，可从更多操作重新演唱。'),
                      if ((work['interruption'] as String? ?? '').isNotEmpty)
                        Notice('录音已保留：${work['interruption']}'),
                      if (waveform.isNotEmpty &&
                          waveform.every((v) => v < .001))
                        const Notice('这段录音没有明显人声，请检查麦克风后试听。'),
                      if (!missing)
                        LayoutBuilder(
                          builder: (context, constraints) {
                            final copy = Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '声音比例',
                                  style: text.bodyMedium?.copyWith(
                                    fontWeight: FontWeight.w500,
                                  ),
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  '人声 ${(voice * 100).round()}% · 伴奏 ${(backing * 100).round()}%${shift == 0 ? '' : ' · 已调整同步'}',
                                  style: text.bodySmall?.copyWith(
                                    color: scheme.onSurfaceVariant,
                                  ),
                                ),
                              ],
                            );
                            final adjust = TextButton.icon(
                              onPressed: saved || working ? null : adjustSound,
                              icon: const Icon(Icons.tune_rounded, size: 20),
                              label: const Text('调整声音'),
                            );
                            final stack =
                                state.senior ||
                                MediaQuery.textScalerOf(context).scale(15) >
                                    21 ||
                                constraints.maxWidth < 310;
                            return Padding(
                              padding: const EdgeInsets.symmetric(vertical: 12),
                              child: stack
                                  ? Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        copy,
                                        const SizedBox(height: 8),
                                        adjust,
                                      ],
                                    )
                                  : Row(
                                      children: [
                                        Expanded(child: copy),
                                        adjust,
                                      ],
                                    ),
                            );
                          },
                        ),
                      if (working)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          child: Semantics(
                            liveRegion: true,
                            child: Column(
                              children: [
                                const LinearProgressIndicator(),
                                const SizedBox(height: 8),
                                const Text('正在处理录音，请稍候…'),
                                const SizedBox(height: 4),
                                Text(
                                  saving ? '正在保存到此手机。' : '原始录音已保留，完成后即可回听。',
                                  style: text.bodySmall?.copyWith(
                                    color: scheme.onSurfaceVariant,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      if (error != null)
                        Notice(
                          error!,
                          action:
                              renderFailed && canRender && !saved && !missing
                              ? () => render(listen: true)
                              : load,
                        ),
                      if (!saved && !missing) ...[
                        const Divider(),
                        const SizedBox(height: 16),
                        if (dirty || !ready)
                          FilledButton(
                            onPressed: canRender
                                ? () => render(listen: true)
                                : null,
                            child: Text(renderFailed ? '重新处理并试听' : '应用调整并试听'),
                          )
                        else
                          FilledButton.icon(
                            key: const ValueKey('review-save'),
                            onPressed: working ? null : save,
                            icon: const Icon(Icons.check_rounded),
                            label: const Text('保存作品'),
                          ),
                        const SizedBox(height: 12),
                        Text(
                          '返回后仍可在作品中继续处理草稿',
                          style: text.bodySmall?.copyWith(
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                      if (saved) ...[
                        const Divider(),
                        const SizedBox(height: 16),
                        FilledButton.icon(
                          onPressed: ready && !working ? export : null,
                          icon: const Icon(Icons.ios_share_rounded),
                          label: const Text('导出音频'),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          '保存在本机，可以随时回听或导出。',
                          style: text.bodySmall?.copyWith(
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                      const SizedBox(height: 16),
                      LayoutBuilder(
                        builder: (context, constraints) {
                          final again = OutlinedButton.icon(
                            onPressed: working || song == null
                                ? null
                                : () => openSinging(context, state, song),
                            icon: const Icon(Icons.replay_rounded, size: 20),
                            label: const Text('重新唱一遍'),
                          );
                          final all = OutlinedButton.icon(
                            onPressed: showWorks,
                            icon: const Icon(
                              Icons.graphic_eq_rounded,
                              size: 20,
                            ),
                            label: const Text('查看作品'),
                          );
                          if (state.senior ||
                              MediaQuery.textScalerOf(context).scale(15) > 21 ||
                              constraints.maxWidth < 310) {
                            return Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [again, const SizedBox(height: 8), all],
                            );
                          }
                          return Row(
                            children: [
                              Expanded(child: again),
                              const SizedBox(width: 12),
                              Expanded(child: all),
                            ],
                          );
                        },
                      ),
                    ],
                  ),
          ),
        ),
      );
    },
  );
}

class _ReviewSkip extends StatelessWidget {
  const _ReviewSkip({
    required this.forward,
    required this.senior,
    this.onPressed,
  });
  final bool forward, senior;
  final VoidCallback? onPressed;
  @override
  Widget build(BuildContext context) => TextButton(
    key: ValueKey(forward ? 'review-forward' : 'review-backward'),
    onPressed: onPressed,
    style: TextButton.styleFrom(
      foregroundColor: Theme.of(context).colorScheme.onSurfaceVariant,
      minimumSize: Size(senior ? 64 : 56, senior ? 64 : 56),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
    ),
    child: Semantics(
      label: forward ? '前进 15 秒' : '后退 15 秒',
      excludeSemantics: true,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Transform.flip(
            flipX: forward,
            child: const Icon(Icons.replay_rounded, size: 22),
          ),
          const SizedBox(height: 4),
          const Text('15 秒'),
        ],
      ),
    ),
  );
}

class _RenameDialog extends StatefulWidget {
  const _RenameDialog({required this.initial});
  final String initial;
  @override
  State<_RenameDialog> createState() => _RenameDialogState();
}

class _RenameDialogState extends State<_RenameDialog> {
  late final controller = TextEditingController(text: widget.initial);
  String? error;
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  void submit() {
    if (controller.text.trim().isEmpty || controller.text.trim().length > 120) {
      setState(() => error = '请输入 1 到 120 字的名称');
      return;
    }
    Navigator.pop(context, controller.text.trim());
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('作品名称'),
    content: TextField(
      controller: controller,
      autofocus: true,
      maxLength: 120,
      decoration: InputDecoration(errorText: error),
      onSubmitted: (_) => submit(),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(onPressed: submit, child: const Text('完成')),
    ],
  );
}
