package tacos.web.api;

public class BusinessRuleException extends RuntimeException {

  private final String code;

  public BusinessRuleException(String arg1, String arg2) {
    super(resolveMessage(arg1, arg2));
    this.code = resolveCode(arg1, arg2);
  }

  public BusinessRuleException(String message) {
    super(message);
    this.code = isCode(message) ? message : "BUSINESS_RULE_VIOLATION";
  }

  private static boolean isCode(String s) {
    return s != null && s.matches("^[A-Z0-9_]+$");
  }

  private static String resolveCode(String a, String b) {
    if (isCode(a)) return a;
    if (isCode(b)) return b;
    return a != null ? a : "BUSINESS_RULE_VIOLATION";
  }

  private static String resolveMessage(String a, String b) {
    if (isCode(a) && b != null) return b;
    if (isCode(b) && a != null) return a;
    return b != null ? b : (a != null ? a : "");
  }

  public String getCode() {
    return code;
  }

  public String getErrorCode() {
    return code;
  }
}
