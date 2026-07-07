package com.whitehawk.javaformatter.core.lexer;

import com.whitehawk.javaformatter.core.Kind;
import com.whitehawk.javaformatter.core.Token;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/// Lexes Java source into a flat token stream, preserving comments and the number of line
/// terminators between tokens. Never rejects input: unknown characters become single-char
/// [Kind#PUNCT] tokens and unterminated literals/comments run to end of input.
@NullMarked
public final class JavaLexer {
  /// Shared single-char texts so each ASCII punctuation token does not allocate a new string.
  private static final String[] ASCII = new String[128];

  static {
    for (char c = 0; c < 128; c++) {
      ASCII[c] = String.valueOf(c);
    }
  }

  private final String src;
  private int pos;
  private int newlines;
  private boolean atLineStart = true;

  public JavaLexer(String src) {
    this.src = src;
  }

  public Stream<Token> stream() {
    return tokenize().stream();
  }

  /// Lexes the whole source into a list in one pass. The one consumer collects the tokens anyway,
  /// so building the list directly avoids the per-token overhead of a [Stream] pipeline.
  public List<Token> tokenize() {
    List<Token> tokens = new ArrayList<>(Math.max(16, src.length() / 4));
    for (Token t = next(); t != null; t = next()) {
      tokens.add(t);
    }
    return tokens;
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
    String text = kind == Kind.PUNCT ? punctText(start, pos) : src.substring(start, pos);
    return new Token(kind, text, start, pos, newlinesBefore, column0);
  }

  /// Shared text of the punctuation token `start..end`, avoiding a string allocation per
  /// operator: the multi-char cases mirror exactly what [#consumeOperator] can produce.
  private String punctText(int start, int end) {
    char c = src.charAt(start);
    if (end - start == 1) {
      return c < 128 ? ASCII[c] : src.substring(start, end);
    }
    char c1 = src.charAt(start + 1);
    int len = end - start;
    return switch (c) {
      case '=' -> "==";
      case '!' -> "!=";
      case '*' -> "*=";
      case '/' -> "/=";
      case '%' -> "%=";
      case '^' -> "^=";
      case ':' -> "::";
      case '.' -> "...";
      case '+' -> c1 == '+' ? "++" : "+=";
      case '-' -> c1 == '-' ? "--" : c1 == '=' ? "-=" : "->";
      case '&' -> c1 == '&' ? "&&" : "&=";
      case '|' -> c1 == '|' ? "||" : "|=";
      case '<' -> c1 == '=' ? "<=" : len == 2 ? "<<" : "<<=";
      case '>' -> switch (len) {
        case 2 -> c1 == '=' ? ">=" : ">>";
        case 3 -> src.charAt(start + 2) == '=' ? ">>=" : ">>>";
        default -> ">>>=";
      };
      default -> src.substring(start, end);
    };
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
    if (
      Character.isDigit(c) || (
        c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))
      )
    ) {
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
    consumeOperator(c);
    return Kind.PUNCT;
  }

  /// Advances past the operator starting at `pos`, dispatching on its first character `c`; a
  /// character that starts no multi-char operator is consumed alone.
  private void consumeOperator(char c) {
    pos++;
    char c1 = pos < src.length() ? src.charAt(pos) : '\0';
    switch (c) {
      case '=', '!', '*', '/', '%', '^' -> {
        if (c1 == '=') {
          pos++;
        }
      }
      case ':' -> {
        if (c1 == ':') {
          pos++;
        }
      }
      case '+' -> {
        if (c1 == '+' || c1 == '=') {
          pos++;
        }
      }
      case '-' -> {
        if (c1 == '-' || c1 == '=' || c1 == '>') {
          pos++;
        }
      }
      case '&' -> {
        if (c1 == '&' || c1 == '=') {
          pos++;
        }
      }
      case '|' -> {
        if (c1 == '|' || c1 == '=') {
          pos++;
        }
      }
      case '.' -> {
        // `...` is the only multi-char dot operator; `..` lexes as two dots.
        if (c1 == '.' && pos + 1 < src.length() && src.charAt(pos + 1) == '.') {
          pos += 2;
        }
      }
      case '<' -> {
        if (c1 == '=') {
          pos++;
        } else if (c1 == '<') {
          pos++;
          if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
          }
        }
      }
      case '>' -> {
        if (c1 == '=') {
          pos++;
        } else if (c1 == '>') {
          pos++;
          if (pos < src.length() && src.charAt(pos) == '>') {
            pos++;
          }
          if (pos < src.length() && src.charAt(pos) == '=') {
            pos++;
          }
        }
      }
      default -> {}
    }
  }

  private void consumeNumber() {
    boolean hexOrBinary = src.startsWith("0x", pos)
      || src.startsWith("0X", pos)
      || src.startsWith("0b", pos)
      || src.startsWith("0B", pos);
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
