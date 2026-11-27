package org.kirikick.api.publication.infra.model;

public record PublicationCommitSnapshot(
    boolean commitEntered,
    boolean commitReturned,
    boolean failureCorrelated,
    boolean transportFailure,
    boolean parsedBackendError,
    boolean backendErrorReadable) {
  public boolean isOutcomeUnknown() {
    return commitEntered
        && !commitReturned
        && failureCorrelated
        && transportFailure
        && !parsedBackendError
        && backendErrorReadable;
  }
}
