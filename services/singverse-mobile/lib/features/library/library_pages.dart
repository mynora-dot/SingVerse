import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import 'widgets.dart';

const tagDimensions = {
  'LANGUAGE': '语种',
  'DECADE': '年代',
  'GENRE': '风格',
  'MOOD': '情绪',
  'SCENE': '场景',
  'CUSTOM': '其他分类',
};

Future<void> showSong(BuildContext context, AppState state, Song song) =>
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (sheetContext) => SongDetail(state: state, song: song),
    );

class SongsPage extends StatefulWidget {
  const SongsPage({
    super.key,
    required this.state,
    this.title = '歌曲',
    this.artistId,
    this.tags = const [],
    this.embedded = false,
    this.showBrowse = false,
    this.browseItem,
  });
  final AppState state;
  final String title;
  final String? artistId;
  final List<String> tags;
  final bool embedded, showBrowse;
  final Json? browseItem;
  @override
  State<SongsPage> createState() => _SongsPageState();
}

class _SongsPageState extends State<SongsPage> {
  late final query = SongQuery(
    widget.state,
    artistId: widget.artistId,
    tags: widget.tags,
  );
  final search = TextEditingController();
  final searchFocus = FocusNode();
  final tagNames = <String, String>{};
  Timer? debounce;
  @override
  void initState() {
    super.initState();
    searchFocus.addListener(searchChanged);
    unawaited(query.load());
  }

  void searchChanged() => setState(() {});

  @override
  void dispose() {
    debounce?.cancel();
    searchFocus.removeListener(searchChanged);
    searchFocus.dispose();
    search.dispose();
    query.dispose();
    super.dispose();
  }

