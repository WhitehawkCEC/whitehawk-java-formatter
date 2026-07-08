package com.whitehawk.javaformatter.core.printer;

import com.whitehawk.javaformatter.core.ArraySmallEnumSet;
import com.whitehawk.javaformatter.core.Sym;
import com.whitehawk.javaformatter.core.Token;

import org.jspecify.annotations.NullMarked;

import java.util.EnumSet;
import java.util.Set;

/// A class a token's symbol can belong to; resolved once so hot paths avoid repeated set lookups.
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

  private static final Set<Sym> PAREN_KEYWORDS = EnumSet.of(
    Sym.IF,
    Sym.FOR,
    Sym.WHILE,
    Sym.SWITCH,
    Sym.CATCH,
    Sym.SYNCHRONIZED,
    Sym.TRY,
    Sym.RETURN,
    Sym.THROW,
    Sym.ASSERT,
    Sym.YIELD
  );

  /// Every classifying symbol is either identifier-shaped or an operator; a token whose symbol
  /// names neither (the common case) matches nothing.
  static void classify(ArraySmallEnumSet<Classification> classes, int i, Token t) {
    switch (t.sym()) {
      case ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN, AMP_ASSIGN,
           BAR_ASSIGN, CARET_ASSIGN, LT_LT_ASSIGN, GT_GT_ASSIGN, GT_GT_GT_ASSIGN, EQ, NE, AMP_AMP,
           BAR_BAR, PLUS, MINUS, STAR, SLASH, PERCENT, AMP, BAR, CARET, LT_LT, GT_GT, GT_GT_GT, LT,
           GT, LE, GE, QUESTION, COLON, ARROW -> classes.set(i, BINARY_OPERATOR);
      case LPAREN, LBRACKET, LBRACE -> classes.set(i, OPENER);
      case RPAREN, RBRACKET, RBRACE -> classes.set(i, CLOSER);
      default -> {
        // PRIMITIVE, MODIFIER, and PAREN_KEYWORD symbols are all keywords, so a non-keyword token
        // (the common case) matches none of them and skips their probes.
        if (t.isKeyword()) {
          classes.set(i, KEYWORD);
          if (t.isPrimitive()) {
            classes.set(i, PRIMITIVE);
          }
          if (t.isModifier()) {
            classes.set(i, MODIFIER);
          }
          if (PAREN_KEYWORDS.contains(t.sym())) {
            classes.set(i, PAREN_KEYWORD);
          }
        }
      }
    }
  }
}
