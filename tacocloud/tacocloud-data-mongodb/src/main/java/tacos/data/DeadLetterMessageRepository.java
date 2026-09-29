package tacos.data;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import reactor.core.publisher.Flux;
import tacos.DeadLetterMessage;

public interface DeadLetterMessageRepository extends ReactiveMongoRepository<DeadLetterMessage, String> {

  Flux<DeadLetterMessage> findByReprocessedFalseOrderByFailedAtDesc();

}
