import 'dart:ui';

import 'package:flutter/material.dart';

import '../core/app_state.dart';

const mint = Color(0xFF2CCB9A);
const coral = Color(0xFFFF6B70);
const ink = Color(0xFF172B28);
ThemeData singVerseTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final scheme = ColorScheme.fromSeed(seedColor: mint, brightness: brightness)
      .copyWith(
        primary: dark ? mint : const Color(0xFF087D5D),
        surface: dark ? const Color(0xFF101216) : const Color(0xFFF5F6FA),
      );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: scheme.surface,
    visualDensity: VisualDensity.standard,
    textTheme: const TextTheme(
      headlineLarge: TextStyle(
        fontSize: 32,
        height: 1.2,
        fontWeight: FontWeight.w700,
        letterSpacing: -1,
      ),
      headlineSmall: TextStyle(fontSize: 24, fontWeight: FontWeight.w700),
      titleMedium: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
      bodyLarge: TextStyle(fontSize: 16),
      bodySmall: TextStyle(fontSize: 13),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: dark ? const Color(0xFF1B1E25) : Colors.white,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: dark ? const Color(0xFF22262D) : Colors.white,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(18),
        borderSide: BorderSide.none,
      ),
      contentPadding: const EdgeInsets.all(18),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(minimumSize: const Size(48, 48)),
    ),
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: PredictiveBackPageTransitionsBuilder(),
      },
    ),
  );
}

class Glass extends StatelessWidget {
  const Glass({
    super.key,
    required this.state,
    required this.child,
    this.radius = 28,
  });
  final AppState state;
  final Widget child;
  final double radius;
  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final surface = DecoratedBox(
      decoration: BoxDecoration(
        color: (dark ? const Color(0xFF252932) : Colors.white).withValues(
          alpha: state.reduceTransparency ? 1 : .86,
        ),
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(
          color: (dark ? Colors.white : Colors.black).withValues(alpha: .07),
        ),
      ),
      child: Material(type: MaterialType.transparency, child: child),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: state.reduceTransparency
          ? surface
          : BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
              child: surface,
            ),
    );
  }
}

Future<void> uiTask(BuildContext context, Future<void> Function() task) async {
  try {
    await task();
  } catch (e) {
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(e.toString())));
    }
  }
}

class Notice extends StatelessWidget {
  const Notice(this.text, {super.key, this.action, this.label = '重试'});
  final String text, label;
  final VoidCallback? action;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 24),
    child: Column(
      children: [
        Icon(
          Icons.info_outline_rounded,
          size: 32,
          color: Theme.of(context).colorScheme.primary,
        ),
        const SizedBox(height: 12),
        Text(text, textAlign: TextAlign.center),
        if (action != null) TextButton(onPressed: action, child: Text(label)),
      ],
    ),
  );
}

class PageTracker extends NavigatorObserver {
  final current = ValueNotifier<String>('/');
  void update(Route<dynamic>? route) {
    final name = route is PageRoute ? route.settings.name ?? 'page' : 'modal';
    WidgetsBinding.instance.addPostFrameCallback((_) => current.value = name);
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      update(route);
  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) =>
      update(previousRoute);
  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) =>
      update(newRoute);
}
