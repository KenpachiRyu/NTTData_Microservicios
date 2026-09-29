package tacos.web.api;

import java.util.UUID;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public class SimulatedPaymentGateway implements PaymentGateway {

  @Override
  public Mono<TokenizeResponse> tokenize(TokenizeRequest request) {
    if (request == null || request.getCardNumber() == null || request.getCardNumber().trim().isEmpty()) {
      return Mono.error(new IllegalArgumentException("Card number is required"));
    }
    String pan = request.getCardNumber().replaceAll("\\s+|-", "");
    String last4 = pan.length() >= 4 ? pan.substring(pan.length() - 4) : pan;
    String brand = detectBrand(pan);
    String token = "tok_" + UUID.randomUUID().toString().replace("-", "");

    // El CVV se descarta de forma inmediata y estricta; no se almacena ni se devuelve
    return Mono.just(TokenizeResponse.builder()
        .paymentToken(token)
        .brand(brand)
        .last4(last4)
        .expiration(request.getExpiration())
        .build());
  }

  private String detectBrand(String pan) {
    if (pan.startsWith("4")) {
      return "VISA";
    }
    if (pan.startsWith("5")) {
      return "MASTERCARD";
    }
    if (pan.startsWith("3")) {
      return "AMEX";
    }
    return "UNKNOWN";
  }
}
