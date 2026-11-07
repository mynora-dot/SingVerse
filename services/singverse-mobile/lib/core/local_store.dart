import 'dart:convert';

import 'package:sqflite/sqflite.dart';

import 'models.dart';

class LocalStore {
  LocalStore(this.db);
  final Database db;
  static Future<LocalStore> open({
    DatabaseFactory? factory,
    String? path,
  }) async {
    final driver = factory ?? databaseFactory;
    final location =
        path ?? '${await driver.getDatabasesPath()}/singverse-personal.db';
    return LocalStore(
      await driver.openDatabase(
        location,
        options: OpenDatabaseOptions(
          version: 2,
          onUpgrade: (db, oldVersion, _) async {
            if (oldVersion < 2) await _createIndexes(db);
          },
          onCreate: (db, _) async {
            await db.execute(
              'CREATE TABLE personal_song (instance_id TEXT NOT NULL, song_id TEXT NOT NULL, snapshot TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played_at INTEGER, position_ms INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance_id,song_id))',
            );
            await _createIndexes(db);
          },
        ),
      ),
    );
  }

  static Future<void> _createIndexes(DatabaseExecutor db) async {
    await db.execute(
      'CREATE INDEX IF NOT EXISTS personal_song_history ON personal_song(instance_id, played_at DESC)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS personal_song_favorites ON personal_song(instance_id, favorite, song_id)',
    );
  }

  Future<List<Song>> favorites(String server) async =>
      (await db.query(
            'personal_song',
            where: 'instance_id = ? AND favorite = 1',
            whereArgs: [server],
            orderBy: 'song_id',
          ))
          .map((row) => Song(jsonDecode(row['snapshot']! as String) as Json))
          .toList();
  Future<List<Song>> history(String server) async =>
      (await db.query(
            'personal_song',
            where: 'instance_id = ? AND played_at IS NOT NULL',
            whereArgs: [server],
            orderBy: 'played_at DESC',
            limit: 100,
          ))
          .map(
            (row) => Song({
              ...jsonDecode(row['snapshot']! as String) as Json,
              'positionMs': row['position_ms'],
            }),
          )
          .toList();
  Future<void> favorite(String server, Song song, bool selected) async {
    await db.transaction((tx) async {
      await tx.rawInsert(
        'INSERT OR IGNORE INTO personal_song(instance_id,song_id,snapshot) VALUES(?,?,?)',
        [server, song.id, jsonEncode(song.data)],
      );
      await tx.update(
        'personal_song',
        {'snapshot': jsonEncode(song.data), 'favorite': selected ? 1 : 0},
        where: 'instance_id = ? AND song_id = ?',
        whereArgs: [server, song.id],
      );
    });
  }

  Future<void> played(
    String server,
    Song song,
    int position, {
    bool newPlay = false,
  }) async {
    await db.transaction((tx) async {
      await tx.rawInsert(
        'INSERT OR IGNORE INTO personal_song(instance_id,song_id,snapshot) VALUES(?,?,?)',
        [server, song.id, jsonEncode(song.data)],
      );
      await tx.update(
        'personal_song',
        {
          'snapshot': jsonEncode(song.data),
          'position_ms': position,
          if (newPlay) 'played_at': DateTime.now().millisecondsSinceEpoch,
        },
        where: 'instance_id = ? AND song_id = ?',
        whereArgs: [server, song.id],
      );
    });
  }

  Future<void> clearHistory(String server) => db.update(
    'personal_song',
    {'played_at': null, 'position_ms': 0},
    where: 'instance_id = ?',
    whereArgs: [server],
  );
  Future<void> close() => db.close();
}
