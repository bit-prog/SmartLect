import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import 'package:intl/intl.dart';
import 'package:record/record.dart';

import '../lectures/models.dart';
import 'drafts.dart';

enum _Phase { idle, recording, paused }

/// Запись лекции. Фото доски получают таймкод — сколько шла запись на момент снимка,
/// чтобы конспект вставил формулу с доски в нужное место.
class RecordingScreen extends ConsumerStatefulWidget {
  const RecordingScreen({super.key});

  @override
  ConsumerState<RecordingScreen> createState() => _RecordingScreenState();
}

class _RecordingScreenState extends ConsumerState<RecordingScreen> {
  final _recorder = AudioRecorder();
  final _picker = ImagePicker();
  final _stopwatch = Stopwatch();
  final _title = TextEditingController(text: 'Лекция ${DateFormat('d.MM HH:mm').format(DateTime.now())}');
  final _photos = <DraftPhoto>[];

  late final String _id = DateTime.now().millisecondsSinceEpoch.toString();
  late final DateTime _startedAt;
  Directory? _dir;
  Timer? _ticker;
  _Phase _phase = _Phase.idle;
  bool _saving = false;

  @override
  void dispose() {
    _ticker?.cancel();
    _recorder.dispose();
    _title.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    if (!await _recorder.hasPermission()) {
      _snack('Нет доступа к микрофону. Разрешите его в настройках.');
      return;
    }
    _dir = await Draft.newDir(_id);
    // AAC моно 64 кбит/с: 1,5 часа ≈ 45 МБ, для распознавания речи качества хватает
    await _recorder.start(
      const RecordConfig(encoder: AudioEncoder.aacLc, bitRate: 64000, sampleRate: 32000, numChannels: 1),
      path: '${_dir!.path}/audio.m4a',
    );
    _startedAt = DateTime.now();
    _stopwatch.start();
    _ticker = Timer.periodic(const Duration(milliseconds: 500), (_) => setState(() {}));
    setState(() => _phase = _Phase.recording);
  }

  Future<void> _togglePause() async {
    if (_phase == _Phase.recording) {
      await _recorder.pause();
      _stopwatch.stop();
      setState(() => _phase = _Phase.paused);
    } else {
      await _recorder.resume();
      _stopwatch.start();
      setState(() => _phase = _Phase.recording);
    }
  }

  Future<void> _takePhoto() async {
    // Таймкод — момент нажатия, а не момент, когда камера вернула снимок
    final offsetMs = _stopwatch.elapsedMilliseconds;
    final source = _picker.supportsImageSource(ImageSource.camera) ? ImageSource.camera : ImageSource.gallery;
    final image = await _picker.pickImage(source: source, maxWidth: 2560, imageQuality: 85);
    if (image == null) return;
    final extension = image.path.split('.').last;
    final copy = await File(image.path).copy('${_dir!.path}/photo_${_photos.length}.$extension');
    setState(() => _photos.add(DraftPhoto(copy.path, offsetMs)));
  }

  Future<void> _stop() async {
    setState(() => _saving = true);
    _ticker?.cancel();
    _stopwatch.stop();
    final path = await _recorder.stop();
    if (path == null) {
      _snack('Не удалось сохранить запись');
      setState(() => _saving = false);
      return;
    }
    await ref.read(draftsProvider.notifier).add(Draft(
          id: _id,
          dir: _dir!.path,
          title: _title.text.trim().isEmpty ? 'Лекция' : _title.text.trim(),
          recordedAt: _startedAt,
          audioPath: path,
          durationMs: _stopwatch.elapsedMilliseconds,
          photos: _photos,
        ));
    if (mounted) context.pop();
  }

  Future<void> _leave() async {
    if (_phase == _Phase.idle) return context.pop();
    final discard = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Прервать запись?'),
        content: const Text('Запись и фото будут удалены.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Продолжить запись')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Удалить')),
        ],
      ),
    );
    if (discard != true) return;
    await _recorder.cancel();
    await _dir?.delete(recursive: true);
    if (mounted) context.pop();
  }

  void _snack(String text) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final active = _phase != _Phase.idle;

    return PopScope(
      canPop: !active,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _leave();
      },
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Запись лекции'),
          leading: IconButton(icon: const Icon(Icons.close), onPressed: _saving ? null : _leave),
        ),
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              children: [
                TextField(
                  controller: _title,
                  decoration: const InputDecoration(labelText: 'Название', border: OutlineInputBorder()),
                ),
                const Spacer(),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    if (_phase == _Phase.recording)
                      Icon(Icons.fiber_manual_record, color: theme.colorScheme.error, size: 18),
                    const SizedBox(width: 6),
                    Text(formatDuration(_stopwatch.elapsedMilliseconds),
                        style: theme.textTheme.displayMedium?.copyWith(fontFeatures: const [FontFeature.tabularFigures()])),
                  ],
                ),
                const SizedBox(height: 8),
                Text(switch (_phase) {
                  _Phase.idle => 'Нажмите, чтобы начать',
                  _Phase.recording => 'Идёт запись',
                  _Phase.paused => 'Пауза',
                }),
                const Spacer(),
                if (_photos.isNotEmpty) ...[
                  SizedBox(
                    height: 88,
                    child: ListView.separated(
                      scrollDirection: Axis.horizontal,
                      itemCount: _photos.length,
                      separatorBuilder: (_, _) => const SizedBox(width: 8),
                      itemBuilder: (context, i) => Stack(
                        children: [
                          ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: Image.file(File(_photos[i].path), width: 88, height: 88, fit: BoxFit.cover),
                          ),
                          Positioned(
                            left: 4,
                            bottom: 4,
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                              color: Colors.black54,
                              child: Text(formatDuration(_photos[i].offsetMs),
                                  style: const TextStyle(color: Colors.white, fontSize: 11)),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),
                ],
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    _RoundButton(
                      icon: Icons.photo_camera,
                      label: 'Фото доски',
                      onPressed: active && !_saving ? _takePhoto : null,
                    ),
                    if (!active)
                      _RoundButton(icon: Icons.mic, label: 'Начать', primary: true, onPressed: _start)
                    else
                      _RoundButton(
                        icon: _phase == _Phase.recording ? Icons.pause : Icons.play_arrow,
                        label: _phase == _Phase.recording ? 'Пауза' : 'Продолжить',
                        primary: true,
                        onPressed: _saving ? null : _togglePause,
                      ),
                    _RoundButton(
                      icon: Icons.stop,
                      label: 'Завершить',
                      onPressed: active && !_saving ? _stop : null,
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RoundButton extends StatelessWidget {
  const _RoundButton({required this.icon, required this.label, required this.onPressed, this.primary = false});

  final IconData icon;
  final String label;
  final VoidCallback? onPressed;
  final bool primary;

  @override
  Widget build(BuildContext context) {
    final size = primary ? 80.0 : 60.0;
    return Column(
      children: [
        SizedBox.square(
          dimension: size,
          child: primary
              ? FilledButton(
                  onPressed: onPressed,
                  style: FilledButton.styleFrom(shape: const CircleBorder(), padding: EdgeInsets.zero),
                  child: Icon(icon, size: 36),
                )
              : FilledButton.tonal(
                  onPressed: onPressed,
                  style: FilledButton.styleFrom(shape: const CircleBorder(), padding: EdgeInsets.zero),
                  child: Icon(icon),
                ),
        ),
        const SizedBox(height: 6),
        Text(label, style: Theme.of(context).textTheme.bodySmall),
      ],
    );
  }
}
