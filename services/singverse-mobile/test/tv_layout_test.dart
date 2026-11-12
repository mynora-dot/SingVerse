import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:singverse_mobile/app/design.dart';
import 'package:singverse_mobile/core/app_state.dart';
import 'package:singverse_mobile/core/local_store.dart';
import 'package:singverse_mobile/features/tv_control/tv_page.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'widget_test.dart' show FakeApi;

// Only the plugin's platform boundary is replaced. TvPage, MobileScanner and
// its controller still render and handle errors; no camera or QR is simulated.
class ScannerPlatformFixture extends MobileScannerPlatform {
  final _barcodes = StreamController<BarcodeCapture?>.broadcast();
  final _torch = StreamController<TorchState>.broadcast();
  final _zoom = StreamController<double>.broadcast();
  bool permissionDenied = false;
  int starts = 0;
  int torchToggles = 0;
  TorchState torch = TorchState.off;

  @override
  Stream<BarcodeCapture?> get barcodesStream => _barcodes.stream;
  @override
  Stream<TorchState> get torchStateStream => _torch.stream;
  @override
  Stream<double> get zoomScaleStateStream => _zoom.stream;

  @override
  Widget buildCameraView() => const ColoredBox(
    key: ValueKey('scanner-platform-fixture'),
    color: Colors.black,
  );

  @override
  Future<MobileScannerViewAttributes> start(StartOptions startOptions) async {
    starts++;
    if (permissionDenied) {
      throw const MobileScannerException(
        errorCode: MobileScannerErrorCode.permissionDenied,
      );
    }
    torch = TorchState.off;
    return const MobileScannerViewAttributes(
      cameraDirection: CameraFacing.back,
      currentTorchMode: TorchState.off,
      size: Size(640, 480),
      numberOfCameras: 1,
      initialDeviceOrientation: DeviceOrientation.portraitUp,
    );
  }

  @override
  Future<void> toggleTorch() async {
    torchToggles++;
    torch = torch == TorchState.off ? TorchState.on : TorchState.off;
    _torch.add(torch);
  }

  @override
  Future<void> stop() async {}
  @override
  Future<void> pause() async {}
  @override
  Future<void> updateScanWindow(Rect? window) async {}
  @override
  Future<void> setFocusPoint(Offset position) async {}

  // A page can replace its controller on retry. Keep the fixture streams alive
  // until the test unmounts every controller and explicitly closes the fixture.
  @override
  Future<void> dispose() async {}

  Future<void> close() async {
    await Future.wait([_barcodes.close(), _torch.close(), _zoom.close()]);
  }
}

const viewports = [Size(320, 568), Size(390, 844), Size(844, 390)];

void prepareView(WidgetTester tester) {
  tester.view.devicePixelRatio = 1;
  tester.platformDispatcher.textScaleFactorTestValue = 1.8;
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPadding);
  addTearDown(tester.view.resetViewPadding);
  addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
  addTearDown(() => tester.binding.setSurfaceSize(null));
}

