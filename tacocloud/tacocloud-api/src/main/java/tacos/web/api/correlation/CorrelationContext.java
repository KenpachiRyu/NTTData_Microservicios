package tacos.web.api.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

import reactor.core.publisher.Mono;

public final class CorrelationContext {

  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  public static final String CORRELATION_ID_KEY = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  private static final int MAX_LENGTH = 64;
  private static final Pattern SAFE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

  private CorrelationContext() {
  }

  public static boolean isValid(String correlationId) {
    if (correlationId == null) {
      return false;
    }
    String trimmed = correlationId.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
      return false;
    }
    // Previene CRLF injection y carácteres de control
    return SAFE_PATTERN.matcher(trimmed).matches();
  }

  public static String sanitizeOrGenerate(String correlationId) {
    if (isValid(correlationId)) {
      return correlationId.trim();
    }
    return UUID.randomUUID().toString();
  }

  public static Mono<String> getCorrelationId() {
    return Mono.deferContextual(ctx -> {
      if (ctx.hasKey(CORRELATION_ID_KEY)) {
        return Mono.just(ctx.get(CORRELATION_ID_KEY));
      }
      return Mono.empty();
    });
  }
}
