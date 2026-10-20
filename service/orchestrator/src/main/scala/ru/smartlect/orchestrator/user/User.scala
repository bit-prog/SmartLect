package ru.smartlect.orchestrator.user

import jakarta.persistence.*
import org.hibernate.annotations.{CreationTimestamp, UpdateTimestamp}

import java.time.Instant
import java.util.UUID
import scala.beans.BeanProperty
import scala.compiletime.uninitialized

@Entity
@Table(name = "users")
class User:
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @BeanProperty var id: UUID = uninitialized

  /** Всегда в нижнем регистре, см. [[User.normalizeEmail]]. */
  @BeanProperty var email: String = uninitialized

  @BeanProperty var passwordHash: String = uninitialized

  @CreationTimestamp
  @BeanProperty var createdAt: Instant = uninitialized

  @UpdateTimestamp
  @BeanProperty var updatedAt: Instant = uninitialized

object User:
  def normalizeEmail(email: String): String = email.trim.toLowerCase

  def apply(email: String, passwordHash: String): User =
    val user = new User
    user.email = normalizeEmail(email)
    user.passwordHash = passwordHash
    user
