package tacos.data;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import reactor.core.publisher.Flux;
import tacos.OutboxEvent;
import tacos.OutboxStatus;

public interface OutboxEventRepository extends ReactiveMongoRepository<OutboxEvent, String> {

  Flux<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);

  Flux<OutboxEvent> findByStatusInOrderByCreatedAtAsc(Iterable<OutboxStatus> statuses);

}
