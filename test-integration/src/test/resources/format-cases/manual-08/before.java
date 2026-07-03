package example;

public final class ConsistentlyWrapHugeObject {
  private static void what(Foo foo, Bar bar, @Nullable Baz baz) {
    return new FooDetails(
      foo.id(),
      foo.name(),
      foo.domain(),
      new BarInfo(
        bar.barId(), bar.barName()
      ),
      baz == null
        ? null
        : new FooInfo(
          baz.id(),
          baz.name(),
          baz.domain()
        )
    );
  }
}
