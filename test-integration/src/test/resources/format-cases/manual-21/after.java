package example;

public final class ReallyLongMethodChain {
  public int getSomeJsonValueThingThatIsDeeplyNested() {
    return SomeJsonThing
      .get("Whatever")
      .asObject()
      .get("SomethingAsHere")
      .asObject()
      .get("RatingScore")
      .asInt();
  }
}
