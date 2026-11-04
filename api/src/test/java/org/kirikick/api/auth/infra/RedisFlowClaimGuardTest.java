package org.kirikick.api.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.kirikick.api.auth.support.RedisTestSupport;

class RedisFlowClaimGuardTest {
  private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

  @Test
  void transitionsOnlyTheExactSlotAndTuple() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var flow = UUID.randomUUID();
      var claim = UUID.randomUUID();
      var proof = UUID.randomUUID();
      var deadline = NOW.plusSeconds(60);
      assertThat(guard.beginOAuth(slot, flow, deadline, NOW))
          .isEqualTo(RedisFlowClaimGuard.BeginOAuthResult.STARTED);
      assertThat(guard.claimOAuth(slot, flow, deadline.minusSeconds(1), claim, NOW))
          .isEqualTo(RedisFlowClaimGuard.ClaimOAuthResult.STALE);
      assertThat(guard.claimOAuth(slot, flow, deadline, claim, NOW))
          .isEqualTo(RedisFlowClaimGuard.ClaimOAuthResult.CLAIMED);
      assertThat(
              guard.finishOAuth(
                  slot,
                  flow,
                  deadline,
                  claim,
                  RedisFlowClaimGuard.OAuthFinish.TO_PROOF,
                  proof,
                  NOW.plusSeconds(40),
                  NOW))
          .isEqualTo(RedisFlowClaimGuard.FinishOAuthResult.PROOF_TRANSITION_PERMIT);
      assertThat(guard.inspectProof(slot, proof, NOW.plusSeconds(40), NOW))
          .isEqualTo(RedisFlowClaimGuard.InspectProofResult.CURRENT);
      assertThat(guard.finishProof(slot, proof, NOW.plusSeconds(40), NOW))
          .isEqualTo(RedisFlowClaimGuard.FinishProofResult.CLEANUP_PERMIT);
      assertThat(guard.inspectProof(slot, proof, NOW.plusSeconds(40), NOW))
          .isEqualTo(RedisFlowClaimGuard.InspectProofResult.STALE);
    }
  }

  @Test
  void signupLeaseIsBusyUntilItsFixedLeaseExpiresThenCleansUp() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var proof = UUID.randomUUID();
      var deadline = NOW.plusSeconds(600);
      var claim = UUID.randomUUID();
      seedProof(guard, slot, proof, deadline);
      assertThat(guard.claimSignup(slot, proof, deadline, claim, NOW))
          .isEqualTo(RedisFlowClaimGuard.ClaimSignupResult.CLAIMED);
      assertThat(guard.inspectProof(slot, proof, deadline, NOW))
          .isEqualTo(RedisFlowClaimGuard.InspectProofResult.ALREADY_CLAIMED);
      assertThat(guard.beginOAuth(slot, UUID.randomUUID(), NOW.plusSeconds(120), NOW))
          .isEqualTo(RedisFlowClaimGuard.BeginOAuthResult.SIGNUP_IN_PROGRESS);
      assertThat(guard.claimSignup(slot, proof, deadline, UUID.randomUUID(), NOW.plusSeconds(301)))
          .isEqualTo(RedisFlowClaimGuard.ClaimSignupResult.EXPIRED_CLAIM_CLEANUP_PERMIT);
      assertThat(
              guard.beginOAuth(slot, UUID.randomUUID(), NOW.plusSeconds(600), NOW.plusSeconds(301)))
          .isEqualTo(RedisFlowClaimGuard.BeginOAuthResult.STARTED);
    }
  }

  @Test
  void finishSignupPermitsPublicationOnlyForItsExactLiveClaim() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var proof = UUID.randomUUID();
      var deadline = NOW.plusSeconds(600);
      var claim = UUID.randomUUID();
      seedProof(guard, slot, proof, deadline);
      assertThat(guard.claimSignup(slot, proof, deadline, claim, NOW))
          .isEqualTo(RedisFlowClaimGuard.ClaimSignupResult.CLAIMED);
      assertThat(guard.finishSignup(slot, proof, deadline, UUID.randomUUID(), NOW))
          .isEqualTo(RedisFlowClaimGuard.FinishSignupResult.STALE);
      assertThat(guard.finishSignup(slot, proof, deadline, claim, NOW))
          .isEqualTo(RedisFlowClaimGuard.FinishSignupResult.FINISHED_CLEANUP_PERMIT);
      assertThat(redis.keys()).isEmpty();
    }
  }

  @Test
  void expiryReturnsCleanupPermitWithoutRelyingOnRedisWallClock() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var flow = UUID.randomUUID();
      var deadline = NOW.plusSeconds(1);
      assertThat(guard.beginOAuth(slot, flow, deadline, NOW))
          .isEqualTo(RedisFlowClaimGuard.BeginOAuthResult.STARTED);
      assertThat(guard.claimOAuth(slot, flow, deadline, UUID.randomUUID(), NOW.plusSeconds(1)))
          .isEqualTo(RedisFlowClaimGuard.ClaimOAuthResult.EXPIRED_CLEANUP_PERMIT);
      assertThat(redis.keys()).isEmpty();
    }
  }

  @Test
  void oauthClaimIsIdempotentForItsOwnerAndReplaysForAnotherClaim() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var flow = UUID.randomUUID();
      var deadline = NOW.plusSeconds(60);
      var owner = UUID.randomUUID();
      guard.beginOAuth(slot, flow, deadline, NOW);
      assertThat(guard.claimOAuth(slot, flow, deadline, owner, NOW))
          .isEqualTo(RedisFlowClaimGuard.ClaimOAuthResult.CLAIMED);
      assertThat(guard.claimOAuth(slot, flow, deadline, owner, NOW))
          .isEqualTo(RedisFlowClaimGuard.ClaimOAuthResult.CLAIMED);
      assertThat(guard.claimOAuth(slot, flow, deadline, UUID.randomUUID(), NOW))
          .isEqualTo(RedisFlowClaimGuard.ClaimOAuthResult.REPLAYED);
    }
  }

  @Test
  void finishOAuthEndIsExactAndBadFinishShapeIsUnavailable() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var flow = UUID.randomUUID();
      var deadline = NOW.plusSeconds(60);
      var claim = UUID.randomUUID();
      guard.beginOAuth(slot, flow, deadline, NOW);
      guard.claimOAuth(slot, flow, deadline, claim, NOW);
      assertThat(
              guard.finishOAuth(
                  slot,
                  flow,
                  deadline,
                  UUID.randomUUID(),
                  RedisFlowClaimGuard.OAuthFinish.END,
                  null,
                  null,
                  NOW))
          .isEqualTo(RedisFlowClaimGuard.FinishOAuthResult.STALE);
      assertThat(
              guard.finishOAuth(
                  slot,
                  flow,
                  deadline,
                  claim,
                  RedisFlowClaimGuard.OAuthFinish.END,
                  null,
                  null,
                  NOW))
          .isEqualTo(RedisFlowClaimGuard.FinishOAuthResult.END_CLEANUP_PERMIT);
      org.assertj.core.api.Assertions.assertThatThrownBy(() ->
              guard.finishOAuth(
                  slot,
                  flow,
                  deadline,
                  claim,
                  RedisFlowClaimGuard.OAuthFinish.TO_PROOF,
                  null,
                  null,
                  NOW))
          .isInstanceOf(SessionStateUnavailableException.class);
    }
  }

  @Test
  void proofAndClaimTtlsAreRetainedBeyondPermissionWithoutWallClockAssumptions() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var proof = UUID.randomUUID();
      var proofDeadline = NOW.plusSeconds(60);
      String key = redis.namespace() + ":temporary-slot:" + slot;
      seedProof(guard, slot, proof, proofDeadline);
      assertThat(redis.template().getExpire(key, TimeUnit.MILLISECONDS))
          .isBetween(419_000L, 420_000L);
      assertThat(guard.claimSignup(slot, proof, proofDeadline, UUID.randomUUID(), NOW))
          .isEqualTo(RedisFlowClaimGuard.ClaimSignupResult.CLAIMED);
      assertThat(redis.template().getExpire(key, TimeUnit.MILLISECONDS))
          .isBetween(659_000L, 660_000L);
    }
  }

  @Test
  void proofAndClaimExpiryReturnTheirDistinctCleanupPermits() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var proofSlot = UUID.randomUUID();
      var expiredProof = UUID.randomUUID();
      var proofDeadline = NOW.plusSeconds(1);
      seedProof(guard, proofSlot, expiredProof, proofDeadline);
      assertThat(guard.inspectProof(proofSlot, expiredProof, proofDeadline, NOW.plusSeconds(1)))
          .isEqualTo(RedisFlowClaimGuard.InspectProofResult.EXPIRED_CLEANUP_PERMIT);

      var claimSlot = UUID.randomUUID();
      var proof = UUID.randomUUID();
      var deadline = NOW.plusSeconds(600);
      seedProof(guard, claimSlot, proof, deadline);
      guard.claimSignup(claimSlot, proof, deadline, UUID.randomUUID(), NOW);
      assertThat(guard.inspectProof(claimSlot, proof, deadline, NOW.plusSeconds(301)))
          .isEqualTo(RedisFlowClaimGuard.InspectProofResult.EXPIRED_CLAIM_CLEANUP_PERMIT);
    }
  }

  @Test
  void expiredSignupFinishCannotPermitPublicationAndNullArgumentsFailUnavailable() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var proof = UUID.randomUUID();
      var deadline = NOW.plusSeconds(600);
      var claim = UUID.randomUUID();
      seedProof(guard, slot, proof, deadline);
      guard.claimSignup(slot, proof, deadline, claim, NOW);
      assertThat(guard.finishSignup(slot, proof, deadline, claim, NOW.plusSeconds(301)))
          .isEqualTo(RedisFlowClaimGuard.FinishSignupResult.EXPIRED_CLEANUP_PERMIT);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> guard.beginOAuth(null, UUID.randomUUID(), deadline, NOW))
          .isInstanceOf(SessionStateUnavailableException.class);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> guard.claimSignup(slot, proof, deadline, null, NOW))
          .isInstanceOf(SessionStateUnavailableException.class);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> guard.claimOAuth(slot, proof, deadline, null, NOW))
          .isInstanceOf(SessionStateUnavailableException.class);
      org.assertj.core.api.Assertions.assertThatThrownBy(() ->
              guard.finishOAuth(slot, proof, deadline, UUID.randomUUID(), null, null, null, NOW))
          .isInstanceOf(SessionStateUnavailableException.class);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> guard.inspectProof(slot, proof, deadline, null))
          .isInstanceOf(SessionStateUnavailableException.class);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> guard.finishProof(slot, proof, deadline, null))
          .isInstanceOf(SessionStateUnavailableException.class);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> guard.finishSignup(slot, proof, deadline, null, NOW))
          .isInstanceOf(SessionStateUnavailableException.class);
    }
  }

  @Test
  void concurrentSignupClaimHasExactlyOneWinner() throws Exception {
    try (var redis = RedisTestSupport.create();
        var pool = Executors.newFixedThreadPool(2)) {
      var slot = UUID.randomUUID();
      var proof = UUID.randomUUID();
      var deadline = NOW.plusSeconds(600);
      var first = guard(redis);
      var second = guard(redis);
      seedProof(first, slot, proof, deadline);
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var a = pool.submit(() -> claimAfterBarrier(first, slot, proof, deadline, ready, start));
      var b = pool.submit(() -> claimAfterBarrier(second, slot, proof, deadline, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var results = java.util.List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
      assertThat(results)
          .containsExactlyInAnyOrder(
              RedisFlowClaimGuard.ClaimSignupResult.CLAIMED,
              RedisFlowClaimGuard.ClaimSignupResult.ALREADY_CLAIMED);
    }
  }

  @Test
  void retentionIsBoundedAndKeyTypeOrMalformedHashFailsUnavailable() {
    try (var redis = RedisTestSupport.create()) {
      var guard = guard(redis);
      var slot = UUID.randomUUID();
      var flow = UUID.randomUUID();
      var deadline = NOW.plusSeconds(60);
      String key = redis.namespace() + ":temporary-slot:" + slot;
      assertThat(guard.beginOAuth(slot, flow, deadline, NOW))
          .isEqualTo(RedisFlowClaimGuard.BeginOAuthResult.STARTED);
      assertThat(redis.template().getExpire(key, TimeUnit.MILLISECONDS))
          .isBetween(60_000L, 12 * 60_000L);
      redis.template().delete(key);
      redis.template().opsForValue().set(key, "not-a-hash");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> guard.beginOAuth(slot, flow, deadline, NOW))
          .isInstanceOf(SessionStateUnavailableException.class);
      redis.template().delete(key);
      redis.template().opsForHash().put(key, "kind", "PROOF");
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> guard.inspectProof(slot, UUID.randomUUID(), deadline, NOW))
          .isInstanceOf(SessionStateUnavailableException.class);
    }
  }

  private static RedisFlowClaimGuard.ClaimSignupResult claimAfterBarrier(
      RedisFlowClaimGuard guard,
      UUID slot,
      UUID proof,
      Instant deadline,
      CountDownLatch ready,
      CountDownLatch start)
      throws Exception {
    ready.countDown();
    start.await(5, TimeUnit.SECONDS);
    return guard.claimSignup(slot, proof, deadline, UUID.randomUUID(), NOW);
  }

  private static void seedProof(
      RedisFlowClaimGuard guard, UUID slot, UUID proof, Instant deadline) {
    UUID flow = UUID.randomUUID();
    UUID claim = UUID.randomUUID();
    guard.beginOAuth(slot, flow, NOW.plusSeconds(120), NOW);
    guard.claimOAuth(slot, flow, NOW.plusSeconds(120), claim, NOW);
    guard.finishOAuth(
        slot,
        flow,
        NOW.plusSeconds(120),
        claim,
        RedisFlowClaimGuard.OAuthFinish.TO_PROOF,
        proof,
        deadline,
        NOW);
  }

  private static RedisFlowClaimGuard guard(RedisTestSupport redis) {
    return new RedisFlowClaimGuard(redis.template(), redis.namespace());
  }
}
