package com.whitehawk.javaformatter.core;

import java.util.Set;

/// @param start          offset of the first character in the source
/// @param end            offset just past the last character
/// @param newlinesBefore line terminators between the previous token and this one
/// @param atColumn0      token starts at the very beginning of its line
public record Token(
  Kind kind,
  String text,
  int start,
  int end,
  int newlinesBefore,
  boolean atColumn0
) {
  private static final Set<String> PRIMITIVES = Set.of(
    "boolean",
    "byte",
    "char",
    "short",
    "int",
    "long",
    "float",
    "double",
    "void"
  );
  private static final Set<String> MODIFIERS = Set.of(
    "public",
    "private",
    "protected",
    "static",
    "final",
    "default",
    "abstract",
    "synchronized",
    "native",
    "strictfp"
  );

  public boolean is(String s) {
    return text.equals(s);
  }

  public boolean isComment() {
    return kind == Kind.LINE_COMMENT || kind == Kind.BLOCK_COMMENT;
  }

  /// The text is one of [#KEYWORDS]. Keyword texts are identifier-shaped, so no other token
  /// kind's text can match.
  public boolean isKeyword() {
    return JavaLexer.KEYWORDS.contains(text);
  }

  /// The text is a primitive type keyword, including `void`.
  public boolean isPrimitive() {
    return PRIMITIVES.contains(text);
  }

  /// The text is a modifier keyword that can open a declaration (`public`, `static`, ...).
  public boolean isModifier() {
    return MODIFIERS.contains(text);
  }
}
