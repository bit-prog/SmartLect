package ru.smartlect.orchestrator

import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

import java.util.UUID

/** Регистрирует нового пользователя и возвращает заголовок `Authorization`. */
object TestAuth:
  def registerUser(mvc: MockMvc): String =
    val body = mvc.perform(
      post("/api/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(s"""{"email":"user-${UUID.randomUUID()}@example.com","password":"secret-password"}""")
    ).andReturn().getResponse.getContentAsString
    "Bearer " + JsonPath.read[String](body, "$.accessToken")
