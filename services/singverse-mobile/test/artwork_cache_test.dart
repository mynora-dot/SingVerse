import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:singverse_mobile/core/artwork_cache.dart';

void main() {
  test('deduplicates requests and evicts least recently used bytes', () async {
    final cache = ArtworkCache(maxBytes: 4);
    final gate = Completer<Uint8List>();
    var reads = 0;
    Future<Uint8List> read() {
      reads++;
      return gate.future;
    }

    final first = cache.load('nas:a:1', read);
    final duplicate = cache.load('nas:a:1', read);
    expect(reads, 1);
    gate.complete(Uint8List.fromList([1, 2]));
    expect(await first, await duplicate);
    await cache.load('nas:b:1', () async => Uint8List.fromList([3, 4]));
    await cache.load('nas:a:1', read);
    await cache.load('nas:c:1', () async => Uint8List.fromList([5, 6]));
    await cache.load('nas:b:1', () async {
      reads++;
      return Uint8List(2);
    });
    expect(reads, 2);
  });

  test('clearing a NAS cannot cache an old in-flight response', () async {
    final cache = ArtworkCache();
    final old = Completer<Uint8List>();
    final pending = cache.load('same-path', () => old.future);
    cache.clear();
    final current = Uint8List.fromList([2]);
    await cache.load('same-path', () async => current);
    old.complete(Uint8List.fromList([1]));
    await pending;
    expect(
      await cache.load('same-path', () => throw StateError('cache miss')),
      current,
    );
  });

  test(
    'failures may be retried and different revisions do not share work',
    () async {
      final cache = ArtworkCache();
      await expectLater(
        cache.load('nas:path:1', () => Future.error(StateError('offline'))),
        throwsStateError,
      );
      expect(
        await cache.load('nas:path:1', () async => Uint8List.fromList([1])),
        [1],
      );
      expect(
        await cache.load('nas:path:2', () async => Uint8List.fromList([2])),
        [2],
      );
    },
  );
}
