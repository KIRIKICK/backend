package org.kirikick.api.auth.onboarding;

import org.springframework.lang.Nullable;

public record OnboardingProofResolution(@Nullable OnboardingProof proof, State state) {
  public enum State {
    VALID,
    MISSING,
    EXPIRED,
    BINDING_MISMATCH
  }
}
