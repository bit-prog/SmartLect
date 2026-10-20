package ru.smartlect.orchestrator.auth

import jakarta.validation.Valid
import jakarta.validation.constraints.{Email, NotBlank, Size}
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

import scala.annotation.meta.field

// Аннотации валидации вешаются на поле (@field), иначе Scala оставит их только на параметре конструктора
case class RegisterRequest(
    @(NotBlank @field) @(Email @field) @(Size @field)(max = 320) email: String,
    // BCrypt учитывает только первые 72 байта пароля
    @(NotBlank @field) @(Size @field)(min = 8, max = 64) password: String
)

case class LoginRequest(
    @(NotBlank @field) email: String,
    @(NotBlank @field) password: String
)

case class RefreshRequest(@(NotBlank @field) refreshToken: String)

@RestController
@RequestMapping(Array("/api/auth"))
class AuthController(auth: AuthService):

  @PostMapping(Array("/register"))
  @ResponseStatus(HttpStatus.CREATED)
  def register(@Valid @RequestBody request: RegisterRequest): AuthTokens =
    auth.register(request.email, request.password)

  @PostMapping(Array("/login"))
  def login(@Valid @RequestBody request: LoginRequest): AuthTokens =
    auth.login(request.email, request.password)

  @PostMapping(Array("/refresh"))
  def refresh(@Valid @RequestBody request: RefreshRequest): AuthTokens =
    auth.refresh(request.refreshToken)

  @PostMapping(Array("/logout"))
  @ResponseStatus(HttpStatus.NO_CONTENT)
  def logout(@Valid @RequestBody request: RefreshRequest): Unit =
    auth.logout(request.refreshToken)
