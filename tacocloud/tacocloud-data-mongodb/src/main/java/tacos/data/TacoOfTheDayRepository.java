package tacos.data;

import java.time.LocalDate;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import tacos.TacoOfTheDayConfig;

public interface TacoOfTheDayRepository extends ReactiveCrudRepository<TacoOfTheDayConfig, String> {

  Mono<TacoOfTheDayConfig> findByDate(LocalDate date);
}
