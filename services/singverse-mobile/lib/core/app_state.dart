import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';
import 'artwork_cache.dart';
import 'local_store.dart';
import 'models.dart';

enum UiMode { standard, senior }

class AppState extends ChangeNotifier {
  AppState({
    required this.api,
    required this.store,
    required this.preferences,
    bool listenPlayer = true,
  }) {
    themeMode = ThemeMode.values[preferences.getInt('theme')?.clamp(0, 2) ?? 0];
    uiMode = preferences.getString('uiMode') == 'senior'
        ? UiMode.senior
        : UiMode.standard;
    reduceTransparency = preferences.getBool('reduceTransparency') ?? false;
    nativeEnabled = listenPlayer;
    if (listenPlayer) {
      _events = const EventChannel('singverse/playback-events')
          .receiveBroadcastStream()
          .listen(
            _onPlayback,
            onError: (Object e) {
              playbackError = e.toString();
              notifyListeners();
            },
          );
    }
  }
  final playback = ChangeNotifier();
  late final Listenable performance = Listenable.merge([this, playback]);
  final NasApi api;
  final LocalStore store;
  final SharedPreferences preferences;
  static const player = MethodChannel('singverse/player');
  StreamSubscription<dynamic>? _events;
  String origin = '', instanceId = '', accountName = '';
  bool initialized = false,
      authenticated = false,
      mfaRequired = false,
      busy = false;
  String? error;
  String? retryAfter;
  ThemeMode themeMode = ThemeMode.system;
  UiMode uiMode = UiMode.standard;
  bool get senior => uiMode == UiMode.senior;
  bool reduceTransparency = false;
  bool nativeEnabled = true;
  Json singing = {};
  List<Json> works = [];
  bool worksLoading = false;
  String? worksError;
  List<Json> get visibleWorks => works
      .where(
        (w) =>
            instanceId.isEmpty ||
            w['instanceId'] == null ||
            w['instanceId'] == instanceId,
      )
      .toList();
  String workId = '';
  int stageRequests = 0;
  int countdown = 0, pitch = 0, rangeA = 0, rangeB = 0;
  double speed = 1, volume = 1;
  String channelMode = 'STEREO', outputRoute = '系统默认', audioRole = '';
  double videoAspect = 16 / 9;
  bool backingMapped = false, originalMapped = false;
  static const karaoke = MethodChannel('singverse/karaoke');
  String get singingPhase => singing['phase'] as String? ?? 'IDLE';
  bool get recording => singingPhase == 'RECORDING';
  bool get taking => recording || countdown > 0 || singingPhase == 'FINALIZING';
  String get takeId => singing['takeId'] as String? ?? '';
  String get pendingReviewId =>
      countdown == 0 &&
          takeId.isNotEmpty &&
          ['FINALIZING', 'PREVIEW', 'FAILED'].contains(singingPhase) &&
          singing['songId'] == current?.id &&
          singing['instanceId'] == playbackInstanceId
      ? takeId
      : '';
  String _workStatus = '';
  int _worksGeneration = 0;
  int _personalGeneration = 0;
  int _playGeneration = 0;
  void cancelPendingPlay() => _playGeneration++;
  Future<dynamic> sing(String method, [Json args = const {}]) async {
    final result = await karaoke.invokeMethod<dynamic>(method, args);
    return result is String &&
            (result.startsWith('{') || result.startsWith('['))
        ? jsonDecode(result)
        : result;
  }

  Future<void> refreshWorks() async {
    if (!nativeEnabled || _disposed) return;
    final ticket = ++_worksGeneration;
    worksLoading = true;
    worksError = null;
    // WorksPage also requests its initial data from initState.
    await Future<void>.value();
    if (_disposed || ticket != _worksGeneration) return;
    notifyListeners();
    try {
      final result = (await sing('works') as List)
          .map((row) => Map<String, dynamic>.from(row as Map))
          .toList();
      if (!_disposed && ticket == _worksGeneration) works = result;
    } catch (e) {
      if (!_disposed && ticket == _worksGeneration) {
        worksError = e is PlatformException
            ? e.message ?? '无法读取本机录音，请重试。'
            : e.toString();
      }
    } finally {
      if (!_disposed && ticket == _worksGeneration) {
        worksLoading = false;
        notifyListeners();
      }
    }
  }

  Future<void> endTake() async {
    await sing('end', {'id': takeId});
  }

