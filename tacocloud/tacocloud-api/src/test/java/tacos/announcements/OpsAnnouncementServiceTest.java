package tacos.announcements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;
import tacos.data.OpsAnnouncementRepository;

@ExtendWith(MockitoExtension.class)
public class OpsAnnouncementServiceTest {

  @Mock
  private OpsAnnouncementRepository announcementRepo;

  private OpsAnnouncementService service;
  private Clock fixedClock;

  @BeforeEach
  void setUp() {
    service = new OpsAnnouncementService(announcementRepo);
    fixedClock = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneId.of("UTC"));
    service.setClock(fixedClock);
  }

  @Test
  @DisplayName("Crear anuncio válido persiste con ID estable y estado activo")
  void testCreateValidAnnouncement() {
    when(announcementRepo.countByActiveTrue()).thenReturn(Mono.just(5L));
    when(announcementRepo.save(any(OpsAnnouncement.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.createAnnouncement("Mantenimiento programado", Severity.WARN, null, "admin"))
        .assertNext(created -> {
          assertThat(created.getId()).isNotNull();
          assertThat(created.getText()).isEqualTo("Mantenimiento programado");
          assertThat(created.getSeverity()).isEqualTo(Severity.WARN);
          assertThat(created.getCreatedBy()).isEqualTo("admin");
          assertThat(created.isActive()).isTrue();
          assertThat(created.getExpiresAt()).isNotNull();
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Rechazar anuncio con texto vacío")
  void testRejectEmptyText() {
    StepVerifier.create(service.createAnnouncement("   ", Severity.INFO, null, "admin"))
        .expectErrorMatches(err -> err instanceof IllegalArgumentException && err.getMessage().contains("vacío"))
        .verify();
  }

  @Test
  @DisplayName("Rechazar anuncio que contiene caracteres de control (prevención de log/injection)")
  void testRejectControlCharacters() {
    String textWithControl = "Alerta con carácter de control \u0007 en texto";
    StepVerifier.create(service.createAnnouncement(textWithControl, Severity.CRITICAL, null, "admin"))
        .expectErrorMatches(err -> err instanceof IllegalArgumentException && err.getMessage().contains("caracteres de control"))
        .verify();
  }

  @Test
  @DisplayName("Rechazar anuncio que supera los 255 caracteres")
  void testRejectExcessiveLength() {
    String longText = "a".repeat(256);
    StepVerifier.create(service.createAnnouncement(longText, Severity.INFO, null, "admin"))
        .expectErrorMatches(err -> err instanceof IllegalArgumentException && err.getMessage().contains("255"))
        .verify();
  }

  @Test
  @DisplayName("Filtrar anuncios expirados según reloj determinista")
  void testFilterExpiredAnnouncements() {
    Date pastDate = Date.from(Instant.parse("2026-09-29T09:00:00Z"));
    Date futureDate = Date.from(Instant.parse("2026-09-29T12:00:00Z"));

    OpsAnnouncement expired = OpsAnnouncement.builder()
        .id("ann-expired")
        .text("Expirado")
        .expiresAt(pastDate)
        .active(true)
        .build();

    OpsAnnouncement active = OpsAnnouncement.builder()
        .id("ann-active")
        .text("Vigente")
        .expiresAt(futureDate)
        .active(true)
        .build();

    when(announcementRepo.findByActiveTrue()).thenReturn(Flux.just(expired, active));

    StepVerifier.create(service.getActiveAnnouncements())
        .assertNext(ann -> assertThat(ann.getId()).isEqualTo("ann-active"))
        .verifyComplete();
  }

  @Test
  @DisplayName("Borrado por ID estable elimina el documento correspondiente")
  void testDeleteById() {
    String id = "ann-to-delete";
    OpsAnnouncement existing = OpsAnnouncement.builder().id(id).build();

    when(announcementRepo.findById(id)).thenReturn(Mono.just(existing));
    when(announcementRepo.delete(existing)).thenReturn(Mono.empty());

    StepVerifier.create(service.deleteAnnouncement(id))
        .expectNext(true)
        .verifyComplete();

    verify(announcementRepo, times(1)).delete(existing);
  }

  @Test
  @DisplayName("Borrado con ID inexistente retorna false")
  void testDeleteNonExistent() {
    when(announcementRepo.findById("missing-id")).thenReturn(Mono.empty());

    StepVerifier.create(service.deleteAnnouncement("missing-id"))
        .expectNext(false)
        .verifyComplete();

    verify(announcementRepo, never()).delete(any());
  }

  @Test
  @DisplayName("Límite de anuncios activos previene saturación (máximo 50)")
  void testMaxActiveLimitEnforced() {
    when(announcementRepo.countByActiveTrue()).thenReturn(Mono.just(50L));

    StepVerifier.create(service.createAnnouncement("Nuevo anuncio", Severity.INFO, null, "admin"))
        .expectErrorMatches(err -> err instanceof IllegalStateException && err.getMessage().contains("Límite"))
        .verify();
  }
}
