package tacos.data;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoRating;

public interface TacoRatingRepository extends ReactiveCrudRepository<TacoRating, String> {

  Mono<TacoRating> findByUserIdAndTacoId(String userId, String tacoId);

  Flux<TacoRating> findByTacoId(String tacoId);
}
