import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import 'features/auth/auth_controller.dart';
import 'features/auth/login_screen.dart';
import 'features/lectures/lecture_screen.dart';
import 'features/lectures/lectures_screen.dart';
import 'features/recording/recording_screen.dart';

final routerProvider = Provider<GoRouter>((ref) {
  // Роутер создаётся один раз, а при входе/выходе только перепроверяет redirect
  final authChanges = ValueNotifier(ref.read(authProvider));
  ref.listen(authProvider, (_, next) => authChanges.value = next);
  ref.onDispose(authChanges.dispose);

  return GoRouter(
    refreshListenable: authChanges,
    redirect: (context, state) {
      final auth = authChanges.value;
      if (auth == AuthStatus.unknown) return null;
      final onLogin = state.matchedLocation == '/login';
      if (auth == AuthStatus.signedOut) return onLogin ? null : '/login';
      return onLogin ? '/' : null;
    },
    routes: [
      GoRoute(path: '/login', builder: (_, _) => const LoginScreen()),
      GoRoute(path: '/', builder: (_, _) => const LecturesScreen()),
      GoRoute(path: '/record', builder: (_, _) => const RecordingScreen()),
      GoRoute(path: '/lectures/:id', builder: (_, state) => LectureScreen(id: state.pathParameters['id']!)),
    ],
  );
});
