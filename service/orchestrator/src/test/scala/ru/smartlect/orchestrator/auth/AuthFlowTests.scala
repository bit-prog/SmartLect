package ru.smartlect.orchestrator.auth

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.{get, post}
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.{jsonPath, status}

import java.util.UUID
import scala.compiletime.uninitialized

/** Сквозной сценарий авторизации через HTTP (нужен docker compose). */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTests:

  @Autowired private var mvc: MockMvc = uninitialized

  private def postJson(path: String, body: String) =
    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

  private def credentials(email: String, password: String) =
    s"""{"email":"$email","password":"$password"}"""

  private def refreshBody(token: String) = s"""{"refreshToken":"$token"}"""

  private def field(body: String, name: String): String = JsonPath.read[String](body, "$." + name)

  @Test
  def registerLoginRefreshLogout(): Unit =
    val email = s"student-${UUID.randomUUID()}@example.com"

    // Регистрация сразу выдаёт токены
    val registered = postJson("/api/auth/register", credentials(email.toUpperCase, "secret-password"))
      .andExpect(status().isCreated)
      .andExpect(jsonPath("$.tokenType").value("Bearer"))
      .andReturn().getResponse.getContentAsString

    // Тот же email в другом регистре занят
    postJson("/api/auth/register", credentials(email, "another-password")).andExpect(status().isConflict)

    // Доступ к API по access-токену
    mvc.perform(get("/api/me").header("Authorization", "Bearer " + field(registered, "accessToken")))
      .andExpect(status().isOk)
      .andExpect(jsonPath("$.email").value(email))
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized)

    postJson("/api/auth/login", credentials(email, "wrong-password")).andExpect(status().isUnauthorized)
    val loggedIn = postJson("/api/auth/login", credentials(email, "secret-password"))
      .andExpect(status().isOk)
      .andReturn().getResponse.getContentAsString

    // Ротация: старый refresh-токен после обмена больше не работает
    val oldRefresh = field(loggedIn, "refreshToken")
    val refreshed = postJson("/api/auth/refresh", refreshBody(oldRefresh))
      .andExpect(status().isOk)
      .andReturn().getResponse.getContentAsString
    val newRefresh = field(refreshed, "refreshToken")

    // Повторное использование старого токена отзывает и новый
    postJson("/api/auth/refresh", refreshBody(oldRefresh)).andExpect(status().isUnauthorized)
    postJson("/api/auth/refresh", refreshBody(newRefresh)).andExpect(status().isUnauthorized)

    // Выход: токен от регистрации отзывается и больше не обменивается
    val registeredRefresh = field(registered, "refreshToken")
    postJson("/api/auth/logout", refreshBody(registeredRefresh)).andExpect(status().isNoContent)
    postJson("/api/auth/refresh", refreshBody(registeredRefresh)).andExpect(status().isUnauthorized)

  @Test
  def rejectsInvalidRegistration(): Unit =
    postJson("/api/auth/register", credentials("not-an-email", "secret-password")).andExpect(status().isBadRequest)
    postJson("/api/auth/register", credentials("short@example.com", "123")).andExpect(status().isBadRequest)
    postJson("/api/auth/register", """{"email":"missing-password@example.com"}""").andExpect(status().isBadRequest)
