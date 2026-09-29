package tacos.data;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import reactor.core.publisher.Mono;
import tacos.ProcessedEvent;

public interface ProcessedEventRepository extends ReactiveMongoRepository<ProcessedEvent, String> {

  Mono<ProcessedEvent> findByEventIdAndConsumerName(String eventId, String consumerName);

  Mono<Boolean> existsByEventIdAndConsumerName(String eventId, String consumerName);

}
