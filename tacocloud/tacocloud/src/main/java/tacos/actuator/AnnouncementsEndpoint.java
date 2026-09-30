package tacos.actuator;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;
import tacos.announcements.OpsAnnouncementService;

/**
 * Endpoint de Actuator para Anuncios Operativos (TC-33).
 *
 * Expuesto en /actuator/announcements.
 * Reemplaza el NotesEndpoint en memoria por IDs estables, validación estricta y persistencia durable.
 */
@Component
@Endpoint(id = "announcements", enableByDefault = true)
public class AnnouncementsEndpoint {

  private final OpsAnnouncementService announcementService;

  @Autowired
  public AnnouncementsEndpoint(OpsAnnouncementService announcementService) {
    this.announcementService = announcementService;
  }

  @ReadOperation
  public List<OpsAnnouncement> announcements() {
    return announcementService.getActiveAnnouncements().collectList().block();
  }

  @WriteOperation
  public OpsAnnouncement addAnnouncement(String text, String severity) {
    Severity sev = Severity.INFO;
    if (severity != null) {
      try {
        sev = Severity.valueOf(severity.toUpperCase());
      } catch (IllegalArgumentException ignored) {
      }
    }
    return announcementService.createAnnouncement(text, sev, null, "ACTUATOR_ADMIN").block();
  }

  @DeleteOperation
  public boolean deleteAnnouncement(@Selector String id) {
    return Boolean.TRUE.equals(announcementService.deleteAnnouncement(id).block());
  }
}
