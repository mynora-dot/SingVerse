import 'dart:async';

import 'package:flutter/material.dart';

import '../core/app_state.dart';
import '../core/models.dart';
import '../features/library/connection.dart';
import '../features/library/library_pages.dart';
import '../features/library/widgets.dart';
import '../features/player/player_page.dart';
import '../features/singing/actions.dart';
import '../features/singing/works_page.dart';
import '../features/tv_control/tv_page.dart';
import 'design.dart';

class AppShell extends StatefulWidget {
  const AppShell({super.key, required this.state});
  final AppState state;
  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int tab = 0;
  Future<void> connect() async {
    if (widget.state.taking) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请先结束当前录音，再修改 NAS 连接。')));
      return;
    }
    await Navigator.push(
      context,
      MaterialPageRoute<void>(
        settings: const RouteSettings(name: 'connection'),
        builder: (_) => ConnectionPage(state: widget.state),
      ),
    );
  }

  void personal(bool history) => Navigator.push(
    context,
    MaterialPageRoute<void>(
      builder: (_) => PersonalPage(state: widget.state, history: history),
    ),
  );
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state,
    builder: (context, _) {
      final state = widget.state;
      return Scaffold(
        extendBody: true,
        body: SafeArea(
          bottom: false,
          child: IndexedStack(
            index: tab,
            children: [
              state.authenticated
                  ? LibraryHome(key: ValueKey(state.instanceId), state: state)
                  : ListView(
                      padding: const EdgeInsets.fromLTRB(24, 24, 24, 200),
                      children: [
                        Row(
                          children: [
                            Text(
                              '曲库',
                              style: Theme.of(context).textTheme.headlineLarge,
                            ),
                            const Spacer(),
                            IconButton(
                              tooltip: '扫码点歌',
                              onPressed: () => openTv(context, state),
                              icon: const Icon(Icons.qr_code_scanner_rounded),
                            ),
                          ],
                        ),
                        const SizedBox(height: 32),
                        const _SingingHero(),
                        const SizedBox(height: 28),
                        Text(
                          '从家里的曲库，\n唱出自己的故事。',
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                        const SizedBox(height: 12),
                        const Text(
                          '连接你的 NAS，选好伴奏，就能练唱和录唱。',
                          style: TextStyle(
                            color: Color(0xFF7C8683),
                            height: 1.7,
                          ),
                        ),
                        if (state.error != null) Notice(state.error!),
                        const SizedBox(height: 28),
                        FilledButton(
                          onPressed: connect,
                          child: Text(
                            state.origin.isEmpty ? '连接我的 NAS' : '登录我的 NAS',
                          ),
                        ),
                        const SizedBox(height: 8),
                        TextButton.icon(
                          onPressed: () => openTv(context, state),
                          icon: const Icon(Icons.qr_code_scanner_rounded),
                          label: const Text('扫码点歌'),
                        ),
                      ],
                    ),
              _my(context),
            ],
          ),
        ),
        bottomNavigationBar: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (state.current != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
                child: Glass(
                  state: state,
                  radius: 20,
                  child: ListTile(
                    contentPadding: const EdgeInsets.symmetric(horizontal: 14),
                    leading: CircleAvatar(
                      backgroundColor: (state.recording ? coral : mint)
                          .withValues(alpha: .15),
                      foregroundColor: state.recording
                          ? coral
                          : Theme.of(context).colorScheme.primary,
                      child: Icon(
                        state.recording
                            ? Icons.mic_rounded
                            : Icons.mic_none_rounded,
                      ),
                    ),
                    title: Text(
                      state.current!.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    subtitle: Text(
                      state.recording
                          ? '● 正在录唱 · ${durationLabel((state.singing['recordedMs'] as num? ?? 0).toInt())}'
                          : state.workId.isNotEmpty
                          ? '返回作品回听'
                          : '返回演唱',
                      style: TextStyle(
                        fontSize: 12,
                        color: state.recording ? coral : null,
                      ),
                    ),
                    trailing: const Icon(Icons.chevron_right_rounded),
                    onTap: () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        settings: RouteSettings(
                          name: state.workId.isNotEmpty ? 'review' : 'player',
                        ),
                        builder: (_) => performancePage(state),
                      ),
                    ),
                  ),
                ),
              ),
            Glass(
              state: state,
              radius: 0,
              child: SafeArea(
                top: false,
                child: SizedBox(
                  height:
                      64 + (MediaQuery.textScalerOf(context).scale(16) - 16),
                  child: Row(
                    children: [
                      for (final entry in [
                        (0, Icons.mic_none_rounded, '曲库'),
                        (1, Icons.person_outline_rounded, '我的'),
                      ])
                        Expanded(
                          child: Semantics(
                            selected: tab == entry.$1,
                            child: TextButton(
                              style: TextButton.styleFrom(
                                foregroundColor: tab == entry.$1
                                    ? Theme.of(context).colorScheme.primary
                                    : const Color(0xFF929A96),
                                padding: const EdgeInsets.symmetric(
                                  vertical: 8,
                                ),
                              ),
                              onPressed: () => setState(() => tab = entry.$1),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(entry.$2, size: 25),
                                  const SizedBox(height: 3),
                                  Text(
                                    entry.$3,
                                    style: const TextStyle(fontSize: 11),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    },
  );
  Widget _my(BuildContext context) {
    final state = widget.state;
    final saved = state.works.where((w) => w['status'] == 'SAVED').length;
    final pending = state.works.where((w) => w['status'] != 'SAVED').length;
    return ListView(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 190),
      children: [
        Text('我的', style: Theme.of(context).textTheme.headlineLarge),
        const SizedBox(height: 24),
        Row(
          children: [
            Container(
              width: 58,
              height: 58,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: LinearGradient(
                  colors: [mint.withValues(alpha: .3), const Color(0xFFCCE5D9)],
                ),
              ),
              child: const Icon(
                Icons.person_outline_rounded,
                size: 32,
                color: Color(0xFF35785F),
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '唱给自己的每一天',
                    style: TextStyle(fontSize: 19, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 5),
                  Text(
                    state.authenticated ? '家庭曲库已连接' : '本机作品随时可听',
                    style: const TextStyle(
                      fontSize: 13,
                      color: Color(0xFF909994),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 28),
        Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(24),
            gradient: const LinearGradient(
              colors: [Color(0xFFEEF7F2), Color(0xFFF8F3EB)],
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.all(22),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    const Icon(
                      Icons.mic_none_rounded,
                      color: Color(0xFF3D7F64),
                    ),
                    const SizedBox(width: 8),
                    const Text(
                      '我的作品',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const Spacer(),
                    const Icon(
                      Icons.arrow_forward_rounded,
                      color: Color(0xFF3D7F64),
                    ),
                  ],
                ),
                const SizedBox(height: 14),
                Text(
                  '$saved 个已保存作品 · $pending 段待处理录音',
                  style: const TextStyle(
                    color: Color(0xFF77877F),
                    fontSize: 13,
                  ),
                ),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: () => Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (_) => WorksPage(state: state),
                    ),
                  ),
                  child: const Text('打开我的作品'),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 24),
        Card(
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.favorite_border_rounded),
                title: const Text('常唱收藏'),
                trailing: Text(
                  '${state.favorites.length}',
                  style: const TextStyle(color: Color(0xFF909994)),
                ),
                onTap: () => personal(false),
              ),
              const Divider(indent: 56, height: 1),
              ListTile(
                leading: const Icon(Icons.history_rounded),
                title: const Text('练唱历史'),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => personal(true),
              ),
            ],
          ),
        ),
        const SizedBox(height: 18),
        Card(
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.dns_outlined),
                title: const Text('NAS 连接与账号'),
                subtitle: Text(
                  state.origin.isEmpty ? '尚未连接家庭曲库' : state.origin,
                ),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: connect,
              ),
              const Divider(indent: 56, height: 1),
              ListTile(
                leading: const Icon(Icons.qr_code_scanner_rounded),
                title: const Text('扫码点歌'),
                onTap: () => openTv(context, state),
              ),
              const Divider(indent: 56, height: 1),
              SwitchListTile(
                secondary: const Icon(Icons.blur_off_outlined),
                title: const Text('减少透明效果'),
                value: state.reduceTransparency,
                onChanged: state.setTransparency,
              ),
            ],
          ),
        ),
        if (state.authenticated && state.defaultPassword)
          const Notice('建议到 NAS 管理端修改初始密码。'),
        if (state.authenticated)
          TextButton(
            onPressed: () => uiTask(context, state.logout),
            child: const Text('退出登录'),
          ),
        const SizedBox(height: 20),
        const Text(
          '声音留在手机，快乐留在此刻。',
          textAlign: TextAlign.center,
          style: TextStyle(color: Color(0xFFA1A7A2), fontSize: 12),
        ),
      ],
    );
  }
}

