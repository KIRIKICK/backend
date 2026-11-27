package org.kirikick.api.room.repository.projection;

public interface PublicationState {

  Long getHostUserId();

  String getStatus();

  String getResultState();

  String getRawOpenedAt();

  String getRawClosesAt();

  boolean getOpenedAtPresent();

  boolean getClosesAtPresent();

  boolean getOpenedAtFinite();

  boolean getClosesAtFinite();
}
