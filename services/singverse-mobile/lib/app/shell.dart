import 'package:flutter/material.dart';

import '../core/app_state.dart';
import '../features/library/connection.dart';
import '../features/library/library_pages.dart';
import '../features/library/widgets.dart';
import '../features/singing/works_page.dart';
import '../features/tv_control/tv_page.dart';
import 'design.dart';
import 'mini_player.dart';

class AppShell extends StatefulWidget {
  const AppShell({super.key, required this.state});
  final AppState state;
  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int tab = 0;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state,
    builder: (context, _) {
      final state = widget.state;
      final drafts = state.visibleWorks
          .where((w) => w['status'] != 'SAVED' && w['status'] != 'MISSING')
          .length;
      final largeToolbar = MediaQuery.textScalerOf(context).scale(16) > 22;
      return PopScope(
        canPop: tab == 0,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) setState(() => tab = 0);
        },
        child: Scaffold(
          appBar: AppBar(
            automaticallyImplyLeading: false,
            title: Text(
              const ['点歌', '常唱', '作品'][tab],
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            actions: [
              if (state.senior && largeToolbar)
                TextButton(
                  key: const Key('open-tv'),
                  style: TextButton.styleFrom(
                    textStyle: Theme.of(context).textTheme.labelLarge
                        ?.copyWith(inherit: true, fontSize: 18),
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                  ),
                  onPressed: () => openTv(context, state),
                  child: const Text('扫码'),
                )
              else if (state.senior)
                TextButton.icon(
                  key: const Key('open-tv'),
                  onPressed: () => openTv(context, state),
                  icon: const Icon(Icons.qr_code_scanner_rounded),
                  label: const Text('扫码'),
                )
              else
                IconButton(
                  key: const Key('open-tv'),
                  tooltip: '扫一扫',
                  onPressed: () => openTv(context, state),
                  icon: const Icon(Icons.qr_code_scanner_rounded),
                ),
              if (state.senior && largeToolbar)
                TextButton(
                  key: const Key('open-settings'),
                  style: TextButton.styleFrom(
                    textStyle: Theme.of(context).textTheme.labelLarge
                        ?.copyWith(inherit: true, fontSize: 18),
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                  ),
                  onPressed: () => openSettings(context, state),
                  child: const Text('设置'),
                )
              else if (state.senior)
                TextButton.icon(
                  key: const Key('open-settings'),
                  onPressed: () => openSettings(context, state),
                  icon: const Icon(Icons.settings_outlined),
                  label: const Text('设置'),
                )
              else
                IconButton(
                  key: const Key('open-settings'),
                  tooltip: '设置',
                  onPressed: () => openSettings(context, state),
                  icon: const Icon(Icons.settings_outlined),
                ),
              const SizedBox(width: 8),
            ],
          ),
          body: SafeArea(
            top: false,
            bottom: false,
            child: ContentWidth(
              child: IndexedStack(
                index: tab,
                children: [
                  if (state.authenticated)
                    SongsPage(
                      key: ValueKey(state.instanceId),
                      state: state,
                      embedded: true,
                      showBrowse: true,
                    )
                  else
                    _Disconnected(state: state),
                  FrequentPage(
                    key: ValueKey('personal:${state.instanceId}'),
                    state: state,
                  ),
                  WorksPage(state: state, embedded: true),
                ],
              ),
            ),
          ),
          bottomNavigationBar: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (state.current != null &&
                  MediaQuery.viewInsetsOf(context).bottom == 0)
                MiniPlayer(state: state),
              Glass(
                state: state,
                radius: 0,
                child: SafeArea(
                  top: false,
                  child: Row(
                    children: [
                      for (final entry in [
                        (0, Icons.library_music_outlined, '点歌', 'nav-songs'),
                        (
                          1,
                          Icons.favorite_outline_rounded,
                          '常唱',
                          'nav-frequent',
                        ),
                        (2, Icons.graphic_eq_rounded, '作品', 'nav-works'),
                      ])
                        Expanded(
                          child: Semantics(
                            selected: tab == entry.$1,
                            child: TextButton(
                              key: Key(entry.$4),
                              style: TextButton.styleFrom(
                                minimumSize: Size(56, state.senior ? 64 : 56),
                                foregroundColor: tab == entry.$1
                                    ? Theme.of(context).colorScheme.primary
                                    : Theme.of(context)
                                          .colorScheme
                                          .onSurfaceVariant,
                                padding: EdgeInsets.symmetric(vertical: 8),
                              ),
                              onPressed: () => setState(() => tab = entry.$1),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Badge(
                                    isLabelVisible: entry.$1 == 2 && drafts > 0,
                                    backgroundColor: Theme.of(context)
                                        .colorScheme
                                        .secondaryContainer,
                                    textColor: Theme.of(context)
                                        .colorScheme
                                        .onSurfaceVariant,
                                    label: Text('$drafts'),
                                    child: Icon(
                                      entry.$2,
                                      size: state.senior ? 28 : 24,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    entry.$3,
                                    style: TextStyle(
                                      fontSize: state.senior ? 18 : 12,
                                      fontWeight: tab == entry.$1
                                          ? FontWeight.w600
                                          : FontWeight.w400,
                                    ),
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
            ],
          ),
        ),
      );
    },
  );
}

class _Disconnected extends StatelessWidget {
  const _Disconnected({required this.state});
  final AppState state;
  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(20),
    children: [
      const SizedBox(height: 24),
      Align(
        alignment: Alignment.centerLeft,
        child: Icon(
          Icons.library_music_outlined,
          size: 32,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
      const SizedBox(height: 24),
      Text(
        state.origin.isEmpty ? '连接家庭曲库' : '重新连接家庭曲库',
        style: Theme.of(context).textTheme.headlineSmall,
      ),
      const SizedBox(height: 12),
      Text(
        '从 NAS 选歌练唱，录音保存在手机。已保存的作品可以离线回听。',
        style: Theme.of(context).textTheme.bodyLarge
            ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
      ),
      if (state.error != null) Notice(state.error!),
      const SizedBox(height: 24),
      FilledButton.icon(
        onPressed: () => openConnection(context, state),
        icon: const Icon(Icons.link_rounded),
        label: Text(state.origin.isEmpty ? '连接 NAS' : '重新登录'),
      ),
    ],
  );
}

class FrequentPage extends StatefulWidget {
  const FrequentPage({super.key, required this.state});
  final AppState state;
  @override
  State<FrequentPage> createState() => _FrequentPageState();
}

class _FrequentPageState extends State<FrequentPage> {
  bool history = false;
  final search = TextEditingController();
  @override
  void dispose() {
    search.dispose();
    super.dispose();
  }

  Future<void> clear() async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清空最近练唱？'),
        content: const Text('只清除此 NAS 在手机上的记录，不影响收藏和作品。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('清空记录'),
          ),
        ],
      ),
    );
    if (yes == true && mounted) {
      await uiTask(context, () async {
        await widget.state.store.clearHistory(widget.state.instanceId);
        await widget.state.refreshPersonal();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final rows = (history ? state.history : state.favorites)
        .where(
          (s) => '${s.title} ${s.artist}'.toLowerCase().contains(
            search.text.toLowerCase(),
          ),
        )
        .toList();
    return CustomScrollView(
      key: PageStorageKey('personal-scroll:${state.instanceId}'),
      keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
          sliver: SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextField(
                  controller: search,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    prefixIcon: const Icon(Icons.search_rounded),
                    hintText: history ? '搜索最近练唱' : '搜索收藏歌曲',
                    suffixIcon: search.text.isEmpty
                        ? null
                        : IconButton(
                            tooltip: '清除搜索',
                            onPressed: () => setState(search.clear),
                            icon: const Icon(Icons.cancel_rounded),
                          ),
                  ),
                ),
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(4),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.secondaryContainer,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Row(
                    children: [
                      for (final item in [(false, '收藏'), (true, '最近练唱')])
                        Expanded(
                          child: Semantics(
                            selected: history == item.$1,
                            child: TextButton(
                              style: TextButton.styleFrom(
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                backgroundColor: history == item.$1
                                    ? Theme.of(context).colorScheme.surface
                                    : Colors.transparent,
                                foregroundColor: history == item.$1
                                    ? Theme.of(context).colorScheme.onSurface
                                    : Theme.of(context)
                                          .colorScheme
                                          .onSurfaceVariant,
                                padding: const EdgeInsets.symmetric(
                                  vertical: 8,
                                ),
                              ),
                              onPressed: () =>
                                  setState(() => history = item.$1),
                              child: Text(item.$2),
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(0, 16, 0, 8),
                  child: SectionTitle(
                    history ? '最近练唱' : '我的收藏',
                    trailing: history && state.history.isNotEmpty
                        ? TextButton(onPressed: clear, child: const Text('清空'))
                        : Text(
                            '${history ? state.history.length : state.favorites.length} 首',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                  ),
                ),
                if (rows.isEmpty)
                  Notice(
                    search.text.isNotEmpty
                        ? '没有匹配的歌曲，试试其他关键词。'
                        : history
                        ? '唱过的歌曲会出现在这里。'
                        : '还没有收藏。点歌曲旁的爱心，就能在这里快速找到。',
                  ),
              ],
            ),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.symmetric(horizontal: 20),
          sliver: SliverList.builder(
            itemCount: rows.length,
            itemBuilder: (context, index) =>
                SongRow(state: state, song: rows[index]),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
          sliver: SliverToBoxAdapter(
            child: Column(
              children: [
                if (rows.isNotEmpty) ...[
                  const SizedBox(height: 20),
                  Text(
                    '只保存在此手机 · 按 NAS 分别记录',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ],
    );
  }
}

void openSettings(BuildContext context, AppState state) => Navigator.push(
  context,
  MaterialPageRoute<void>(
    settings: const RouteSettings(name: 'settings'),
    builder: (_) => SettingsPage(state: state),
  ),
);

class SettingsPage extends StatelessWidget {
  const SettingsPage({super.key, required this.state});
  final AppState state;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) => Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ContentWidth(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 4, 20, 32),
          children: [
            const SectionTitle('使用方式'),
            const SizedBox(height: 12),
            Card(
              child: SwitchListTile(
                value: state.senior,
                onChanged: (value) => uiTask(
                  context,
                  () =>
                      state.setUiMode(value ? UiMode.senior : UiMode.standard),
                ),
                title: Text(state.senior ? '长辈模式已开启' : '长辈模式'),
                subtitle: const Text('放大文字和按钮，主导航位置保持不变'),
              ),
            ),
            const SizedBox(height: 24),
            const SectionTitle('连接与显示'),
            const SizedBox(height: 12),
            Card(
              child: ListTile(
                leading: const Icon(Icons.dns_outlined, size: 24),
                title: const Text('NAS 连接与账号'),
                subtitle: Text(
                  state.authenticated
                      ? '已登录\n${state.origin}'
                      : state.origin.isEmpty
                      ? '尚未连接'
                      : '需要登录\n${state.origin}',
                ),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => openConnection(context, state),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
                    child: Text(
                      '外观',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                  for (final choice in [
                    (ThemeMode.system, '跟随系统'),
                    (ThemeMode.light, '浅色'),
                    (ThemeMode.dark, '深色'),
                  ])
                    Semantics(
                      checked: state.themeMode == choice.$1,
                      inMutuallyExclusiveGroup: true,
                      child: ListTile(
                        title: Text(choice.$2),
                        trailing: Icon(
                          state.themeMode == choice.$1
                              ? Icons.check_circle_rounded
                              : Icons.circle_outlined,
                          color: state.themeMode == choice.$1
                              ? Theme.of(context).colorScheme.primary
                              : null,
                        ),
                        onTap: () =>
                            uiTask(context, () => state.setTheme(choice.$1)),
                      ),
                    ),
                  const Divider(indent: 16),
                  SwitchListTile(
                    value: state.reduceTransparency,
                    onChanged: (v) =>
                        uiTask(context, () => state.setTransparency(v)),
                    title: const Text('减少透明效果'),
                    subtitle: Text(
                      state.senior ? '长辈模式始终使用清晰的不透明背景' : '提高导航和播放器的背景清晰度',
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            Text(
              '歌曲来自 NAS。收藏、练唱记录和录音作品保存在此手机，不会自动上传。',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            if (state.defaultPassword) const Notice('NAS 仍使用初始密码，请在管理端修改。'),
          ],
        ),
      ),
    ),
  );
}
