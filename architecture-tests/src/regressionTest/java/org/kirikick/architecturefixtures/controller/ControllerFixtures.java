package org.kirikick.architecturefixtures.controller;

import java.util.List;
import org.kirikick.architecturefixtures.controller.dto.ControllerDtos.Response;
import org.kirikick.architecturefixtures.domain.DomainFixtures;
import org.kirikick.architecturefixtures.repository.RepositoryFixtures.StoreRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

public final class ControllerFixtures {

  private ControllerFixtures() {}

  @RestController
  public static class ValidController {

    @GetMapping("/response")
    public Response response() {
      return null;
    }

    @GetMapping("/responses")
    public ResponseEntity<List<Response>> responses() {
      return null;
    }

    @RequestMapping("/accepted")
    public Response[] responseArray() {
      return null;
    }

    @GetMapping("/empty")
    public ResponseEntity<Void> empty() {
      return ResponseEntity.noContent().build();
    }

    @GetMapping("/void")
    public void voidResponse() {}

    public String helper() {
      return "not a request handler";
    }
  }

  @RestController
  public static class InvalidController {

    @GetMapping("/entity")
    public DomainFixtures.PrivateState entity() {
      return null;
    }

    @GetMapping("/raw-string")
    public String rawString() {
      return "invalid";
    }

    @GetMapping("/raw-container")
    @SuppressWarnings("rawtypes")
    public List rawContainer() {
      return List.of();
    }

    @GetMapping("/raw-response")
    @SuppressWarnings("rawtypes")
    public ResponseEntity rawResponse() {
      return ResponseEntity.ok().build();
    }

    @GetMapping("/string-response")
    public ResponseEntity<String> stringResponse() {
      return ResponseEntity.ok("invalid");
    }

    @GetMapping("/entity-list")
    public List<DomainFixtures.PrivateState> entityList() {
      return List.of();
    }

    @GetMapping("/repository")
    public StoreRepository repository() {
      return null;
    }

    @GetMapping("/object")
    public Object object() {
      return null;
    }
  }
}