  bool defaultPassword = false;
  List<Song> favorites = [], history = [];
  Song? current;
  String playbackState = 'IDLE', playbackError = '', mode = '';
  int positionMs = 0, durationMs = 0;
  bool isPlaying = false, loop = false;
  List<Json> tracks = [];
  String playbackInstanceId = '';
  int _lastSaved = -1;
  String _lastPlayingKey = '';
  bool _disposed = false;

  Future<void> initialize() async {
    try {
      origin = await api.restore();
      if (origin.isNotEmpty) {
        instanceId = preferences.getString('server-instance:$origin') ?? '';
        await refreshPersonal();
        final info = await api.inspect(origin) as Json;
        _checkServer(info);
        instanceId = info['instanceId'] as String;
        await preferences.setString('server-instance:$origin', instanceId);
        await refreshPersonal();
        try {
          await readSession();
        } on ApiFailure catch (e) {
          if (e.status != 401) rethrow;
        }
      }
    } catch (e) {
      error = e is FormatException ? e.message : e.toString();
    }
    initialized = true;
    notifyListeners();
    if (nativeEnabled) unawaited(refreshWorks());
  }

  void _checkServer(Json info) {
    if (info['service'] != 'singverse' ||
        info['apiVersion'] != 1 ||
        info['instanceId'] is! String) {
      throw const FormatException('此地址不是兼容的 SingVerse NAS 服务');
    }
  }

  Future<void> connect(String address) async {
    await _run(() async {
      final next = normalizeOrigin(address);
      final info = await api.inspect(next) as Json;
      _checkServer(info);
      if (origin != next) await command('stop');
      _artworkCache.clear();
      await api.configure(next);
      origin = next;
      instanceId = info['instanceId'] as String;
      await preferences.setString('server-instance:$origin', instanceId);
      authenticated = false;
      mfaRequired = false;
      await refreshPersonal();
      try {
        await readSession();
      } on ApiFailure catch (e) {
        if (e.status != 401) rethrow;
      }
    });
  }

  Future<void> login(String password) => _run(() async {
    final result = await api.request(
      '/api/v1/auth/login',
      method: 'POST',
      body: {'username': 'admin', 'password': password},
    ) as Json;
    mfaRequired = result['state'] == 'mfaRequired';
    authenticated = result['state'] == 'authenticated';
    if (authenticated) {
      await readSession();
      await refreshPersonal();
    }
  });
  Future<void> verify(String code) => _run(() async {
    await api.request(
      '/api/v1/auth/mfa/verify',
      method: 'POST',
      body: {'code': code},
    );
    mfaRequired = false;
    authenticated = true;
    await readSession();
    await refreshPersonal();
  });
  Future<void> readSession() async {
    try {
      final session = await api.request('/api/v1/auth/session') as Json;
      authenticated = true;
      defaultPassword = session['defaultPassword'] == true;
      accountName = session['username'] as String? ?? '';
    } on ApiFailure {
      authenticated = false;
      rethrow;
    }
  }

  final _artworkCache = ArtworkCache();

  Future<Uint8List> artwork(String path, {String? revision}) async {
    try {
      final identity = '$origin:$instanceId:$authenticated';
      return await _artworkCache.load('$identity:$path:$revision', () async {
        final bytes = await api.bytes(path);
        if (identity != '$origin:$instanceId:$authenticated') {
          throw ApiFailure(409, 'NAS 连接已切换');
        }
        return bytes;
      });
    } on ApiFailure catch (e) {
      if (e.status == 401 && authenticated) {
        authenticated = false;
        error = '登录已失效，请重新登录';
        notifyListeners();
      }
      rethrow;
    }
  }

  Future<void> logout() async {
    await command('stop');
    try {
      if (authenticated) {
        await api.request('/api/v1/auth/logout', method: 'POST');
      }
    } finally {
      _artworkCache.clear();
      await api.forget();
      authenticated = false;
      accountName = '';
      mfaRequired = false;
      error = null;
      notifyListeners();
    }
  }

