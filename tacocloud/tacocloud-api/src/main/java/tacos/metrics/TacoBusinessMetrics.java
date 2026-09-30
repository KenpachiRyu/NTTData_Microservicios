package tacos.metrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Servicio centralizado de métricas de negocio para Taco Cloud (TC-32).
 *
 * Utiliza Micrometer con tags de baja cardinalidad ('status', 'source', 'result', 'ingredient', 'type').
 * Nunca expone ni utiliza identificadores de alta cardinalidad como tags (p. ej. orderId, userId, correlationId).
 * Los gauges registran el backlog de outbox y DLQ de forma segura y no bloqueante.
 */
@Component
public class TacoBusinessMetrics {

  private final MeterRegistry meterRegistry;
  private final AtomicLong outboxBacklog = new AtomicLong(0);
  private final AtomicLong dlqBacklog = new AtomicLong(0);

  @Autowired
  public TacoBusinessMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    this.meterRegistry.gauge("tacocloud.outbox.backlog", outboxBacklog);
    this.meterRegistry.gauge("tacocloud.dlq.backlog", dlqBacklog);
  }

  public void countOrderCreated(String source) {
    meterRegistry.counter("tacocloud.orders.count", "status", "created", "source", normalizeSource(source)).increment();
  }

  public void countOrderFailed(String source, String reason) {
    meterRegistry.counter("tacocloud.orders.count", "status", "failed", "source", normalizeSource(source)).increment();
  }

  public void countOrderCancelled(String source) {
    meterRegistry.counter("tacocloud.orders.count", "status", "cancelled", "source", normalizeSource(source)).increment();
  }

  public void countCouponApplied(String result) {
    meterRegistry.counter("tacocloud.coupons.applied", "result", result != null ? result : "unknown").increment();
  }

  public void countInventoryRejected(String ingredient) {
    meterRegistry.counter("tacocloud.inventory.rejected", "ingredient", ingredient != null ? ingredient : "unknown").increment();
  }

  public void countDlqEvent(String type) {
    meterRegistry.counter("tacocloud.dlq.events", "type", type != null ? type : "unknown").increment();
  }

  public void recordPlacementTime(Duration duration, String status) {
    Timer.builder("tacocloud.orders.placement.time")
        .tag("status", status != null ? status : "success")
        .register(meterRegistry)
        .record(duration);
  }

  public void recordKitchenProcessingTime(Duration duration, String status) {
    Timer.builder("tacocloud.kitchen.processing.time")
        .tag("status", status != null ? status : "success")
        .register(meterRegistry)
        .record(duration);
  }

  public void setOutboxBacklog(long count) {
    outboxBacklog.set(count);
  }

  public void setDlqBacklog(long count) {
    dlqBacklog.set(count);
  }

  public long getOutboxBacklog() {
    return outboxBacklog.get();
  }

  public long getDlqBacklog() {
    return dlqBacklog.get();
  }

  public MeterRegistry getMeterRegistry() {
    return meterRegistry;
  }

  private String normalizeSource(String source) {
    return (source != null && !source.trim().isEmpty()) ? source.toLowerCase() : "api";
  }
}
