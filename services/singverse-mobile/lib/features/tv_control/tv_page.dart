import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../app/design.dart';
import '../../core/api.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';
import '../singing/actions.dart';

// A retained WebView keeps H5 sessionStorage alive when returning to native pages.
final tvSession = TvSession();

class TvSession {
  WebViewController? controller;
  TvInvite? invite;
  String? deviceName;
  Future<void> disconnect() async {
    final old = controller;
    if (old != null) {
      await old.runJavaScript(
        "sessionStorage.removeItem('singverse-party'); location.replace('/party/');",
      );
      await old.clearLocalStorage();
      await old.loadHtmlString('<html><body></body></html>');
    }
    controller = null;
    invite = null;
    deviceName = null;
  }
}

Future<bool> prepareTvPlayback(BuildContext context, AppState state) async {
  if (!context.mounted || !await keepCurrentRecording(context, state)) {
    return false;
  }
  // Invalidate a pending song-detail request before it can open native media.
  state.cancelPendingPlay();
  // Pausing also cancels autoplay when a pending media request finishes.
  if (state.current != null ||
      state.isPlaying ||
      state.playbackState == 'PREPARING') {
    await state.command('pause');
  }
  return context.mounted;
}

Future<void> openTv(BuildContext context, AppState state) =>
    uiTask(context, () async {
      if (!context.mounted || !await prepareTvPlayback(context, state)) return;
      if (!context.mounted) return;
      await Navigator.push(
        context,
        MaterialPageRoute<void>(
          settings: const RouteSettings(name: 'tv'),
          builder: (_) => TvPage(state: state),
        ),
      );
    });

class TvPage extends StatefulWidget {
  const TvPage({super.key, required this.state, this.resume = false});
  final AppState state;
  final bool resume;
  @override
  State<TvPage> createState() => _TvPageState();
}

class _TvPageState extends State<TvPage> {
  @override
  void initState() {
    super.initState();
    if (!widget.resume || tvSession.controller == null) {
      scanning = true;
      camera = MobileScannerController(formats: [BarcodeFormat.qrCode]);
    }
  }

  bool scanning = false, checking = false;
  String? error;
  MobileScannerController? camera;
  Future<void> scan() async {
    if (!mounted) return;
    await camera?.dispose();
    if (!mounted) return;
    setState(() {
      camera = MobileScannerController(formats: [BarcodeFormat.qrCode]);
      scanning = true;
      error = null;
    });
  }

