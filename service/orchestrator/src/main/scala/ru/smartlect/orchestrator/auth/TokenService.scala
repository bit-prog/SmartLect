package ru.smartlect.orchestrator.auth

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.{JwsHeader, JwtClaimsSet, JwtEncoder, JwtEncoderParameters}
import org.springframework.stereotype.Service
import ru.smartlect.orchestrator.user.User

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{Clock, Instant}
import java.util.{Base64, HexFormat}

/** Access-токен: подписанный JWT, проверяется без похода в БД. */
case class AccessToken(value: String, expiresAt: Instant)

/** Refresh-токен: случайная строка; в БД хранится только её хеш. */
case class IssuedRefreshToken(value: String, hash: String, expiresAt: Instant)

@Service
class TokenService(encoder: JwtEncoder, props: AuthProperties, clock: Clock):
  private val random = SecureRandom()

  def issueAccessToken(user: User): AccessToken =
    val now = clock.instant()
    val expiresAt = now.plus(props.accessTokenTtl)
    val claims = JwtClaimsSet.builder()
      .issuer(TokenService.Issuer)
      .subject(user.id.toString)
      .issuedAt(now)
      .expiresAt(expiresAt)
      .claim("email", user.email)
      .build()
    val header = JwsHeader.`with`(MacAlgorithm.HS256).build()
    AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue, expiresAt)

  def issueRefreshToken(): IssuedRefreshToken =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    val value = Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    IssuedRefreshToken(value, TokenService.hash(value), clock.instant().plus(props.refreshTokenTtl))

object TokenService:
  val Issuer = "smartlect"

  def hash(refreshToken: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8))
    HexFormat.of().formatHex(digest)
