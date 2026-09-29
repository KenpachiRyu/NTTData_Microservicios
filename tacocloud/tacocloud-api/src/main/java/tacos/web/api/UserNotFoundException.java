package tacos.web.api;

public class UserNotFoundException extends RuntimeException {
  public UserNotFoundException(String email) {
    super("User not found for email: " + email);
  }
}
