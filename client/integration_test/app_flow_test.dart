// Сквозной сценарий на настоящем бэкенде: docker compose + оркестратор + воркер с WORKER_FAKE=1.
// Запуск: flutter test integration_test -d macos

import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:path_provider/path_provider.dart';
import 'package:smartlect/core/token_store.dart';
import 'package:smartlect/features/lectures/lectures_screen.dart';
import 'package:smartlect/features/recording/drafts.dart';
import 'package:smartlect/main.dart' as app;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  Future<void> pumpUntil(WidgetTester tester, Finder finder, {Duration timeout = const Duration(seconds: 30)}) async {
    final end = DateTime.now().add(timeout);
    while (DateTime.now().isBefore(end)) {
      await tester.pump(const Duration(milliseconds: 200));
      // .at(i) на коротком списке бросает IndexError — считаем это «ещё не найдено»
      try {
        if (finder.evaluate().isNotEmpty) return;
      } on RangeError {
        continue;
      }
    }
    fail('Not found in $timeout: $finder');
  }

  // Скриншот текущего экрана в каталог temp приложения (песочница macOS не даёт писать в другие места)
  Future<void> screenshot(WidgetTester tester, String name) async {
    final boundary = tester.renderObject<RenderRepaintBoundary>(find.byType(RepaintBoundary).first);
    await tester.runAsync(() async {
      final image = await boundary.toImage(pixelRatio: 1.5);
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      final dir = await Directory('${(await getApplicationSupportDirectory()).path}/screenshots').create(recursive: true);
      final file = File('${dir.path}/$name.png');
      await file.writeAsBytes(bytes!.buffer.asUint8List());
      debugPrint('SCREENSHOT ${file.path}');
    });
  }

  testWidgets('register, process a lecture and read the notes', (tester) async {
    await TokenStore().clear();
    app.main();
    await pumpUntil(tester, find.text('Войти'));
    await screenshot(tester, '1_login');

    final email = 'ui-${DateTime.now().millisecondsSinceEpoch}@example.com';
    await tester.tap(find.text('Регистрация'));
    await tester.pump();
    await tester.enterText(find.byType(TextFormField).at(0), email);
    await tester.enterText(find.byType(TextFormField).at(1), 'secret-password');
    await tester.tap(find.text('Зарегистрироваться'));
    await pumpUntil(tester, find.textContaining('Лекций пока нет'));
    await screenshot(tester, '2_empty_list');

    // Лекцию загружаем через API (запись и выбор файла — нативные окна, их тест не нажмёт)
    final token = (await TokenStore().read())!.accessToken;
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost:8081', headers: {'Authorization': 'Bearer $token'}));
    final id = await tester.runAsync(() async {
      final created = await dio.post('/api/lectures', data: {
        'title': 'Матанализ: производная',
        'source': 'MOBILE',
        'recordedAt': DateTime.now().toUtc().toIso8601String(),
      });
      final id = created.data['id'] as String;
      final bytes = Uint8List.fromList(List.filled(4096, 1));
      await dio.put('/api/lectures/$id/audio',
          queryParameters: {'durationMs': 1500000},
          data: FormData.fromMap({
            'file': MultipartFile.fromBytes(bytes, filename: 'a.m4a', contentType: DioMediaType('audio', 'mp4')),
          }));
      await dio.post('/api/lectures/$id/photos',
          data: FormData.fromMap({
            'file': MultipartFile.fromBytes(_png, filename: 'b.png', contentType: DioMediaType('image', 'png')),
            'offsetMs': 600000,
          }));
      await dio.post('/api/lectures/$id/submit');
      return id;
    });
    expect(id, isNotNull);

    // Список обновляется жестом pull-to-refresh
    await tester.fling(find.byType(Scrollable).first, const Offset(0, 400), 1000);
    await pumpUntil(tester, find.text('Матанализ: производная'));
    await pumpUntil(tester, find.text('Готово'), timeout: const Duration(seconds: 60));
    await screenshot(tester, '3_list_done');

    await tester.tap(find.text('Матанализ: производная'));
    await pumpUntil(tester, find.text('Конспект'));
    await pumpUntil(tester, find.textContaining('Конспект, сгенерированный заглушкой'));
    // Формула из конспекта отрисована LaTeX, а не осталась текстом
    expect(find.textContaining(r'$$E = mc^2$$'), findsNothing);
    await screenshot(tester, '4_notes');

    await tester.tap(find.text('Расшифровка'));
    await pumpUntil(tester, find.text('Фрагмент речи номер 1.'));
    await screenshot(tester, '5_transcript');

    await tester.tap(find.text('Фото'));
    await pumpUntil(tester, find.text('10:00'));
    await tester.pump(const Duration(seconds: 1));
    await screenshot(tester, '6_photos');

    // Экран записи открывается и закрывается без записи
    await tester.tap(find.byType(BackButton));
    await pumpUntil(tester, find.text('Записать'));
    await tester.tap(find.text('Записать'));
    await pumpUntil(tester, find.text('Нажмите, чтобы начать'));
    await screenshot(tester, '7_record');
    await tester.tap(find.byIcon(Icons.close));
    await pumpUntil(tester, find.byType(LecturesScreen));

    // Запись, сохранённая на устройстве, загружается по шагам и после обработки исчезает из черновиков
    final container = ProviderScope.containerOf(tester.element(find.byType(LecturesScreen)));
    await tester.runAsync(() async {
      final dir = await Draft.newDir('test-${DateTime.now().millisecondsSinceEpoch}');
      final audio = await File('${dir.path}/audio.m4a').writeAsBytes(List.filled(2048, 3));
      final photo = await File('${dir.path}/photo_0.png').writeAsBytes(_png);
      await container.read(draftsProvider.notifier).add(Draft(
            id: dir.path.split('/').last,
            dir: dir.path,
            title: 'Записано на устройстве',
            recordedAt: DateTime.now(),
            audioPath: audio.path,
            durationMs: 600000,
            photos: [DraftPhoto(photo.path, 120000)],
          ));
      expect(container.read(draftsProvider), isEmpty);
      expect(await dir.exists(), isFalse);
    });
    await pumpUntil(tester, find.text('Записано на устройстве'));
    // Обе лекции обработаны (список сам обновляется, пока что-то в обработке)
    await pumpUntil(tester, find.text('Готово').at(1), timeout: const Duration(seconds: 60));
    await screenshot(tester, '8_list_two');
  });
}

// PNG 1×1
final _png = Uint8List.fromList([
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01,
  0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53, 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
  0x54, 0x08, 0xD7, 0x63, 0xF8, 0xCF, 0xC0, 0x00, 0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xDD, 0x8D, 0xB0, 0x00, 0x00, 0x00,
  0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
]);
