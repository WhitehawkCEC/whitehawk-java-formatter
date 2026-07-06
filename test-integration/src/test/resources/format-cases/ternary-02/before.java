package example;

public class TernaryInsideParens {
  Whatever transform(Foo foo) {
    return Whatever
      .newBuilder()
      .setId(foo.id())
      .setName(foo.name())
      .setDescription(
        (
          foo instanceof SomethingElse
            && ((SomethingElse) foo).description() != null
            ? ((SomethingElse) foo).description()
            : ""
        )
      );
  }
}
