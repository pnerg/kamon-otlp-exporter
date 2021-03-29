package org.dmonix.kamon.otel

import com.google.common.util.concurrent.{FutureCallback, Futures}
import com.typesafe.config.Config
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc.TraceServiceFutureStub
import io.opentelemetry.proto.collector.trace.v1.{ExportTraceServiceRequest, ExportTraceServiceResponse, TraceServiceGrpc}
import org.slf4j.LoggerFactory

import java.io.Closeable
import java.net.URL
import java.util.concurrent.{Executors, ThreadFactory, TimeUnit}
import scala.concurrent.{Future, Promise}

/**
 * Service for exporting OpenTelemetry traces
 */
private[otel] trait TraceService extends Closeable {
  def export(request:ExportTraceServiceRequest):Future[ExportTraceServiceResponse]
}

private[otel] object GrpcTraceService {
  private val logger = LoggerFactory.getLogger(classOf[GrpcTraceService])
  private val executor = Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(r: Runnable): Thread = new Thread(r, "OpenTelemetryTraceReporterRemote")
  })

  def apply(config: Config): GrpcTraceService = {
    val otelExporterConfig = config.getConfig("kamon.otel.trace")
    val protocol = otelExporterConfig.getString("protocol")
    val endpoint = otelExporterConfig.getString("endpoint")
    val url = new URL(endpoint)

    logger.info(s"Configured endpoint for OTLP trace reporting [${url.getHost}:${url.getPort}]")
    //TODO : stuff with TLS and possibly time-out settings...and other things I've missed
    //val builder = ManagedChannelBuilder.forTarget(endpoint)
    val builder = ManagedChannelBuilder.forAddress(url.getHost, url.getPort)
    if (protocol.equals("https"))
      builder.useTransportSecurity()
    else
      builder.usePlaintext()

    val channel = builder.build()
    new GrpcTraceService(
      channel,
      TraceServiceGrpc.newFutureStub(channel)
    )
  }
}

import org.dmonix.kamon.otel.GrpcTraceService._
/**
 * gRPC implementation of the OpenTelemetry trace service
 */
private[otel] class GrpcTraceService(channel:ManagedChannel, traceService:TraceServiceFutureStub) extends TraceService {

  override def export(request: ExportTraceServiceRequest): Future[ExportTraceServiceResponse] = {
    val promise = Promise[ExportTraceServiceResponse]()
    Futures.addCallback(traceService.export(request), exportCallback(promise), executor)
    promise.future
  }

  override def close(): Unit = {
    //TODO: close underlying channel and make sure all traces are flushed
    channel.shutdown()
    channel.awaitTermination(5, TimeUnit.SECONDS)
  }

  private def exportCallback(promise:Promise[ExportTraceServiceResponse]):FutureCallback[ExportTraceServiceResponse] =
    new FutureCallback[ExportTraceServiceResponse]() {
      override def onSuccess(result: ExportTraceServiceResponse): Unit = promise.success(result)
      override def onFailure(t: Throwable): Unit = promise.failure(t)
    }

}

