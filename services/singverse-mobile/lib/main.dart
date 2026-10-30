import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app/design.dart';
import 'app/shell.dart';
import 'core/api.dart';
import 'core/app_state.dart';
import 'core/local_store.dart';
import 'core/models.dart';
import 'features/player/player_page.dart';
import 'features/library/widgets.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  try {
    final state = AppState(
      api: NativeNasApi(),
      store: await LocalStore.open(),
      preferences: await SharedPreferences.getInstance(),
    );
    runApp(SingVerseApp(state: state));
    await state.initialize();
  } catch (_) {
    runApp(
      MaterialApp(
        theme: singVerseTheme(Brightness.light),
        home: const Scaffold(
          body: SafeArea(
            child: Center(
              child: Padding(
                padding: EdgeInsets.all(20),
                child: Text('无法打开本机数据。请检查手机存储空间后重新启动 SingVerse。'),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class SingVerseApp extends StatefulWidget {
  const SingVerseApp({super.key, required this.state});
  final AppState state;
  @override
  State<SingVerseApp> createState() => _SingVerseAppState();
}

class _SingVerseAppState extends State<SingVerseApp> {
  final navigator = GlobalKey<NavigatorState>();
  final pages = PageTracker();
  int stageRequest = 0;
  @override
  void initState() {
    super.initState();
    widget.state.addListener(openRequestedStage);
    openRequestedStage();
  }

  void openRequestedStage() {
    if (widget.state.stageRequests == stageRequest) return;
    stageRequest = widget.state.stageRequests;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !['player', 'review'].contains(pages.current.value)) {
        navigator.currentState?.push(
          MaterialPageRoute<void>(
            settings: const RouteSettings(name: 'player'),
            builder: (_) => performancePage(widget.state),
          ),
        );
      }
    });
  }

  @override
  void dispose() {
    widget.state.removeListener(openRequestedStage);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.state,
    builder: (context, _) {
      final state = widget.state;
      return MaterialApp(
        title: 'SingVerse',
        debugShowCheckedModeBanner: false,
        navigatorKey: navigator,
        navigatorObservers: [pages],
        theme: singVerseTheme(Brightness.light),
        darkTheme: singVerseTheme(Brightness.dark),
        themeMode: state.themeMode,
        builder: (context, child) => ValueListenableBuilder<String>(
          valueListenable: pages.current,
          builder: (context, page, _) => Stack(
            children: [
              child!,
              if (state.current != null && page == 'page')
                Positioned(
                  left: 20,
                  right: 20,
                  bottom: 16 + MediaQuery.paddingOf(context).bottom,
                  child: Material(
                    type: MaterialType.transparency,
                    child: Glass(
                      state: state,
                      radius: 22,
                      child: ListTile(
                        leading: Artwork(
                          state: state,
                          path: state.current!.cover,
                          size: 44,
                        ),
                        title: Text(
                          state.current!.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(
                          state.recording
                              ? '● 正在录唱 · ${durationLabel((state.singing['recordedMs'] as num? ?? 0).toInt())}'
                              : '返回演唱 · ${durationLabel(state.positionMs)}',
                        ),
                        trailing: IconButton(
                          icon: Icon(
                            state.isPlaying
                                ? Icons.pause_rounded
                                : Icons.play_arrow_rounded,
                          ),
                          onPressed: () =>
                              state.command(state.isPlaying ? 'pause' : 'play'),
                        ),
                        onTap: () => navigator.currentState!.push(
                          MaterialPageRoute<void>(
                            settings: const RouteSettings(name: 'player'),
                            builder: (_) => performancePage(state),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
        home: state.initialized
            ? AppShell(state: state)
            : const Scaffold(body: Center(child: CircularProgressIndicator())),
      );
    },
  );
}
