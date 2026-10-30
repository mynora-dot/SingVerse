import 'dart:async';

import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import '../singing/actions.dart';
import 'widgets.dart';

void showSong(BuildContext context, AppState state, Song song) =>
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => SongDetail(state: state, song: song),
      ),
    );

class SongsPage extends StatefulWidget {
  const SongsPage({
    super.key,
    required this.state,
    this.title = '歌曲',
    this.artistId,
    this.tags = const [],
  });
  final AppState state;
  final String title;
  final String? artistId;
  final List<String> tags;
  @override
  State<SongsPage> createState() => _SongsPageState();
}

class _SongsPageState extends State<SongsPage> {
  late final query = SongQuery(
    widget.state,
    artistId: widget.artistId,
    tags: widget.tags,
  );
  Timer? debounce;
  @override
  void initState() {
    super.initState();
    unawaited(query.load());
  }

  @override
  void dispose() {
    debounce?.cancel();
    query.dispose();
    super.dispose();
  }

  Future<void> filters() async {
    await uiTask(context, () async {
      final tags = (await widget.state.get(
        '/api/v1/classification/tags',
      ) as List).cast<Json>().where((t) => t['active'] == true).toList();
      final selected = {...query.tagIds};
      if (!mounted) return;
      final result = await showModalBottomSheet<List<String>>(
        context: context,
        isScrollControlled: true,
        builder: (context) => StatefulBuilder(
          builder: (context, update) => SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: SizedBox(
                height: MediaQuery.sizeOf(context).height * .65,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      '按分类筛选',
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 12),
                    const Text('同维度任选，跨维度同时满足'),
                    const SizedBox(height: 16),
                    Expanded(
                      child: SingleChildScrollView(
                        child: Wrap(
                          spacing: 8,
                          children: tags
                              .map(
                                (tag) => FilterChip(
                                  label: Text(tag['name'] as String),
                                  selected: selected.contains(tag['id']),
                                  onSelected: (value) => update(() {
                                    value
                                        ? selected.add(tag['id'] as String)
                                        : selected.remove(tag['id']);
                                  }),
                                ),
                              )
                              .toList(),
                        ),
                      ),
                    ),
                    Row(
                      children: [
                        TextButton(
                          onPressed: () => update(selected.clear),
                          child: const Text('清空'),
                        ),
                        const Spacer(),
                        FilledButton(
                          onPressed: () =>
                              Navigator.pop(context, selected.toList()),
                          child: const Text('应用筛选'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      if (result != null) {
        query.tagIds = result;
        await query.load();
      }
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.title),
      actions: [
        IconButton(
          tooltip: '分类筛选',
          onPressed: filters,
          icon: const Icon(Icons.tune_rounded),
        ),
      ],
    ),
    body: ListenableBuilder(
      listenable: query,
      builder: (context, _) => RefreshIndicator(
        onRefresh: () => query.load(),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 100),
          children: [
            TextField(
              decoration: const InputDecoration(
                hintText: '歌名、歌手、专辑或拼音',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: (text) {
                query.query = text;
                debounce?.cancel();
                debounce = Timer(
                  const Duration(milliseconds: 350),
                  () => query.load(),
                );
              },
            ),
            const SizedBox(height: 16),
            Text(
              '${query.total} 个歌曲条目${query.tagIds.isEmpty ? '' : ' · 已筛选 ${query.tagIds.length} 项'}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            ...query.items.map(
              (song) => SongRow(
                state: widget.state,
                song: song,
                onTap: () => showSong(context, widget.state, song),
              ),
            ),
            if (query.error != null)
              Notice(query.error!, action: () => query.load()),
            if (query.loading)
              const Center(
                child: Padding(
                  padding: EdgeInsets.all(24),
                  child: CircularProgressIndicator(),
                ),
              ),
            if (!query.loading && query.error == null && query.items.isEmpty)
              const Notice('没有找到歌曲，试试其他关键词或分类。'),
            if (query.hasMore && !query.loading)
              TextButton(
                onPressed: () => query.load(more: true),
                child: const Text('加载更多'),
              ),
          ],
        ),
      ),
    ),
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
    final ticket = ++generation, next = more ? page + 1 : 0;
    setState(() {
      loading = true;
      error = null;
      if (!more) items = [];
    });
    try {
      final path = tags
          ? '/api/v1/classification/tags'
          : '/api/v1/${artist ? 'artists' : 'playlists'}?${Uri(queryParameters: {'q': search.text, 'page': '$next', 'size': '20'}).query}';
      final result = await widget.state.get(path);
      if (!mounted || ticket != generation) return;
      final rows = tags
          ? (result as List)
                .cast<Json>()
                .where((t) => t['active'] == true)
                .toList()
          : (result['items'] as List).cast<Json>();
      setState(() {
        items = more ? [...items, ...rows] : rows;
        page = next;
        total = tags ? rows.length : result['totalElements'] as int;
      });
    } catch (e) {
      if (mounted && ticket == generation) setState(() => error = e.toString());
    } finally {
      if (mounted && ticket == generation) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(widget.kind)),
    body: RefreshIndicator(
      onRefresh: () => load(),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 100),
        children: [
          if (!tags)
            TextField(
              controller: search,
              decoration: InputDecoration(
                hintText: '搜索${widget.kind}',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: IconButton(
                  tooltip: '搜索',
                  onPressed: () => load(),
                  icon: const Icon(Icons.arrow_forward),
                ),
              ),
              onSubmitted: (_) => load(),
            ),
          const SizedBox(height: 16),
          ...items.map(
            (item) => Card(
              child: ListTile(
                contentPadding: const EdgeInsets.all(12),
                leading: Artwork(
                  state: widget.state,
                  path:
                      (item[artist ? 'portraitPath' : 'coverPath']) as String?,
                  round: artist,
                  revision: item['revision']?.toString(),
                ),
                title: Text(item['name'] as String),
                subtitle: Text(
                  '${item['count'] ?? 0} 个歌曲条目${!tags && !artist ? ' · 家庭共享，只读' : ''}',
                ),
                trailing: const Icon(Icons.chevron_right),
                onTap: () {
                  if (tags || artist) {
                    Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => SongsPage(
                          state: widget.state,
                          title: item['name'] as String,
                          artistId: artist ? item['id'] as String : null,
                          tags: tags ? [item['id'] as String] : [],
                        ),
                      ),
                    );
                  } else {
                    Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => PlaylistPage(
                          state: widget.state,
                          id: item['id'] as String,
                        ),
                      ),
                    );
                  }
                },
              ),
            ),
          ),
          if (error != null) Notice(error!, action: () => load()),
          if (loading) const Center(child: CircularProgressIndicator()),
          if (!loading && error == null && items.isEmpty)
            Notice('还没有${widget.kind}。'),
          if (items.length < total && !loading)
            TextButton(
              onPressed: () => load(more: true),
              child: const Text('加载更多'),
            ),
        ],
      ),
    ),
  );
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
    appBar: AppBar(title: const Text('共享歌单')),
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
        return ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 100),
          children: [
            Center(
              child: Artwork(
                state: widget.state,
                path: item['coverPath'] as String?,
                size: 180,
              ),
            ),
            const SizedBox(height: 24),
            Text(
              item['name'] as String,
              style: Theme.of(context).textTheme.headlineLarge,
            ),
            Text(item['description'] as String? ?? ''),
            const SizedBox(height: 12),
            Text('${item['count']} 个歌曲条目 · 家庭共享，只读'),
            if ((item['missingCount'] as num? ?? 0) > 0)
              Notice('${item['missingCount']} 项歌曲已失效，请在 NAS 管理端整理。'),
            const SizedBox(height: 16),
            ...(item['songs'] as List).map(
              (s) => SongRow(
                state: widget.state,
                song: Song(s as Json),
                onTap: () => showSong(context, widget.state, Song(s)),
              ),
            ),
          ],
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
  late Future<dynamic> data = widget.state.get(
    '/api/v1/mobile/library/songs/${widget.song.id}',
  );
  bool preparing = false;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('歌曲详情')),
    body: FutureBuilder<dynamic>(
      future: data,
      builder: (context, result) {
        if (result.hasError) {
          return Notice(
            result.error.toString(),
            action: () => setState(
              () => data = widget.state.get(
                '/api/v1/mobile/library/songs/${widget.song.id}',
              ),
            ),
          );
        }
        if (!result.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final song = Song(result.data as Json);
        return ListenableBuilder(
          listenable: widget.state,
          builder: (context, _) => ListView(
            padding: const EdgeInsets.all(20),
            children: [
              Center(
                child: Artwork(
                  state: widget.state,
                  path: song.cover,
                  revision: song.data['revision']?.toString(),
                  size: 220,
                  label: song.title,
                ),
              ),
              const SizedBox(height: 28),
              Text(
                song.title,
                style: Theme.of(context).textTheme.headlineLarge,
              ),
              const SizedBox(height: 8),
              Text(
                song.artist.isEmpty ? '歌手未知' : song.artist,
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 8,
                children: [
                  Chip(label: Text(song.version)),
                  if (song.format.isNotEmpty)
                    Chip(
                      label: Text(
                        '${song.mediaType == 'AUDIO' ? '音频' : '视频'} · ${song.format}',
                      ),
                    ),
                ],
              ),
              const SizedBox(height: 12),
              Text('来源：${song.source}'),
              if (song.data['album'] != null) Text('专辑：${song.data['album']}'),
              const SizedBox(height: 12),
              const Text('先试听伴奏，再选择练唱或录唱。你的声音只保存在此手机。'),
              const SizedBox(height: 28),
              FilledButton.icon(
                onPressed: preparing || !widget.state.authenticated
                    ? null
                    : () => uiTask(context, () async {
                        setState(() => preparing = true);
                        try {
                          await openPreparation(context, widget.state, song);
                        } finally {
                          if (mounted) setState(() => preparing = false);
                        }
                      }),
                icon: const Icon(Icons.mic_none_rounded),
                label: Text(preparing ? '正在打开…' : '开始演唱'),
              ),
              const SizedBox(height: 8),
              TextButton.icon(
                onPressed: () =>
                    uiTask(context, () => widget.state.favorite(song)),
                icon: Icon(
                  widget.state.isFavorite(song)
                      ? Icons.favorite_rounded
                      : Icons.favorite_border_rounded,
                ),
                label: Text(
                  widget.state.isFavorite(song) ? '已收藏 · 保存在此手机' : '收藏到此手机',
                ),
              ),
              const SizedBox(height: 80),
            ],
          ),
        );
      },
    ),
  );
}
