package ru.smartlect.orchestrator

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class OrchestratorApplication

object OrchestratorApplication:
  def main(args: Array[String]): Unit =
    SpringApplication.run(classOf[OrchestratorApplication], args*)
