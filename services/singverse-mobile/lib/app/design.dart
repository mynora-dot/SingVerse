import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../core/app_state.dart';

const berry = Color(0xFF3159D9);
const coral = Color(0xFFFF5F6D);
const ink = Color(0xFF1C1C1E);

ThemeData singVerseTheme(Brightness brightness, {bool senior = false}) {
  final dark = brightness == Brightness.dark;
  final scheme = ColorScheme.fromSeed(seedColor: berry, brightness: brightness)
      .copyWith(
        primary: dark ? const Color(0xFF8CA9FF) : berry,
        onPrimary: dark ? const Color(0xFF101D40) : Colors.white,
        primaryContainer: dark
            ? const Color(0xFF27344E)
            : const Color(0xFFEDF1FF),
        onPrimaryContainer: dark
            ? const Color(0xFFDCE5FF)
            : const Color(0xFF2547B2),
        surface: dark ? const Color(0xFF1C1C1E) : Colors.white,
        onSurface: dark ? const Color(0xFFF7F7F9) : ink,
        onSurfaceVariant: dark
            ? const Color(0xFFBEBEC4)
            : const Color(0xFF6E6E73),
        secondaryContainer: dark
            ? const Color(0xFF2C2C2E)
            : const Color(0xFFECECF0),
        onSecondaryContainer: dark ? const Color(0xFFF7F7F9) : ink,
        outline: dark ? const Color(0xFF5A5A60) : const Color(0xFF8E8E93),
        outlineVariant: dark
            ? const Color(0xFF38383A)
            : const Color(0xFFD9D9DE),
        error: dark ? coral : const Color(0xFFC81E3C),
        onError: dark ? const Color(0xFF3B0712) : Colors.white,
      );
  final background = dark ? Colors.black : const Color(0xFFF7F7F9);
  final target = senior ? 56.0 : 48.0;
  final typography = Typography.material2021();
  final textTheme = (dark ? typography.white : typography.black)
      .merge(
        TextTheme(
          headlineLarge: TextStyle(
            fontSize: senior ? 32 : 30,
            height: 1.18,
            fontWeight: FontWeight.w700,
            letterSpacing: -.3,
          ),
          headlineSmall: TextStyle(
            fontSize: senior ? 26 : 22,
            height: 1.24,
            fontWeight: FontWeight.w700,
            letterSpacing: -.15,
          ),
          titleLarge: TextStyle(
            fontSize: senior ? 23 : 16,
            height: 1.28,
            fontWeight: FontWeight.w600,
          ),
          titleMedium: TextStyle(
            fontSize: senior ? 20 : 15,
            height: 1.3,
            fontWeight: FontWeight.w600,
          ),
          bodyLarge: TextStyle(fontSize: senior ? 21 : 15, height: 1.4),
          bodyMedium: TextStyle(fontSize: senior ? 19 : 14, height: 1.4),
          bodySmall: TextStyle(fontSize: senior ? 17 : 12, height: 1.4),
          labelLarge: TextStyle(
            fontSize: senior ? 19 : 15,
            height: 1.25,
            fontWeight: FontWeight.w600,
          ),
          labelMedium: TextStyle(
            fontSize: senior ? 17 : 13,
            fontWeight: FontWeight.w500,
          ),
        ),
      )
      .apply(bodyColor: scheme.onSurface, displayColor: scheme.onSurface);
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: background,
    textTheme: textTheme,
    visualDensity: VisualDensity.standard,
    splashFactory: InkSparkle.splashFactory,
    appBarTheme: AppBarTheme(
      backgroundColor: background,
      surfaceTintColor: Colors.transparent,
      foregroundColor: scheme.onSurface,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      toolbarHeight: senior ? 68 : 56,
      titleSpacing: 20,
      titleTextStyle: textTheme.titleMedium?.copyWith(
        fontSize: senior ? 23 : 18,
        fontWeight: FontWeight.w600,
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: scheme.surface,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
    ),
    dividerTheme: DividerThemeData(
      color: scheme.outlineVariant,
      space: 1,
      thickness: .5,
    ),
    listTileTheme: ListTileThemeData(
      minVerticalPadding: senior ? 16 : 9,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16),
      iconColor: scheme.onSurfaceVariant,
      titleTextStyle: textTheme.titleMedium,
      subtitleTextStyle: textTheme.bodySmall?.copyWith(
        color: scheme.onSurfaceVariant,
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.secondaryContainer,
      hintStyle: textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
      prefixIconColor: scheme.onSurfaceVariant,
      suffixIconColor: scheme.onSurfaceVariant,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(11),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(11),
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(11),
        borderSide: BorderSide(color: scheme.primary, width: 1.4),
      ),
      contentPadding: EdgeInsets.symmetric(
        horizontal: 14,
        vertical: senior ? 18 : 10,
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: Size(target, target),
        padding: const EdgeInsets.symmetric(horizontal: 20),
        textStyle: textTheme.labelLarge,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: Size(target, target),
        padding: const EdgeInsets.symmetric(horizontal: 18),
        textStyle: textTheme.labelLarge,
        backgroundColor: scheme.secondaryContainer,
        side: BorderSide.none,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        minimumSize: Size(target, target),
        textStyle: textTheme.labelLarge,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(minimumSize: Size(target, target)),
    ),
    chipTheme: ChipThemeData(
      padding: EdgeInsets.symmetric(horizontal: 10, vertical: senior ? 12 : 7),
      labelStyle: textTheme.labelLarge,
      side: BorderSide.none,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      selectedColor: scheme.primaryContainer,
      checkmarkColor: scheme.onPrimaryContainer,
    ),
    sliderTheme: SliderThemeData(
      trackHeight: 3,
      thumbShape: RoundSliderThumbShape(enabledThumbRadius: senior ? 9 : 7),
      overlayShape: RoundSliderOverlayShape(overlayRadius: senior ? 20 : 16),
      activeTrackColor: scheme.primary,
      thumbColor: scheme.primary,
      inactiveTrackColor: scheme.outlineVariant,
    ),
    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: scheme.primary,
      linearTrackColor: scheme.outlineVariant,
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: !senior,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
      titleTextStyle: textTheme.titleLarge,
      contentTextStyle: textTheme.bodyMedium,
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: dark ? const Color(0xFFEEEAE5) : const Color(0xFF282624),
      contentTextStyle: textTheme.bodyMedium?.copyWith(
        color: dark ? ink : Colors.white,
      ),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    ),
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: PredictiveBackPageTransitionsBuilder(),
      },
    ),
  );
}

