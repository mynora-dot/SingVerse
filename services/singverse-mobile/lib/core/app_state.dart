import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';
import 'local_store.dart';
import 'models.dart';

class AppState extends ChangeNotifier {
  AppState({
    required this.api,
    required this.store,
    required this.preferences,
    bool listenPlayer = true,
  }) {
    themeMode = ThemeMode.values[preferences.getInt('theme')?.clamp(0, 2) ?? 1];
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
  final NasApi api;
  final LocalStore store;
  final SharedPreferences preferences;
  static const player = MethodChannel('singverse/player');
  StreamSubscription<dynamic>? _events;
  String origin = '', instanceId = '';
  bool initialized = false,
      authenticated = false,
      mfaRequired = false,
      busy = false;
  String? error;
  String? retryAfter;
  ThemeMode themeMode = ThemeMode.system;
  bool reduceTransparency = false;
  bool nativeEnabled = true;
  Json singing = {};
  List<Json> works = [];
  String workId = '';
  int stageRequests = 0;
  int countdown = 0, pitch = 0, rangeA = 0, rangeB = 0;
  double speed = 1, volume = 1;
  String channelMode = 'STEREO', outputRoute = '系统默认';
  bool backingMapped = false, originalMapped = false;
  static const karaoke = MethodChannel('singverse/karaoke');
  String get singingPhase => singing['phase'] as String? ?? 'IDLE';
  bool get recording => singingPhase == 'RECORDING';
  bool get taking => recording || countdown > 0 || singingPhase == 'FINALIZING';
  String get takeId => singing['takeId'] as String? ?? '';
  String _workStatus = '';
  Future<dynamic> sing(String method, [Json args = const {}]) async {
    final result = await karaoke.invokeMethod<dynamic>(method, args);
    return result is String &&
            (result.startsWith('{') || result.startsWith('['))
        ? jsonDecode(result)
        : result;
  }

  Future<void> refreshWorks() async {
    if (!nativeEnabled || _disposed) return;
    try {
      works = (await sing('works') as List).cast<Json>();
      if (!_disposed) notifyListeners();
    } on PlatformException catch (e) {
      if (!_disposed) {
        error = e.message;
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
    } on ApiFailure {
      authenticated = false;
      rethrow;
    }
  }

  Future<Uint8List> artwork(String path) async {
    try {
      return await api.bytes(path);
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
      await api.forget();
      authenticated = false;
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
    if (instanceId.isEmpty) return;
    favorites = await store.favorites(instanceId);
    history = await store.history(instanceId);
    if (!_disposed) notifyListeners();
  }

  bool isFavorite(Song song) => favorites.any((s) => s.id == song.id);
  Future<void> favorite(Song song) async {
    await store.favorite(instanceId, song, !isFavorite(song));
    await refreshPersonal();
  }

  Future<void> setTheme(ThemeMode mode) async {
    themeMode = mode;
    await preferences.setInt('theme', mode.index);
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
  }) async {
    final detail = Song(
      await get('/api/v1/mobile/library/songs/${Uri.encodeComponent(song.id)}')
          as Json,
    );
    await player.invokeMethod<void>('open', {
      'song': jsonEncode(detail.data),
      'instanceId': instanceId,
      'positionMs': start,
      'force': force,
      'autoplay': autoplay,
    });
    final snapshot = await player.invokeMapMethod<String, dynamic>('snapshot');
    if (snapshot != null) _onPlayback(snapshot);
  }

  Future<void> command(String action, [Object? value]) async {
    await player.invokeMethod<void>('command', {
      'action': action,
      'value': value,
    });
  }

  void _onPlayback(dynamic event) {
    if (_disposed) return;
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
            .then((_) => refreshPersonal())
            .catchError((Object e) {
              if (!_disposed) {
                playbackError = '本机历史保存失败';
                notifyListeners();
              }
            }),
      );
    }
    if (current == null) _lastPlayingKey = '';
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    unawaited(_events?.cancel());
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
  bool get hasMore => items.length < total;
  Future<void> load({bool more = false}) async {
    if (more && (loading || !hasMore)) return;
    final ticket = ++_generation;
    final page = more ? _page + 1 : 0;
    loading = true;
    error = null;
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
    } catch (e) {
      if (!disposed && ticket == _generation) {
        error = e is FormatException ? e.message : e.toString();
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
