package tacos.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.IdempotencyRecord;
import tacos.data.IdempotencyRecordRepository;
import tacos.web.api.BusinessRuleException;
import tacos.web.api.OrderCreateRequest;
import tacos.web.api.OrderResponse;

public class IdempotencyKeyTest {

  private IdempotencyRecordRepository repository;
  private ObjectMapper objectMapper;
  private IdempotencyService service;

  @BeforeEach
  void setUp() {
    repository = mock(IdempotencyRecordRepository.class);
    objectMapper = new ObjectMapper();
    service = new IdempotencyService(repository, objectMapper);
  }

  @Test
  @DisplayName("TC-34: Invalid Idempotency-Key format is rejected")
  void invalidKeyValidation() {
    assertThatThrownBy(() -> service.validateKey(""))
        .isInstanceOf(BusinessRuleException.class)
        .hasFieldOrPropertyWithValue("code", "INVALID_IDEMPOTENCY_KEY");

    assertThatThrownBy(() -> service.validateKey("a".repeat(65)))
        .isInstanceOf(BusinessRuleException.class)
        .hasFieldOrPropertyWithValue("code", "INVALID_IDEMPOTENCY_KEY");

    assertThatThrownBy(() -> service.validateKey("invalid key with spaces"))
        .isInstanceOf(BusinessRuleException.class)
        .hasFieldOrPropertyWithValue("code", "INVALID_IDEMPOTENCY_KEY");

    assertThatThrownBy(() -> service.validateKey("bad/key@name!"))
        .isInstanceOf(BusinessRuleException.class)
        .hasFieldOrPropertyWithValue("code", "INVALID_IDEMPOTENCY_KEY");
  }

  @Test
  @DisplayName("TC-34: First request claims key and executes action atomically")
  void firstRequestExecution() {
    String key = "order-test-key-1";
    String userId = "user-craig";

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Craig Walls");

    OrderResponse expectedResponse = new OrderResponse();
    expectedResponse.setId("order-100");
    expectedResponse.setDeliveryName("Craig Walls");

    when(repository.findByKeyAndUserId(key, userId)).thenReturn(Mono.empty());
    when(repository.insert(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(repository.save(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    AtomicInteger counter = new AtomicInteger(0);
    Mono<OrderResponse> action = Mono.fromCallable(() -> {
      counter.incrementAndGet();
      return expectedResponse;
    });

    StepVerifier.create(service.process(key, userId, req, action))
        .expectNextMatches(resp -> resp.getId().equals("order-100"))
        .verifyComplete();

    assertThat(counter.get()).isEqualTo(1);
    verify(repository).insert(any(IdempotencyRecord.class));
    verify(repository).save(any(IdempotencyRecord.class));
  }

  @Test
  @DisplayName("TC-34: Sequential duplicate returns cached response without re-executing action")
  void sequentialDuplicateReturnsCached() throws Exception {
    String key = "order-test-key-1";
    String userId = "user-craig";

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Craig Walls");
    String hash = service.computePayloadHash(req);

    OrderResponse cachedResponse = new OrderResponse();
    cachedResponse.setId("order-100");
    cachedResponse.setDeliveryName("Craig Walls");
    String cachedJson = objectMapper.writeValueAsString(cachedResponse);

    IdempotencyRecord existingRecord = IdempotencyRecord.builder()
        .key(key)
        .userId(userId)
        .requestHash(hash)
        .status(IdempotencyRecord.STATUS_COMPLETED)
        .statusCode(201)
        .responseBody(cachedJson)
        .build();

    when(repository.findByKeyAndUserId(key, userId)).thenReturn(Mono.just(existingRecord));

    AtomicInteger counter = new AtomicInteger(0);
    Mono<OrderResponse> action = Mono.fromCallable(() -> {
      counter.incrementAndGet();
      return cachedResponse;
    });

    StepVerifier.create(service.process(key, userId, req, action))
        .expectNextMatches(resp -> resp.getId().equals("order-100") && "Craig Walls".equals(resp.getDeliveryName()))
        .verifyComplete();

    // The action must NOT have been executed again
    assertThat(counter.get()).isEqualTo(0);
  }

  @Test
  @DisplayName("TC-34: Duplicate key with different payload returns 409 Conflict")
  void differentPayloadReturnsConflict() {
    String key = "order-test-key-1";
    String userId = "user-craig";

    OrderCreateRequest initialReq = new OrderCreateRequest();
    initialReq.setDeliveryName("Craig Walls");
    String initialHash = service.computePayloadHash(initialReq);

    IdempotencyRecord existingRecord = IdempotencyRecord.builder()
        .key(key)
        .userId(userId)
        .requestHash(initialHash)
        .status(IdempotencyRecord.STATUS_COMPLETED)
        .build();

    when(repository.findByKeyAndUserId(key, userId)).thenReturn(Mono.just(existingRecord));

    // Send different payload with same key
    OrderCreateRequest alteredReq = new OrderCreateRequest();
    alteredReq.setDeliveryName("Malicious Attacker");

    StepVerifier.create(service.process(key, userId, alteredReq, Mono.empty()))
        .expectErrorMatches(err -> err instanceof BusinessRuleException
            && "IDEMPOTENCY_PAYLOAD_MISMATCH".equals(((BusinessRuleException) err).getCode()))
        .verify();
  }

  @Test
  @DisplayName("TC-34: Concurrent in-progress request returns 409 Conflict")
  void inProgressReturnsConflict() {
    String key = "order-test-key-1";
    String userId = "user-craig";

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Craig Walls");
    String hash = service.computePayloadHash(req);

    IdempotencyRecord inProgressRecord = IdempotencyRecord.builder()
        .key(key)
        .userId(userId)
        .requestHash(hash)
        .status(IdempotencyRecord.STATUS_IN_PROGRESS)
        .build();

    when(repository.findByKeyAndUserId(key, userId)).thenReturn(Mono.just(inProgressRecord));

    StepVerifier.create(service.process(key, userId, req, Mono.empty()))
        .expectErrorMatches(err -> err instanceof BusinessRuleException
            && "IDEMPOTENCY_IN_PROGRESS".equals(((BusinessRuleException) err).getCode()))
        .verify();
  }

  @Test
  @DisplayName("TC-34: User scoping ensures different users do not collide with same key")
  void userScopingIsolation() {
    String key = "order-common-key";
    String user1 = "alice";
    String user2 = "bob";

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Same Name");

    when(repository.findByKeyAndUserId(key, user1)).thenReturn(Mono.empty());
    when(repository.insert(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(repository.save(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderResponse response = new OrderResponse();
    response.setId("order-alice");

    StepVerifier.create(service.process(key, user1, req, Mono.just(response)))
        .expectNextMatches(r -> r.getId().equals("order-alice"))
        .verifyComplete();

    verify(repository).findByKeyAndUserId(key, user1);
  }
}
