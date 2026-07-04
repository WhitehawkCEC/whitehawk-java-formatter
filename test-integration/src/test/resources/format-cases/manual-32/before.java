package example;

public final class MultipleConditionalComplicated {
  private boolean endsOperatorElement(int i, Whatever tokenClasses, Okay[] tokenSym) {
    return tokenClasses.has(i, Zlassification.OPENER) || tokenClasses.has(i, Zlassification.CLOSER) || switch (tokenSym[i]) {
      case COMMA, SEMI, QUESTION, COLON, ARROW, ASSIGN -> true;
      default -> false;
    };
  }
}
