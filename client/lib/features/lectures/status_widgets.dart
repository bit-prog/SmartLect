import 'package:flutter/material.dart';

import 'models.dart';

class StatusChip extends StatelessWidget {
  const StatusChip(this.status, {super.key});

  final LectureStatus status;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (background, foreground) = switch (status) {
      LectureStatus.done => (scheme.primaryContainer, scheme.onPrimaryContainer),
      LectureStatus.failed => (scheme.errorContainer, scheme.onErrorContainer),
      LectureStatus.queued || LectureStatus.processing => (scheme.tertiaryContainer, scheme.onTertiaryContainer),
      _ => (scheme.surfaceContainerHighest, scheme.onSurfaceVariant),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(color: background, borderRadius: BorderRadius.circular(12)),
      child: Text(status.label, style: TextStyle(color: foreground, fontSize: 12, fontWeight: FontWeight.w600)),
    );
  }
}

/// Этапы обработки: пройденные отмечены, у текущего — процент.
class StageProgress extends StatelessWidget {
  const StageProgress(this.lecture, {super.key});

  final Lecture lecture;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final current = lecture.stage;
    return Column(
      children: [
        for (final stage in ProcessingStage.values)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(
              children: [
                SizedBox.square(
                  dimension: 24,
                  child: switch (_state(stage, current)) {
                    _StageState.done => Icon(Icons.check_circle, color: theme.colorScheme.primary),
                    _StageState.active => const Padding(
                        padding: EdgeInsets.all(3), child: CircularProgressIndicator(strokeWidth: 2.5)),
                    _StageState.pending => Icon(Icons.radio_button_unchecked, color: theme.disabledColor),
                  },
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(stage.label),
                      if (stage == current && lecture.status == LectureStatus.processing) ...[
                        const SizedBox(height: 4),
                        LinearProgressIndicator(value: lecture.progress / 100),
                      ],
                    ],
                  ),
                ),
                if (stage == current && lecture.status == LectureStatus.processing)
                  Padding(padding: const EdgeInsets.only(left: 12), child: Text('${lecture.progress}%')),
              ],
            ),
          ),
      ],
    );
  }

  _StageState _state(ProcessingStage stage, ProcessingStage? current) {
    if (current == null) return _StageState.pending;
    if (stage.index < current.index) return _StageState.done;
    return stage == current ? _StageState.active : _StageState.pending;
  }
}

enum _StageState { done, active, pending }
