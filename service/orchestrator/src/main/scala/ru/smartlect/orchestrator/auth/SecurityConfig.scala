package ru.smartlect.orchestrator.auth

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.{Bean, Configuration}
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.security.web.SecurityFilterChain

import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Configuration
class SecurityConfig:

  @Bean
  def securityFilterChain(http: HttpSecurity): SecurityFilterChain =
    http
      // API без cookie-сессий: токен приходит в заголовке Authorization, CSRF не нужен
      .csrf(_.disable())
      .sessionManagement(_.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests { auth =>
        auth
          .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
          .requestMatchers("/actuator/health", "/error").permitAll()
          .anyRequest().authenticated()
      }
      .oauth2ResourceServer(_.jwt(_ => ()))
      .build()

  @Bean
  def passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

  @Bean
  def jwtSigningKey(props: AuthProperties): SecretKey =
    val bytes = Base64.getDecoder.decode(props.jwtSecret)
    require(bytes.length >= 32, "smartlect.auth.jwt-secret must be at least 32 bytes")
    SecretKeySpec(bytes, "HmacSHA256")

  @Bean
  def jwtEncoder(key: SecretKey): JwtEncoder =
    NimbusJwtEncoder(ImmutableSecret[SecurityContext](key))

  @Bean
  def jwtDecoder(key: SecretKey): JwtDecoder =
    val decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.Issuer))
    decoder
