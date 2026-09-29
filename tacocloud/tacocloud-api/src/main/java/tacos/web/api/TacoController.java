package tacos.web.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.data.TacoRepository;
import tacos.physics.TacoPhysicsValidator;
import tacos.physics.ValidationReport;

@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class TacoController {

  private final TacoRepository tacoRepo;
  private final TacoClassificationService classificationService;
  private final TacoPhysicsValidator physicsValidator;

  @Autowired
  public TacoController(TacoRepository tacoRepo,
                        @Autowired(required = false) TacoClassificationService classificationService,
                        @Autowired(required = false) TacoPhysicsValidator physicsValidator) {
    this.tacoRepo = tacoRepo;
    this.classificationService = classificationService;
    this.physicsValidator = physicsValidator;
  }

  public TacoController(TacoRepository tacoRepo) {
    this(tacoRepo, null, null);
  }

  @GetMapping(params = "recent")
  public Flux<Taco> recentTacos() {
    return tacoRepo.findAll().take(12);
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
