package org.kirikick.api.auth.onboarding;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.lang.Nullable;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OnboardingSessionProofs {

  private static final String ATTRIBUTE = OnboardingSessionProofs.class.getName() + ".proof";
  public static final Duration PROOF_TTL = Duration.ofMinutes(5);

  public static OnboardingProof publish(
      HttpSession session,
      UUID slotId,
      String provider,
      String subject,
      @Nullable String suggestedDisplayName,
      UUID proofId,
      Instant issuedAt,
      Instant expiresAt) {
    if (!expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("proof expiry must be after issuance");
    }
    session.setMaxInactiveInterval((int) PROOF_TTL.toSeconds());
    OnboardingProof proof =
        new OnboardingProof(
            slotId,
            provider,
            subject,
            suggestedDisplayName,
            proofId,
            issuedAt,
            expiresAt,
            session.getId());
    session.setAttribute(ATTRIBUTE, proof);
    return proof;
  }

  public static OnboardingProofResolution resolve(HttpSession session, Clock clock) {
    Object value = session.getAttribute(ATTRIBUTE);
    if (!(value instanceof OnboardingProof proof)) {
      return new OnboardingProofResolution(null, OnboardingProofResolution.State.MISSING);
    }
    if (!proof.sessionId().equals(session.getId())) {
      return new OnboardingProofResolution(proof, OnboardingProofResolution.State.BINDING_MISMATCH);
    }
    if (!proof.expiresAt().isAfter(clock.instant())) {
      return new OnboardingProofResolution(proof, OnboardingProofResolution.State.EXPIRED);
    }
    return new OnboardingProofResolution(proof, OnboardingProofResolution.State.VALID);
  }

  /** Caller must hold the matching Redis cleanup permit. */
  public static boolean invalidateIfExact(HttpSession session, UUID slotId, UUID proofId) {
    Object value = session.getAttribute(ATTRIBUTE);
    if (!(value instanceof OnboardingProof proof)
        || !proof.slotId().equals(slotId)
        || !proof.proofId().equals(proofId)) {
      return false;
    }
    session.invalidate();
    return true;
  }
}