  Future<void> detected(String value) async {
    if (checking) return;
    setState(() => checking = true);
    try {
      if (Uri.tryParse(value)?.path == '/admin/') {
        await approveTv(value);
        return;
      }
      final invite = TvInvite.parse(value);
      await camera?.stop();
      final info = await widget.state.api.inspect(invite.origin) as Json;
      if (info['service'] != 'singverse' || info['apiVersion'] != 1) {
        throw const FormatException('此二维码不是兼容的 SingVerse 服务');
      }
      final preview = await widget.state.api.inspect(
        invite.origin,
        invitation: invite.token,
      ) as Json;
      if (!mounted) return;
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('连接电视聚会'),
          content: Text(
            '${preview['deviceName']}\n${invite.origin}\n\n电视点歌会话独立于本机曲库。',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('打开点歌页面'),
            ),
          ],
        ),
      );
      if (confirmed != true || !mounted) {
        if (mounted) await scan();
        return;
      }
      if (tvSession.invite?.origin != invite.origin ||
          tvSession.invite?.token != invite.token) {
        final web =
            WebViewController(onPermissionRequest: (request) => request.deny())
              ..setJavaScriptMode(JavaScriptMode.unrestricted)
              ..setNavigationDelegate(
                NavigationDelegate(
                  onNavigationRequest: (request) {
                    final target = Uri.tryParse(request.url);
                    return target != null &&
                            target.hasAuthority &&
                            ['http', 'https'].contains(target.scheme) &&
                            target.origin == invite.origin &&
                            target.userInfo.isEmpty &&
                            target.path.startsWith('/party/')
                        ? NavigationDecision.navigate
                        : NavigationDecision.prevent;
                  },
                  onWebResourceError: (failure) {
                    if (mounted && failure.isForMainFrame == true) {
                      setState(() => error = 'NAS 点歌页面无法连接，请重试。');
                    }
                  },
                ),
              );
        await web.loadRequest(Uri.parse(invite.url));
        tvSession.controller = web;
        tvSession.invite = invite;
        tvSession.deviceName = preview['deviceName'] as String?;
      }
      if (mounted) setState(() => scanning = false);
    } catch (e) {
      if (mounted) {
        setState(() {
          error = e.toString();
          scanning = true;
        });
        await camera?.stop();
      }
    } finally {
      if (mounted) setState(() => checking = false);
    }
  }

  Future<void> approveTv(String value) async {
    if (!widget.state.authenticated) {
      throw const FormatException('请先登录当前 NAS 管理账号，再授权电视登录');
    }
    final qr = TvAuthorizationQr.parse(value);
    if (qr.origin != widget.state.origin) {
      throw const FormatException('二维码来自另一台 NAS，请切换到对应 NAS 后再扫描');
    }
    await camera?.stop();
    final result =
        await widget.state.api.request('/api/v1/admin/tv-devices') as Json;
    final pending = (result['pending'] as List? ?? const [])
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item))
        .where((item) => item['id'] == qr.id && item['status'] == 'PENDING')
        .firstOrNull;
    if (pending == null) {
      throw const FormatException('这台电视的登录申请已处理或过期，请在电视上重新申请');
    }
    if (!mounted) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('授权电视登录'),
        content: Text('允许「${pending['name'] ?? 'SingVerse TV'}」登录并访问本机曲库吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认授权'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) {
      if (mounted) await scan();
      return;
    }
    await widget.state.api.request(
      '/api/v1/admin/device-authorizations/${Uri.encodeComponent(qr.id)}/approve',
      method: 'POST',
      body: {'code': qr.code},
    );
    if (!mounted) return;
    setState(() {
      scanning = true;
      error = null;
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已授权「${pending['name'] ?? 'SingVerse TV'}」登录')),
    );
    if (mounted) Navigator.pop(context);
  }

  Future<void> back() async {
    final web = tvSession.controller;
    if (!scanning && web != null && await web.canGoBack()) {
      await web.goBack();
    } else if (mounted) {
      Navigator.pop(context);
    }
  }

  @override
  void dispose() {
    camera?.dispose();
    super.dispose();
  }

  Widget cameraFailure(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 36),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Icon(Icons.camera_alt_outlined, size: 40),
        const SizedBox(height: 20),
        Text(
          '还不能使用相机',
          style: Theme.of(context).textTheme.titleLarge,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 12),
        const Text(
          '允许相机权限后，就能扫描电视上的二维码。若已关闭权限，请到系统设置中允许相机访问。',
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 24),
        FilledButton(
          onPressed: () => uiTask(context, () async {
            await widget.state.sing('settings');
          }),
          child: const Text('打开设置'),
        ),
        const SizedBox(height: 8),
        OutlinedButton(onPressed: scan, child: const Text('重试相机')),
      ],
    ),
  );

  Widget scannerBody(
    BuildContext context,
  ) => ValueListenableBuilder<MobileScannerState>(
    valueListenable: camera!,
    builder: (context, scanner, _) => LayoutBuilder(
      builder: (context, size) => SingleChildScrollView(
        child: ContentWidth(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 6, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (tvSession.controller != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Wrap(
                      alignment: WrapAlignment.spaceBetween,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      spacing: 12,
                      children: [
                        Text(
                          '已连接${tvSession.deviceName ?? '电视'}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        TextButton.icon(
                          onPressed: checking
                              ? null
                              : () async {
                                  await camera?.stop();
                                  if (mounted) {
                                    setState(() {
                                      scanning = false;
                                      error = null;
                                    });
                                  }
                                },
                          icon: const Icon(
                            Icons.arrow_forward_rounded,
                            size: 18,
                          ),
                          label: const Text('继续点歌'),
                        ),
                      ],
                    ),
                  ),
                if (scanner.error != null)
                  cameraFailure(context)
                else ...[
                  SizedBox(
                    height: math.min(
                      size.maxWidth - 40,
                      math.max(220, size.maxHeight * .57),
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(18),
                      child: LayoutBuilder(
                        builder: (context, preview) {
                          final side = math.min(
                            244.0,
                            math.min(preview.maxWidth, preview.maxHeight) * .72,
                          );
                          final window = Rect.fromCenter(
                            center: Offset(
                              preview.maxWidth / 2,
                              preview.maxHeight / 2,
                            ),
                            width: side,
                            height: side,
                          );
                          return Stack(
                            fit: StackFit.expand,
                            children: [
                              MobileScanner(
                                controller: camera,
                                scanWindow: window,
                                onDetect: (capture) {
                                  final raw =
                                      capture.barcodes.firstOrNull?.rawValue;
                                  if (raw != null) detected(raw);
                                },
                                errorBuilder: (_, _) => const SizedBox.shrink(),
                              ),
                              IgnorePointer(
                                child: CustomPaint(painter: _ScanFrame(window)),
                              ),
                            ],
                          );
                        },
                      ),
                    ),
                  ),
                  const SizedBox(height: 22),
                  Text(
                    '对准电视上的二维码',
                    style: Theme.of(context).textTheme.titleMedium,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 7),
                  Text(
                    '将二维码放入框内，自动连接电视或确认授权。',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 14),
                  Center(
                    child: OutlinedButton.icon(
                      onPressed: scanner.torchState == TorchState.unavailable
                          ? null
                          : () => uiTask(context, camera!.toggleTorch),
                      icon: Icon(
                        scanner.torchState == TorchState.on
                            ? Icons.flashlight_off_rounded
                            : Icons.flashlight_on_rounded,
                        size: 20,
                      ),
                      label: Text(
                        scanner.torchState == TorchState.on ? '关闭手电筒' : '打开手电筒',
                      ),
                    ),
                  ),
                ],
                if (checking)
                  const Padding(
                    padding: EdgeInsets.only(top: 18),
                    child: LinearProgressIndicator(),
                  ),
                if (error != null)
                  Notice(error!, action: checking ? null : scan),
              ],
            ),
          ),
        ),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) => Theme(
    data: scanning
        ? singVerseTheme(Brightness.dark, senior: widget.state.senior)
        : Theme.of(context),
    child: Builder(
      builder: (context) => PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, result) {
          if (!didPop) back();
        },
        child: Scaffold(
          backgroundColor: scanning ? const Color(0xFF111318) : null,
          appBar: AppBar(
            backgroundColor: scanning ? const Color(0xFF111318) : null,
            leading: IconButton(
              tooltip: '关闭',
              onPressed: () => Navigator.pop(context),
              icon: const Icon(Icons.close_rounded),
            ),
            title: Text(scanning ? '扫一扫' : tvSession.deviceName ?? '电视点歌'),
            actions: [
              if (!scanning)
                IconButton(
                  tooltip: '重新扫码',
                  onPressed: checking ? null : scan,
                  icon: const Icon(Icons.qr_code_scanner_rounded),
                ),
              if (!scanning && tvSession.controller != null)
                IconButton(
                  tooltip: '断开电视',
                  onPressed: () => uiTask(context, () async {
                    final confirmed = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: const Text('结束电视连接？'),
                        content: const Text('结束后，可以重新扫描电视上的二维码继续点歌。'),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(context, false),
                            child: const Text('取消'),
                          ),
                          FilledButton(
                            onPressed: () => Navigator.pop(context, true),
                            child: const Text('结束连接'),
                          ),
                        ],
                      ),
                    );
                    if (confirmed != true) return;
                    await tvSession.disconnect();
                    if (mounted) await scan();
                  }),
                  icon: const Icon(Icons.link_off_rounded),
                ),
            ],
          ),
          body: SafeArea(
            top: false,
            child: scanning
                ? scannerBody(context)
                : tvSession.controller != null
                ? Column(
                    children: [
                      if (error != null)
                        Notice(
                          error!,
                          action: () => tvSession.controller!.reload(),
                        ),
                      Expanded(
                        child: WebViewWidget(controller: tvSession.controller!),
                      ),
                    ],
                  )
                : Notice('未连接电视，请重新扫码。', action: scan),
          ),
        ),
      ),
    ),
  );
}

