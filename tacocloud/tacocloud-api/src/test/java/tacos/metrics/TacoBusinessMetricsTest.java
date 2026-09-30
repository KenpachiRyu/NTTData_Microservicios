package tacos.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class TacoBusinessMetricsTest {

  private MeterRegistry meterRegistry;
  private TacoBusinessMetrics businessMetrics;
  private OutboxHealthIndicator healthIndicator;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    businessMetrics = new TacoBusinessMetrics(meterRegistry);
    healthIndicator = new OutboxHealthIndicator(businessMetrics);
  }

  @Test
  @DisplayName("Contadores de pedidos registran métricas con tags de baja cardinalidad (status, source)")
  void testOrderCounters() {
    businessMetrics.countOrderCreated("web");
    businessMetrics.countOrderCreated("web");
    businessMetrics.countOrderFailed("api", "validation");
    businessMetrics.countOrderCancelled("web");

    Counter createdCounter = meterRegistry.find("tacocloud.orders.count")
        .tag("status", "created")
        .tag("source", "web")
        .counter();
    assertThat(createdCounter).isNotNull();
    assertThat(createdCounter.count()).isEqualTo(2.0);

    Counter failedCounter = meterRegistry.find("tacocloud.orders.count")
        .tag("status", "failed")
        .tag("source", "api")
        .counter();
    assertThat(failedCounter).isNotNull();
    assertThat(failedCounter.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Contadores de negocio registran cupones, inventario rechazado y eventos DLQ")
  void testBusinessCounters() {
    businessMetrics.countCouponApplied("applied");
    businessMetrics.countInventoryRejected("CARN");
    businessMetrics.countDlqEvent("ORDER_CREATED");

    assertThat(meterRegistry.find("tacocloud.coupons.applied").tag("result", "applied").counter().count()).isEqualTo(1.0);
    assertThat(meterRegistry.find("tacocloud.inventory.rejected").tag("ingredient", "CARN").counter().count()).isEqualTo(1.0);
    assertThat(meterRegistry.find("tacocloud.dlq.events").tag("type", "ORDER_CREATED").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Timers de colocación y cocina registran latencia sin tags de alta cardinalidad")
  void testTimers() {
    businessMetrics.recordPlacementTime(Duration.ofMillis(120), "success");
    businessMetrics.recordKitchenProcessingTime(Duration.ofMillis(350), "success");

    Timer placementTimer = meterRegistry.find("tacocloud.orders.placement.time")
        .tag("status", "success")
        .timer();
    assertThat(placementTimer).isNotNull();
    assertThat(placementTimer.count()).isEqualTo(1L);

    Timer kitchenTimer = meterRegistry.find("tacocloud.kitchen.processing.time")
        .tag("status", "success")
        .timer();
    assertThat(kitchenTimer).isNotNull();
    assertThat(kitchenTimer.count()).isEqualTo(1L);
  }

  @Test
  @DisplayName("Gauges de backlog outbox y DLQ reflejan los valores atómicos de forma no bloqueante")
  void testGauges() {
    businessMetrics.setOutboxBacklog(42);
    businessMetrics.setDlqBacklog(3);

    Double outboxGauge = meterRegistry.find("tacocloud.outbox.backlog").gauge().value();
    Double dlqGauge = meterRegistry.find("tacocloud.dlq.backlog").gauge().value();

    assertThat(outboxGauge).isEqualTo(42.0);
    assertThat(dlqGauge).isEqualTo(3.0);
  }

  @Test
  @DisplayName("OutboxHealthIndicator reporta UP cuando el backlog es normal y DEGRADED cuando supera el umbral")
  void testHealthIndicator() {
    businessMetrics.setOutboxBacklog(50);
    businessMetrics.setDlqBacklog(2);

    Health normalHealth = healthIndicator.health().block();
    assertThat(normalHealth.getStatus()).isEqualTo(Status.UP);
    assertThat(normalHealth.getDetails().get("outboxBacklog")).isEqualTo(50L);
    assertThat(normalHealth.getDetails().get("dlqBacklog")).isEqualTo(2L);

    // Supera el umbral de 500
    businessMetrics.setOutboxBacklog(550);
    Health degradedHealth = healthIndicator.health().block();
    assertThat(degradedHealth.getStatus().getCode()).isEqualTo("DEGRADED");
    assertThat(degradedHealth.getDetails().get("outboxBacklog")).isEqualTo(550L);
    assertThat(degradedHealth.getDetails().get("reason")).isNotNull();
  }
}
