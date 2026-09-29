package tacos.web.api;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoOfTheDayConfig;
import tacos.data.TacoOfTheDayRepository;
import tacos.data.TacoRepository;

@Service
public class TacoOfTheDayService {

  private final TacoRepository tacoRepo;
  private final TacoOfTheDayRepository tacoOfTheDayRepo;
  private final Clock clock;

  @Autowired
  public TacoOfTheDayService(TacoRepository tacoRepo,
                             TacoOfTheDayRepository tacoOfTheDayRepo,
                             @Autowired(required = false) Clock clock) {
    this.tacoRepo = tacoRepo;
    this.tacoOfTheDayRepo = tacoOfTheDayRepo;
    this.clock = clock != null ? clock : Clock.systemDefaultZone();
  }

  public Mono<TacoOfTheDayResponse> getTacoOfTheDay() {
    LocalDate today = LocalDate.now(clock);

    // 1. Revisar si existe configuración explícita fijada por ADMIN para la fecha
    return tacoOfTheDayRepo.findByDate(today)
        .flatMap(config -> tacoRepo.findById(config.getTacoId())
            .filter(this::isTacoAvailable)
            .map(taco -> new TacoOfTheDayResponse(taco, today, config.getReason() != null ? config.getReason() : "Selección destacada por el chef"))
        )
        .switchIfEmpty(Mono.defer(() -> computeDeterministicTaco(today)));
  }

  private boolean isTacoAvailable(Taco taco) {
    if (taco.getIngredients() == null || taco.getIngredients().isEmpty()) {
      return false;
    }
    return taco.getIngredients().stream()
        .allMatch(ing -> ing.getAvailable() == null || Boolean.TRUE.equals(ing.getAvailable()));
  }

  private Mono<TacoOfTheDayResponse> computeDeterministicTaco(LocalDate date) {
    return tacoRepo.findAll()
        .filter(this::isTacoAvailable)
        .collectList()
        .flatMap(candidates -> {
          if (candidates.isEmpty()) {
            return Mono.empty();
          }

          // Ordenar candidatos por ID de forma estrictamente estable
          candidates.sort(Comparator.comparing(Taco::getId, Comparator.nullsLast(String::compareTo)));

          // Cálculo determinista basado en el hash de la fecha
          int index = Math.abs(date.hashCode()) % candidates.size();
          Taco selected = candidates.get(index);
          String reason = "Selección determinista recomendada para el día " + date;

          return Mono.just(new TacoOfTheDayResponse(selected, date, reason));
        });
  }

  public Mono<TacoOfTheDayConfig> setTacoOfTheDay(String tacoId, LocalDate date, String reason) {
    LocalDate targetDate = date != null ? date : LocalDate.now(clock);
    String id = targetDate.toString();

    return tacoRepo.findById(tacoId)
        .switchIfEmpty(Mono.error(new BusinessRuleException("El taco especificado (" + tacoId + ") no existe", "TACO_NOT_FOUND")))
        .flatMap(taco -> {
          TacoOfTheDayConfig config = new TacoOfTheDayConfig(
              id,
              taco.getId(),
              targetDate,
              reason != null ? reason : "Selección administrativa"
          );
          return tacoOfTheDayRepo.save(config);
        });
  }
}