class _ScanFrame extends CustomPainter {
  const _ScanFrame(this.window);
  final Rect window;

  @override
  void paint(Canvas canvas, Size size) {
    final shade = Path()
      ..addRect(Offset.zero & size)
      ..addRRect(RRect.fromRectAndRadius(window, const Radius.circular(18)));
    shade.fillType = PathFillType.evenOdd;
    canvas.drawPath(
      shade,
      Paint()..color = Colors.black.withValues(alpha: .32),
    );
    final border = Paint()
      ..color = Colors.white
      ..strokeWidth = 3
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round;
    for (final corner in [
      window.topLeft,
      window.topRight,
      window.bottomLeft,
      window.bottomRight,
    ]) {
      final dx = corner.dx == window.left ? 1.0 : -1.0;
      final dy = corner.dy == window.top ? 1.0 : -1.0;
      final path = Path()
        ..moveTo(corner.dx, corner.dy + dy * 25)
        ..lineTo(corner.dx, corner.dy + dy * 12)
        ..quadraticBezierTo(
          corner.dx,
          corner.dy,
          corner.dx + dx * 12,
          corner.dy,
        )
        ..lineTo(corner.dx + dx * 25, corner.dy);
      canvas.drawPath(path, border);
    }
  }

  @override
  bool shouldRepaint(_ScanFrame oldDelegate) => oldDelegate.window != window;
}
