import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'settings.dart';
import 'token_store.dart';

/// HTTP-клиент API: подставляет адрес сервера и access-токен,
/// при 401 один раз обновляет токены и повторяет запрос.
class ApiClient {
  ApiClient(this._ref) {
    dio.interceptors.add(QueuedInterceptorsWrapper(
      onRequest: (options, handler) async {
        options.baseUrl = _ref.read(serverUrlProvider);
        final tokens = await _store.read();
        if (tokens != null && options.extra['auth'] != false) {
          options.headers['Authorization'] = 'Bearer ${tokens.accessToken}';
        }
        handler.next(options);
      },
      onError: (error, handler) async {
        final request = error.requestOptions;
        final canRefresh = error.response?.statusCode == 401 &&
            request.extra['auth'] != false &&
            request.extra['retried'] != true;
        if (!canRefresh) return handler.next(error);

        final refreshed = await _refresh();
        if (!refreshed) {
          onSessionExpired?.call();
          return handler.next(error);
        }
        try {
          final data = request.data;
          final tokens = await _store.read();
          // FormData читается один раз — для повтора нужна копия
          final retry = request.copyWith(data: data is FormData ? data.clone() : data)
            ..extra['retried'] = true
            ..headers['Authorization'] = 'Bearer ${tokens!.accessToken}';
          handler.resolve(await _raw.fetch(retry));
        } on DioException catch (e) {
          handler.next(e);
        }
      },
    ));
  }

  final Ref _ref;
  final dio = Dio(_options);

  /// Без интерсепторов: обновление токена и повтор запроса изнутри QueuedInterceptor
  /// через тот же [dio] встали бы в его же очередь и зависли.
  final _raw = Dio(_options);

  static final _options = BaseOptions(
    connectTimeout: const Duration(seconds: 10),
    // Загрузка аудио лекции может идти долго
    sendTimeout: const Duration(minutes: 30),
    receiveTimeout: const Duration(minutes: 2),
  );

  /// Вызывается, когда refresh-токен больше не действует — нужно войти заново.
  void Function()? onSessionExpired;

  TokenStore get _store => _ref.read(tokenStoreProvider);

  /// Опции для запросов без токена (вход, регистрация).
  static Options get anonymous => Options(extra: {'auth': false});

  Future<bool> _refresh() async {
    final tokens = await _store.read();
    if (tokens == null) return false;
    try {
      final response = await _raw.post<Map<String, dynamic>>(
        '${_ref.read(serverUrlProvider)}/api/auth/refresh',
        data: {'refreshToken': tokens.refreshToken},
      );
      await _store.save(Tokens(response.data!['accessToken'], response.data!['refreshToken']));
      return true;
    } on DioException {
      await _store.clear();
      return false;
    }
  }

  /// Текущий заголовок авторизации — для Image.network.
  Future<Map<String, String>> authHeaders() async {
    final tokens = await _store.read();
    return tokens == null ? {} : {'Authorization': 'Bearer ${tokens.accessToken}'};
  }
}

final apiClientProvider = Provider<ApiClient>((ref) => ApiClient(ref));
