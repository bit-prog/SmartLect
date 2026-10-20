import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'core/settings.dart';
import 'features/auth/auth_controller.dart';
import 'router.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('ru');
  final prefs = await SharedPreferences.getInstance();
  runApp(ProviderScope(
    overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
    // Ошибки API показываем сразу, без автоматических повторов Riverpod
    retry: (_, _) => null,
    child: const SmartLectApp(),
  ));
}

class SmartLectApp extends ConsumerWidget {
  const SmartLectApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Пока не прочитаны сохранённые токены, показываем заставку, а не экран входа
    if (ref.watch(authProvider) == AuthStatus.unknown) {
      return const MaterialApp(home: Scaffold(body: Center(child: CircularProgressIndicator())));
    }
    return MaterialApp.router(
      title: 'SmartLect',
      routerConfig: ref.watch(routerProvider),
      theme: _theme(Brightness.light),
      darkTheme: _theme(Brightness.dark),
      locale: const Locale('ru'),
      supportedLocales: const [Locale('ru'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
    );
  }

  ThemeData _theme(Brightness brightness) => ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF3F51B5), brightness: brightness),
        useMaterial3: true,
      );
}
