import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Адрес сервера. Для телефона в одной сети с ноутбуком — `http://<IP ноутбука>:8081`.
class ServerSettings extends Notifier<String> {
  static const _key = 'server_url';

  /// Значение по умолчанию: из `--dart-define=API_URL=...`, иначе localhost
  /// (эмулятор Android видит компьютер как 10.0.2.2).
  static String get defaultUrl {
    const fromEnv = String.fromEnvironment('API_URL');
    if (fromEnv.isNotEmpty) return fromEnv;
    return Platform.isAndroid ? 'http://10.0.2.2:8081' : 'http://localhost:8081';
  }

  late SharedPreferences _prefs;

  @override
  String build() {
    _prefs = ref.watch(sharedPreferencesProvider);
    return _prefs.getString(_key) ?? defaultUrl;
  }

  Future<void> set(String url) async {
    final normalized = url.trim().replaceAll(RegExp(r'/+$'), '');
    await _prefs.setString(_key, normalized);
    state = normalized;
  }
}

final sharedPreferencesProvider = Provider<SharedPreferences>(
  (ref) => throw UnimplementedError('Overridden in main()'),
);

final serverUrlProvider = NotifierProvider<ServerSettings, String>(ServerSettings.new);
