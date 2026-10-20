import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:gpt_markdown/gpt_markdown.dart';
import 'package:intl/intl.dart';

import '../../core/api_client.dart';
import '../../core/settings.dart';
import 'lectures_api.dart';
import 'models.dart';
import 'status_widgets.dart';

final _notesProvider = FutureProvider.autoDispose.family<String, String>(
  (ref, id) => ref.watch(lecturesApiProvider).notes(id),
);
final _transcriptProvider = FutureProvider.autoDispose.family<List<TranscriptSegment>, String>(
  (ref, id) => ref.watch(lecturesApiProvider).transcript(id),
);
final _photosProvider = FutureProvider.autoDispose.family<List<BoardPhoto>, String>(
  (ref, id) => ref.watch(lecturesApiProvider).photos(id),
);
final _authHeadersProvider = FutureProvider.autoDispose<Map<String, String>>(
  (ref) => ref.watch(apiClientProvider).authHeaders(),
);

class LectureScreen extends ConsumerStatefulWidget {
  const LectureScreen({super.key, required this.id});

  final String id;

  @override
  ConsumerState<LectureScreen> createState() => _LectureScreenState();
}

class _LectureScreenState extends ConsumerState<LectureScreen> {
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) {
      if (ref.read(lectureProvider(widget.id)).value?.status.inProgress ?? false) {
        ref.invalidate(lectureProvider(widget.id));
      }
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() action) async {
    try {
      await action();
    } catch (e) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
    }
  }

  Future<void> _delete() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Удалить лекцию?'),
        content: const Text('Запись, фото и конспект будут удалены с сервера.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Отмена')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (confirmed != true) return;
    await _run(() async {
      await ref.read(lecturesApiProvider).delete(widget.id);
      ref.invalidate(lecturesProvider);
      if (mounted) context.pop();
    });
  }

  Future<void> _retry() => _run(() async {
        await ref.read(lecturesApiProvider).submit(widget.id);
        ref.invalidate(lectureProvider(widget.id));
        ref.invalidate(lecturesProvider);
      });

  @override
  Widget build(BuildContext context) {
    final lecture = ref.watch(lectureProvider(widget.id));
    // Список на главном экране тоже должен увидеть новый статус
    ref.listen(lectureProvider(widget.id), (previous, next) {
      if (previous?.value?.status != next.value?.status) ref.invalidate(lecturesProvider);
    });

    return lecture.when(
      loading: () => Scaffold(appBar: AppBar(), body: const Center(child: CircularProgressIndicator())),
      error: (e, _) => Scaffold(appBar: AppBar(), body: Center(child: Text(e.toString()))),
      data: (lecture) {
        final actions = [
          IconButton(
            tooltip: 'Удалить',
            icon: const Icon(Icons.delete_outline),
            onPressed: lecture.status.inProgress ? null : _delete,
          ),
        ];
        if (lecture.status != LectureStatus.done) {
          return Scaffold(
            appBar: AppBar(title: Text(lecture.title), actions: actions),
            body: ListView(padding: const EdgeInsets.all(20), children: [
              _Header(lecture),
              const SizedBox(height: 24),
              if (lecture.status == LectureStatus.failed) ...[
                Card(
                  color: Theme.of(context).colorScheme.errorContainer,
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Не удалось обработать лекцию', style: Theme.of(context).textTheme.titleMedium),
                        const SizedBox(height: 4),
                        Text('Мы уже получили уведомление об ошибке на этапе '
                            '«${lecture.stage?.label ?? '—'}». Можно попробовать ещё раз.'),
                        const SizedBox(height: 12),
                        FilledButton(onPressed: _retry, child: const Text('Повторить обработку')),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
              ],
              if (lecture.status == LectureStatus.uploaded)
                FilledButton(onPressed: _retry, child: const Text('Отправить на обработку')),
              if (lecture.status.inProgress || lecture.status == LectureStatus.failed) StageProgress(lecture),
              if (lecture.status == LectureStatus.queued)
                const Padding(
                  padding: EdgeInsets.only(top: 12),
                  child: Text('Лекция ждёт своей очереди на обработку.'),
                ),
            ]),
          );
        }
        return DefaultTabController(
          length: 3,
          child: Scaffold(
            appBar: AppBar(
              title: Text(lecture.title),
              actions: actions,
              bottom: const TabBar(tabs: [Tab(text: 'Конспект'), Tab(text: 'Расшифровка'), Tab(text: 'Фото')]),
            ),
            body: TabBarView(children: [
              _NotesTab(lecture),
              _TranscriptTab(lecture.id),
              _PhotosTab(lecture.id),
            ]),
          ),
        );
      },
    );
  }
}

