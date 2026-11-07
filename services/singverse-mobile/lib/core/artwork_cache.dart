import 'dart:typed_data';

/// Device-local byte cache. Only identical NAS, resource and revision share work.
class ArtworkCache {
  ArtworkCache({this.maxBytes = 16 * 1024 * 1024});
  final int maxBytes;
  final _images = <String, Uint8List>{};
  final _pending = <String, Future<Uint8List>>{};
  int _bytes = 0, _generation = 0;

  Future<Uint8List> load(String key, Future<Uint8List> Function() read) {
    final cached = _images.remove(key);
    if (cached != null) {
      _images[key] = cached;
      return Future.value(cached);
    }
    return _pending.putIfAbsent(key, () {
      final generation = _generation;
      late final Future<Uint8List> request;
      request = Future.sync(read)
          .then((bytes) {
            if (generation == _generation && bytes.length <= maxBytes) {
              while (_bytes + bytes.length > maxBytes && _images.isNotEmpty) {
                _bytes -= _images.remove(_images.keys.first)!.length;
              }
              _images[key] = bytes;
              _bytes += bytes.length;
            }
            return bytes;
          })
          .whenComplete(() {
            if (identical(_pending[key], request)) _pending.remove(key);
          });
      return request;
    });
  }

  void clear() {
    _generation++;
    _images.clear();
    _pending.clear();
    _bytes = 0;
  }
}
