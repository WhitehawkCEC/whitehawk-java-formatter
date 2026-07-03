package example;

public final class ReallyLongAssignmentWithChaining {
  public void buildSomething(Something value) {
    SomethingOrOtherAsset.Builder builder =
      com.zzzgew.foo.bar.baz.whatever.okay.sure.domain.v1.SomethingOrOtherAsset
        .newBuilder()
        .setId(Integer.valueOf(value.id()))
        .setReferenceId(value.referenceId())
        .setName(value.name())
        .build();
  }
}
