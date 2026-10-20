package ru.smartlect.orchestrator.storage

import org.springframework.boot.context.properties.ConfigurationProperties

import scala.beans.BeanProperty
import scala.compiletime.uninitialized

/** Настройки S3-хранилища: `smartlect.storage.*` в application.properties. */
@ConfigurationProperties(prefix = "smartlect.storage")
class StorageProperties:
  @BeanProperty var endpoint: String = uninitialized
  @BeanProperty var region: String = "us-east-1"
  @BeanProperty var accessKey: String = uninitialized
  @BeanProperty var secretKey: String = uninitialized
  @BeanProperty var bucket: String = uninitialized