class _SingingHero extends StatelessWidget {
  const _SingingHero({this.onFind});
  final VoidCallback? onFind;
  @override
  Widget build(BuildContext context) => Container(
    clipBehavior: Clip.antiAlias,
    decoration: BoxDecoration(
      borderRadius: BorderRadius.circular(26),
      gradient: const LinearGradient(
        colors: [Color(0xFFE2F2E9), Color(0xFFF4F5E9)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      ),
    ),
    child: Stack(
      children: [
        Positioned(
          right: -20,
          top: -25,
          child: Container(
            width: 190,
            height: 190,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: Colors.white.withValues(alpha: .5),
                width: 30,
              ),
            ),
          ),
        ),
        Positioned(
          right: 10,
          bottom: -15,
          child: Transform.rotate(
            angle: -.25,
            child: Icon(
              Icons.mic_none_rounded,
              size: 128,
              color: const Color(0xFF477D64).withValues(alpha: .28),
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'SINGVERSE  /  YOUR LITTLE STAGE',
                style: TextStyle(
                  fontSize: 9,
                  letterSpacing: 1.5,
                  fontWeight: FontWeight.w700,
                  color: Color(0xFF6F927F),
                ),
              ),
              const SizedBox(height: 18),
              const Text(
                '今天，\n想唱哪首？',
                style: TextStyle(
                  fontSize: 31,
                  height: 1.2,
                  fontWeight: FontWeight.w700,
                  color: ink,
                  letterSpacing: -.7,
                ),
              ),
              const SizedBox(height: 12),
              const Text(
                '把熟悉的旋律，唱成自己的故事。',
                style: TextStyle(color: Color(0xFF7D8F80), fontSize: 12),
              ),
              if (onFind != null) ...[
                const SizedBox(height: 20),
                FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: const Color(0xFF397B60),
                    foregroundColor: Colors.white,
                    minimumSize: const Size(0, 42),
                  ),
                  onPressed: onFind,
                  icon: const Icon(Icons.mic_none_rounded, size: 17),
                  label: const Text('找首拿手歌'),
                ),
              ],
            ],
          ),
        ),
      ],
    ),
  );
}

