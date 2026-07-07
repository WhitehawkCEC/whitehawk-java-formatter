package com.whitehawk.javaformatter.core.printer;

import com.whitehawk.javaformatter.core.ArraySmallEnumSet;
import com.whitehawk.javaformatter.core.Kind;
import com.whitehawk.javaformatter.core.Token;

import org.jspecify.annotations.NullMarked;

import java.util.Set;

/// A class a token's text can belong to; resolved once so hot paths avoid repeated set lookups.
@NullMarked
enum Classification {
  KEYWORD,
  PRIMITIVE,
  MODIFIER,
  BINARY_OPERATOR,
  PAREN_KEYWORD,
  OPENER,
  CLOSER,
  ;

  private static final Set<String> PAREN_KEYWORDS = Set.of(
    "if",
    "for",
    "while",
    "switch",
    "catch",
    "synchronized",
    "try",
    "return",
    "throw",
    "assert",
    "yield"
  );

  /// Every classifying set entry is either identifier-shaped or an operator, so only the matching
  /// token kind is probed.
  static void classify(ArraySmallEnumSet<Classification> classes, int i, Token t) {
    if (t.kind() == Kind.PUNCT) {
      switch (t.text()) {
        case "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "==", "!=",
             "&&", "||", "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>", "<", ">", "<=",
             ">=", "?", ":", "->" -> classes.set(i, BINARY_OPERATOR);
        case "(", "[", "{" -> classes.set(i, OPENER);
        case ")", "]", "}" -> classes.set(i, CLOSER);
      }
    } else if (t.kind() == Kind.IDENT && t.isKeyword()) {
      // PRIMITIVE, MODIFIER, and PAREN_KEYWORD texts are all keywords, so a non-keyword identifier
      // (the common case) matches none of them and skips their probes.
      classes.set(i, KEYWORD);
      if (t.isPrimitive()) {
        classes.set(i, PRIMITIVE);
      }
      if (t.isModifier()) {
        classes.set(i, MODIFIER);
      }
      if (PAREN_KEYWORDS.contains(t.text())) {
        classes.set(i, PAREN_KEYWORD);
      }
    }
  }
}
