package ru.smartlect.orchestrator.storage

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

import java.io.InputStream
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Работа с файлами лекций в S3: аудио, фото доски, результаты обработки. */
@Service
class ObjectStorage(s3: S3Client, props: StorageProperties):

  private val log = LoggerFactory.getLogger(classOf[ObjectStorage])
  private def bucket = props.bucket

  /** Создаёт бакет при старте, если его ещё нет. */
  @PostConstruct
  def ensureBucket(): Unit =
    try s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
    catch
      case e: S3Exception if e.statusCode() == 404 =>
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        log.info("Created S3 bucket '{}'", bucket)

  def put(key: String, content: InputStream, sizeBytes: Long, contentType: String): Unit =
    s3.putObject(
      PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
      RequestBody.fromInputStream(content, sizeBytes)
    )

  def putString(key: String, content: String, contentType: String): Unit =
    s3.putObject(
      PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
      RequestBody.fromString(content, StandardCharsets.UTF_8)
    )

  /** Открывает объект для чтения; поток нужно закрыть. `None`, если объекта нет. */
  def open(key: String): Option[ResponseInputStream[GetObjectResponse]] =
    try Some(s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()))
    catch case _: NoSuchKeyException => None

  def readString(key: String): Option[String] =
    open(key).map { in =>
      try new String(in.readAllBytes(), StandardCharsets.UTF_8)
      finally in.close()
    }

  def delete(key: String): Unit =
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())

  /** Удаляет все объекты с префиксом, например все файлы одной лекции. */
  def deletePrefix(prefix: String): Unit =
    val keys = s3
      .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
      .contents()
      .asScala
      .map(obj => ObjectIdentifier.builder().key(obj.key()).build())

    // DeleteObjects принимает не больше 1000 ключей за запрос
    keys.grouped(1000).foreach { batch =>
      s3.deleteObjects(
        DeleteObjectsRequest.builder()
          .bucket(bucket)
          .delete(Delete.builder().objects(batch.toSeq.asJava).quiet(true).build())
          .build()
      )
    }
