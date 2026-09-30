package tacos.web.api;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.springframework.core.codec.DecodingException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class RestExceptionHandler {

  private static final MediaType PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

  private String extractPath(HttpServletRequest request) {
    return (request != null && request.getRequestURI() != null) ? request.getRequestURI() : "/api/v1";
  }

  @ExceptionHandler({
      MethodArgumentNotValidException.class,
      BindException.class,
      WebExchangeBindException.class
  })
  public ResponseEntity<ApiProblem> handleValidationException(Exception ex, HttpServletRequest request) {
    List<ApiProblem.Violation> violations = Collections.emptyList();
    if (ex instanceof MethodArgumentNotValidException) {
      violations = ((MethodArgumentNotValidException) ex).getBindingResult().getFieldErrors().stream()
          .map(fe -> new ApiProblem.Violation(fe.getField(), fe.getDefaultMessage()))
          .collect(Collectors.toList());
    } else if (ex instanceof BindException) {
      violations = ((BindException) ex).getFieldErrors().stream()
          .map(fe -> new ApiProblem.Violation(fe.getField(), fe.getDefaultMessage()))
          .collect(Collectors.toList());
    } else if (ex instanceof WebExchangeBindException) {
      violations = ((WebExchangeBindException) ex).getFieldErrors().stream()
          .map(fe -> new ApiProblem.Violation(fe.getField(), fe.getDefaultMessage()))
          .collect(Collectors.toList());
    }

    ApiProblem problem = ApiProblem.builder()
        .type(URI.create("https://tacocloud.com/probs/validation-error"))
        .title("Validation Error")
        .status(HttpStatus.BAD_REQUEST.value())
        .detail("Input validation failed for one or more fields")
        .instance(extractPath(request))
        .code("VALIDATION_FAILED")
        .correlationId(UUID.randomUUID().toString())
        .violations(violations)
        .build();

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler({
      UserNotFoundException.class,
      PaymentMethodNotFoundException.class,
      IngredientNotFoundException.class
  })
  public ResponseEntity<ApiProblem> handleNotFoundException(RuntimeException ex, HttpServletRequest request) {
    ApiProblem problem = ApiProblem.builder()
        .type(URI.create("https://tacocloud.com/probs/resource-not-found"))
        .title("Resource Not Found")
        .status(HttpStatus.NOT_FOUND.value())
        .detail(ex.getMessage())
        .instance(extractPath(request))
        .code("RESOURCE_NOT_FOUND")
        .correlationId(UUID.randomUUID().toString())
        .build();

    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<ApiProblem> handleDuplicateKeyException(DuplicateKeyException ex, HttpServletRequest request) {
    ApiProblem problem = ApiProblem.builder()
        .type(URI.create("https://tacocloud.com/probs/conflict"))
        .title("Conflict")
        .status(HttpStatus.CONFLICT.value())
        .detail("The specified resource or key already exists")
        .instance(extractPath(request))
        .code("DUPLICATE_RESOURCE")
        .correlationId(UUID.randomUUID().toString())
        .build();

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
  public ResponseEntity<ApiProblem> handleOptimisticLockingException(org.springframework.dao.OptimisticLockingFailureException ex, HttpServletRequest request) {
    ApiProblem problem = ApiProblem.builder()
        .type(URI.create("https://tacocloud.com/probs/optimistic-lock-conflict"))
        .title("Version Conflict")
        .status(HttpStatus.CONFLICT.value())
        .detail("El recurso fue modificado concurrentemente por otra transacción. Intente de nuevo.")
        .instance(extractPath(request))
        .code("VERSION_CONFLICT")
        .correlationId(UUID.randomUUID().toString())
        .build();

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler(BusinessRuleException.class)
  public ResponseEntity<ApiProblem> handleBusinessRuleException(BusinessRuleException ex, HttpServletRequest request) {
    HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
    if (ex.getCode() != null && ("INVALID_ORDER_STATE_TRANSITION".equals(ex.getCode()) || ex.getCode().contains("CONFLICT") || ex.getCode().startsWith("IDEMPOTENCY_"))) {
      status = HttpStatus.CONFLICT;
    } else if (ex.getCode() != null && (ex.getCode().startsWith("INVALID_") || "BAD_REQUEST".equals(ex.getCode()))) {
      status = HttpStatus.BAD_REQUEST;
    }

    String probType = "business-rule-violation";
    String title = "Unprocessable Entity";
    if (status == HttpStatus.CONFLICT) {
      probType = "conflict";
      title = "Conflict";
    } else if (status == HttpStatus.BAD_REQUEST) {
      probType = "bad-request";
      title = "Bad Request";
    }

    ApiProblem problem = ApiProblem.builder()
        .type(URI.create("https://tacocloud.com/probs/" + probType))
        .title(title)
        .status(status.value())
        .detail(ex.getMessage())
        .instance(extractPath(request))
        .code(ex.getCode())
        .correlationId(UUID.randomUUID().toString())
        .build();

    return ResponseEntity.status(status)
        .contentType(PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler({
      IllegalArgumentException.class,
      DecodingException.class,
      HttpMessageNotReadableException.class,
      ServerWebInputException.class
  })
  public ResponseEntity<ApiProblem> handleBadRequest(Exception ex, HttpServletRequest request) {
    ApiProblem problem = ApiProblem.builder()
        .type(URI.create("https://tacocloud.com/probs/bad-request"))
        .title("Bad Request")
        .status(HttpStatus.BAD_REQUEST.value())
        .detail(ex.getMessage() != null ? ex.getMessage() : "Invalid request syntax or parameters")
        .instance(extractPath(request))
        .code("BAD_REQUEST")
        .correlationId(UUID.randomUUID().toString())
        .build();

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(PROBLEM_JSON)
        .body(problem);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiProblem> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
    ApiProblem problem = ApiProblem.builder()
        .type(URI.create("https://tacocloud.com/probs/access-denied"))
        .title("Forbidden")
        .status(HttpStatus.FORBIDDEN.value())
        .detail("Access to the requested resource is denied")
        .instance(extractPath(request))
        .code("ACCESS_DENIED")
        .correlationId(UUID.randomUUID().toString())
        .build();

    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(PROBLEM_JSON)
        .body(problem);
  }
}
