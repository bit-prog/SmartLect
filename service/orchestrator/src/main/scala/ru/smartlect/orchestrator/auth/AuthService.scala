package ru.smartlect.orchestrator.auth

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.smartlect.orchestrator.user.{User, UserRepository}

import java.time.{Clock, Duration}
import scala.jdk.OptionConverters.*

case class AuthTokens(accessToken: String, refreshToken: String, tokenType: String, expiresIn: Long)

@Service
class AuthService(
    users: UserRepository,
    refreshTokens: RefreshTokenRepository,
    tokens: TokenService,
    passwordEncoder: PasswordEncoder,
    clock: Clock
):

  @Transactional
  def register(email: String, password: String): AuthTokens =
    val normalized = User.normalizeEmail(email)
    if users.existsByEmail(normalized) then throw emailTaken()
    val user =
      try users.saveAndFlush(User(normalized, passwordEncoder.encode(password)))
      // Два одновременных запроса с одним email: второй упрётся в UNIQUE
      catch case _: DataIntegrityViolationException => throw emailTaken()
    issueTokens(user)

  @Transactional
  def login(email: String, password: String): AuthTokens =
    users.findByEmail(User.normalizeEmail(email)).toScala
      .filter(user => passwordEncoder.matches(password, user.passwordHash))
      .map(issueTokens)
      // Одинаковый ответ для «нет пользователя» и «неверный пароль»
      .getOrElse(throw unauthorized("Invalid email or password"))

  /** Выдаёт новую пару токенов; старый refresh-токен после этого недействителен. */
  // Без noRollbackFor ответ 401 откатил бы отзыв сессий при повторном использовании токена
  @Transactional(noRollbackFor = Array(classOf[ResponseStatusException]))
  def refresh(refreshToken: String): AuthTokens =
    val now = clock.instant()
    val stored = refreshTokens.findByTokenHash(TokenService.hash(refreshToken)).toScala
      .getOrElse(throw unauthorized("Invalid refresh token"))

    if stored.revokedAt != null then
      // Отозванный токен прислали повторно — вероятно, его украли. Отзываем все сессии пользователя.
      refreshTokens.revokeAllForUser(stored.userId, now)
      throw unauthorized("Invalid refresh token")
    if !stored.isActive(now) then throw unauthorized("Refresh token expired")

    stored.revokedAt = now
    val user = users.findById(stored.userId).orElseThrow()
    issueTokens(user)

  @Transactional
  def logout(refreshToken: String): Unit =
    refreshTokens.findByTokenHash(TokenService.hash(refreshToken)).toScala
      .filter(_.revokedAt == null)
      .foreach(_.revokedAt = clock.instant())

  private def issueTokens(user: User): AuthTokens =
    val access = tokens.issueAccessToken(user)
    val refresh = tokens.issueRefreshToken()
    refreshTokens.save(RefreshToken(user.id, refresh.hash, refresh.expiresAt))
    val expiresIn = Duration.between(clock.instant(), access.expiresAt).toSeconds
    AuthTokens(access.value, refresh.value, "Bearer", expiresIn)

  private def emailTaken() = ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered")
  private def unauthorized(reason: String) = ResponseStatusException(HttpStatus.UNAUTHORIZED, reason)
