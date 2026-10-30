import 'dart:convert';

import 'package:flutter/services.dart';

class ApiFailure implements Exception {
  ApiFailure(this.status, this.message, {this.retryAfter});
  final int status;
  final String message;
  final String? retryAfter;
  @override
  String toString() => message;
}

abstract interface class NasApi {
  Future<String> restore();
  Future<void> configure(String origin);
  Future<void> forget();
  Future<dynamic> request(String path, {String method = 'GET', Object? body});
  Future<dynamic> inspect(String origin, {String? invitation});
  Future<Uint8List> bytes(String path);
}

class NativeNasApi implements NasApi {
  static const channel = MethodChannel('singverse/nas');
  Future<T?> _call<T>(String method, [Map<String, Object?>? arguments]) async {
    try {
      return await channel.invokeMethod<T>(method, arguments);
    } on PlatformException catch (e) {
      throw ApiFailure(
        int.tryParse(e.code) ?? 0,
        e.message ?? '连接失败',
        retryAfter: e.details?.toString(),
      );
    }
  }

  @override
  Future<String> restore() async => await _call<String>('restore') ?? '';
  @override
  Future<void> configure(String origin) =>
      _call('configure', {'origin': origin});
  @override
  Future<void> forget() => _call('forget');
  @override
  Future<dynamic> request(
    String path, {
    String method = 'GET',
    Object? body,
  }) async => jsonDecode(
    await _call<String>('request', {
          'path': path,
          'method': method,
          'body': body == null ? null : jsonEncode(body),
        }) ??
        'null',
  );
  @override
  Future<dynamic> inspect(String origin, {String? invitation}) async =>
      jsonDecode(
        await _call<String>('inspect', {
              'origin': origin,
              'invitation': invitation,
            }) ??
            '{}',
      );
  @override
  Future<Uint8List> bytes(String path) async =>
      await _call<Uint8List>('bytes', {'path': path}) ?? Uint8List(0);
}

String normalizeOrigin(String input) {
  final uri = Uri.tryParse(input.trim());
  if (uri == null ||
      !['http', 'https'].contains(uri.scheme) ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment ||
      !['', '/'].contains(uri.path)) {
    throw const FormatException('请输入 NAS 根地址，例如 http://nas.local:18080');
  }
  return Uri(
    scheme: uri.scheme,
    host: uri.host.toLowerCase(),
    port: uri.hasPort ? uri.port : null,
  ).origin;
}

class TvInvite {
  TvInvite(this.origin, this.token);
  final String origin;
  final String token;
  String get url =>
      '$origin/party/#${Uri(queryParameters: {'invite': token}).query}';
  static TvInvite parse(String input) {
    final uri = Uri.tryParse(input);
    if (uri == null ||
        !['http', 'https'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.path != '/party/' ||
        uri.hasQuery) {
      throw const FormatException('请扫描 SingVerse 电视上的聚会二维码');
    }
    final token = Uri.splitQueryString(uri.fragment)['invite'] ?? '';
    if (!RegExp(r'^[A-Za-z0-9_-]{20,200}$').hasMatch(token)) {
      throw const FormatException('聚会邀请无效，请重新扫码');
    }
    return TvInvite(uri.origin, token);
  }
}
