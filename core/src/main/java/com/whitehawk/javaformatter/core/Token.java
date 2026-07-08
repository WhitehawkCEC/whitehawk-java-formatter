package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

import java.util.EnumSet;
import java.util.Set;

/// @param start          offset of the first character in the source
/// @param end            offset just past the last character
/// @param newlinesBefore line terminators between the previous token and this one
/// @param atColumn0      token starts at the very beginning of its line
/// @param sym            the canonical symbol the text names, or [Sym#OTHER]; derived from `text`
@NullMarked
public record Token(
  Kind kind,
  String text,
  int start,
  int end,
  int newlinesBefore,
  boolean atColumn0,
  Sym sym
) {
  private static final Set<Sym> PRIMITIVES = EnumSet.of(
    Sym.BOOLEAN,
    Sym.BYTE,
    Sym.CHAR,
    Sym.SHORT,
    Sym.INT,
    Sym.LONG,
    Sym.FLOAT,
    Sym.DOUBLE,
    Sym.VOID
  );
  private static final Set<Sym> MODIFIERS = EnumSet.of(
    Sym.PUBLIC,
    Sym.PRIVATE,
    Sym.PROTECTED,
    Sym.STATIC,
    Sym.FINAL,
    Sym.DEFAULT,
    Sym.ABSTRACT,
    Sym.SYNCHRONIZED,
    Sym.NATIVE,
    Sym.STRICTFP
  );

  /// Reserved words plus literals and contextual keywords that formatting logic (or renaming
  /// tools) must never treat as plain identifiers.
  private static final Set<Sym> KEYWORDS = EnumSet.of(
    Sym.ABSTRACT,
    Sym.ASSERT,
    Sym.BOOLEAN,
    Sym.BREAK,
    Sym.BYTE,
    Sym.CASE,
    Sym.CATCH,
    Sym.CHAR,
    Sym.CLASS,
    Sym.CONST,
    Sym.CONTINUE,
    Sym.DEFAULT,
    Sym.DO,
    Sym.DOUBLE,
    Sym.ELSE,
    Sym.ENUM,
    Sym.EXTENDS,
    Sym.FINAL,
    Sym.FINALLY,
    Sym.FLOAT,
    Sym.FOR,
    Sym.GOTO,
    Sym.IF,
    Sym.IMPLEMENTS,
    Sym.IMPORT,
    Sym.INSTANCEOF,
    Sym.INT,
    Sym.INTERFACE,
    Sym.LONG,
    Sym.NATIVE,
    Sym.NEW,
    Sym.PACKAGE,
    Sym.PRIVATE,
    Sym.PROTECTED,
    Sym.PUBLIC,
    Sym.RETURN,
    Sym.SHORT,
    Sym.STATIC,
    Sym.STRICTFP,
    Sym.SUPER,
    Sym.SWITCH,
    Sym.SYNCHRONIZED,
    Sym.THIS,
    Sym.THROW,
    Sym.THROWS,
    Sym.TRANSIENT,
    Sym.TRY,
    Sym.VOID,
    Sym.VOLATILE,
    Sym.WHILE,
    Sym.TRUE,
    Sym.FALSE,
    Sym.NULL,
    Sym.VAR,
    Sym.YIELD,
    Sym.RECORD,
    Sym.SEALED,
    Sym.PERMITS,
    Sym.WHEN,
    Sym.UNDERSCORE
  );

  /// Builds a token, deriving any per-token facts from its text. Prefer this over the constructor
  /// so those facts are computed once at construction.
  public static Token of(
    Kind kind,
    String text,
    int start,
    int end,
    int newlinesBefore,
    boolean atColumn0
  ) {
    return new Token(kind, text, start, end, newlinesBefore, atColumn0, Sym.of(text));
  }

  public boolean is(String s) {
    return text.equals(s);
  }

  public boolean isComment() {
    return kind == Kind.LINE_COMMENT || kind == Kind.BLOCK_COMMENT;
  }

  /// The text is one of [#KEYWORDS]. Keyword texts are identifier-shaped, so no other token
  /// kind's text can match.
  public boolean isKeyword() {
    return KEYWORDS.contains(sym);
  }

  /// The text is a primitive type keyword, including `void`.
  public boolean isPrimitive() {
    return PRIMITIVES.contains(sym);
  }

  /// The text is a modifier keyword that can open a declaration (`public`, `static`, ...).
  public boolean isModifier() {
    return MODIFIERS.contains(sym);
  }
}
