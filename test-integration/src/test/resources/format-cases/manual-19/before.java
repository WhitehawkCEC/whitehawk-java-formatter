package example;

public final class TryWithMultipleStatements {
  public void list(
    ListRequest request,
    StreamObserver<ListResponse> responseObserver
  ) {
    Repo__products.ListResponse response = Whatever
      .as(auth.get())
      .exec(new Repo__products.ListRequest());

    try (
      var found = response.found();
      var batches = PartitionedStream.of(found, 40)
    ) {

      batches
        .map(this::serialize)
        .forEach(responseObserver::onNext);
    }

    responseObserver.onCompleted();
  }
}
