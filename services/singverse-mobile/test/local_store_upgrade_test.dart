import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  test(
    'v1 upgrade adds query indexes without changing or trimming history',
    () async {
      final directory = await Directory.systemTemp.createTemp(
        'singverse-index-test-',
      );
      final path = '${directory.path}/personal.db';
      try {
        final old = await databaseFactoryFfiNoIsolate.openDatabase(
          path,
          options: OpenDatabaseOptions(version: 1),
        );
        await old.execute(
          'CREATE TABLE personal_song (instance_id TEXT NOT NULL, song_id TEXT NOT NULL, snapshot TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played_at INTEGER, position_ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance_id,song_id))',
        );
        for (var i = 0; i < 150; i++) {
          await old.insert('personal_song', {
            'instance_id': 'nas',
            'song_id': '$i',
            'snapshot': '{"id":"$i","title":"song $i"}',
            'favorite': i == 0 ? 1 : 0,
            'played_at': i,
            'position_ms': i * 1000,
          });
        }
        await old.close();
        final store = await LocalStore.open(
          factory: databaseFactoryFfiNoIsolate,
          path: path,
        );
        try {
          expect(
            (await store.db.rawQuery('SELECT COUNT(*) AS n FROM personal_song'))
                .single['n'],
            150,
          );
          expect((await store.favorites('nas')).single.id, '0');
          expect((await store.history('nas')).first.data['positionMs'], 149000);
          expect(
            (await store.db.rawQuery(
              'SELECT position_ms FROM personal_song WHERE song_id = ?',
              ['0'],
            )).single['position_ms'],
            0,
          );
          final plan = await store.db.rawQuery(
            'EXPLAIN QUERY PLAN SELECT * FROM personal_song WHERE instance_id = ? AND played_at IS NOT NULL ORDER BY played_at DESC LIMIT 100',
            ['nas'],
          );
          expect(plan.toString(), contains('personal_song_history'));
          expect(plan.toString(), isNot(contains('TEMP B-TREE')));
        } finally {
          await store.close();
        }
      } finally {
        await directory.delete(recursive: true);
      }
    },
  );
}
