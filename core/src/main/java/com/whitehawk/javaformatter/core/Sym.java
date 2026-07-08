package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

import java.util.HashMap;
import java.util.Map;

/// A canonical text the formatter dispatches on, so hot paths switch on a symbol instead of running
/// chains of string comparisons. [#OTHER] covers every text no symbol names.
@NullMarked
public enum Sym {
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
  LT_LT("<<"),
  ASSIGN("="),
  EQ("=="),
  NE("!="),
  LE("<="),
  GE(">="),
  AMP("&"),
  BAR("|"),
  CARET("^"),
  AMP_AMP("&&"),
  BAR_BAR("||"),
  BANG("!"),
  TILDE("~"),
  PLUS("+"),
  MINUS("-"),
  STAR("*"),
  SLASH("/"),
  PERCENT("%"),
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
  SYNCHRONIZED("synchronized"),
  CLASS("class"),
  INTERFACE("interface"),
  RECORD("record"),
  IF("if"),
  DO("do"),
  ELSE("else"),
  WHILE("while"),
  SWITCH("switch"),
  ENUM("enum"),
  ASSERT("assert"),
  THIS("this"),
  TRUE("true"),
  FALSE("false"),
  NULL("null"),
  FOR("for"),
  TRY("try"),
  THROWS("throws"),
  IMPORT("import"),
  ;

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

  public static Sym of(String text) {
    return BY_TEXT.getOrDefault(text, OTHER);
  }
}
