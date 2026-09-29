package tacos.web.api;

import reactor.core.publisher.Mono;

public interface PaymentGateway {

  Mono<TokenizeResponse> tokenize(TokenizeRequest request);

}
