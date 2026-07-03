package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// Lexes Java source into a flat token stream, preserving comments and the number of line
/// terminators between tokens. Never rejects input: unknown characters become single-char
/// [Kind#PUNCT] tokens and unterminated literals/comments run to end of input.
@NullMarked
public final class JavaLexer {
  public enum Kind {
    IDENT,
    NUMBER,
    STRING,
    CHAR,
    TEXT_BLOCK,
    LINE_COMMENT,
    BLOCK_COMMENT,
    PUNCT,
  }

  /// @param start offset of the first character in the source
  /// @param end offset just past the last character
  /// @param newlinesBefore line terminators between the previous token and this one
  /// @param atColumn0 token starts at the very beginning of its line
  public record Token(Kind kind, String text, int start, int end, int newlinesBefore, boolean atColumn0) {
    public boolean is(String s) {
      return text.equals(s);
    }

    public boolean isComment() {
      return kind == Kind.LINE_COMMENT || kind == Kind.BLOCK_COMMENT;
    }
  }

  /// Reserved words plus literals and contextual keywords that formatting logic (or renaming
  /// tools) must never treat as plain identifiers.
  public static final Set<String> KEYWORDS = Set.of(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
    "native", "new", "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
    "void", "volatile", "while",
    "true", "false", "null",
    "var", "yield", "record", "sealed", "permits", "when", "_"
  );

  private static final List<String> OPERATORS = List.of(
    ">>>=",
    ">>>", "<<=", ">>=", "...",
    "->", "::", "==", "!=", "<=", ">=", "&&", "||", "++", "--",
    "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>",
    "(", ")", "{", "}", "[", "]", ";", ",", ".", "=", "<", ">",
    "+", "-", "*", "/", "%", "&", "|", "^", "!", "~", "?", ":", "@"
  );

  private final String src;
  private int pos;
  private int newlines;
  private boolean atLineStart = true;

  private JavaLexer(String src) {
    this.src = src;
  }

  public static List<Token> lex(String source) {
    JavaLexer lexer = new JavaLexer(source);
    List<Token> tokens = new ArrayList<>();
    while (true) {
      Token token = lexer.next();
      if (token == null) {
        return tokens;
      }
      tokens.add(token);
    }
  }

  private @Nullable Token next() {
    skipWhitespace();
    if (pos >= src.length()) {
      return null;
    }
    boolean column0 = atLineStart;
    int newlinesBefore = newlines;
    int start = pos;
    Kind kind = consumeToken();
    newlines = 0;
    atLineStart = false;
    return new Token(kind, src.substring(start, pos), start, pos, newlinesBefore, column0);
  }

  private void skipWhitespace() {
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (c == '\n') {
        newlines++;
        atLineStart = true;
        pos++;
      } else if (c == '\r') {
        newlines++;
        atLineStart = true;
        pos++;
        if (pos < src.length() && src.charAt(pos) == '\n') {
          pos++;
        }
      } else if (c == ' ' || c == '\t' || c == '\f') {
        atLineStart = false;
        pos++;
      } else {
        return;
      }
    }
  }

  private Kind consumeToken() {
    char c = src.charAt(pos);
    if (Character.isJavaIdentifierStart(c)) {
      while (pos < src.length() && Character.isJavaIdentifierPart(src.charAt(pos))) {
        pos++;
      }
      return Kind.IDENT;
    }
    if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
      consumeNumber();
      return Kind.NUMBER;
    }
    if (c == '"') {
      if (src.startsWith("\"\"\"", pos)) {
        consumeTextBlock();
        return Kind.TEXT_BLOCK;
      }
      consumeQuoted('"');
      return Kind.STRING;
    }
    if (c == '\'') {
      consumeQuoted('\'');
      return Kind.CHAR;
    }
    if (c == '/' && pos + 1 < src.length()) {
      char c1 = src.charAt(pos + 1);
      if (c1 == '/') {
        while (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') {
          pos++;
        }
        return Kind.LINE_COMMENT;
      }
      if (c1 == '*') {
        int end = src.indexOf("*/", pos + 2);
        pos = end < 0 ? src.length() : end + 2;
        return Kind.BLOCK_COMMENT;
      }
    }
    for (String op : OPERATORS) {
      if (src.startsWith(op, pos)) {
        pos += op.length();
        return Kind.PUNCT;
      }
    }
    pos++;
    return Kind.PUNCT;
  }

  private void consumeNumber() {
    boolean hexOrBinary = src.startsWith("0x", pos) || src.startsWith("0X", pos)
      || src.startsWith("0b", pos) || src.startsWith("0B", pos);
    if (hexOrBinary) {
      pos += 2;
    }
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (Character.isLetterOrDigit(c) || c == '_') {
        pos++;
        // A sign is part of the literal only in a float exponent: 1e+3, 0x1p-2.
        boolean exponent = hexOrBinary ? (c == 'p' || c == 'P') : (c == 'e' || c == 'E');
        if (exponent && pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
          pos++;
        }
      } else if (c == '.' && pos + 1 < src.length() && isNumberContinuation(src.charAt(pos + 1))) {
        pos++;
      } else {
        return;
      }
    }
  }

  private static boolean isNumberContinuation(char c) {
    return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private void consumeQuoted(char quote) {
    pos++;
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (c == '\\') {
        pos += 2;
      } else if (c == quote || c == '\n' || c == '\r') {
        if (c == quote) {
          pos++;
        }
        return;
      } else {
        pos++;
      }
    }
  }

  private void consumeTextBlock() {
    pos += 3;
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (c == '\\') {
        pos += 2;
      } else if (src.startsWith("\"\"\"", pos)) {
        pos += 3;
        return;
      } else {
        pos++;
      }
    }
  }
}
