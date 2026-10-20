package ru.smartlect.orchestrator.auth

import org.springframework.data.jpa.repository.{JpaRepository, Modifying, Query}

import java.time.Instant
import java.util.{Optional, UUID}

trait RefreshTokenRepository extends JpaRepository[RefreshToken, UUID]:
  def findByTokenHash(tokenHash: String): Optional[RefreshToken]

  @Modifying
  @Query("update RefreshToken t set t.revokedAt = ?2 where t.userId = ?1 and t.revokedAt is null")
  def revokeAllForUser(userId: UUID, now: Instant): Int
