package tacos.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import tacos.IdempotencyRecord;
import tacos.data.IdempotencyRecordRepository;
import tacos.web.api.BusinessRuleException;
import tacos.web.api.OrderCreateRequest;
import tacos.web.api.OrderResponse;

@Service
public class IdempotencyService {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
  private static final Pattern VALID_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
  private static final long DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

  private final IdempotencyRecordRepository repository;
  private final ObjectMapper objectMapper;

  @Autowired
  public IdempotencyService(IdempotencyRecordRepository repository,
                            @Autowired(required = false) ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
  }

  public void validateKey(String key) {
    if (key == null || key.trim().isEmpty()) {
      throw new BusinessRuleException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must not be blank");
    }
    if (key.length() > 64) {
      throw new BusinessRuleException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must not exceed 64 characters");
    }
    if (!VALID_KEY_PATTERN.matcher(key).matches()) {
      throw new BusinessRuleException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key contains invalid characters");
    }
  }

  public String computePayloadHash(OrderCreateRequest request) {
    try {
      String json = objectMapper.writeValueAsString(request);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (Exception e) {
      throw new RuntimeException("Error computing idempotency hash", e);
    }
  }

  public Mono<OrderResponse> process(String idempotencyKey,
                                     String userId,
                                     OrderCreateRequest request,
                                     Mono<OrderResponse> orderExecution) {
    if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
      return orderExecution;
    }

    validateKey(idempotencyKey);
    String effectiveUserId = (userId != null && !userId.trim().isEmpty()) ? userId : "anonymous";
    String payloadHash = computePayloadHash(request);

    return repository.findByKeyAndUserId(idempotencyKey, effectiveUserId)
        .flatMap(existingRecord -> handleExistingRecord(existingRecord, payloadHash, idempotencyKey, effectiveUserId, orderExecution))
        .switchIfEmpty(Mono.defer(() -> claimAndExecute(idempotencyKey, effectiveUserId, payloadHash, orderExecution)));
  }

  private Mono<OrderResponse> handleExistingRecord(IdempotencyRecord record,
                                                  String currentHash,
                                                  String key,
                                                  String userId,
                                                  Mono<OrderResponse> orderExecution) {
    // Check payload mismatch
    if (!record.getRequestHash().equals(currentHash)) {
      return Mono.error(new BusinessRuleException("IDEMPOTENCY_PAYLOAD_MISMATCH",
          "Idempotency-Key '" + key + "' was previously used with a different request payload"));
    }

    if (IdempotencyRecord.STATUS_IN_PROGRESS.equals(record.getStatus())) {
      return Mono.error(new BusinessRuleException("IDEMPOTENCY_IN_PROGRESS",
          "A request with Idempotency-Key '" + key + "' is currently in progress. Please retry shortly."));
    }

    if (IdempotencyRecord.STATUS_COMPLETED.equals(record.getStatus())) {
      try {
        OrderResponse cachedResponse = objectMapper.readValue(record.getResponseBody(), OrderResponse.class);
        log.info("Idempotent replay served for key='{}', user='{}'", key, userId);
        return Mono.just(cachedResponse);
      } catch (Exception e) {
        log.error("Failed to deserialize cached response for key='{}'", key, e);
        return Mono.error(new RuntimeException("Failed to read cached idempotent response", e));
      }
    }

    // If previously failed, allow retry by updating status back to IN_PROGRESS
    record.setStatus(IdempotencyRecord.STATUS_IN_PROGRESS);
    record.setCreatedAt(new Date());
    return repository.save(record)
        .then(executeAndSaveResult(record, orderExecution));
  }

  private Mono<OrderResponse> claimAndExecute(String key,
                                             String userId,
                                             String payloadHash,
                                             Mono<OrderResponse> orderExecution) {
    IdempotencyRecord newRecord = IdempotencyRecord.builder()
        .key(key)
        .userId(userId)
        .requestHash(payloadHash)
        .status(IdempotencyRecord.STATUS_IN_PROGRESS)
        .createdAt(new Date())
        .expiresAt(new Date(System.currentTimeMillis() + DEFAULT_TTL_MS))
        .build();

    return repository.insert(newRecord)
        .flatMap(savedRecord -> executeAndSaveResult(savedRecord, orderExecution))
        .onErrorResume(DuplicateKeyException.class, ex ->
            // Race condition: another request claimed the key simultaneously
            repository.findByKeyAndUserId(key, userId)
                .flatMap(existing -> handleExistingRecord(existing, payloadHash, key, userId, orderExecution))
        );
  }

  private Mono<OrderResponse> executeAndSaveResult(IdempotencyRecord record, Mono<OrderResponse> orderExecution) {
    return orderExecution
        .flatMap(response -> {
          try {
            String json = objectMapper.writeValueAsString(response);
            record.setStatus(IdempotencyRecord.STATUS_COMPLETED);
            record.setStatusCode(201);
            record.setResponseBody(json);
            record.setCompletedAt(new Date());
            return repository.save(record).thenReturn(response);
          } catch (Exception e) {
            log.error("Failed to serialize response for idempotency record key='{}'", record.getKey(), e);
            return Mono.just(response);
          }
        })
        .onErrorResume(error -> {
          record.setStatus(IdempotencyRecord.STATUS_FAILED);
          return repository.save(record).then(Mono.error(error));
        });
  }
}
