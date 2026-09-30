package tacos.announcements;

import java.time.Clock;
import java.util.Date;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;
import tacos.data.OpsAnnouncementRepository;

/**
 * Servicio reactivo para gestión segura de anuncios operativos (TC-33).
 *
 * Reemplaza el NotesEndpoint en memoria por persistencia durable en MongoDB con IDs estables,
 * validación contra control characters, límites de longitud y cantidad activa, y filtrado por expiración.
 */
@Service
@Slf4j
public class OpsAnnouncementService {

  public static final int MAX_TEXT_LENGTH = 255;
  public static final int MAX_ACTIVE_ANNOUNCEMENTS = 50;
  private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");

  private final OpsAnnouncementRepository announcementRepo;
  private Clock clock = Clock.systemDefaultZone();

  @Autowired
  public OpsAnnouncementService(OpsAnnouncementRepository announcementRepo) {
    this.announcementRepo = announcementRepo;
  }

  public void setClock(Clock clock) {
    this.clock = clock;
  }

  public Mono<OpsAnnouncement> createAnnouncement(String text, Severity severity, Date expiresAt, String createdBy) {
    if (text == null || text.trim().isEmpty()) {
      return Mono.error(new IllegalArgumentException("El texto del anuncio no puede estar vacío"));
    }
    String cleanText = text.trim();
    if (cleanText.length() > MAX_TEXT_LENGTH) {
      return Mono.error(new IllegalArgumentException("El texto del anuncio no puede superar los " + MAX_TEXT_LENGTH + " caracteres"));
    }
    if (CONTROL_CHARS.matcher(cleanText).find()) {
      return Mono.error(new IllegalArgumentException("El texto del anuncio contiene caracteres de control no permitidos"));
    }

    Date now = Date.from(clock.instant());
    if (expiresAt != null && expiresAt.before(now)) {
      return Mono.error(new IllegalArgumentException("La fecha de expiración debe ser futura"));
    }

    Date effectiveExpiresAt = expiresAt != null ? expiresAt : new Date(now.getTime() + 24 * 3600 * 1000L); // 24h por defecto
    Severity effectiveSeverity = severity != null ? severity : Severity.INFO;
    String creator = (createdBy != null && !createdBy.trim().isEmpty()) ? createdBy.trim() : "SYSTEM";

    return announcementRepo.countByActiveTrue()
        .flatMap(count -> {
          if (count >= MAX_ACTIVE_ANNOUNCEMENTS) {
            return Mono.error(new IllegalStateException("Límite de anuncios activos alcanzado (" + MAX_ACTIVE_ANNOUNCEMENTS + ")"));
          }

          OpsAnnouncement announcement = OpsAnnouncement.builder()
              .id(UUID.randomUUID().toString())
              .text(cleanText)
              .severity(effectiveSeverity)
              .createdAt(now)
              .expiresAt(effectiveExpiresAt)
              .createdBy(creator)
              .active(true)
              .build();

          return announcementRepo.save(announcement);
        });
  }

  public Flux<OpsAnnouncement> getActiveAnnouncements() {
    Date now = Date.from(clock.instant());
    return announcementRepo.findByActiveTrue()
        .filter(a -> a.getExpiresAt() == null || a.getExpiresAt().after(now));
  }

  public Mono<Boolean> deleteAnnouncement(String id) {
    if (id == null || id.trim().isEmpty()) {
      return Mono.just(false);
    }
    return announcementRepo.findById(id)
        .flatMap(existing -> announcementRepo.delete(existing).thenReturn(true))
        .defaultIfEmpty(false);
  }
}
