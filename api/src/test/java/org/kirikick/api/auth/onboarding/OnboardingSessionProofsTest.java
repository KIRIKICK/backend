package org.kirikick.api.auth.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class OnboardingSessionProofsTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("연결이 일치하는 유효한 증명만 조회하며 만료 시각은 변경하지 않는다")
  void resolvesOnlyTheBoundUnexpiredProofAndNeverMovesItsDeadline() {
    MockHttpSession session = new MockHttpSession();
    UUID slotId = UUID.randomUUID();
    OnboardingProof proof =
        OnboardingSessionProofs.publish(
            session,
            slotId,
            "kakao",
            "subject",
            "nickname",
            UUID.randomUUID(),
            CLOCK.instant(),
            CLOCK.instant().plusSeconds(300));

    OnboardingProofResolution resolved = OnboardingSessionProofs.resolve(session, CLOCK);

    assertThat(resolved.state()).isEqualTo(OnboardingProofResolution.State.VALID);
    assertThat(resolved.proof()).isEqualTo(proof);
    assertThat(proof.slotId()).isEqualTo(slotId);
    assertThat(proof.expiresAt()).isEqualTo(CLOCK.instant().plusSeconds(300));
    assertThat(
            OnboardingSessionProofs.resolve(session, Clock.offset(CLOCK, Duration.ofMinutes(5)))
                .state())
        .isEqualTo(OnboardingProofResolution.State.EXPIRED);
  }

  @Test
  @DisplayName("슬롯이나 증명이 일치하지 않으면 증명을 무효화할 수 없다")
  void nonmatchingSlotOrProofCannotInvalidateTheProof() {
    MockHttpSession session = new MockHttpSession();
    UUID slotId = UUID.randomUUID();
    OnboardingProof proof =
        OnboardingSessionProofs.publish(
            session,
            slotId,
            "kakao",
            "subject",
            null,
            UUID.randomUUID(),
            CLOCK.instant(),
            CLOCK.instant().plusSeconds(300));

    assertThat(
            OnboardingSessionProofs.invalidateIfExact(session, UUID.randomUUID(), proof.proofId()))
        .isFalse();
    assertThat(OnboardingSessionProofs.invalidateIfExact(session, slotId, UUID.randomUUID()))
        .isFalse();
    assertThat(OnboardingSessionProofs.resolve(session, CLOCK).proof()).isEqualTo(proof);
    assertThat(OnboardingSessionProofs.invalidateIfExact(session, slotId, proof.proofId()))
        .isTrue();
    assertThat(session.isInvalid()).isTrue();
  }
}
