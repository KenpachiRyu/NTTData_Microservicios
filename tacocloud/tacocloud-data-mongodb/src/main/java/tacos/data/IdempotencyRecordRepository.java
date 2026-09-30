package tacos.data;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import reactor.core.publisher.Mono;
import tacos.IdempotencyRecord;

public interface IdempotencyRecordRepository extends ReactiveMongoRepository<IdempotencyRecord, String> {

  Mono<IdempotencyRecord> findByKeyAndUserId(String key, String userId);

  Mono<Boolean> existsByKeyAndUserId(String key, String userId);

}
