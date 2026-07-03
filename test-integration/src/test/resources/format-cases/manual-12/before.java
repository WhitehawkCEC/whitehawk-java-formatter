package example;

public final class UnwrapLongCondition {
  public static void modify(Row oldImage, Row newImage) {
    if (
      oldImage.name().equals(newImage.name())
        && oldImage.domain().equals(newImage.domain())
    ) {
      return;
    }

    // ...
  }
}
