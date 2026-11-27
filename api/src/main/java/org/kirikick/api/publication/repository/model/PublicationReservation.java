package org.kirikick.api.publication.repository.model;

public sealed interface PublicationReservation permits LockedReservation, PublicationReplay {}
