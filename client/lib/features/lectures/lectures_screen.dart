import 'dart:async';
import 'dart:io';

import 'package:file_selector/file_selector.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../core/server_dialog.dart';
import '../auth/auth_controller.dart';
import '../recording/drafts.dart';
import 'lectures_api.dart';
import 'models.dart';
import 'status_widgets.dart';

final _dateFormat = DateFormat('d MMMM, HH:mm', 'ru');

class LecturesScreen extends ConsumerStatefulWidget {
  const LecturesScreen({super.key});

  @override
  ConsumerState<LecturesScreen> createState() => _LecturesScreenState();
}

class _LecturesScreenState extends ConsumerState<LecturesScreen> {
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    // Пока есть лекции в обработке — обновляем список, чтобы был виден прогресс
    _poll = Timer.periodic(const Duration(seconds: 5), (_) {
      final lectures = ref.read(lecturesProvider).value ?? [];
      if (lectures.any((l) => l.status.inProgress)) ref.invalidate(lecturesProvider);
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _importAudio() async {
    const audio = XTypeGroup(
      label: 'Аудио',
      extensions: ['m4a', 'mp3', 'wav', 'ogg', 'opus', 'flac', 'webm', 'aac'],
      uniformTypeIdentifiers: ['public.audio'],
    );
    final file = await openFile(acceptedTypeGroups: [audio]);
    if (file == null || !mounted) return;

    final name = file.name.replaceAll(RegExp(r'\.[^.]+$'), '');
    final title = await _askTitle(name);
    if (title == null) return;

    final id = DateTime.now().millisecondsSinceEpoch.toString();
    final dir = await Draft.newDir(id);
    final extension = file.name.split('.').last;
    final copy = await File(file.path).copy('${dir.path}/audio.$extension');
    await ref.read(draftsProvider.notifier).add(Draft(
          id: id,
          dir: dir.path,
          title: title,
          recordedAt: DateTime.now(),
          audioPath: copy.path,
          durationMs: null, // длительность определит распознавание
          photos: [],
        ));
  }

  Future<String?> _askTitle(String initial) {
    final controller = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Название лекции'),
        content: TextField(controller: controller, autofocus: true),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text.trim().isEmpty ? initial : controller.text.trim()),
            child: const Text('Загрузить'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final lectures = ref.watch(lecturesProvider);
    final drafts = ref.watch(draftsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Лекции'),
        actions: [
          IconButton(
            tooltip: 'Загрузить аудиофайл',
            icon: const Icon(Icons.upload_file),
            onPressed: _importAudio,
          ),
          PopupMenuButton<String>(
            onSelected: (value) => switch (value) {
              'server' => showServerDialog(context, ref),
              'logout' => ref.read(authProvider.notifier).logout(),
              _ => null,
            },
            itemBuilder: (context) => [
              PopupMenuItem(
                enabled: false,
                child: Text(ref.watch(currentEmailProvider).value ?? '…'),
              ),
              const PopupMenuItem(value: 'server', child: Text('Адрес сервера')),
              const PopupMenuItem(value: 'logout', child: Text('Выйти')),
            ],
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push('/record'),
        icon: const Icon(Icons.mic),
        label: const Text('Записать'),
      ),
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(lecturesProvider.future),
        child: lectures.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => _Message(
            icon: Icons.cloud_off,
            text: e.toString(),
            action: TextButton(onPressed: () => ref.invalidate(lecturesProvider), child: const Text('Повторить')),
          ),
          data: (items) => items.isEmpty && drafts.isEmpty
              ? const _Message(
                  icon: Icons.mic_none,
                  text: 'Лекций пока нет.\nНажмите «Записать» на лекции или загрузите аудиофайл.')
              : ListView(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 96),
                  children: [
                    for (final draft in drafts) _DraftCard(draft),
                    for (final lecture in items) _LectureCard(lecture),
                  ],
                ),
        ),
      ),
    );
  }
}

class _LectureCard extends StatelessWidget {
  const _LectureCard(this.lecture);

  final Lecture lecture;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final details = [
      _dateFormat.format(lecture.recordedAt),
      if (lecture.durationMs != null) formatDuration(lecture.durationMs!),
      if (lecture.photoCount > 0) 'фото: ${lecture.photoCount}',
    ].join(' · ');

    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => context.push('/lectures/${lecture.id}'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(child: Text(lecture.title, style: theme.textTheme.titleMedium)),
                  const SizedBox(width: 8),
                  StatusChip(lecture.status),
                ],
              ),
              const SizedBox(height: 4),
              Text(details, style: theme.textTheme.bodySmall),
              if (lecture.status.inProgress && lecture.stage != null) ...[
                const SizedBox(height: 12),
                Text('${lecture.stage!.label} · ${lecture.progress}%', style: theme.textTheme.bodySmall),
                const SizedBox(height: 4),
                LinearProgressIndicator(
                  value: lecture.status == LectureStatus.queued ? null : lecture.progress / 100,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _DraftCard extends ConsumerWidget {
  const _DraftCard(this.draft);

  final Draft draft;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final controller = ref.read(draftsProvider.notifier);
    return Card(
      color: theme.colorScheme.surfaceContainerHigh,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.phone_android, size: 18),
                const SizedBox(width: 8),
                Expanded(child: Text(draft.title, style: theme.textTheme.titleMedium)),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              draft.uploading
                  ? 'Загрузка на сервер…'
                  : draft.error != null
                      ? 'Не загружено: ${draft.error}'
                      : 'Сохранено на устройстве',
              style: theme.textTheme.bodySmall?.copyWith(color: draft.error != null ? theme.colorScheme.error : null),
            ),
            if (draft.uploading) ...[
              const SizedBox(height: 8),
              LinearProgressIndicator(value: draft.progress > 0 ? draft.progress : null),
            ] else
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    onPressed: () async {
                      final ok = await _confirmDiscard(context);
                      if (ok) controller.discard(draft.id);
                    },
                    child: const Text('Удалить'),
                  ),
                  FilledButton.tonal(onPressed: () => controller.upload(draft.id), child: const Text('Загрузить')),
                ],
              ),
          ],
        ),
      ),
    );
  }

  Future<bool> _confirmDiscard(BuildContext context) async =>
      await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Удалить запись?'),
          content: const Text('Запись ещё не загружена на сервер и будет потеряна.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
          ],
        ),
      ) ??
      false;
}

class _Message extends StatelessWidget {
  const _Message({required this.icon, required this.text, this.action});

  final IconData icon;
  final String text;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    // ListView — чтобы работал pull-to-refresh и на пустом экране
    return ListView(
      children: [
        const SizedBox(height: 120),
        Icon(icon, size: 48, color: Theme.of(context).disabledColor),
        const SizedBox(height: 12),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Text(text, textAlign: TextAlign.center),
        ),
        if (action != null) Center(child: action),
      ],
    );
  }
}
