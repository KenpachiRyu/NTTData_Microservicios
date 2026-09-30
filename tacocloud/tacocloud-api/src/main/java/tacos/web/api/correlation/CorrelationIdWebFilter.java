package tacos.web.api.correlation;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Filtro reactivo WebFlux para trazabilidad distribuida mediante Correlation ID (TC-31).
 *
 * 1. Inspecciona el header HTTP 'X-Correlation-Id'.
 * 2. Valida la cadena contra longitud y carácteres de control/CRLF para prevenir Log Injection.
 * 3. Si no existe o es inválido, genera un UUID seguro.
 * 4. Añade 'X-Correlation-Id' a los headers de respuesta HTTP.
 * 5. Propaga el correlation ID en el Reactor Context y en MDC de SLF4J.
 * 6. Garantiza la limpieza de MDC al finalizar la petición para evitar contaminación de hilos.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String incomingHeader = exchange.getRequest().getHeaders().getFirst(CorrelationContext.CORRELATION_ID_HEADER);
    String correlationId = CorrelationContext.sanitizeOrGenerate(incomingHeader);

    // Adjuntar header en la respuesta HTTP
    exchange.getResponse().getHeaders().set(CorrelationContext.CORRELATION_ID_HEADER, correlationId);

    // Configurar MDC para el hilo actual y garantizar limpieza al finalizar
    MDC.put(CorrelationContext.MDC_KEY, correlationId);

    return chain.filter(exchange)
        .doOnEach(signal -> {
          // Re-sincronizar MDC si el operador reactivo salta entre hilos del pool
          if (signal.getContextView().hasKey(CorrelationContext.CORRELATION_ID_KEY)) {
            MDC.put(CorrelationContext.MDC_KEY, signal.getContextView().get(CorrelationContext.CORRELATION_ID_KEY));
          }
        })
        .contextWrite(ctx -> ctx.put(CorrelationContext.CORRELATION_ID_KEY, correlationId))
        .doFinally(signalType -> MDC.remove(CorrelationContext.MDC_KEY));
  }
}
