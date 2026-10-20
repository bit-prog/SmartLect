package ru.smartlect.orchestrator.user

import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.{GetMapping, RestController}
import org.springframework.web.server.ResponseStatusException
import ru.smartlect.orchestrator.auth.CurrentUser

import java.time.Instant
import java.util.UUID

case class MeResponse(id: UUID, email: String, createdAt: Instant)

@RestController
class MeController(users: UserRepository):

  @GetMapping(Array("/api/me"))
  def me(@AuthenticationPrincipal jwt: Jwt): MeResponse =
    val user = users.findById(CurrentUser.id(jwt))
      .orElseThrow(() => ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists"))
    MeResponse(user.id, user.email, user.createdAt)