class _Header extends StatelessWidget {
  const _Header(this.lecture);

  final Lecture lecture;

  @override
  Widget build(BuildContext context) {
    final details = [
      DateFormat('d MMMM y, HH:mm', 'ru').format(lecture.recordedAt),
      if (lecture.durationMs != null) formatDuration(lecture.durationMs!),
      if (lecture.photoCount > 0) 'фото: ${lecture.photoCount}',
    ].join(' · ');
    return Row(
      children: [
        Expanded(child: Text(details, style: Theme.of(context).textTheme.bodyMedium)),
        StatusChip(lecture.status),
      ],
    );
  }
}

class _NotesTab extends ConsumerWidget {
  const _NotesTab(this.lecture);

  final Lecture lecture;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ref.watch(_notesProvider(lecture.id)).when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Center(child: Text(e.toString())),
          data: (markdown) => SelectionArea(
            child: ListView(
              padding: const EdgeInsets.all(20),
              children: [
                _Header(lecture),
                const SizedBox(height: 16),
                // Markdown с формулами LaTeX: воркер пишет их как $...$ и $$...$$
                GptMarkdown(markdown, useDollarSignsForLatex: true),
              ],
            ),
          ),
        );
  }
}

class _TranscriptTab extends ConsumerWidget {
  const _TranscriptTab(this.id);

  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return ref.watch(_transcriptProvider(id)).when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Center(child: Text(e.toString())),
          data: (segments) => segments.isEmpty
              ? const Center(child: Text('Речь не распознана'))
              : SelectionArea(
                  child: ListView.builder(
                    padding: const EdgeInsets.all(16),
                    itemCount: segments.length,
                    itemBuilder: (context, i) => Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          SizedBox(
                            width: 64,
                            child: Text(formatDuration(segments[i].startMs),
                                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.primary)),
                          ),
                          Expanded(child: Text(segments[i].text)),
                        ],
                      ),
                    ),
                  ),
                ),
        );
  }
}

class _PhotosTab extends ConsumerWidget {
  const _PhotosTab(this.id);

  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final photos = ref.watch(_photosProvider(id));
    final headers = ref.watch(_authHeadersProvider).value;
    final api = ref.read(lecturesApiProvider);
    final server = ref.watch(serverUrlProvider);

    return photos.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (e, _) => Center(child: Text(e.toString())),
      data: (items) => items.isEmpty || headers == null
          ? Center(child: Text(items.isEmpty ? 'Фото доски нет' : ''))
          : GridView.builder(
              padding: const EdgeInsets.all(12),
              gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 240, mainAxisSpacing: 8, crossAxisSpacing: 8),
              itemCount: items.length,
              itemBuilder: (context, i) {
                final url = api.photoUrl(server, id, items[i].id);
                return GestureDetector(
                  onTap: () => showDialog(
                    context: context,
                    builder: (_) => Dialog(child: InteractiveViewer(child: Image.network(url, headers: headers))),
                  ),
                  child: Stack(fit: StackFit.expand, children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(8),
                      child: Image.network(url, headers: headers, fit: BoxFit.cover),
                    ),
                    Positioned(
                      left: 6,
                      bottom: 6,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(4)),
                        child: Text(formatDuration(items[i].offsetMs), style: const TextStyle(color: Colors.white)),
                      ),
                    ),
                  ]),
                );
              },
            ),
    );
  }
}
