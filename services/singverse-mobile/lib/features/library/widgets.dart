import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../app/design.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import '../singing/actions.dart';

class Artwork extends StatefulWidget {
  const Artwork({
    super.key,
    required this.state,
    this.path,
    this.size = 56,
    this.round = false,
    this.revision,
    this.label = '',
  });
  final AppState state;
  final String? path, revision;
  final String label;
  final double size;
  final bool round;
  @override
  State<Artwork> createState() => _ArtworkState();
}

class _ArtworkState extends State<Artwork> {
  Future<Uint8List>? _image;
  String identity = '';
  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(Artwork old) {
    super.didUpdateWidget(old);
    if (old.path != widget.path ||
        old.revision != widget.revision ||
        identity !=
            '${widget.state.instanceId}:${widget.state.authenticated}') {
      _load();
    }
  }

  void _load() {
    identity = '${widget.state.instanceId}:${widget.state.authenticated}';
    _image = widget.path?.trim().isNotEmpty == true
        ? widget.state.artwork(widget.path!)
        : null;
  }

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(widget.round ? widget.size : 11),
    child: SizedBox(
      width: widget.size,
      height: widget.size,
      child: FutureBuilder<Uint8List>(
        key: ValueKey('$identity:${widget.path}:${widget.revision}'),
        future: _image,
        builder: (context, snapshot) {
          if (snapshot.hasData) {
            return Image.memory(
              snapshot.data!,
              fit: BoxFit.cover,
              excludeFromSemantics: true,
              errorBuilder: (_, _, _) => _placeholder(context),
            );
          }
          return _placeholder(context);
        },
      ),
    ),
  );

  Widget _placeholder(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ExcludeSemantics(
      child: ColoredBox(
        color: scheme.secondaryContainer,
        child: Center(
          child: Icon(
            widget.round
                ? Icons.person_outline_rounded
                : Icons.music_note_rounded,
            color: scheme.onSurfaceVariant.withValues(alpha: .55),
            size: widget.size * .42,
          ),
        ),
      ),
    );
  }
}

class SongRow extends StatelessWidget {
  const SongRow({
    super.key,
    required this.state,
    required this.song,
    this.onTap,
  });
  final AppState state;
  final Song song;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) => buildRow(context),
  );

  Widget buildRow(BuildContext context) {
    void open() {
      if (onTap != null) {
        onTap!();
      } else {
        openSinging(context, state, song);
      }
    }

    final favorite = IconButton(
      tooltip: state.isFavorite(song)
          ? '取消收藏 ${song.title}'
          : '收藏 ${song.title}',
      onPressed: () => uiTask(context, () => state.favorite(song)),
      icon: Icon(
        state.isFavorite(song)
            ? Icons.favorite_rounded
            : Icons.favorite_border_rounded,
        color: state.isFavorite(song)
            ? Theme.of(context).colorScheme.primary
            : Theme.of(context).colorScheme.onSurfaceVariant,
        size: 19,
      ),
    );
    if (state.senior) {
      return DecoratedBox(
        decoration: BoxDecoration(
          border: Border(
            bottom: BorderSide(
              color: Theme.of(context).dividerColor,
              width: .5,
            ),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Artwork(
                    state: state,
                    path: song.cover,
                    label: song.title,
                    revision: song.data['revision']?.toString(),
                    size: 64,
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          song.title,
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 4),
                        Text(song.artist.isEmpty ? '歌手未知' : song.artist),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 14),
              LayoutBuilder(
                builder: (context, constraints) {
                  final favoriteButton = OutlinedButton.icon(
                    onPressed: () =>
                        uiTask(context, () => state.favorite(song)),
                    icon: Icon(
                      state.isFavorite(song)
                          ? Icons.favorite_rounded
                          : Icons.favorite_border_rounded,
                    ),
                    label: Text(state.isFavorite(song) ? '已收藏' : '收藏'),
                  );
                  final startButton = FilledButton.icon(
                    onPressed: open,
                    icon: const Icon(Icons.play_arrow_rounded),
                    label: const Text('开始练唱'),
                  );
                  if (constraints.maxWidth < 320 ||
                      MediaQuery.textScalerOf(context).scale(19) > 24) {
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        startButton,
                        const SizedBox(height: 8),
                        favoriteButton,
                      ],
                    );
                  }
                  return Row(
                    children: [
                      Expanded(child: favoriteButton),
                      const SizedBox(width: 12),
                      Expanded(child: startButton),
                    ],
                  );
                },
              ),
            ],
          ),
        ),
      );
    }
    final enlarged = MediaQuery.textScalerOf(context).scale(15) > 19;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: open,
        child: Container(
          constraints: const BoxConstraints(minHeight: 64),
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(
                color: Theme.of(context).colorScheme.outlineVariant,
                width: .6,
              ),
            ),
          ),
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            children: [
              Artwork(
                state: state,
                path: song.cover,
                label: song.title,
                revision: song.data['revision']?.toString(),
                size: 44,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      song.title,
                      maxLines: enlarged ? null : 2,
                      overflow: enlarged ? null : TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        fontSize: 15,
                        fontWeight: FontWeight.w500,
                        height: 1.35,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      [
                        song.artist.isEmpty ? '歌手未知' : song.artist,
                        if (song.data['version'] != null &&
                            song.data['version'] != 'UNKNOWN')
                          song.version,
                      ].join(' · '),
                      maxLines: enlarged ? null : 1,
                      overflow: enlarged ? null : TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              favorite,
              IconButton(
                tooltip: '开始练唱 ${song.title}',
                onPressed: open,
                icon: Icon(
                  Icons.play_arrow_rounded,
                  size: 19,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
