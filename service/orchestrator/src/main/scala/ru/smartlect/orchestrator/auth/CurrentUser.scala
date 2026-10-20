package ru.smartlect.orchestrator.auth

import org.springframework.security.oauth2.jwt.Jwt

import java.util.UUID

/** Id пользователя из access-токена (claim `sub`). */
object CurrentUser:
  def id(jwt: Jwt): UUID = UUID.fromString(jwt.getSubject)