class ContentWidth extends StatelessWidget {
  const ContentWidth({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topCenter,
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 720),
      child: child,
    ),
  );
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key, this.trailing});
  final String text;
  final Widget? trailing;
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Text(text, style: Theme.of(context).textTheme.titleLarge),
      ),
      ?trailing,
    ],
  );
}

class SheetHeader extends StatelessWidget {
  const SheetHeader(this.title, {super.key, this.done = '完成'});
  final String title, done;
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: Text(title, style: Theme.of(context).textTheme.headlineSmall),
      ),
      TextButton(onPressed: () => Navigator.pop(context), child: Text(done)),
    ],
  );
}

class Glass extends StatelessWidget {
  const Glass({
    super.key,
    required this.state,
    required this.child,
    this.radius = 20,
  });
  final AppState state;
  final Widget child;
  final double radius;
  @override
  Widget build(BuildContext context) {
    final solid =
        state.reduceTransparency ||
        state.senior ||
        MediaQuery.disableAnimationsOf(context) ||
        MediaQuery.highContrastOf(context);
    final scheme = Theme.of(context).colorScheme;
    final surface = DecoratedBox(
      decoration: BoxDecoration(
        color: scheme.surface.withValues(alpha: solid ? 1 : .94),
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: scheme.outlineVariant.withValues(alpha: .8)),
      ),
      child: Material(type: MaterialType.transparency, child: child),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: solid
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
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            e is PlatformException
                ? e.message ?? '操作未完成，请重试。'
                : e is FormatException
                ? e.message
                : e.toString(),
          ),
        ),
      );
    }
  }
}

class Notice extends StatelessWidget {
  const Notice(this.text, {super.key, this.action, this.label = '重试'});
  final String text, label;
  final VoidCallback? action;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 20),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(
          Icons.music_note_rounded,
          size: 30,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
        const SizedBox(height: 12),
        Text(
          text,
          textAlign: TextAlign.center,
          style: Theme.of(context).textTheme.bodyMedium
              ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
        ),
        if (action != null) ...[
          const SizedBox(height: 8),
          TextButton(onPressed: action, child: Text(label)),
        ],
      ],
    ),
  );
}

class PageTracker extends NavigatorObserver {
  final current = ValueNotifier<String>('/');
  final modalOpen = ValueNotifier<bool>(false);
  final popups = <Route<dynamic>>[];
  void popup(Route<dynamic> route, bool open) {
    if (route is! PopupRoute) return;
    open ? popups.add(route) : popups.remove(route);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      modalOpen.value = popups.isNotEmpty;
    });
  }

  void update(Route<dynamic>? route) {
    if (route is! PageRoute) return;
    final name = route.settings.name ?? 'page';
    WidgetsBinding.instance.addPostFrameCallback((_) {
      current.value = name;
    });
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    popup(route, true);
    update(route);
  }

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    popup(route, false);
    update(previousRoute);
  }

  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) =>
      update(newRoute);
}
