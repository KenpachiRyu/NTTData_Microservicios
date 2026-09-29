package tacos.web.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;
import tacos.Taco;

@Service
public class TacoSearchService {

  public static final int MAX_PAGE_SIZE = 50;
  public static final Set<String> ALLOWED_SORT_FIELDS = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("createdAt", "name", "id"))
  );

  private final ReactiveMongoTemplate mongoTemplate;

  @Autowired
  public TacoSearchService(ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  public Mono<PageResponse<Taco>> searchTacos(
      String name,
      String dietaryTagStr,
      String excludeAllergenStr,
      String spiceLevelStr,
      String ingredientId,
      String sortField,
      String sortDir,
      int page,
      int size) {

    if (page < 0) {
      return Mono.error(new BusinessRuleException("INVALID_PAGE", "El número de página no puede ser negativo: " + page));
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      return Mono.error(new BusinessRuleException("INVALID_PAGE_SIZE", "El tamaño de página debe estar entre 1 y " + MAX_PAGE_SIZE + " (recibido: " + size + ")"));
    }

    DietaryTag diet = null;
    if (dietaryTagStr != null && !dietaryTagStr.trim().isEmpty()) {
      try {
        diet = DietaryTag.valueOf(dietaryTagStr.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        return Mono.error(new BusinessRuleException("INVALID_DIETARY_TAG", "Etiqueta dietaria inválida: " + dietaryTagStr));
      }
    }

    Allergen excludeAllergen = null;
    if (excludeAllergenStr != null && !excludeAllergenStr.trim().isEmpty()) {
      try {
        excludeAllergen = Allergen.valueOf(excludeAllergenStr.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        return Mono.error(new BusinessRuleException("INVALID_ALLERGEN", "Alérgeno inválido: " + excludeAllergenStr));
      }
    }

    SpiceLevel spice = null;
    if (spiceLevelStr != null && !spiceLevelStr.trim().isEmpty()) {
      try {
        spice = SpiceLevel.valueOf(spiceLevelStr.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        return Mono.error(new BusinessRuleException("INVALID_SPICE_LEVEL", "Nivel de picante inválido: " + spiceLevelStr));
      }
    }

    String directionStr = sortDir != null && !sortDir.trim().isEmpty() ? sortDir.trim().toUpperCase() : "DESC";
    if (!"ASC".equals(directionStr) && !"DESC".equals(directionStr)) {
      return Mono.error(new BusinessRuleException("INVALID_SORT_DIRECTION", "Dirección de ordenamiento inválida: " + sortDir + ". Use ASC o DESC"));
    }

    String fieldStr = sortField != null && !sortField.trim().isEmpty() ? sortField.trim() : "createdAt";
    if (!ALLOWED_SORT_FIELDS.contains(fieldStr)) {
      return Mono.error(new BusinessRuleException("INVALID_SORT_FIELD", "Campo de ordenamiento no permitido: " + fieldStr));
    }

    String sortParam = fieldStr + "," + directionStr.toLowerCase();

    return searchTacos(name, ingredientId, diet, excludeAllergen, spice, page, size, sortParam);
  }

  public Mono<PageResponse<Taco>> searchTacos(
      String name,
      String ingredientId,
      DietaryTag diet,
      Allergen excludeAllergen,
      SpiceLevel spice,
      int page,
      int size,
      String sortParam) {

    if (page < 0) {
      return Mono.error(new BusinessRuleException("INVALID_PAGE", "El número de página no puede ser negativo: " + page));
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      return Mono.error(new BusinessRuleException("INVALID_PAGE_SIZE", "El tamaño de página debe estar entre 1 y " + MAX_PAGE_SIZE + " (recibido: " + size + ")"));
    }

    Sort sort = parseSort(sortParam);
    Pageable pageable = PageRequest.of(page, size, sort);

    List<Criteria> criteriaList = new ArrayList<>();

    if (name != null && !name.trim().isEmpty()) {
      criteriaList.add(Criteria.where("name").regex(Pattern.quote(name.trim()), "i"));
    }

    if (ingredientId != null && !ingredientId.trim().isEmpty()) {
      criteriaList.add(new Criteria().orOperator(
          Criteria.where("ingredients.id").is(ingredientId.trim()),
          Criteria.where("ingredients._id").is(ingredientId.trim())
      ));
    }

    if (diet != null) {
      criteriaList.add(Criteria.where("ingredients.dietaryTags").is(diet.name()));
    }

    if (excludeAllergen != null) {
      criteriaList.add(Criteria.where("ingredients.allergens").ne(excludeAllergen.name()));
    }

    if (spice != null) {
      criteriaList.add(Criteria.where("ingredients.spiceLevel").is(spice.name()));
    }

    Query countQuery = new Query();
    Query findQuery = new Query();

    if (!criteriaList.isEmpty()) {
      Criteria compoundCriteria = new Criteria().andOperator(criteriaList.toArray(new Criteria[0]));
      countQuery.addCriteria(compoundCriteria);
      findQuery.addCriteria(compoundCriteria);
    }

    findQuery.with(pageable);

    Mono<Long> countMono = mongoTemplate.count(countQuery, Taco.class);
    Mono<List<Taco>> tacosMono = mongoTemplate.find(findQuery, Taco.class).collectList();

    return Mono.zip(tacosMono, countMono)
        .map(tuple -> PageResponse.of(tuple.getT1(), page, size, tuple.getT2()));
  }

  private Sort parseSort(String sortParam) {
    if (sortParam == null || sortParam.trim().isEmpty()) {
      return Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));
    }

    String[] parts = sortParam.trim().split(",");
    String field = parts[0].trim();

    if (!ALLOWED_SORT_FIELDS.contains(field)) {
      throw new BusinessRuleException("INVALID_SORT_FIELD", "Campo de ordenamiento no permitido: " + field);
    }

    Sort.Direction direction = Sort.Direction.ASC;
    if (parts.length > 1) {
      String d = parts[1].trim().toUpperCase();
      if (!"ASC".equals(d) && !"DESC".equals(d)) {
        throw new BusinessRuleException("INVALID_SORT_DIRECTION", "Dirección de ordenamiento inválida: " + parts[1] + ". Use ASC o DESC");
      }
      if ("DESC".equals(d)) {
        direction = Sort.Direction.DESC;
      }
    }

    Sort primarySort = Sort.by(direction, field);
    return primarySort.and(Sort.by(Sort.Direction.ASC, "id"));
  }
}
