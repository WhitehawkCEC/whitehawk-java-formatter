package com.whitehawk.javaformatter.core.printer;

import org.jspecify.annotations.NullMarked;

import java.util.HashMap;
import java.util.Map;

/// Texts the printer dispatches on, resolved once per token (see [Printer#tokenSym]) so hot paths
/// compare or switch on a symbol instead of running chains of string comparisons. [#OTHER]
/// covers every text no dispatch names.
@NullMarked
enum Sym {
  OTHER(""),
  LPAREN("("),
  RPAREN(")"),
  LBRACKET("["),
  RBRACKET("]"),
  LBRACE("{"),
  RBRACE("}"),
  SEMI(";"),
  COMMA(","),
  DOT("."),
  METHOD_REF("::"),
  COLON(":"),
  QUESTION("?"),
  ARROW("->"),
  AT("@"),
  ELLIPSIS("..."),
  LT("<"),
  GT(">"),
  GT_GT(">>"),
  GT_GT_GT(">>>"),
  ASSIGN("="),
  AMP("&"),
  BAR("|"),
  AMP_AMP("&&"),
  BAR_BAR("||"),
  BANG("!"),
  TILDE("~"),
  PLUS("+"),
  MINUS("-"),
  INCREMENT("++"),
  DECREMENT("--"),
  RETURN("return"),
  NEW("new"),
  EXTENDS("extends"),
  SUPER("super"),
  IMPLEMENTS("implements"),
  INSTANCEOF("instanceof"),
  CASE("case"),
  YIELD("yield"),
  PUBLIC("public"),
  PRIVATE("private"),
  PROTECTED("protected"),
  STATIC("static"),
  FINAL("final"),
  DEFAULT("default"),
  ABSTRACT("abstract"),
  CLASS("class"),
  INTERFACE("interface"),
  RECORD("record"),
  DO("do"),
  ELSE("else"),
  SWITCH("switch"),
  ENUM("enum"),
  ASSERT("assert"),
  THIS("this"),
  TRUE("true"),
  FALSE("false"),
  NULL("null"),
  FOR("for"),
  TRY("try"),
  THROWS("throws");

  private static final Map<String, Sym> BY_TEXT = new HashMap<>();

  static {
    for (Sym s : values()) {
      if (s != OTHER) {
        BY_TEXT.put(s.text, s);
      }
    }
  }

  private final String text;

  Sym(String text) {
    this.text = text;
  }

  static Sym of(String text) {
    return BY_TEXT.getOrDefault(text, OTHER);
  }
}
