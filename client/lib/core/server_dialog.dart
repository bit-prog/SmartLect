import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'settings.dart';

Future<void> showServerDialog(BuildContext context, WidgetRef ref) async {
  final controller = TextEditingController(text: ref.read(serverUrlProvider));
  final url = await showDialog<String>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Адрес сервера'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextField(controller: controller, autofocus: true, keyboardType: TextInputType.url),
          const SizedBox(height: 8),
          Text('С телефона: http://<IP компьютера>:8081',
              style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => controller.text = ServerSettings.defaultUrl,
          child: const Text('По умолчанию'),
        ),
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
        FilledButton(onPressed: () => Navigator.pop(context, controller.text), child: const Text('Сохранить')),
      ],
    ),
  );
  if (url != null && url.trim().isNotEmpty) await ref.read(serverUrlProvider.notifier).set(url);
}
