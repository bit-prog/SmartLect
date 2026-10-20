import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api_client.dart';
import '../../core/api_error.dart';
import 'models.dart';

/// Обёртка над `/api/lectures`. Все ошибки превращаются в [ApiError].
class LecturesApi {
  LecturesApi(this._client);

  final ApiClient _client;

  Dio get _dio => _client.dio;

  Future<T> _call<T>(Future<T> Function() request) async {
    try {
      return await request();
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  Future<List<Lecture>> list() => _call(() async {
        final r = await _dio.get<Map<String, dynamic>>('/api/lectures', queryParameters: {'size': 100});
        return (r.data!['items'] as List).map((j) => Lecture.fromJson(j)).toList();
      });

  Future<Lecture> get(String id) => _call(() async {
        final r = await _dio.get<Map<String, dynamic>>('/api/lectures/$id');
        return Lecture.fromJson(r.data!);
      });

  Future<String> create(String title, DateTime recordedAt) => _call(() async {
        final r = await _dio.post<Map<String, dynamic>>('/api/lectures', data: {
          'title': title,
          'source': 'MOBILE',
          'recordedAt': recordedAt.toUtc().toIso8601String(),
        });
        return r.data!['id'] as String;
      });

  Future<void> uploadAudio(String id, String path, {int? durationMs, void Function(double)? onProgress}) =>
      _call(() => _dio.put('/api/lectures/$id/audio',
          data: FormData.fromMap({'file': _file(path)}),
          queryParameters: {'durationMs': ?durationMs},
          onSendProgress: (sent, total) => onProgress?.call(total > 0 ? sent / total : 0)));

  Future<void> addPhoto(String id, String path, int offsetMs) => _call(() => _dio.post('/api/lectures/$id/photos',
      data: FormData.fromMap({'file': _file(path), 'offsetMs': offsetMs})));

  Future<void> submit(String id) => _call(() => _dio.post('/api/lectures/$id/submit'));

  Future<void> delete(String id) => _call(() => _dio.delete('/api/lectures/$id'));

  Future<String> notes(String id) => _call(() async {
        final r = await _dio.get<String>('/api/lectures/$id/notes', options: Options(responseType: ResponseType.plain));
        return r.data!;
      });

  Future<List<TranscriptSegment>> transcript(String id) => _call(() async {
        final r = await _dio.get<Map<String, dynamic>>('/api/lectures/$id/transcript');
        return (r.data!['segments'] as List)
            .map((s) => TranscriptSegment(s['startMs'] as int, s['text'] as String))
            .toList();
      });

  Future<List<BoardPhoto>> photos(String id) => _call(() async {
        final r = await _dio.get<List<dynamic>>('/api/lectures/$id/photos');
        return r.data!.map((j) => BoardPhoto.fromJson(j)).toList();
      });

  String photoUrl(String serverUrl, String lectureId, String photoId) =>
      '$serverUrl/api/lectures/$lectureId/photos/$photoId/content';

  static MultipartFile _file(String path) =>
      MultipartFile.fromFileSync(path, contentType: DioMediaType.parse(contentTypeFor(path)));
}

/// Тип файла по расширению: сервер принимает только audio/* и image/*.
String contentTypeFor(String path) {
  final ext = path.split('.').last.toLowerCase();
  return switch (ext) {
    'm4a' || 'mp4' || 'aac' => 'audio/mp4',
    'mp3' => 'audio/mpeg',
    'wav' => 'audio/wav',
    'ogg' || 'oga' || 'opus' => 'audio/ogg',
    'flac' => 'audio/flac',
    'webm' => 'audio/webm',
    'png' => 'image/png',
    'heic' => 'image/heic',
    'webp' => 'image/webp',
    _ => 'image/jpeg',
  };
}

final lecturesApiProvider = Provider<LecturesApi>((ref) => LecturesApi(ref.watch(apiClientProvider)));

final lecturesProvider = FutureProvider<List<Lecture>>((ref) => ref.watch(lecturesApiProvider).list());

final lectureProvider = FutureProvider.autoDispose.family<Lecture, String>(
  (ref, id) => ref.watch(lecturesApiProvider).get(id),
);
