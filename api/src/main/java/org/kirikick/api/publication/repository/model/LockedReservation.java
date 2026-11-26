package org.kirikick.api.publication.repository.model;

public sealed interface LockedReservation extends PublicationReservation
    permits PublicationClaim, ExistingReservation {}