class LibraryHome extends StatefulWidget {
  const LibraryHome({super.key, required this.state});
  final AppState state;
  @override
  State<LibraryHome> createState() => _LibraryHomeState();
}

class _LibraryHomeState extends State<LibraryHome> {
  late final query = SongQuery(widget.state);
  @override
  void initState() {
    super.initState();
    unawaited(query.load());
  }

  @override
  void dispose() {
    query.dispose();
    super.dispose();
  }

  void search() => Navigator.push(
    context,
    MaterialPageRoute<void>(
      builder: (_) => SongsPage(state: widget.state, title: '找首想唱的歌'),
    ),
  );
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: query,
    builder: (context, _) => RefreshIndicator(
      onRefresh: () => query.load(),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(24, 20, 24, 200),
        children: [
          Row(
            children: [
              Text('曲库', style: Theme.of(context).textTheme.headlineLarge),
              const Spacer(),
              IconButton(
                tooltip: '扫码点歌',
                onPressed: () => openTv(context, widget.state),
                icon: const Icon(Icons.qr_code_scanner_rounded, size: 23),
              ),
            ],
          ),
          const SizedBox(height: 3),
          Row(
            children: [
              Container(
                width: 5,
                height: 5,
                decoration: const BoxDecoration(
                  color: mint,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 6),
              const Text(
                '已连接家庭曲库',
                style: TextStyle(fontSize: 11, color: Color(0xFF8B958F)),
              ),
            ],
          ),
          const SizedBox(height: 22),
          _SingingHero(onFind: search),
          const SizedBox(height: 20),
          InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: search,
            child: const IgnorePointer(
              child: TextField(
                decoration: InputDecoration(
                  hintText: '搜索歌名、歌手，找到你的拿手歌',
                  hintStyle: TextStyle(fontSize: 13),
                  prefixIcon: Icon(Icons.search_rounded, size: 21),
                ),
              ),
            ),
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              for (final entry in [
                (Icons.person_outline_rounded, '歌手'),
                (Icons.grid_view_rounded, '分类'),
                (Icons.queue_music_rounded, '共享歌单'),
              ])
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 3),
                    child: OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        side: const BorderSide(color: Color(0xFFE4EAE5)),
                        padding: const EdgeInsets.symmetric(horizontal: 5),
                        minimumSize: const Size(0, 42),
                      ),
                      onPressed: () => Navigator.push(
                        context,
                        MaterialPageRoute<void>(
                          builder: (_) =>
                              BrowsePage(state: widget.state, kind: entry.$2),
                        ),
                      ),
                      icon: Icon(entry.$1, size: 17),
                      label: Text(
                        entry.$2 == '共享歌单' ? '家庭歌单' : entry.$2,
                        style: const TextStyle(fontSize: 12),
                      ),
                    ),
                  ),
                ),
            ],
          ),
          if (widget.state.history.isNotEmpty) ...[
            const SizedBox(height: 24),
            const Text(
              '接着，把这首唱好。',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 10),
            Card(
              child: ListTile(
                leading: Artwork(
                  state: widget.state,
                  path: widget.state.history.first.cover,
                  label: widget.state.history.first.title,
                ),
                title: Text(widget.state.history.first.title),
                subtitle: const Text('上次的旋律，还在等你'),
                trailing: const Icon(Icons.mic_none_rounded),
                onTap: () => openPreparation(
                  context,
                  widget.state,
                  widget.state.history.first,
                ),
              ),
            ),
          ],
          const SizedBox(height: 28),
          Row(
            children: [
              const Expanded(
                child: Text(
                  '选首想唱的歌',
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w700,
                    letterSpacing: -.3,
                  ),
                ),
              ),
              Text(
                '${query.total} 首曲目',
                style: const TextStyle(color: Color(0xFF97A198), fontSize: 11),
              ),
            ],
          ),
          const SizedBox(height: 12),
          ...query.items.map(
            (song) => SongRow(
              state: widget.state,
              song: song,
              onTap: () => showSong(context, widget.state, song),
            ),
          ),
          if (query.loading)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(20),
                child: CircularProgressIndicator(),
              ),
            ),
          if (query.error != null)
            Notice(query.error!, action: () => query.load()),
          if (!query.loading && query.error == null && query.items.isEmpty)
            const Notice('曲库还没有歌曲，在 NAS 管理端添加自备歌曲后即可开唱。'),
          if (query.hasMore && !query.loading)
            TextButton(
              onPressed: () => query.load(more: true),
              child: const Text('再找一些好歌'),
            ),
        ],
      ),
    ),
  );
}

