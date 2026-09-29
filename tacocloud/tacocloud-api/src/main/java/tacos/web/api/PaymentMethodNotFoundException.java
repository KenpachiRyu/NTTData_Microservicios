package tacos.web.api;

public class PaymentMethodNotFoundException extends RuntimeException {
  public PaymentMethodNotFoundException(String userId) {
    super("Payment method not found for user: " + userId);
  }
}
