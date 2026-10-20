enum LectureStatus {
  created('Создана'),
  uploaded('Загружена'),
  queued('В очереди'),
  processing('Обрабатывается'),
  done('Готово'),
  failed('Ошибка');

  const LectureStatus(this.label);

  final String label;

  static LectureStatus parse(String value) => LectureStatus.values.byName(value.toLowerCase());

  bool get inProgress => this == queued || this == processing;
}

enum ProcessingStage {
  transcribing('Распознавание речи'),
  readingBoard('Чтение доски'),
  generating('Написание конспекта'),
  assembling('Сборка');

  const ProcessingStage(this.label);

  final String label;

  static ProcessingStage? parse(String? value) => switch (value) {
        'TRANSCRIBING' => transcribing,
        'READING_BOARD' => readingBoard,
        'GENERATING' => generating,
        'ASSEMBLING' => assembling,
        _ => null,
      };
}

class Lecture {
  Lecture({
    required this.id,
    required this.title,
    required this.recordedAt,
    required this.status,
    required this.stage,
    required this.progress,
    required this.durationMs,
    required this.photoCount,
    required this.errorMessage,
  });

  factory Lecture.fromJson(Map<String, dynamic> json) => Lecture(
        id: json['id'],
        title: json['title'],
        recordedAt: DateTime.parse(json['recordedAt']).toLocal(),
        status: LectureStatus.parse(json['status']),
        stage: ProcessingStage.parse(json['stage']),
        progress: json['progress'],
        durationMs: json['durationMs'],
        photoCount: json['photoCount'],
        errorMessage: json['errorMessage'],
      );

  final String id;
  final String title;
  final DateTime recordedAt;
  final LectureStatus status;
  final ProcessingStage? stage;
  final int progress;
  final int? durationMs;
  final int photoCount;
  final String? errorMessage;
}

class BoardPhoto {
  BoardPhoto(this.id, this.offsetMs);

  factory BoardPhoto.fromJson(Map<String, dynamic> json) => BoardPhoto(json['id'], json['offsetMs']);

  final String id;
  final int offsetMs;
}

class TranscriptSegment {
  TranscriptSegment(this.startMs, this.text);

  final int startMs;
  final String text;
}

String formatDuration(int ms) {
  final d = Duration(milliseconds: ms);
  String two(int n) => n.toString().padLeft(2, '0');
  final minutes = two(d.inMinutes.remainder(60));
  final seconds = two(d.inSeconds.remainder(60));
  return d.inHours > 0 ? '${d.inHours}:$minutes:$seconds' : '$minutes:$seconds';
}
