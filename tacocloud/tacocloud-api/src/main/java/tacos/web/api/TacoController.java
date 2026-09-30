package tacos.web.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoRating;
import tacos.data.TacoRepository;
import tacos.physics.TacoPhysicsValidator;
import tacos.physics.ValidationReport;

@RestController
@RequestMapping(path = {"/api/v1/tacos", "/api/tacos"}, produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class TacoController {

  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;
  private final TacoPhysicsValidator physicsValidator;
  private final TacoSearchService tacoSearchService;
  private final TacoOfTheDayService tacoOfTheDayService;
  private final TacoRatingService tacoRatingService;

  @Autowired
  public TacoController(TacoRepository tacoRepo,
                        @Autowired(required = false) TacoClassificationService classificationService,
                        @Autowired(required = false) TacoPhysicsValidator physicsValidator,
                        @Autowired(required = false) TacoSearchService tacoSearchService,
                        @Autowired(required = false) TacoOfTheDayService tacoOfTheDayService,
                        @Autowired(required = false) TacoRatingService tacoRatingService) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
    this.physicsValidator = physicsValidator;
    this.tacoSearchService = tacoSearchService;
    this.tacoOfTheDayService = tacoOfTheDayService;
    this.tacoRatingService = tacoRatingService;
  }

  public TacoController(TacoRepository tacoRepo) {
    this(tacoRepo, null, null, null, null, null);
  }

  // =========================================================================
  // TC-19: Búsqueda, filtros, ordenamiento y paginación
  // =========================================================================
  @GetMapping(params = "recent")
  public Flux<Taco> recentTacos() {
    return tacoRepo.findAll().take(12);
  }

  @GetMapping
  public Mono<PageResponse<Taco>> searchTacos(
      @org.springframework.web.bind.annotation.RequestParam(name = "q", required = false) String q,
      @org.springframework.web.bind.annotation.RequestParam(name = "name", required = false) String name,
      @org.springframework.web.bind.annotation.RequestParam(name = "tag", required = false) String tag,
      @org.springframework.web.bind.annotation.RequestParam(name = "diet", required = false) String diet,
      @org.springframework.web.bind.annotation.RequestParam(name = "excludeAllergen", required = false) String excludeAllergen,
      @org.springframework.web.bind.annotation.RequestParam(name = "spiceLevel", required = false) String spiceLevel,
      @org.springframework.web.bind.annotation.RequestParam(name = "spice", required = false) String spice,
      @org.springframework.web.bind.annotation.RequestParam(name = "ingredient", required = false) String ingredient,
      @org.springframework.web.bind.annotation.RequestParam(name = "ingredientId", required = false) String ingredientId,
      @org.springframework.web.bind.annotation.RequestParam(name = "sort", defaultValue = "createdAt") String sort,
      @org.springframework.web.bind.annotation.RequestParam(name = "dir", defaultValue = "desc") String dir,
      @org.springframework.web.bind.annotation.RequestParam(name = "page", defaultValue = "0") int page,
      @org.springframework.web.bind.annotation.RequestParam(name = "size", defaultValue = "12") int size) {

    String queryText = q != null ? q : name;
    String dietaryTag = tag != null ? tag : diet;
    String spiceVal = spiceLevel != null ? spiceLevel : spice;
    String ingredientVal = ingredient != null ? ingredient : ingredientId;

    if (tacoSearchService == null) {
      return tacoRepo.findAll()
          .collectList()
          .map(list -> new PageResponse<>(list, 0, list.size(), (long) list.size(), 1, false));
    }
    return tacoSearchService.searchTacos(queryText, dietaryTag, excludeAllergen, spiceVal, ingredientVal, sort, dir, page, size);
  }

  // =========================================================================
  // TC-20: Taco del día determinista
  // =========================================================================
  @GetMapping({"/today", "/taco-of-the-day"})
  public Mono<ResponseEntity<TacoOfTheDayResponse>> getTacoOfTheDay() {
    if (tacoOfTheDayService == null) {
      return Mono.just(ResponseEntity.notFound().build());
    }
    return tacoOfTheDayService.getTacoOfTheDay()
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  // =========================================================================
  // TC-22: Calificaciones y Top Ranking
  // =========================================================================
  @GetMapping("/top")
  public Flux<TacoTopRankItem> getTopTacos(
      @org.springframework.web.bind.annotation.RequestParam(name = "limit", defaultValue = "10") int limit,
      @org.springframework.web.bind.annotation.RequestParam(name = "minVotes", defaultValue = "1") int minVotes) {
    if (tacoRatingService == null) {
      return Flux.empty();
    }
    return tacoRatingService.getTopTacos(limit, minVotes);
  }

  @PutMapping(path = "/{id}/rating", consumes = "application/json")
  public Mono<ResponseEntity<TacoRating>> rateTaco(
      @PathVariable("id") String id,
      @RequestBody TacoRatingRequest request,
      java.security.Principal principal) {
    if (principal == null) {
      return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
    if (tacoRatingService == null) {
      return Mono.just(ResponseEntity.notFound().build());
    }
    return tacoRatingService.rateTaco(principal.getName(), id, request.getScore())
        .map(ResponseEntity::ok);
  }

  @GetMapping("/{id}/rating")
  public Mono<ResponseEntity<TacoRatingSummary>> getRatingSummary(
      @PathVariable("id") String id,
      java.security.Principal principal) {
    if (tacoRatingService == null) {
      return Mono.just(ResponseEntity.notFound().build());
    }
    String username = principal != null ? principal.getName() : null;
    return tacoRatingService.getRatingSummary(id, username)
        .map(ResponseEntity::ok);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<Taco> postTaco(@RequestBody Taco taco) {
    return tacoRepo.save(taco);
  }

  @GetMapping("/{id}")
  public Mono<Taco> tacoById(@PathVariable("id") String id) {
    return tacoRepo.findById(id);
  }

  // =========================================================================
  // TC-17: Clasificación dietaria, alérgenos y picante del taco
  // =========================================================================
  @GetMapping("/{id}/classification")
  public Mono<ResponseEntity<TacoClassificationResponse>> getClassification(@PathVariable("id") String id) {
    return tacoRepo.findById(id)
        .flatMap(classificationService::classifyTaco)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  // =========================================================================
  // TC-18: Taco Physics - Validación componible de diseño
  // =========================================================================
  @PostMapping(path = "/validate", consumes = "application/json")
  public Mono<ResponseEntity<ValidationReport>> validateTacoDesign(@RequestBody Taco taco) {
    return physicsValidator.validateTaco(taco)
        .map(report -> {
          if (report.isValid()) {
            return ResponseEntity.ok(report);
          } else {
            return ResponseEntity.unprocessableEntity().body(report);
          }
        });
  }
}
