package example;

public final class LongLineTypeCasting {
  private void endsOperatorElement(Object incoming) {
    SomeReallyLongClassNameThatWillNeedToWrapAlot foo =
      (SomeReallyLongClassNameThatWillNeedToWrapAlot) incoming;
  }
}
