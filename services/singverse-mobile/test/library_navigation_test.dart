import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:singverse_mobile/app/design.dart';
import 'package:singverse_mobile/core/app_state.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:singverse_mobile/core/models.dart';
import 'package:singverse_mobile/features/library/connection.dart';
import 'package:singverse_mobile/features/library/library_pages.dart';
import 'package:singverse_mobile/features/library/widgets.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'widget_test.dart' show FakeApi;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  late LocalStore store;
  late AppState state;
  late FakeApi api;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    store = await LocalStore.open(
      factory: databaseFactoryFfiNoIsolate,
      path: inMemoryDatabasePath,
    );
    api = FakeApi();
    state =
        AppState(
            api: api,
            store: store,
            preferences: await SharedPreferences.getInstance(),
            listenPlayer: false,
          )
          ..instanceId = 'nas-a'
          ..origin = 'https://nas.example.test'
          ..authenticated = true;
  });
  tearDown(() async {
    state.dispose();
    await store.close();
  });

  testWidgets(
    'favorite updates on a secondary song page without rebuilding its route',
    (tester) async {
      final song = Song({
        'id': 'one',
        'title': '真实曲库歌曲',
        'artists': ['歌手'],
      });
      await tester.pumpWidget(
        MaterialApp(
          theme: singVerseTheme(Brightness.light),
          home: Scaffold(
            body: SongRow(state: state, song: song),
          ),
        ),
      );
      await tester.tap(find.byTooltip('收藏 真实曲库歌曲'));
      await tester.pumpAndSettle();
      expect(find.byTooltip('取消收藏 真实曲库歌曲'), findsOneWidget);
      expect((await store.favorites('nas-a')).single.id, 'one');
      expect(await store.favorites('nas-b'), isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'catalog keeps search and browse before songs without repeating recent history',
    (tester) async {
      api.rows = [
        {
          'id': 'one',
          'title': '只出现一次的歌曲',
          'artists': ['歌手'],
        },
      ];
      state.history = [Song(api.rows.single)];
      await tester.binding.setSurfaceSize(const Size(390, 844));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          theme: singVerseTheme(Brightness.light),
          home: Scaffold(
            body: SongsPage(state: state, embedded: true, showBrowse: true),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('只出现一次的歌曲'), findsOneWidget);
      expect(find.text('最近练唱'), findsNothing);
      expect(find.text('家庭歌单'), findsOneWidget);
      expect(find.text('1 首歌，随时开唱'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('artist details use the selected NAS profile and artist id', (
    tester,
  ) async {
    api.browseRows = [
      {
        'id': 'artist-42',
        'name': '曲库歌手',
        'biography': '这是一段来自曲库的歌手简介。',
        'count': 2,
      },
    ];
    api.rows = [
      {
        'id': 'song-42',
        'title': '歌手的歌曲',
        'artists': ['曲库歌手'],
      },
    ];
    await tester.binding.setSurfaceSize(const Size(390, 844));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(
        theme: singVerseTheme(Brightness.dark),
        home: BrowsePage(state: state, kind: '歌手'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('曲库歌手'));
    await tester.pumpAndSettle();
    expect(find.text('这是一段来自曲库的歌手简介。'), findsOneWidget);
    expect(find.text('歌手的歌曲'), findsOneWidget);
    expect(
      api.requests.any(
        (path) => Uri.parse(path).queryParameters['artistId'] == 'artist-42',
      ),
      isTrue,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'connected account stays readable at large text and locks editing during mixing',
    (tester) async {
      state.accountName = '家庭管理员';
      await tester.binding.setSurfaceSize(const Size(320, 568));
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      addTearDown(() => tester.binding.setSurfaceSize(null));
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await tester.pumpWidget(
        MaterialApp(
          theme: singVerseTheme(Brightness.light, senior: true),
          home: ConnectionPage(state: state),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('已登录家庭曲库'), findsOneWidget);
      expect(find.text('家庭管理员'), findsOneWidget);
      expect(find.byType(TextField), findsNothing);
      state.singing = {'phase': 'RENDERING'};
      state.notifyListeners();
      await tester.pumpAndSettle();
      final edit = find.widgetWithText(ListTile, '修改 NAS 地址');
      await tester.scrollUntilVisible(
        edit,
        240,
        scrollable: find.byType(Scrollable).first,
      );
      expect(tester.widget<ListTile>(edit).onTap, isNull);
      expect(tester.takeException(), isNull);
    },
  );
}
