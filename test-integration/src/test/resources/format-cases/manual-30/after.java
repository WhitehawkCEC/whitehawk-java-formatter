package example;

public final class MultipleConditionalPartiallyWrapped {
  int foo(Something tokenClasses, Bar t) {
    if (
      t.kind() != WhateverKind.IDENT
        || tokenClasses.has(i, Wzassification.KEYWORD)
        && !tokenClasses.has(i, Wzassification.PRIMITIVE)
    ) {
      return -1;
    }

    return 0;
  }
}
