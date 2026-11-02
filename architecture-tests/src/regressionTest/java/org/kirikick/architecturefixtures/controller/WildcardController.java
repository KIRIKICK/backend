package org.kirikick.architecturefixtures.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WildcardController {
  @GetMapping("/wildcard")
  public ResponseEntity<? extends Void> response() {
    return null;
  }
}
