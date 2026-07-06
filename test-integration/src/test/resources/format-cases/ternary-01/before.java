package example;

public class TernaryInsideParens {
  Whatever transform(Foo foo) {

    return new Whatever(
      foo.getSomeKindOfThing(),
      foo.getAnotherAttribute(),
      (
        foo.getLastUpdated().equals(SomeTime.importantCutoff())
          ? null
          : HelperThing.from(foo.getLastUpdated())
      )
    );
  }
}
