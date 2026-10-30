import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../app/design.dart';
import '../../core/api.dart';
import '../../core/app_state.dart';
import '../../core/models.dart';

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

void openTv(BuildContext context, AppState state) => Navigator.push(
  context,
  MaterialPageRoute<void>(
    settings: const RouteSettings(name: 'tv'),
    builder: (_) => TvPage(state: state),
  ),
);

class TvPage extends StatefulWidget {
  const TvPage({super.key, required this.state});
  final AppState state;
  @override
  State<TvPage> createState() => _TvPageState();
}

class _TvPageState extends State<TvPage> {
  bool scanning = false, checking = false;
  String? error;
  MobileScannerController? camera;
  Future<void> scan() async {
    camera?.dispose();
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
        if (mounted) setState(() => scanning = false);
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
          scanning = false;
        });
      }
    } finally {
      if (mounted) setState(() => checking = false);
    }
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

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: false,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) back();
    },
    child: Scaffold(
      appBar: AppBar(
        leading: IconButton(
          tooltip: '返回',
          onPressed: back,
          icon: const Icon(Icons.arrow_back_rounded),
        ),
        title: Text(tvSession.deviceName ?? '扫码点歌'),
        actions: [
          IconButton(
            tooltip: '重新扫码',
            onPressed: checking ? null : scan,
            icon: const Icon(Icons.qr_code_scanner),
          ),
          if (tvSession.controller != null)
            IconButton(
              tooltip: '断开电视',
              onPressed: () => uiTask(context, () async {
                await tvSession.disconnect();
                if (mounted) setState(() {});
              }),
              icon: const Icon(Icons.link_off),
            ),
        ],
      ),
      body: Column(
        children: [
          if (error != null)
            Padding(padding: const EdgeInsets.all(12), child: Text(error!)),
          if (checking) const LinearProgressIndicator(),
          Expanded(
            child: scanning
                ? MobileScanner(
                    controller: camera,
                    onDetect: (capture) {
                      final raw = capture.barcodes.firstOrNull?.rawValue;
                      if (raw != null) detected(raw);
                    },
                    errorBuilder: (context, e) =>
                        Notice('相机不可用或权限被拒绝。可在系统设置中允许相机后重试。', action: scan),
                  )
                : tvSession.controller != null
                ? WebViewWidget(controller: tvSession.controller!)
                : Center(
                    child: Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(
                            Icons.qr_code_scanner_rounded,
                            size: 72,
                            color: mint,
                          ),
                          const SizedBox(height: 24),
                          Text(
                            '让好歌，在电视上相聚。',
                            style: Theme.of(context).textTheme.headlineSmall,
                            textAlign: TextAlign.center,
                          ),
                          const SizedBox(height: 12),
                          const Text(
                            '扫描电视上的 SingVerse 聚会二维码。无需先连接本机 NAS。',
                            textAlign: TextAlign.center,
                          ),
                          const SizedBox(height: 24),
                          FilledButton(
                            onPressed: scan,
                            child: const Text('扫描电视二维码'),
                          ),
                        ],
                      ),
                    ),
                  ),
          ),
        ],
      ),
    ),
  );
}
