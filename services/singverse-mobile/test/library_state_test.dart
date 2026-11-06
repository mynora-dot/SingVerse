import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:singverse_mobile/app/design.dart';
import 'package:singverse_mobile/core/api.dart';
import 'package:singverse_mobile/core/app_state.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:singverse_mobile/core/models.dart';
import 'package:singverse_mobile/features/library/library_pages.dart';
import 'package:singverse_mobile/features/library/widgets.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'widget_test.dart' show FakeApi;

class _DelayedPersonalStore extends LocalStore {
  _DelayedPersonalStore(super.db);

  final nasAGate = Completer<void>();
  final historyInstances = <String>[];

  @override
  Future<List<Song>> favorites(String server) async {
    final rows = await super.favorites(server);
    if (server == 'nas-a') await nasAGate.future;
    return rows;
  }

  @override
  Future<List<Song>> history(String server) {
    historyInstances.add(server);
    return super.history(server);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  late LocalStore store;
  late AppState state;
  late FakeApi api;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    store = await LocalStore.open(
      factory: databaseFactoryFfiNoIsolate,
      path: inMemoryDatabasePath,
    );
    api = FakeApi();
    state = AppState(
      api: api,
      store: store,
      preferences: await SharedPreferences.getInstance(),
      listenPlayer: false,
    )..instanceId = 'nas-a';
  });

  tearDown(() async {
    messenger.setMockMethodCallHandler(AppState.karaoke, null);
    state.dispose();
    await store.close();
  });

  test(
    'late NAS A personal refresh cannot replace NAS B same-song snapshots',
    () async {
      final songA = Song({'id': 'same-id', 'title': 'NAS A 歌曲'});
      final songB = Song({'id': 'same-id', 'title': 'NAS B 歌曲'});
      await store.favorite('nas-a', songA, true);
      await store.played('nas-a', songA, 12000, newPlay: true);
      await store.favorite('nas-b', songB, true);
      await store.played('nas-b', songB, 34000, newPlay: true);
      final delayedStore = _DelayedPersonalStore(store.db);
      final racingState = AppState(
        api: api,
        store: delayedStore,
        preferences: state.preferences,
        listenPlayer: false,
      )..instanceId = 'nas-a';
      addTearDown(racingState.dispose);
      final published = <(String, String)>[];
      racingState.addListener(
        () => published.add((
          racingState.favorites.single.title,
          racingState.history.single.title,
        )),
      );

      final lateA = racingState.refreshPersonal();
      racingState.instanceId = 'nas-b';
      await racingState.refreshPersonal();
      expect(racingState.favorites.single.title, 'NAS B 歌曲');
      expect(racingState.history.single.title, 'NAS B 歌曲');
      expect(racingState.history.single.data['positionMs'], 34000);

      delayedStore.nasAGate.complete();
      await lateA;
      expect(racingState.favorites.single.title, 'NAS B 歌曲');
      expect(racingState.history.single.title, 'NAS B 歌曲');
      expect(racingState.history.single.data['positionMs'], 34000);
      expect(delayedStore.historyInstances, ['nas-b', 'nas-a']);
      expect(published, [('NAS B 歌曲', 'NAS B 歌曲')]);
      expect((await store.favorites('nas-a')).single.title, 'NAS A 歌曲');
      expect((await store.history('nas-a')).single.data['positionMs'], 12000);
    },
  );

  test(
    'failed song pagination retries the same page and retains loaded rows',
    () async {
      api.deferred = true;
      final query = SongQuery(state);
      addTearDown(query.dispose);
      final first = query.load();
      api.pending[0].complete({
        'items': [
          {'id': 'first', 'title': '第一页'},
        ],
        'totalElements': 2,
      });
      await first;
      final next = query.load(more: true);
      api.pending[1].completeError(ApiFailure(503, '连接暂时中断'));
      await next;
      expect(query.items.single.id, 'first');
      expect(query.error, '连接暂时中断');
      final retry = query.retry();
      expect(query.items.single.id, 'first');
      api.pending[2].complete({
        'items': [
          {'id': 'second', 'title': '第二页'},
        ],
        'totalElements': 2,
      });
      await retry;
      expect(query.items.map((song) => song.id), ['first', 'second']);
      expect(query.error, isNull);
      expect(query.hasMore, isFalse);
      expect(
        api.requests.map((path) => Uri.parse(path).queryParameters['page']),
        ['0', '1', '1'],
      );
    },
  );

  test(
    'a changed search cannot append a page from the previous search',
    () async {
      api.deferred = true;
      final query = SongQuery(state);
      addTearDown(query.dispose);
      final first = query.load();
      api.pending[0].complete({
        'items': [
          {'id': 'old', 'title': '原有结果'},
        ],
        'totalElements': 2,
      });
      await first;
      query.query = '新歌曲';
      final next = query.load(more: true);
      expect(Uri.parse(api.requests.last).queryParameters['page'], '0');
      expect(Uri.parse(api.requests.last).queryParameters['q'], '新歌曲');
      api.pending[1].complete({
        'items': [
          {'id': 'new', 'title': '新歌曲'},
        ],
        'totalElements': 1,
      });
      await next;
      expect(query.items.single.id, 'new');
    },
  );

  test(
    'work loading failures preserve records and have a retryable local error',
    () async {
      state.nativeEnabled = true;
      state.works = [
        {'id': 'saved', 'status': 'SAVED', 'instanceId': 'nas-a'},
      ];
      var fail = true;
      messenger.setMockMethodCallHandler(AppState.karaoke, (call) async {
        if (fail) {
          throw PlatformException(code: 'storage', message: '暂时无法读取录音');
        }
        return <Json>[
          {'id': 'draft', 'status': 'PREVIEW', 'instanceId': 'nas-a'},
        ];
      });
      final failed = state.refreshWorks();
      expect(state.worksLoading, isTrue);
      await failed;
      expect(state.worksLoading, isFalse);
      expect(state.works.single['id'], 'saved');
      expect(state.worksError, '暂时无法读取录音');
      expect(state.error, isNull);
      fail = false;
      await state.refreshWorks();
      expect(state.worksError, isNull);
      expect(state.works.single['id'], 'draft');
    },
  );

  test('late work reads cannot overwrite a newer successful refresh', () async {
    state.nativeEnabled = true;
    final responses = <Completer<List<Json>>>[];
    messenger.setMockMethodCallHandler(AppState.karaoke, (call) {
      final response = Completer<List<Json>>();
      responses.add(response);
      return response.future;
    });
    final first = state.refreshWorks();
    await Future<void>.delayed(Duration.zero);
    final second = state.refreshWorks();
    await Future<void>.delayed(Duration.zero);
    responses[1].complete([
      {'id': 'new', 'status': 'SAVED', 'instanceId': 'nas-a'},
    ]);
    await second;
    responses[0].completeError(
      PlatformException(code: 'storage', message: '过期请求失败'),
    );
    await first;
    expect(state.works.single['id'], 'new');
    expect(state.worksLoading, isFalse);
    expect(state.worksError, isNull);
  });

  testWidgets('song rows build lazily and search focuses the result list', (
    tester,
  ) async {
    api.rows = List.generate(
      200,
      (index) => {
        'id': '$index',
        'title': '歌曲 $index',
        'artists': ['测试歌手'],
      },
    );
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
    expect(find.byType(SongRow).evaluate().length, lessThan(30));
    expect(find.text('歌曲 199'), findsNothing);
    expect(find.text('家庭歌单'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '歌');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('搜索结果'), findsOneWidget);
    expect(find.text('家庭歌单'), findsNothing);
    expect(find.byTooltip('筛选歌曲'), findsOneWidget);
    await tester.tap(find.byTooltip('清除搜索'));
    await tester.pumpAndSettle();
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pumpAndSettle();
    expect(find.text('家庭歌单'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'senior browse and song actions fit a narrow screen at large type',
    (tester) async {
      state.uiMode = UiMode.senior;
      api.rows = [
        {
          'id': 'long',
          'title': '一首很长的歌曲名称需要自然换行',
          'artists': ['测试歌手'],
        },
      ];
      await tester.binding.setSurfaceSize(const Size(320, 568));
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      addTearDown(() => tester.binding.setSurfaceSize(null));
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await tester.pumpWidget(
        MaterialApp(
          theme: singVerseTheme(Brightness.light, senior: true),
          home: Scaffold(
            body: SongsPage(state: state, embedded: true, showBrowse: true),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('歌手'), findsOneWidget);
      expect(find.text('分类'), findsOneWidget);
      expect(find.text('家庭歌单'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('开始练唱'),
        180,
        scrollable: find
            .descendant(
              of: find.byType(CustomScrollView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      final start = tester.getSize(find.widgetWithText(FilledButton, '开始练唱'));
      expect(start.height, greaterThanOrEqualTo(56));
      expect(tester.takeException(), isNull);
    },
  );
}