class PersonalPage extends StatelessWidget {
  const PersonalPage({super.key, required this.state, required this.history});
  final AppState state;
  final bool history;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        title: Text(history ? '播放历史' : '本机收藏'),
        actions: [
          if (history)
            IconButton(
              tooltip: '清空本机历史',
              onPressed: () async {
                final confirmed = await showDialog<bool>(
                  context: context,
                  builder: (context) => AlertDialog(
                    title: const Text('清空此 NAS 的本机历史？'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(context, false),
                        child: const Text('取消'),
                      ),
                      FilledButton(
                        onPressed: () => Navigator.pop(context, true),
                        child: const Text('清空'),
                      ),
                    ],
                  ),
                );
                if (confirmed == true && context.mounted) {
                  await uiTask(context, () async {
                    await state.store.clearHistory(state.instanceId);
                    await state.refreshPersonal();
                  });
                }
              },
              icon: const Icon(Icons.delete_outline),
            ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text('保存在此手机 · 按 NAS 分别记录'),
          const SizedBox(height: 16),
          if ((history ? state.history : state.favorites).isEmpty)
            const Notice('还没有记录，去曲库找首喜欢的歌吧。'),
          ...(history ? state.history : state.favorites).map(
            (song) => SongRow(
              state: state,
              song: song,
              onTap: () => showSong(context, state, song),
            ),
          ),
        ],
      ),
    ),
  );
}
