package ru.smartlect.orchestrator.user

import org.springframework.data.jpa.repository.JpaRepository

import java.util.{Optional, UUID}

trait UserRepository extends JpaRepository[User, UUID]:
  def findByEmail(email: String): Optional[User]
  def existsByEmail(email: String): Boolean
