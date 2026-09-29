package tacos.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class BrokerSelectionTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  @DisplayName("Transporte noop activa exactamente NoOpOrderMessagingService")
  void testNoopTransportActivatesSingleBean() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=noop")
        .withUserConfiguration(NoOpOrderMessagingService.class, MessagingTransportValidator.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OrderMessagingService.class);
          assertThat(context).getBean(OrderMessagingService.class).isInstanceOf(NoOpOrderMessagingService.class);
        });
  }

  @Test
  @DisplayName("Default sin propiedad activa NoOpOrderMessagingService (matchIfMissing)")
  void testDefaultTransportActivatesNoop() {
    contextRunner
        .withUserConfiguration(NoOpOrderMessagingService.class, MessagingTransportValidator.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OrderMessagingService.class);
          assertThat(context).getBean(OrderMessagingService.class).isInstanceOf(NoOpOrderMessagingService.class);
        });
  }

  @Test
  @DisplayName("Transporte inválido falla en el arranque con mensaje descriptivo")
  void testInvalidTransportFailsStartup() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=unknown-broker")
        .withUserConfiguration(NoOpOrderMessagingService.class, MessagingTransportValidator.class)
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(IllegalStateException.class)
              .hasRootCauseMessage("Transporte de mensajería desconocido: 'unknown-broker'. Valores permitidos: noop, jms, rabbit, kafka");
        });
  }
}
