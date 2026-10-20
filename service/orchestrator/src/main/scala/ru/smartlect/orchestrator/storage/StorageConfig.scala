package ru.smartlect.orchestrator.storage

import org.springframework.context.annotation.{Bean, Configuration}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.{RequestChecksumCalculation, ResponseChecksumValidation}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

import java.net.URI

@Configuration
class StorageConfig:

  @Bean(destroyMethod = "close")
  def s3Client(props: StorageProperties): S3Client =
    S3Client.builder()
      .endpointOverride(URI.create(props.endpoint))
      .region(Region.of(props.region))
      .credentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey))
      )
      // S3-совместимые хранилища адресуют бакет путём (host/bucket/key), а не поддоменом
      .forcePathStyle(true)
      // Новые контрольные суммы AWS SDK поддерживаются не всеми S3-совместимыми хранилищами
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
      .build()
