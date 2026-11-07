import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:singverse_mobile/core/app_state.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:singverse_mobile/core/models.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'widget_test.dart' show FakeApi;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  test(
    'progress updates notify performance views without rebuilding global state',
    () async {
      SharedPreferences.setMockInitialValues({});
      final store = await LocalStore.open(
        factory: databaseFactoryFfiNoIsolate,
        path: inMemoryDatabasePath,
      );
      final state = AppState(
        api: FakeApi(),
        store: store,
        preferences: await SharedPreferences.getInstance(),
        listenPlayer: false,
      );
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      var position = 100;
      final reusedAttempts = <bool>[];
      messenger.setMockMethodCallHandler(AppState.player, (call) async {
        if (call.method == 'open') {
          reusedAttempts.add((call.arguments as Map)['reuseAttempt'] == true);
        }
        return call.method == 'snapshot'
            ? {
                'song': jsonEncode({'id': '1', 'title': '测试歌曲'}),
                'state': 'PAUSED',
                'instanceId': 'nas-a',
                'positionMs': position,
                'durationMs': 10000,
              }
            : null;
      });
      try {
        await state.play(Song({'id': '1'}));
        var globals = 0, progress = 0;
        state.addListener(() => globals++);
        state.playback.addListener(() => progress++);
        position = 200;
        await state.play(Song({'id': '1'}));
        expect(globals, 0);
        expect(progress, 1);
        expect(state.positionMs, 200);
        await state.play(Song({'id': '1'}));
        expect(progress, 1);
        await state.play(Song({'id': '1'}), reuseAttempt: true);
        expect(reusedAttempts, [false, false, false, true]);
      } finally {
        messenger.setMockMethodCallHandler(AppState.player, null);
        state.dispose();
        await store.close();
      }
    },
  );
}
