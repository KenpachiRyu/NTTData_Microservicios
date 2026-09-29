package tacos.web.api;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path = "/api/payment-methods", produces = "application/json")
@CrossOrigin(origins = "*")
public class PaymentApiController {

  private final PaymentGateway paymentGateway;

  public PaymentApiController(PaymentGateway paymentGateway) {
    this.paymentGateway = paymentGateway;
  }

  @PostMapping(path = "/tokenize", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<TokenizeResponse> tokenize(@Valid @RequestBody TokenizeRequest request) {
    return paymentGateway.tokenize(request);
  }
}
