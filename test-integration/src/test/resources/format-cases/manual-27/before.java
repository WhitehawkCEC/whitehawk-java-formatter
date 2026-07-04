package example;

import org.jspecify.annotations.NullMarked;

import java.util.List;

@NullMarked
public final class ReallyLongTernary {
  private void whatever(List<Okay> tokenClasses) {
    int bodyIndent = tokenClasses.has(firstToken, SomethingWhatever.CLOSER) ? scopeFor(stack, firstSym).contentIndent : indent;
  }
}
