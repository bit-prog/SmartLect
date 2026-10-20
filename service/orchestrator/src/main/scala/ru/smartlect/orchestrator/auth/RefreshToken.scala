package ru.smartlect.orchestrator.auth

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp

import java.time.Instant
import java.util.UUID
import scala.beans.BeanProperty
import scala.compiletime.uninitialized

/** Refresh-токен пользователя. Хранится только SHA-256 хеш, сам токен знает клиент. */
@Entity
@Table(name = "refresh_tokens")
class RefreshToken:
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @BeanProperty var id: UUID = uninitialized

  @BeanProperty var userId: UUID = uninitialized
  @BeanProperty var tokenHash: String = uninitialized
  @BeanProperty var expiresAt: Instant = uninitialized
  @BeanProperty var revokedAt: Instant = uninitialized

  @CreationTimestamp
  @BeanProperty var createdAt: Instant = uninitialized

  def isActive(now: Instant): Boolean = revokedAt == null && now.isBefore(expiresAt)

object RefreshToken:
  def apply(userId: UUID, tokenHash: String, expiresAt: Instant): RefreshToken =
    val token = new RefreshToken
    token.userId = userId
    token.tokenHash = tokenHash
    token.expiresAt = expiresAt
    token
