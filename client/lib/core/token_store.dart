import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class Tokens {
  const Tokens(this.accessToken, this.refreshToken);

  final String accessToken;
  final String refreshToken;
}

/// Токены в Keychain (iOS/macOS) и зашифрованном хранилище (Android).
class TokenStore {
  // Обычный Keychain на macOS: data protection keychain требует подписанное приложение
  static const _storage = FlutterSecureStorage(
    mOptions: MacOsOptions(usesDataProtectionKeychain: false),
  );
  static const _access = 'access_token';
  static const _refresh = 'refresh_token';

  Tokens? _cached;

  Future<Tokens?> read() async {
    if (_cached != null) return _cached;
    final access = await _storage.read(key: _access);
    final refresh = await _storage.read(key: _refresh);
    if (access == null || refresh == null) return null;
    return _cached = Tokens(access, refresh);
  }

  Future<void> save(Tokens tokens) async {
    _cached = tokens;
    await _storage.write(key: _access, value: tokens.accessToken);
    await _storage.write(key: _refresh, value: tokens.refreshToken);
  }

  Future<void> clear() async {
    _cached = null;
    await _storage.delete(key: _access);
    await _storage.delete(key: _refresh);
  }
}

final tokenStoreProvider = Provider<TokenStore>((ref) => TokenStore());
