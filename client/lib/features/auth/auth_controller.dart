import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import '../../core/token_store.dart';

enum AuthStatus { unknown, signedOut, signedIn }

class AuthController extends Notifier<AuthStatus> {
  ApiClient get _api => ref.read(apiClientProvider);
  TokenStore get _store => ref.read(tokenStoreProvider);

  @override
  AuthStatus build() {
    _api.onSessionExpired = () => state = AuthStatus.signedOut;
    _restore();
    return AuthStatus.unknown;
  }

  Future<void> _restore() async {
    state = await _store.read() == null ? AuthStatus.signedOut : AuthStatus.signedIn;
  }

  Future<void> login(String email, String password) => _authenticate('/api/auth/login', email, password);

  Future<void> register(String email, String password) => _authenticate('/api/auth/register', email, password);

  Future<void> _authenticate(String path, String email, String password) async {
    try {
      final response = await _api.dio.post<Map<String, dynamic>>(path,
          data: {'email': email.trim(), 'password': password}, options: ApiClient.anonymous);
      await _store.save(Tokens(response.data!['accessToken'], response.data!['refreshToken']));
      state = AuthStatus.signedIn;
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  Future<void> logout() async {
    final tokens = await _store.read();
    if (tokens != null) {
      // Отзываем refresh-токен на сервере; если сервер недоступен — всё равно выходим
      await _api.dio
          .post('/api/auth/logout', data: {'refreshToken': tokens.refreshToken}, options: ApiClient.anonymous)
          .catchError((_) => Response(requestOptions: RequestOptions()));
    }
    await _store.clear();
    state = AuthStatus.signedOut;
  }
}

final authProvider = NotifierProvider<AuthController, AuthStatus>(AuthController.new);

/// Email текущего пользователя.
final currentEmailProvider = FutureProvider<String>((ref) async {
  ref.watch(authProvider);
  final response = await ref.read(apiClientProvider).dio.get<Map<String, dynamic>>('/api/me');
  return response.data!['email'] as String;
});
