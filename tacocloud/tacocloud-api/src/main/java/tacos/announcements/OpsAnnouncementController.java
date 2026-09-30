package tacos.announcements;

import java.security.Principal;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OpsAnnouncement;
import tacos.OpsAnnouncement.Severity;

@RestController
@RequestMapping(produces = "application/json")
@CrossOrigin(origins = "*")
public class OpsAnnouncementController {

  private final OpsAnnouncementService announcementService;

  @Autowired
  public OpsAnnouncementController(OpsAnnouncementService announcementService) {
    this.announcementService = announcementService;
  }

  @GetMapping("/api/announcements")
  public Flux<OpsAnnouncement> getActiveAnnouncements() {
    return announcementService.getActiveAnnouncements();
  }

  @GetMapping("/api/admin/announcements")
  @PreAuthorize("hasRole('ADMIN')")
  public Flux<OpsAnnouncement> getAllActiveAdminAnnouncements() {
    return announcementService.getActiveAnnouncements();
  }

  @PostMapping(path = "/api/admin/announcements", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('ADMIN')")
  public Mono<OpsAnnouncement> createAnnouncement(@RequestBody AnnouncementCreateRequest request, Principal principal) {
    String creator = (principal != null) ? principal.getName() : "ADMIN";
    return announcementService.createAnnouncement(
        request.getText(),
        request.getSeverity(),
        request.getExpiresAt(),
        creator
    );
  }

  @DeleteMapping("/api/admin/announcements/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public Mono<ResponseEntity<Void>> deleteAnnouncement(@PathVariable("id") String id) {
    return announcementService.deleteAnnouncement(id)
        .map(deleted -> deleted ? ResponseEntity.noContent().<Void>build() : ResponseEntity.notFound().<Void>build());
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class AnnouncementCreateRequest {
    private String text;
    private Severity severity;
    private Date expiresAt;
  }
}