Future<void> showScanner(
  WidgetTester tester,
  AppState state, {
  required Size size,
  required bool senior,
}) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pumpAndSettle();
  await tester.binding.setSurfaceSize(size);
  final landscape = size.width > size.height;
  tester.view.padding = FakeViewPadding(
    top: landscape ? 0 : 24,
    bottom: landscape ? 16 : 24,
    left: landscape ? 24 : 0,
    right: landscape ? 24 : 0,
  );
  tester.view.viewPadding = tester.view.padding;
  state.uiMode = senior ? UiMode.senior : UiMode.standard;
  await tester.pumpWidget(
    MaterialApp(
      theme: singVerseTheme(Brightness.light, senior: senior),
      home: TvPage(state: state),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> expectReachable(
  WidgetTester tester,
  Finder target, {
  required Size size,
  required String reason,
  double? minimumHeight,
}) async {
  expect(target, findsOneWidget, reason: reason);
  await tester.ensureVisible(target);
  await tester.pumpAndSettle();
  expect(target.hitTestable(), findsOneWidget, reason: reason);
  final rect = tester.getRect(target);
  final padding = tester.view.padding;
  expect(rect.left, greaterThanOrEqualTo(padding.left), reason: reason);
  expect(
    rect.right,
    lessThanOrEqualTo(size.width - padding.right),
    reason: reason,
  );
  expect(rect.top, greaterThanOrEqualTo(padding.top), reason: reason);
  expect(
    rect.bottom,
    lessThanOrEqualTo(size.height - padding.bottom),
    reason: reason,
  );
  if (minimumHeight != null) {
    expect(rect.height, greaterThanOrEqualTo(minimumHeight), reason: reason);
    expect(rect.width, greaterThanOrEqualTo(minimumHeight), reason: reason);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  late LocalStore store;
  late AppState state;
  late FakeApi api;
  final nativeCalls = <String>[];

  setUpAll(() async {
    final bytes = await File('test/fixtures/fonts/UiCapture.ttf').readAsBytes();
    // Use the same local Chinese font as the redesign layout checks, so line
    // wrapping is assessed with real glyph metrics rather than Ahem boxes.
    for (final family in ['UiCapture', 'Roboto', 'Ahem']) {
      await (FontLoader(
        family,
      )..addFont(Future.value(ByteData.sublistView(bytes)))).load();
    }
  });
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
    nativeCalls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(AppState.karaoke, (call) async {
          nativeCalls.add(call.method);
          return null;
        });
  });
  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(AppState.karaoke, null);
    state.dispose();
    await store.close();
  });

  testWidgets('scan instructions and torch stay reachable with large text', (
    tester,
  ) async {
    prepareView(tester);
    final original = MobileScannerPlatform.instance;
    final scanner = ScannerPlatformFixture();
    MobileScannerPlatform.instance = scanner;
    try {
      for (final senior in [false, true]) {
        for (final size in viewports) {
          final reason = '$size senior=$senior scale=1.8';
          final starts = scanner.starts;
          await showScanner(tester, state, size: size, senior: senior);
          expect(scanner.starts, starts + 1, reason: reason);
          expect(find.byType(MobileScanner), findsOneWidget, reason: reason);
          expect(
            find.byKey(const ValueKey('scanner-platform-fixture')),
            findsOneWidget,
            reason: reason,
          );
          for (final text in [
            find.byKey(const ValueKey('tv-scan-instructions')),
            find.text('将二维码放入框内，自动连接电视或确认授权。'),
          ]) {
            await expectReachable(tester, text, size: size, reason: reason);
          }
          final torch = find.byKey(const ValueKey('tv-torch'));
          await expectReachable(
            tester,
            torch,
            size: size,
            reason: reason,
            minimumHeight: senior ? 56 : 48,
          );
          final toggles = scanner.torchToggles;
          await tester.tap(torch);
          await tester.pumpAndSettle();
          expect(scanner.torchToggles, toggles + 1, reason: reason);
          expect(find.text('关闭手电筒'), findsOneWidget, reason: reason);
          expect(tester.takeException(), isNull, reason: reason);
        }
      }
      expect(nativeCalls, isEmpty);
      expect(api.requests, isEmpty);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
      MobileScannerPlatform.instance = original;
      await scanner.close();
    }
  });

  testWidgets(
    'camera failure settings and retry stay reachable with large text',
    (tester) async {
      prepareView(tester);
      final original = MobileScannerPlatform.instance;
      final scanner = ScannerPlatformFixture();
      MobileScannerPlatform.instance = scanner;
      try {
        for (final senior in [false, true]) {
          for (final size in viewports) {
            final reason = '$size senior=$senior scale=1.8';
            scanner.permissionDenied = true;
            await showScanner(tester, state, size: size, senior: senior);
            expect(find.text('还不能使用相机'), findsOneWidget, reason: reason);
            expect(
              find.byKey(const ValueKey('tv-scan-instructions')),
              findsNothing,
              reason: reason,
            );
            final settings = find.byKey(const ValueKey('tv-camera-settings'));
            await expectReachable(
              tester,
              settings,
              size: size,
              reason: reason,
              minimumHeight: senior ? 56 : 48,
            );
            final calls = nativeCalls.length;
            await tester.tap(settings);
            await tester.pumpAndSettle();
            expect(nativeCalls.length, calls + 1, reason: reason);
            expect(nativeCalls.last, 'settings', reason: reason);
            final retry = find.widgetWithText(OutlinedButton, '重试相机');
            await expectReachable(
              tester,
              retry,
              size: size,
              reason: reason,
              minimumHeight: senior ? 56 : 48,
            );
            scanner.permissionDenied = false;
            final starts = scanner.starts;
            await tester.tap(retry);
            await tester.pumpAndSettle();
            expect(scanner.starts, starts + 1, reason: reason);
            expect(
              find.byKey(const ValueKey('tv-scan-instructions')),
              findsOneWidget,
              reason: reason,
            );
            expect(tester.takeException(), isNull, reason: reason);
          }
        }
        expect(api.requests, isEmpty);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pumpAndSettle();
        MobileScannerPlatform.instance = original;
        await scanner.close();
      }
    },
  );
}