  Future<void> _run(Future<void> Function() task) async {
    if (busy) return;
    busy = true;
    error = null;
    retryAfter = null;
    notifyListeners();
    try {
      await task();
    } catch (e) {
      error = e is FormatException ? e.message : e.toString();
      if (e is ApiFailure) retryAfter = e.retryAfter;
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  Future<dynamic> get(String path) async {
    try {
      return await api.request(path);
    } on ApiFailure catch (e) {
      if (e.status == 401) {
        authenticated = false;
        error = '登录已失效，请重新登录';
        notifyListeners();
      }
      if (e.status == 404 && path.startsWith('/api/v1/mobile/library/songs?')) {
        throw ApiFailure(404, 'NAS 版本尚未支持手机曲库，请更新 NAS 服务');
      }
      rethrow;
    }
  }

  Future<void> refreshPersonal() async {
    final expectedInstance = instanceId;
    final ticket = ++_personalGeneration;
    if (expectedInstance.isEmpty || _disposed) return;
    final nextFavorites = await store.favorites(expectedInstance);
    final nextHistory = await store.history(expectedInstance);
    if (_disposed ||
        ticket != _personalGeneration ||
        instanceId != expectedInstance) {
      return;
    }
    favorites = nextFavorites;
    _favoriteIds = nextFavorites.map((song) => song.id).toSet();
    history = nextHistory;
    notifyListeners();
  }

  bool isFavorite(Song song) => _favoriteIds.contains(song.id);
  Set<String> _favoriteIds = {};
  Future<void> favorite(Song song) async {
    await store.favorite(instanceId, song, !isFavorite(song));
    await refreshPersonal();
  }

  Future<void> setTheme(ThemeMode mode) async {
    themeMode = mode;
    await preferences.setInt('theme', mode.index);
    notifyListeners();
  }

  Future<void> setUiMode(UiMode mode) async {
    uiMode = mode;
    await preferences.setString('uiMode', mode.name);
    notifyListeners();
  }

  Future<void> setTransparency(bool reduced) async {
    reduceTransparency = reduced;
    await preferences.setBool('reduceTransparency', reduced);
    notifyListeners();
  }

  Future<void> play(
    Song song, {
    bool force = false,
    int start = 0,
    bool autoplay = true,
    bool reuseAttempt = false,
  }) async {
    final ticket = ++_playGeneration;
    final detail = Song(
      await get('/api/v1/mobile/library/songs/${Uri.encodeComponent(song.id)}')
          as Json,
    );
    if (_disposed || ticket != _playGeneration) return;
    await player.invokeMethod<void>('open', {
      'song': jsonEncode(detail.data),
      'instanceId': instanceId,
      'positionMs': start,
      'force': force,
      'autoplay': autoplay,
      'reuseAttempt': reuseAttempt,
    });
    final snapshot = await player.invokeMapMethod<String, dynamic>('snapshot');
    if (snapshot != null) _onPlayback(snapshot);
  }

  Future<void> command(String action, [Object? value]) async {
    if (action == 'pause' || action == 'stop') cancelPendingPlay();
    await player.invokeMethod<void>('command', {
      'action': action,
      'value': value,
    });
  }

  String get _playbackStructure => [
    current?.data,
    playbackInstanceId,
    playbackState,
    playbackError,
    isPlaying,
    loop,
    mode,
    tracks,
    authenticated,
    workId,
    singingPhase,
    takeId,
    singing['error'],
    singing['input'],
    countdown,
    pitch,
    speed,
    volume,
    channelMode,
    audioRole,
    videoAspect,
    outputRoute,
    rangeA,
    rangeB,
    backingMapped,
    originalMapped,
    stageRequests,
  ].join('\u0000');

  void _onPlayback(dynamic event) {
    if (_disposed) return;
    final previous = _playbackStructure;
    final previousProgress = [
      positionMs,
      durationMs,
      singing['level'],
      singing['clipped'],
      singing['recordedMs'],
    ].toString();
    final value = Map<String, dynamic>.from(event as Map);
    if (value['openSinging'] == true) stageRequests++;
    final raw = jsonDecode(value['song'] as String) as Json;
    current = raw['id'] == null ? null : Song(raw);
    playbackInstanceId = value['instanceId'] as String? ?? '';
    playbackState = value['state'] as String? ?? 'IDLE';
    playbackError = value['message'] as String? ?? '';
    positionMs = (value['positionMs'] as num? ?? 0).toInt();
    durationMs = (value['durationMs'] as num? ?? 0).toInt();
    isPlaying = value['isPlaying'] == true;
    loop = value['loop'] == true;
    mode = value['mode'] as String? ?? '';
    tracks = (value['tracks'] as List? ?? [])
        .map((t) => Map<String, dynamic>.from(t as Map))
        .toList();
    if (playbackState == 'AUTH_REQUIRED') {
      authenticated = false;
      error = playbackError;
    }
    singing = Map<String, dynamic>.from(value['karaoke'] as Map? ?? {});
    workId = value['workId'] as String? ?? '';
    countdown = (value['countdown'] as num? ?? 0).toInt();
    pitch = (value['pitch'] as num? ?? 0).toInt();
    speed = (value['speed'] as num? ?? 1).toDouble();
    volume = (value['volume'] as num? ?? 1).toDouble();
    channelMode = value['channel'] as String? ?? 'STEREO';
    audioRole = value['audioRole'] as String? ?? '';
    final aspect = (value['videoAspect'] as num? ?? 16 / 9).toDouble();
    videoAspect = aspect.isFinite && aspect > 0 ? aspect : 16 / 9;
    outputRoute = value['output'] as String? ?? '系统默认';
    rangeA = (value['rangeA'] as num? ?? 0).toInt();
    rangeB = (value['rangeB'] as num? ?? 0).toInt();
    backingMapped = value['backingMapped'] == true;
    originalMapped = value['originalMapped'] == true;
    final statusKey = '$takeId:$singingPhase';
    if (_workStatus != statusKey) {
      _workStatus = statusKey;
      if (['PREVIEW', 'SAVED', 'FAILED'].contains(singingPhase)) {
        unawaited(refreshWorks());
      }
    }
    final song = current;
    final key = '${playbackInstanceId}_${song?.id}';
    final newPlay = isPlaying && _lastPlayingKey != key;
    if (song != null &&
        workId.isEmpty &&
        playbackInstanceId.isNotEmpty &&
        (newPlay ||
            _lastPlayingKey == key &&
                (positionMs - _lastSaved).abs() >= 5000)) {
      _lastSaved = positionMs;
      if (newPlay) _lastPlayingKey = key;
      unawaited(
        store
            .played(playbackInstanceId, song, positionMs, newPlay: newPlay)
            .then((_) async {
              if (_disposed || playbackInstanceId != instanceId) return;
              if (newPlay) {
                await refreshPersonal();
              } else {
                history = history
                    .map(
                      (item) => item.id == song.id
                          ? Song({...item.data, 'positionMs': positionMs})
                          : item,
                    )
                    .toList();
              }
            })
            .catchError((Object e) {
              if (!_disposed) {
                playbackError = '本机历史保存失败';
                notifyListeners();
              }
            }),
      );
    }
    if (current == null) _lastPlayingKey = '';
    if (previous != _playbackStructure) {
      notifyListeners();
    } else if (previousProgress !=
        [
          positionMs,
          durationMs,
          singing['level'],
          singing['clipped'],
          singing['recordedMs'],
        ].toString()) {
      playback.notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _artworkCache.clear();
    unawaited(_events?.cancel());
    playback.dispose();
    super.dispose();
  }
}

class SongQuery extends ChangeNotifier {
  SongQuery(this.state, {this.artistId, List<String> tags = const []})
    : tagIds = [...tags];
  final AppState state;
  final String? artistId;
  String query = '';
  List<String> tagIds;
  List<Song> items = [];
  int total = 0, _page = 0, _generation = 0;
  bool loading = false, disposed = false;
  String? error;
  bool _failedMore = false;
  String? _loadedCriteria;
  bool get hasMore => items.length < total;
  Future<void> retry() => load(more: _failedMore);
  Future<void> load({bool more = false}) async {
    if (disposed || more && (loading || !hasMore)) return;
    final criteria = jsonEncode([query, tagIds, artistId]);
    if (more && criteria != _loadedCriteria) return load();
    final ticket = ++_generation;
    final page = more ? _page + 1 : 0;
    loading = true;
    error = null;
    _failedMore = false;
    if (!more) {
      items = [];
      total = 0;
    }
    notifyListeners();
    try {
      final params = <String, dynamic>{
        'q': query,
        'page': '$page',
        'size': '20',
        if (tagIds.isNotEmpty) 'tagIds': tagIds,
        if (artistId != null) 'artistId': artistId,
      };
      final result = await state.get(
        '/api/v1/mobile/library/songs?${Uri(queryParameters: params).query}',
      ) as Json;
      if (disposed || ticket != _generation) return;
      final rows = (result['items'] as List).map((x) => Song(x as Json));
      items = more ? [...items, ...rows] : rows.toList();
      total = result['totalElements'] as int;
      _page = page;
      _loadedCriteria = criteria;
    } catch (e) {
      if (!disposed && ticket == _generation) {
        error = e is FormatException ? e.message : e.toString();
        _failedMore = more;
      }
    } finally {
      if (!disposed && ticket == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    disposed = true;
    _generation++;
    super.dispose();
  }
}
