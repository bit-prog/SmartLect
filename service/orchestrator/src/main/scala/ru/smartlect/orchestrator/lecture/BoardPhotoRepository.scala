package ru.smartlect.orchestrator.lecture

import org.springframework.data.jpa.repository.{JpaRepository, Query}

import java.util.{Collection as JCollection, List as JList, Optional, UUID}

trait BoardPhotoRepository extends JpaRepository[BoardPhoto, UUID]:
  def findByLectureIdOrderByOffsetMs(lectureId: UUID): JList[BoardPhoto]
  def findByIdAndLectureId(id: UUID, lectureId: UUID): Optional[BoardPhoto]
  def countByLectureId(lectureId: UUID): Long

  /** Число фото для нескольких лекций одним запросом: строки `[lectureId, count]`. */
  @Query("select p.lectureId, count(p) from BoardPhoto p where p.lectureId in ?1 group by p.lectureId")
  def countByLectureIds(lectureIds: JCollection[UUID]): JList[Array[AnyRef]]
