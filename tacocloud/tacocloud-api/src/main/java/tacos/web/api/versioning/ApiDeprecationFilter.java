package tacos.web.api.versioning;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApiDeprecationFilter implements WebFilter {

  public static final String WARNING_HEADER = "Warning";
  public static final String DEPRECATION_WARNING = "299 - \"Deprecated API endpoint. Please migrate to /api/v1\"";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();
    if (path != null && path.startsWith("/api/") && !path.startsWith("/api/v1/") && !path.startsWith("/api/admin/")) {
      exchange.getResponse().getHeaders().add(WARNING_HEADER, DEPRECATION_WARNING);
    }
    return chain.filter(exchange);
  }
}
