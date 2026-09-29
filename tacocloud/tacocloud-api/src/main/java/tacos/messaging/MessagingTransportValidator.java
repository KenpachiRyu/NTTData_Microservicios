package tacos.messaging;

import java.util.Arrays;
import java.util.List;
import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingTransportValidator {

  private static final List<String> VALID_TRANSPORTS = Arrays.asList("noop", "jms", "rabbit", "kafka");

  @Value("${tacocloud.messaging.transport:noop}")
  private String transport;

  @Autowired(required = false)
  private List<OrderMessagingService> messagingServices;

  @PostConstruct
  public void validate() {
    if (transport == null || !VALID_TRANSPORTS.contains(transport.trim().toLowerCase())) {
      throw new IllegalStateException("Transporte de mensajería desconocido: '" + transport + "'. Valores permitidos: noop, jms, rabbit, kafka");
    }

    if (messagingServices != null && messagingServices.size() > 1) {
      throw new IllegalStateException("Se detectó más de un adaptador activo de OrderMessagingService (" + messagingServices.size() + "). Active sólo uno mediante 'tacocloud.messaging.transport'.");
    }
  }
}
