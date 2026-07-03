package example;

public final class MissingBracketsShouldInsert {
  String doSomething(String value) {
    if (Objects.isNull(value)) {
      return null;
    }

    return value + "whatever";
  }
}
