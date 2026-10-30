import 'dart:typed_data';

import 'package:flutter/material.dart';

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
    _image = widget.path == null ? null : widget.state.artwork(widget.path!);
  }

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(widget.round ? widget.size : 14),
    child: SizedBox(
      width: widget.size,
      height: widget.size,
      child: FutureBuilder<Uint8List>(
        future: _image,
        builder: (context, snapshot) {
          if (snapshot.hasData) {
            return Image.memory(
              snapshot.data!,
              fit: BoxFit.cover,
              gaplessPlayback: true,
              errorBuilder: (_, _, _) => _placeholder(context),
            );
          }
          return _placeholder(context);
        },
      ),
    ),
  );
  Widget _placeholder(BuildContext context) {
    final palettes = [
      [const Color(0xFF416A60), const Color(0xFFA7CFB1)],
      [const Color(0xFFBE7C6D), const Color(0xFFF1D7B8)],
      [const Color(0xFF637DA1), const Color(0xFFCCDCE7)],
      [const Color(0xFF8B6D96), const Color(0xFFE4CBD9)],
    ];
    final colors =
        palettes[widget.label.codeUnits.fold<int>(0, (a, b) => a + b) %
            palettes.length];
    return CustomPaint(
      painter: _CoverPainter(colors),
      child: Center(
        child: Icon(
          widget.round ? Icons.person_outline_rounded : Icons.mic_none_rounded,
          color: Colors.white.withValues(alpha: .82),
          size: widget.size * .42,
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
    required this.onTap,
  });
  final AppState state;
  final Song song;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 4),
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(vertical: 6),
      leading: Artwork(
        state: state,
        path: song.cover,
        label: song.title,
        revision: song.data['revision']?.toString(),
      ),
      title: Text(
        song.title,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.titleMedium,
      ),
      subtitle: Text(
        song.description,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: FilledButton.tonalIcon(
        style: FilledButton.styleFrom(
          minimumSize: const Size(76, 40),
          padding: const EdgeInsets.symmetric(horizontal: 10),
          backgroundColor: const Color(0xFFE8F5EF),
          foregroundColor: const Color(0xFF25745B),
        ),
        onPressed: () => openPreparation(context, state, song),
        icon: const Icon(Icons.mic_none_rounded, size: 17),
        label: const Text('演唱'),
      ),
      onTap: onTap,
    ),
  );
}

class _CoverPainter extends CustomPainter {
  _CoverPainter(this.colors);
  final List<Color> colors;
  @override
  void paint(Canvas c, Size s) {
    final rect = Offset.zero & s;
    c.drawRect(
      rect,
      Paint()
        ..shader = LinearGradient(
          colors: colors,
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ).createShader(rect),
    );
    c.drawCircle(
      Offset(s.width * .85, s.height * .1),
      s.width * .62,
      Paint()..color = Colors.white.withValues(alpha: .13),
    );
    c.drawCircle(
      Offset(s.width * .05, s.height),
      s.width * .58,
      Paint()..color = Colors.white.withValues(alpha: .12),
    );
    c.drawLine(
      Offset(0, s.height * .8),
      Offset(s.width, s.height * .3),
      Paint()
        ..color = Colors.white.withValues(alpha: .15)
        ..strokeWidth = s.width * .16,
    );
  }

  @override
  bool shouldRepaint(_CoverPainter old) => old.colors != colors;
}
