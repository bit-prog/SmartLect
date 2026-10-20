import 'package:dio/dio.dart';

/// Ошибка API с понятным пользователю текстом.
class ApiError implements Exception {
  ApiError(this.message, {this.status});

  final String message;
  final int? status;

  /// Сервер отвечает в формате problem+json: `{"title": ..., "detail": ...}`.
  factory ApiError.from(Object error) {
    if (error is ApiError) return error;
    if (error is DioException) {
      final response = error.response;
      if (response == null) {
        return ApiError('Нет связи с сервером. Проверьте адрес сервера и сеть.');
      }
      final data = response.data;
      final detail = data is Map ? (data['detail'] ?? data['title']) : null;
      return ApiError(_translate(detail?.toString()) ?? 'Ошибка сервера (${response.statusCode})',
          status: response.statusCode);
    }
    return ApiError(error.toString());
  }

  static String? _translate(String? detail) => switch (detail) {
        'Invalid email or password' => 'Неверный email или пароль',
        'Email is already registered' => 'Этот email уже зарегистрирован',
        'Upload audio first' => 'Сначала загрузите аудио',
        'Lecture is being processed' => 'Лекция обрабатывается, удалить её сейчас нельзя',
        'Invalid request content.' => 'Проверьте введённые данные',
        _ => detail,
      };

  @override
  String toString() => message;
}
