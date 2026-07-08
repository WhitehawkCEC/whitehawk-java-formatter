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

  /// Reserved words plus literals and contextual keywords that formatting logic (or renaming
  /// tools) must never treat as plain identifiers.
  private static final Set<String> KEYWORDS = Set.of(
    "abstract",
    "assert",
    "boolean",
    "break",
    "byte",
    "case",
    "catch",
    "char",
    "class",
    "const",
    "continue",
    "default",
    "do",
    "double",
    "else",
    "enum",
    "extends",
    "final",
    "finally",
    "float",
    "for",
    "goto",
    "if",
    "implements",
    "import",
    "instanceof",
    "int",
    "interface",
    "long",
    "native",
    "new",
    "package",
    "private",
    "protected",
    "public",
    "return",
    "short",
    "static",
    "strictfp",
    "super",
    "switch",
    "synchronized",
    "this",
    "throw",
    "throws",
    "transient",
    "try",
    "void",
    "volatile",
    "while",
    "true",
    "false",
    "null",
    "var",
    "yield",
    "record",
    "sealed",
    "permits",
    "when",
    "_"
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
    return KEYWORDS.contains(text);
  }

  /// The text is a primitive type keyword, including `void`.
  public boolean isPrimitive() {
    return PRIMITIVES.contains(sym);
  }

  /// The text is a modifier keyword that can open a declaration (`public`, `static`, ...).
  public boolean isModifier() {
    return MODIFIERS.contains(text);
  }
}
