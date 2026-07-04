package example;

import com.whitehawk.javaformatter.core.JavaLexer;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class MultipleConditions {
  private boolean isUnaryPosition(int i) {
    JavaLexer.Token prev = prevCode(i);
    if (prev == null) {
      return true;
    }
    if (
      prev.kind() == JavaLexer.Kind.NUMBER || prev.kind() == JavaLexer.Kind.STRING || prev.kind() == JavaLexer.Kind.CHAR
        || prev.kind() == JavaLexer.Kind.TEXT_BLOCK
    ) {
      return false;
    }
  }
}
