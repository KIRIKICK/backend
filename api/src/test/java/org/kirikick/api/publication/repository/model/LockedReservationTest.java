package org.kirikick.api.publication.repository.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kirikick.api.publication.domain.PublicationInput;
import org.kirikick.api.publication.domain.PublicationKind;

class LockedReservationTest {

  @Test
  @DisplayName("잠긴 예약을 문자열로 변환해도 내부 값은 노출하지 않는다")
  void toStringShowsOnlySafeState() {
    UUID claimId = UUID.fromString("00000000-0000-4000-8000-000000000076");
    PublicationInput input =
        new PublicationInput(
            PublicationKind.NK_KICK_ADD, 4L, null, 10L, List.of(1L, 2L, 3L, 4L), 1L);
    PublicationClaim claim = new PublicationClaim(1L, "claim-key", input, "123", claimId);
    ExistingReservation existing =
        new ExistingReservation(
            1L,
            "existing-key",
            "{\"correctChoiceId\":1}",
            2L,
            "2030-01-01 00:00:00+00",
            true,
            claimId);

    assertThat(List.of(claim.toString(), existing.toString()))
        .containsExactly(
            "PublicationClaim[kind=NK_KICK_ADD]",
            "ExistingReservation[hasGameId=true, hasExpiry=true, finiteExpiry=true]");
  }
}
