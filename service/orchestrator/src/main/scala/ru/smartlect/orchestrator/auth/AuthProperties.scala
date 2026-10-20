package ru.smartlect.orchestrator.auth

import org.springframework.boot.context.properties.ConfigurationProperties

import java.time.Duration
import scala.beans.BeanProperty
import scala.compiletime.uninitialized

/** Настройки авторизации: `smartlect.auth.*` в application.properties. */
@ConfigurationProperties(prefix = "smartlect.auth")
class AuthProperties:
  /** HMAC-ключ для подписи JWT, base64, не меньше 32 байт. */
  @BeanProperty var jwtSecret: String = uninitialized
  @BeanProperty var accessTokenTtl: Duration = Duration.ofMinutes(15)
  @BeanProperty var refreshTokenTtl: Duration = Duration.ofDays(30)
