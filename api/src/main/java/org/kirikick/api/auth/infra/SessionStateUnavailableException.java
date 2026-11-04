package org.kirikick.api.auth.infra;

public class SessionStateUnavailableException extends RuntimeException {

  public SessionStateUnavailableException(Throwable cause) {
    super(cause);
  }
}
