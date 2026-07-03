package example;

public final class AnnotatedMethod {
  @Secured(SecurityRule.IS_AUTHENTICATED)
  @Get(
    produces = {
      MediaType.ALL
    }
  )
  public Optional<StreamedFile> show(
    @PathVariable FooId fooId,
    Caller caller
  ) {
    if (caller.isAllowed()) {
      return Optional.of(StreamedFiles.helper(fooId));
    }


    return Optional.empty();
  }
}