  Future<void> filters() => uiTask(context, () async {
    final tags = (await widget.state.get('/api/v1/classification/tags') as List)
        .cast<Json>()
        .where(
          (t) =>
              t['active'] == true &&
              ((t['count'] as num? ?? 0) > 0 || query.tagIds.contains(t['id'])),
        )
        .toList();
    for (final tag in tags) {
      tagNames[tag['id'] as String] = tag['name'] as String;
    }
    final selected = {...query.tagIds};
    if (!mounted) return;
    final result = await showModalBottomSheet<List<String>>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (context) => StatefulBuilder(
        builder: (context, update) => SizedBox(
          height: MediaQuery.sizeOf(context).height * .78,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        '筛选歌曲',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                    ),
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('取消'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Expanded(
                  child: ListView(
                    children: [
                      if (tags.isEmpty) const Notice('曲库还没有可用分类。'),
                      for (final dimension in {
                        ...tagDimensions.keys,
                        ...tags.map(
                          (t) => t['dimension'] as String? ?? 'CUSTOM',
                        ),
                      })
                        if (tags.any(
                          (t) => (t['dimension'] ?? 'CUSTOM') == dimension,
                        )) ...[
                          Padding(
                            padding: const EdgeInsets.only(top: 16, bottom: 8),
                            child: Text(
                              tagDimensions[dimension] ?? dimension,
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                          ),
                          Wrap(
                            spacing: 8,
                            runSpacing: 4,
                            children: [
                              for (final tag in tags.where(
                                (t) =>
                                    (t['dimension'] ?? 'CUSTOM') == dimension,
                              ))
                                FilterChip(
                                  label: Text(
                                    '${tag['name']} · ${tag['count']}',
                                  ),
                                  selected: selected.contains(tag['id']),
                                  onSelected: (yes) => update(() {
                                    yes
                                        ? selected.add(tag['id'] as String)
                                        : selected.remove(tag['id']);
                                  }),
                                ),
                            ],
                          ),
                        ],
                    ],
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  alignment: WrapAlignment.spaceBetween,
                  spacing: 12,
                  children: [
                    TextButton(
                      onPressed: () => update(selected.clear),
                      child: const Text('清除全部'),
                    ),
                    FilledButton(
                      onPressed: () =>
                          Navigator.pop(context, selected.toList()),
                      child: Text('应用筛选 (${selected.length})'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (result != null && mounted) {
      query.tagIds = result;
      await query.load();
    }
  });

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: query,
    builder: (context, _) {
      final state = widget.state;
      final searching =
          searchFocus.hasFocus ||
          search.text.isNotEmpty ||
          query.tagIds.isNotEmpty;
      final header = Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.browseItem != null)
            _BrowseIntroduction(state: state, item: widget.browseItem!),
          if (widget.showBrowse && !searching)
            Padding(
              padding: const EdgeInsets.only(top: 4, bottom: 16),
              child: Wrap(
                alignment: WrapAlignment.spaceBetween,
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: 12,
                runSpacing: 4,
                children: [
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (query.error != null) ...[
                        Icon(
                          Icons.wifi_off_rounded,
                          size: 16,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                        const SizedBox(width: 8),
                      ],
                      Text(
                        query.error == null ? '家里的曲库' : '曲库暂不可读',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                  Text(
                    query.loading && query.total == 0
                        ? '正在读取歌曲…'
                        : '${query.total} 首歌曲',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          TextField(
            key: const ValueKey('song-search'),
            controller: search,
            focusNode: searchFocus,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              hintText: '搜索歌名、歌手或拼音',
              prefixIcon: const Icon(Icons.search_rounded),
              suffixIcon: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (search.text.isNotEmpty)
                    IconButton(
                      tooltip: '清除搜索',
                      onPressed: () {
                        debounce?.cancel();
                        search.clear();
                        query.query = '';
                        query.load();
                        setState(() {});
                      },
                      icon: const Icon(Icons.cancel_rounded),
                    ),
                  IconButton(
                    tooltip: '筛选歌曲',
                    onPressed: filters,
                    icon: Badge(
                      isLabelVisible: query.tagIds.isNotEmpty,
                      backgroundColor: Theme.of(context).colorScheme.primary,
                      child: const Icon(Icons.tune_rounded),
                    ),
                  ),
                ],
              ),
            ),
            onChanged: (text) {
              query.query = text;
              setState(() {});
              debounce?.cancel();
              debounce = Timer(
                const Duration(milliseconds: 350),
                () => query.load(),
              );
            },
            onSubmitted: (_) {
              debounce?.cancel();
              query.load();
              searchFocus.unfocus();
            },
          ),
          if (widget.showBrowse && !searching) ...[
            const SizedBox(height: 12),
            _BrowseLinks(state: state),
          ],
          if (query.tagIds.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (final id in query.tagIds)
                    InputChip(
                      label: Text(
                        tagNames[id] ??
                            (widget.tags.contains(id) ? widget.title : '已选分类'),
                      ),
                      onDeleted: () {
                        query.tagIds.remove(id);
                        query.load();
                      },
                    ),
                  TextButton(
                    onPressed: () {
                      query.tagIds.clear();
                      query.load();
                    },
                    child: const Text('清除筛选'),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 24),
          SectionTitle(
            searching ? '搜索结果' : '全部歌曲',
            trailing: Text(
              '${query.total} 首',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ),
          const SizedBox(height: 8),
        ],
      );
      final body = RefreshIndicator(
        onRefresh: () => query.load(),
        child: CustomScrollView(
          key: PageStorageKey(
            'songs:${state.instanceId}:${widget.artistId}:${widget.tags.join(',')}',
          ),
          physics: const AlwaysScrollableScrollPhysics(),
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
              sliver: SliverToBoxAdapter(child: header),
            ),
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              sliver: SliverList.builder(
                itemCount: query.items.length,
                itemBuilder: (context, index) => SongRow(
                  key: ValueKey(query.items[index].id),
                  state: state,
                  song: query.items[index],
                ),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
              sliver: SliverToBoxAdapter(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (query.loading)
                      const Padding(
                        padding: EdgeInsets.all(24),
                        child: Center(child: CircularProgressIndicator()),
                      ),
                    if (query.error != null)
                      Notice(query.error!, action: query.retry),
                    if (!query.loading &&
                        query.error == null &&
                        query.items.isEmpty)
                      Notice(
                        search.text.isEmpty && query.tagIds.isEmpty
                            ? '曲库还没有歌曲，请在 NAS 管理端添加自备歌曲。'
                            : '没有找到歌曲，试试其他关键词或清除筛选。',
                      ),
                    if (query.hasMore && !query.loading && query.error == null)
                      TextButton(
                        onPressed: () => query.load(more: true),
                        child: const Text('加载更多歌曲'),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      );
      return widget.embedded
          ? body
          : Scaffold(
              appBar: AppBar(title: Text(widget.title)),
              body: ContentWidth(child: body),
            );
    },
  );
}

class _BrowseIntroduction extends StatelessWidget {
  const _BrowseIntroduction({required this.state, required this.item});
  final AppState state;
  final Json item;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 8, bottom: 24),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Artwork(
              state: state,
              path: item['portraitPath'] as String?,
              revision: item['portraitVersion']?.toString(),
              round: true,
              size: 80,
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item['name'] as String,
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${item['count'] ?? 0} 首歌曲',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        if ((item['biography'] as String? ?? '').trim().isNotEmpty) ...[
          const SizedBox(height: 16),
          Text(
            item['biography'] as String,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ],
    ),
  );
}

class _BrowseLinks extends StatelessWidget {
  const _BrowseLinks({required this.state});
  final AppState state;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final stacked =
          constraints.maxWidth < 280 ||
          MediaQuery.textScalerOf(context).scale(15) > 20;
      final links = [
        for (final item in [
          (Icons.person_outline_rounded, '歌手', '歌手'),
          (Icons.grid_view_rounded, '分类', '分类'),
          (Icons.queue_music_rounded, '家庭歌单', '共享歌单'),
        ])
          TextButton(
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.onSurfaceVariant,
              padding: EdgeInsets.symmetric(
                horizontal: stacked ? 12 : 4,
                vertical: 8,
              ),
              minimumSize: Size(48, state.senior ? 72 : 48),
            ),
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute<void>(
                builder: (_) => BrowsePage(state: state, kind: item.$3),
              ),
            ),
            child: state.senior && !stacked
                ? Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        item.$1,
                        size: 24,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        item.$2,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  )
                : Row(
                    mainAxisAlignment: stacked
                        ? MainAxisAlignment.start
                        : MainAxisAlignment.center,
                    children: [
                      Icon(
                        item.$1,
                        size: 20,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          item.$2,
                          style: state.senior
                              ? null
                              : Theme.of(context).textTheme.bodySmall,
                        ),
                      ),
                      if (stacked) ...[
                        const Spacer(),
                        const Icon(Icons.chevron_right_rounded, size: 20),
                      ],
                    ],
                  ),
          ),
      ];
      return DecoratedBox(
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.onSurface.withValues(alpha: .04),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Padding(
          padding: const EdgeInsets.all(4),
          child: stacked
              ? Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: links,
                )
              : Row(
                  children: [for (final link in links) Expanded(child: link)],
                ),
        ),
      );
    },
  );
}

class BrowsePage extends StatefulWidget {
  const BrowsePage({super.key, required this.state, required this.kind});
  final AppState state;
  final String kind;
  @override
  State<BrowsePage> createState() => _BrowsePageState();
}

class _BrowsePageState extends State<BrowsePage> {
  final search = TextEditingController();
  List<Json> items = [];
  int page = 0, total = 0, generation = 0;
  bool loading = false;
  bool failedMore = false;
  String loadedSearch = '';
  String? error;
  bool get artist => widget.kind == '歌手';
  bool get tags => widget.kind == '分类';
  @override
  void initState() {
    super.initState();
    unawaited(load());
  }

  @override
  void dispose() {
    search.dispose();
    generation++;
    super.dispose();
  }

  Future<void> load({bool more = false}) async {
    if (more && loading) return;
    if (more && loadedSearch != search.text) return load();
    final requestedSearch = search.text;
    final ticket = ++generation, next = more ? page + 1 : 0;
    setState(() {
      loading = true;
      error = null;
      failedMore = false;
      if (!more) items = [];
    });
    try {
      final path = tags
          ? '/api/v1/classification/tags'
          : '/api/v1/${artist ? 'artists' : 'playlists'}?${Uri(queryParameters: {'q': requestedSearch, 'page': '$next', 'size': '20'}).query}';
      final result = await widget.state.get(path);
      if (!mounted || ticket != generation) return;
      final rows = tags
          ? (result as List)
                .cast<Json>()
                .where(
                  (t) => t['active'] == true && (t['count'] as num? ?? 0) > 0,
                )
                .toList()
          : (result['items'] as List).cast<Json>();
      setState(() {
        items = more ? [...items, ...rows] : rows;
        page = next;
        total = tags ? rows.length : result['totalElements'] as int;
        loadedSearch = requestedSearch;
      });
    } catch (e) {
      if (mounted && ticket == generation) {
        setState(() {
          error = e.toString();
          failedMore = more;
        });
      }
    } finally {
      if (mounted && ticket == generation) setState(() => loading = false);
    }
  }

  void open(Json item) => Navigator.push(
    context,
    MaterialPageRoute<void>(
      builder: (_) => tags || artist
          ? SongsPage(
              state: widget.state,
              title: item['name'] as String,
              artistId: artist ? item['id'] as String : null,
              tags: tags ? [item['id'] as String] : [],
              browseItem: artist ? item : null,
            )
          : PlaylistPage(state: widget.state, id: item['id'] as String),
    ),
  );
  Widget tile(Json item) => DecoratedBox(
    decoration: BoxDecoration(
      border: Border(
        bottom: BorderSide(color: Theme.of(context).dividerColor, width: .5),
      ),
    ),
    child: Material(
      color: Colors.transparent,
      child: ListTile(
        minTileHeight: widget.state.senior ? 88 : 72,
        contentPadding: EdgeInsets.symmetric(
          vertical: widget.state.senior ? 12 : 4,
        ),
        leading: tags
            ? null
            : Artwork(
                state: widget.state,
                path: item[artist ? 'portraitPath' : 'coverPath'] as String?,
                label: item['name'] as String,
                round: artist,
                size: widget.state.senior ? 64 : 48,
                revision: item[artist ? 'portraitVersion' : 'revision']
                    ?.toString(),
              ),
        title: Text(
          item['name'] as String,
          style: Theme.of(context).textTheme.bodyMedium
              ?.copyWith(fontWeight: FontWeight.w500),
        ),
        subtitle: Text('${item['count'] ?? 0} 首歌曲'),
        trailing: const Icon(Icons.chevron_right_rounded, size: 20),
        onTap: () => open(item),
      ),
    ),
  );
  Widget gridTile(Json item) {
    if (tags) return tile(item);
    return LayoutBuilder(
      builder: (context, size) => Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: () => open(item),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Column(
              crossAxisAlignment: artist
                  ? CrossAxisAlignment.center
                  : CrossAxisAlignment.start,
              children: [
                Artwork(
                  state: widget.state,
                  path: item[artist ? 'portraitPath' : 'coverPath'] as String?,
                  round: artist,
                  size: artist ? math.min(112, size.maxWidth) : size.maxWidth,
                  revision: item[artist ? 'portraitVersion' : 'revision']
                      ?.toString(),
                ),
                const SizedBox(height: 12),
                Text(
                  item['name'] as String,
                  textAlign: artist ? TextAlign.center : TextAlign.start,
                  style: Theme.of(context).textTheme.bodyMedium
                      ?.copyWith(fontWeight: FontWeight.w500),
                ),
                const SizedBox(height: 4),
                Text(
                  '${item['count'] ?? 0} 首${artist ? '歌曲' : ' · 家庭共享'}',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final rows = <({String? heading, Json? item})>[];
    if (tags) {
      for (final dimension in {
        ...tagDimensions.keys,
        ...items.map((t) => t['dimension'] as String? ?? 'CUSTOM'),
      }) {
        final group = items.where(
          (t) => (t['dimension'] ?? 'CUSTOM') == dimension,
        );
        if (group.isEmpty) continue;
        rows.add((heading: tagDimensions[dimension] ?? dimension, item: null));
        rows.addAll(group.map((item) => (heading: null, item: item)));
      }
    } else {
      rows.addAll(items.map((item) => (heading: null, item: item)));
    }
    return Scaffold(
      appBar: AppBar(title: Text(widget.kind == '共享歌单' ? '家庭歌单' : widget.kind)),
      body: ContentWidth(
        child: RefreshIndicator(
          onRefresh: load,
          child: CustomScrollView(
            key: PageStorageKey(
              'browse:${widget.state.instanceId}:${widget.kind}',
            ),
            physics: const AlwaysScrollableScrollPhysics(),
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            slivers: [
              if (!tags)
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(20, 4, 20, 16),
                  sliver: SliverToBoxAdapter(
                    child: TextField(
                      controller: search,
                      textInputAction: TextInputAction.search,
                      decoration: InputDecoration(
                        hintText:
                            '搜索${widget.kind == '共享歌单' ? '家庭歌单' : widget.kind}',
                        prefixIcon: const Icon(Icons.search_rounded),
                        suffixIcon: IconButton(
                          tooltip: '搜索',
                          onPressed: load,
                          icon: const Icon(Icons.arrow_forward_rounded),
                        ),
                      ),
                      onSubmitted: (_) => load(),
                    ),
                  ),
                ),
              SliverPadding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                sliver: SliverLayoutBuilder(
                  builder: (context, constraints) {
                    final columns =
                        widget.state.senior ||
                            constraints.crossAxisExtent < 300 ||
                            MediaQuery.textScalerOf(context).scale(15) > 21
                        ? 1
                        : 2;
                    final display = <({String? heading, List<Json> items})>[];
                    for (var index = 0; index < rows.length;) {
                      final row = rows[index++];
                      if (row.heading != null) {
                        display.add((heading: row.heading, items: []));
                      } else {
                        final group = [row.item!];
                        if (columns == 2 &&
                            index < rows.length &&
                            rows[index].heading == null) {
                          group.add(rows[index++].item!);
                        }
                        display.add((heading: null, items: group));
                      }
                    }
                    return SliverList.builder(
                      itemCount: display.length,
                      itemBuilder: (context, index) {
                        final row = display[index];
                        if (row.heading != null) {
                          return Padding(
                            padding: const EdgeInsets.only(top: 20, bottom: 4),
                            child: SectionTitle(row.heading!),
                          );
                        }
                        if (columns == 1) return tile(row.items.single);
                        return Padding(
                          padding: EdgeInsets.only(bottom: tags ? 0 : 20),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Expanded(child: gridTile(row.items.first)),
                              const SizedBox(width: 16),
                              Expanded(
                                child: row.items.length == 2
                                    ? gridTile(row.items.last)
                                    : const SizedBox.shrink(),
                              ),
                            ],
                          ),
                        );
                      },
                    );
                  },
                ),
              ),
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
                sliver: SliverToBoxAdapter(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (error != null)
                        Notice(error!, action: () => load(more: failedMore)),
                      if (loading)
                        const Padding(
                          padding: EdgeInsets.all(24),
                          child: Center(child: CircularProgressIndicator()),
                        ),
                      if (!loading && error == null && items.isEmpty)
                        Notice(
                          search.text.isNotEmpty
                              ? '没有找到匹配内容，试试其他关键词。'
                              : tags
                              ? '还没有歌曲分类，请在 NAS 管理端整理曲库。'
                              : artist
                              ? '曲库还没有歌手。'
                              : '还没有家庭歌单，请在 NAS 管理端创建。',
                        ),
                      if (items.length < total && !loading && error == null)
                        TextButton(
                          onPressed: () => load(more: true),
                          child: const Text('加载更多'),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class PlaylistPage extends StatefulWidget {
  const PlaylistPage({super.key, required this.state, required this.id});
  final AppState state;
  final String id;
  @override
  State<PlaylistPage> createState() => _PlaylistPageState();
}

class _PlaylistPageState extends State<PlaylistPage> {
  late Future<dynamic> data = widget.state.get(
    '/api/v1/playlists/${widget.id}',
  );
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('家庭歌单')),
    body: FutureBuilder<dynamic>(
      future: data,
      builder: (context, result) {
        if (result.hasError) {
          return Notice(
            result.error.toString(),
            action: () => setState(
              () => data = widget.state.get('/api/v1/playlists/${widget.id}'),
            ),
          );
        }
        if (!result.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final item = result.data as Json;
        final songs = (item['songs'] as List).cast<Json>();
        return ContentWidth(
          child: CustomScrollView(
            key: PageStorageKey(
              'playlist:${widget.state.instanceId}:${widget.id}',
            ),
            slivers: [
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
                sliver: SliverToBoxAdapter(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Artwork(
                            state: widget.state,
                            path: item['coverPath'] as String?,
                            label: item['name'] as String,
                            revision: item['revision']?.toString(),
                            size: widget.state.senior ? 88 : 100,
                          ),
                          const SizedBox(width: 16),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  item['name'] as String,
                                  style: Theme.of(context)
                                      .textTheme
                                      .headlineSmall,
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  '${item['count'] ?? songs.length} 首 · 家庭共享',
                                  style: Theme.of(context).textTheme.bodySmall
                                      ?.copyWith(
                                        color: Theme.of(context)
                                            .colorScheme
                                            .onSurfaceVariant,
                                        fontFeatures: const [
                                          FontFeature.tabularFigures(),
                                        ],
                                      ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                      if ((item['description'] as String? ?? '').isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Text(
                            item['description'] as String,
                            style: Theme.of(context).textTheme.bodyMedium
                                ?.copyWith(
                                  color: Theme.of(context)
                                      .colorScheme
                                      .onSurfaceVariant,
                                ),
                          ),
                        ),
                      if ((item['missingCount'] as num? ?? 0) > 0)
                        Notice('${item['missingCount']} 首歌曲暂不可用，请在 NAS 管理端整理。'),
                      if (songs.isEmpty) const Notice('歌单还没有可播放的歌曲。'),
                      if (songs.isNotEmpty) const SectionTitle('歌单里的歌曲'),
                    ],
                  ),
                ),
              ),
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
                sliver: SliverList.builder(
                  itemCount: songs.length,
                  itemBuilder: (context, index) =>
                      SongRow(state: widget.state, song: Song(songs[index])),
                ),
              ),
            ],
          ),
        );
      },
    ),
  );
}

class SongDetail extends StatefulWidget {
  const SongDetail({super.key, required this.state, required this.song});
  final AppState state;
  final Song song;
  @override
  State<SongDetail> createState() => _SongDetailState();
}

class _SongDetailState extends State<SongDetail> {
  late Future<dynamic> detail = widget.state.get(
    '/api/v1/mobile/library/songs/${Uri.encodeComponent(widget.song.id)}',
  );
  @override
  Widget build(BuildContext context) => SingleChildScrollView(
    padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
    child: ListenableBuilder(
      listenable: widget.state,
      builder: (context, _) => FutureBuilder<dynamic>(
        future: detail,
        builder: (context, result) {
          final song = result.hasData ? Song(result.data as Json) : widget.song;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '歌曲信息',
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                  ),
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('关闭'),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Artwork(
                    state: widget.state,
                    path: song.cover,
                    label: song.title,
                    revision: song.data['revision']?.toString(),
                    size: 64,
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          song.title,
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          song.artist.isEmpty ? '歌手未知' : song.artist,
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
                ],
              ),
              const SizedBox(height: 24),
              Table(
                columnWidths: const {0: IntrinsicColumnWidth()},
                defaultVerticalAlignment: TableCellVerticalAlignment.top,
                children: [
                  for (final field in [
                    ('版本', song.version),
                    (
                      '格式',
                      '${song.mediaType == 'AUDIO' ? '音频' : '视频'} · ${song.format}',
                    ),
                    ('来源', song.source.isEmpty ? '家庭曲库' : song.source),
                    if (song.data['album'] != null)
                      ('专辑', '${song.data['album']}'),
                  ])
                    TableRow(
                      children: [
                        Padding(
                          padding: const EdgeInsets.fromLTRB(0, 0, 16, 12),
                          child: Text(
                            field.$1,
                            style: Theme.of(context).textTheme.bodyMedium
                                ?.copyWith(
                                  color: Theme.of(context)
                                      .colorScheme
                                      .onSurfaceVariant,
                                ),
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: Text(field.$2),
                        ),
                      ],
                    ),
                ],
              ),
              if (result.hasError)
                Notice(
                  '详细信息暂不可读。',
                  action: () => setState(
                    () => detail = widget.state.get(
                      '/api/v1/mobile/library/songs/${Uri.encodeComponent(song.id)}',
                    ),
                  ),
                ),
              const SizedBox(height: 16),
              OutlinedButton.icon(
                onPressed: () =>
                    uiTask(context, () => widget.state.favorite(song)),
                icon: Icon(
                  widget.state.isFavorite(song)
                      ? Icons.favorite_rounded
                      : Icons.favorite_border_rounded,
                ),
                label: Text(widget.state.isFavorite(song) ? '取消收藏' : '收藏歌曲'),
              ),
              const SizedBox(height: 12),
              Text(
                '练唱不开麦。主动开始录音后，声音只保存在此手机。',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          );
        },
      ),
    ),
  );
}
