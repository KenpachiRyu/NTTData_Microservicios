package tacos.metrics;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Health indicator reactivo que expone la salud del outbox transaccional y DLQ (TC-32).
 *
 * Reporta UP bajo condiciones normales de backlog. Si el backlog pendiente supera el umbral seguro
 * (ej. 500 eventos acumulados), transiciona a DEGRADED sin exponer datos sensibles ni credenciales.
 */
@Component
public class OutboxHealthIndicator implements ReactiveHealthIndicator {

  public static final long BACKLOG_DEGRADED_THRESHOLD = 500L;

  private final TacoBusinessMetrics businessMetrics;

  public OutboxHealthIndicator(TacoBusinessMetrics businessMetrics) {
    this.businessMetrics = businessMetrics;
  }

  @Override
  public Mono<Health> health() {
    return Mono.fromCallable(() -> {
      long outboxBacklog = businessMetrics.getOutboxBacklog();
      long dlqBacklog = businessMetrics.getDlqBacklog();

      if (outboxBacklog > BACKLOG_DEGRADED_THRESHOLD) {
        return Health.status("DEGRADED")
            .withDetail("reason", "Outbox backlog exceeds threshold")
            .withDetail("outboxBacklog", outboxBacklog)
            .withDetail("dlqBacklog", dlqBacklog)
            .build();
      }

      return Health.up()
          .withDetail("outboxBacklog", outboxBacklog)
          .withDetail("dlqBacklog", dlqBacklog)
          .build();
    });
  }
}
