package org.kirikick.api.room.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.kirikick.api.room.domain.Room;
import org.kirikick.api.room.repository.projection.PublicationState;
import org.kirikick.api.room.repository.projection.RoomState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface RoomRepository extends JpaRepository<Room, Long> {

  @Query(
      value =
          "select id from rooms where host_user_id = :hostUserId and status = 'OPEN' order by id limit 1",
      nativeQuery = true)
  Optional<Long> findStoredOpenRoomIdByHostUserId(@Param("hostUserId") Long hostUserId);

  @Transactional(propagation = Propagation.MANDATORY)
  @Query(
      value =
          "select status, result_state as \"resultState\", opened_at as \"openedAt\","
              + " closes_at as \"closesAt\" from rooms where id = :id for update",
      nativeQuery = true)
  Optional<RoomState> lockAndFindCurrentStateById(@Param("id") Long id);

  // A scalar projection does not reuse a Room loaded before the lock wait.
  @Query(
      """
      select r.status as status, r.resultState as resultState,
             r.openedAt as openedAt, r.closesAt as closesAt
      from Room r
      where r.id = :id
      """)
  Optional<RoomState> findCurrentState(@Param("id") Long id);

  // Keep timestamp values raw until authorization and idempotency-record resolution are complete.
  @Transactional(propagation = Propagation.MANDATORY)
  @Query(
      value =
          """
          select host_user_id as "hostUserId", status, result_state as "resultState",
                 opened_at::text as "rawOpenedAt", closes_at::text as "rawClosesAt",
                 opened_at is not null as "openedAtPresent", closes_at is not null as "closesAtPresent",
                 isfinite(opened_at) as "openedAtFinite", isfinite(closes_at) as "closesAtFinite"
          from rooms
          where id = :id
          for update
          """,
      nativeQuery = true)
  Optional<PublicationState> lockAndFindPublicationStateById(@Param("id") Long id);

  @Query(
      value =
          """
          select id from rooms
          where result_state = 'NOT_READY'
            and opened_at is not null and closes_at is not null
            and (status = 'CLOSED' or closes_at <= :asOf)
          """,
      nativeQuery = true)
  List<Long> findUnsettledCandidateIds(@Param("asOf") Instant asOf);
}
