import 'dart:convert';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';

import '../../core/api_error.dart';
import '../lectures/lectures_api.dart';

class DraftPhoto {
  DraftPhoto(this.path, this.offsetMs, {this.uploaded = false});

  factory DraftPhoto.fromJson(Map<String, dynamic> j) => DraftPhoto(j['path'], j['offsetMs'], uploaded: j['uploaded']);

  final String path;
  final int offsetMs;
  bool uploaded;

  Map<String, dynamic> toJson() => {'path': path, 'offsetMs': offsetMs, 'uploaded': uploaded};
}

/// Запись, ещё не дошедшая до сервера. Хранится на устройстве, поэтому лекцию можно записать
/// без интернета и загрузить позже. Загрузка идёт по шагам, каждый шаг запоминается —
/// после обрыва она продолжается с того же места.
class Draft {
  Draft({
    required this.id,
    required this.dir,
    required this.title,
    required this.recordedAt,
    required this.audioPath,
    required this.durationMs,
    required this.photos,
    this.remoteId,
    this.audioUploaded = false,
    this.submitted = false,
  });

  factory Draft.fromJson(Map<String, dynamic> j) => Draft(
        id: j['id'],
        dir: j['dir'],
        title: j['title'],
        recordedAt: DateTime.parse(j['recordedAt']),
        audioPath: j['audioPath'],
        durationMs: j['durationMs'],
        photos: (j['photos'] as List).map((p) => DraftPhoto.fromJson(p)).toList(),
        remoteId: j['remoteId'],
        audioUploaded: j['audioUploaded'],
        submitted: j['submitted'],
      );

  final String id;
  final String dir;
  final String title;
  final DateTime recordedAt;
  final String audioPath;
  final int? durationMs;
  final List<DraftPhoto> photos;

  // Сделанные шаги загрузки
  String? remoteId;
  bool audioUploaded;
  bool submitted;

  // Состояние текущей загрузки, не сохраняется
  bool uploading = false;
  double progress = 0;
  String? error;

  Map<String, dynamic> toJson() => {
        'id': id,
        'dir': dir,
        'title': title,
        'recordedAt': recordedAt.toIso8601String(),
        'audioPath': audioPath,
        'durationMs': durationMs,
        'photos': photos.map((p) => p.toJson()).toList(),
        'remoteId': remoteId,
        'audioUploaded': audioUploaded,
        'submitted': submitted,
      };

  /// Каталог для файлов новой записи.
  static Future<Directory> newDir(String id) async {
    final root = await getApplicationSupportDirectory();
    return Directory('${root.path}/drafts/$id').create(recursive: true);
  }
}

class DraftsController extends Notifier<List<Draft>> {
  @override
  List<Draft> build() {
    _load();
    return [];
  }

  Future<File> get _index async => File('${(await getApplicationSupportDirectory()).path}/drafts/index.json');

  Future<void> _load() async {
    final file = await _index;
    if (!await file.exists()) return;
    final items = jsonDecode(await file.readAsString()) as List;
    state = items.map((j) => Draft.fromJson(j)).toList();
    // Незавершённые загрузки продолжаем при запуске
    for (final draft in state) {
      upload(draft.id);
    }
  }

  Future<void> _save() async {
    final file = await _index;
    await file.parent.create(recursive: true);
    await file.writeAsString(jsonEncode(state.map((d) => d.toJson()).toList()));
  }

  void _changed() => state = [...state];

  Future<void> add(Draft draft) async {
    state = [draft, ...state];
    await _save();
    await upload(draft.id);
  }

  Future<void> upload(String id) async {
    final draft = state.where((d) => d.id == id).firstOrNull;
    if (draft == null || draft.uploading) return;
    final api = ref.read(lecturesApiProvider);

    draft
      ..uploading = true
      ..error = null
      ..progress = 0;
    _changed();
    try {
      draft.remoteId ??= await api.create(draft.title, draft.recordedAt);
      await _save();

      if (!draft.audioUploaded) {
        await api.uploadAudio(draft.remoteId!, draft.audioPath, durationMs: draft.durationMs, onProgress: (p) {
          draft.progress = p * 0.9;
          _changed();
        });
        draft.audioUploaded = true;
        await _save();
      }

      for (final photo in draft.photos.where((p) => !p.uploaded)) {
        await api.addPhoto(draft.remoteId!, photo.path, photo.offsetMs);
        photo.uploaded = true;
        await _save();
      }

      if (!draft.submitted) {
        await api.submit(draft.remoteId!);
        draft.submitted = true;
      }

      // Всё на сервере — локальные файлы больше не нужны
      state = state.where((d) => d.id != id).toList();
      await _save();
      await Directory(draft.dir).delete(recursive: true).catchError((_) => Directory(draft.dir));
      ref.invalidate(lecturesProvider);
    } catch (e) {
      draft
        ..uploading = false
        ..error = ApiError.from(e).message;
      _changed();
      await _save();
    }
  }

  /// Удаляет черновик вместе с файлами (то, что уже на сервере, остаётся).
  Future<void> discard(String id) async {
    final draft = state.firstWhere((d) => d.id == id);
    state = state.where((d) => d.id != id).toList();
    await _save();
    await Directory(draft.dir).delete(recursive: true).catchError((_) => Directory(draft.dir));
  }
}

final draftsProvider = NotifierProvider<DraftsController, List<Draft>>(DraftsController.new);
