package example;

public final class TernaryIndentation {
  private static String check() {
    return Whatever
      .exec(Yep.delete("abc", "def", 123))
      .hasAttributes()
        ? "good"
        : "bad";
  }
}
