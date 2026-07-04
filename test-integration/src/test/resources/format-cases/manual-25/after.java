package example;

import unrelated.UnrelatedJavaLexer;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class MultipleConditions {
  private boolean isUnaryPosition(int i) {
    UnrelatedJavaLexer.Token prev = prevCode(i);
    if (prev == null) {
      return true;
    }
    if (
      prev.kind() == UnrelatedJavaLexer.Kind.NUMBER
        || prev.kind() == UnrelatedJavaLexer.Kind.STRING
        || prev.kind() == UnrelatedJavaLexer.Kind.CHAR
        || prev.kind() == UnrelatedJavaLexer.Kind.TEXT_BLOCK
    ) {
      return false;
    }
  }
}
