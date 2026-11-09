package org.kirikick.api.auth.infra;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exact frontend origins accepted for browser requests and OAuth callback routing. */
public final class AuthFrontendPolicy {
  private static final String CALLBACK_PATH = "/auth/kakao/callback";

  private final String defaultOrigin;
  private final Set<String> allowedOrigins;

  public AuthFrontendPolicy(String defaultOrigin, List<String> additionalOrigins) {
    String canonicalDefaultOrigin = OriginValidationFilter.canonicalOrigin(defaultOrigin);
    this.defaultOrigin = defaultOrigin;
    Set<String> origins = new LinkedHashSet<>();
    origins.add(canonicalDefaultOrigin);
    for (String origin : additionalOrigins == null ? List.<String>of() : additionalOrigins) {
      if (origin == null || origin.isBlank()) {
        throw new IllegalArgumentException("additional frontend origin must not be blank");
      }
      origins.add(OriginValidationFilter.canonicalOrigin(origin));
    }
    allowedOrigins = Set.copyOf(origins);
  }

  public String defaultOrigin() {
    return defaultOrigin;
  }

  public Set<String> allowedOrigins() {
    return allowedOrigins;
  }

  public boolean isAllowed(String origin) {
    String canonical = OriginValidationFilter.canonicalOriginOrNull(origin);
    return canonical != null && allowedOrigins.contains(canonical);
  }

  public String select(String[] origins) {
    if (origins == null) {
      return defaultOrigin;
    }
    if (origins.length != 1 || !isAllowed(origins[0])) {
      throw new IllegalArgumentException("invalid frontend origin");
    }
    return OriginValidationFilter.canonicalOrigin(origins[0]);
  }

  public String callback(String origin) {
    String selected = origin == null ? defaultOrigin : origin;
    if (!isAllowed(selected)) {
      throw new IllegalArgumentException("invalid frontend origin");
    }
    return selected + CALLBACK_PATH;
  }
}
