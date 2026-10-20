package ru.smartlect.orchestrator.incident

import org.springframework.data.jpa.repository.JpaRepository

import java.util.{List as JList, UUID}

trait IncidentRepository extends JpaRepository[Incident, UUID]:
  def findTop20ByNotifiedAtIsNullOrderByCreatedAt(): JList[Incident]
  def findByLectureId(lectureId: UUID): JList[Incident]
