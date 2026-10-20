package ru.smartlect.orchestrator.lecture

import org.springframework.data.domain.{Page, Pageable}
import org.springframework.data.jpa.repository.JpaRepository

import java.util.{Optional, UUID}

trait LectureRepository extends JpaRepository[Lecture, UUID]:
  def findByOwnerIdOrderByCreatedAtDesc(ownerId: UUID, pageable: Pageable): Page[Lecture]
  def findByIdAndOwnerId(id: UUID, ownerId: UUID): Optional[Lecture]
