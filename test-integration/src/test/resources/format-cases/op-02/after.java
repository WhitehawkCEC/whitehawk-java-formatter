package example;

public final class OpWithLongMethodChain {
  public boolean check(Something something) {
    return something != null
      && (
        something
          .somethingInfo()
          .stream()
          .map(SomethingInfo::somethingStatus)
          .anyMatch((var status) -> status.equals(SomethingStatus.ACTIVE))
      );
  }
}
