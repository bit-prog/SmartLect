package ru.smartlect.orchestrator

import org.springframework.context.annotation.{Bean, Configuration}

import java.time.Clock

/** Часы — отдельным бином, чтобы в тестах можно было подменить время. */
@Configuration
class ClockConfig:
  @Bean
  def clock(): Clock = Clock.systemUTC()
