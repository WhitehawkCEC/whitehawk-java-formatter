package com.whitehawk.javaformatter.core;

import com.whitehawk.javaformatter.core.JavaLexer.Kind;
import com.whitehawk.javaformatter.core.JavaLexer.Token;

import org.jspecify.annotations.NullMarked;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/// Renders a token stream back to source. Line breaks between tokens are preserved as-is;
/// everything else is recomputed: indentation, spacing between tokens on a line, blank-line
/// counts (runs collapse to one; none directly after a `{`/`(` line nor before a `}`/`)` line),
/// and line endings (LF, single final newline).
@NullMarked
final class Printer {
  private static final int INDENT = 2;

  private static final Set<String> PAREN_KEYWORDS = Set.of(
    "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "throw", "assert", "yield"
  );
  private static final Set<String> PRIMITIVES = Set.of(
    "boolean", "byte", "char", "short", "int", "long", "float", "double", "void"
  );
  private static final Set<String> MODIFIER_KEYWORDS = Set.of(
    "public", "private", "protected", "static", "final", "default", "abstract",
    "synchronized", "native", "strictfp"
  );
  private static final Set<String> BINARY_OPERATORS = Set.of(
    "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=",
    "==", "!=", "&&", "||", "+", "-", "*", "/", "%", "&", "|", "^",
    "<<", ">>", ">>>", "<", ">", "<=", ">=", "?", ":", "->"
  );
  /// Tokens allowed inside a type-argument list, used to disambiguate `<` from less-than.
  private static final Set<String> TYPE_ARG_PUNCT = Set.of(".", ",", "?", "@", "&", "[", "]", "extends", "super");
  private static final int TYPE_ARG_SCAN_LIMIT = 500;

  // Per-token roles resolved by the analysis pass.
  private static final byte GENERIC_ANGLE = 1;
  private static final byte WILDCARD = 2;
  private static final byte UNARY = 4;
  private static final byte CAST_CLOSE = 8;
  private static final byte COLON_NO_SPACE_BEFORE = 16;

  private record Line(int firstToken, int tokenCount, int blanksBefore) {}

  private static final class Scope {
    final char kind; // B=block, S=switch body, E=enum body, P=paren, K=bracket, A=array initializer
    final int contentIndent;
    final int closeIndent;
    int elementStartIndent;
    boolean elementOpen;
    boolean forParen;
    boolean sawSwitch;
    boolean sawEnum;
    boolean sawAssert;
    boolean caseLabel;
    int generic;
    int ternary;
    // Cast detection: content so far could be a type reference.
    boolean typeLike = true;
    boolean sawPrimitive;
    boolean hasContent;
    boolean lastWasWord;
    // Annotation-only statement tracking: 0=start, 1=expect name part, 2=after name, -1=broken.
    int annotationState;

    Scope(char kind, int contentIndent, int closeIndent) {
      this.kind = kind;
      this.contentIndent = contentIndent;
      this.closeIndent = closeIndent;
    }
  }

  private final List<Token> tokens;
  private final List<Line> lines = new ArrayList<>();
  private final byte[] marks;
  private final int[] lineIndent;

  Printer(List<Token> tokens) {
    this.tokens = tokens;
    this.marks = new byte[tokens.size()];
    buildLines();
    this.lineIndent = new int[lines.size()];
  }

  private void buildLines() {
    int lineStart = 0;
    for (int i = 1; i <= tokens.size(); i++) {
      if (i == tokens.size() || tokens.get(i).newlinesBefore() > 0) {
        lines.add(new Line(lineStart, i - lineStart, lineStart == 0 ? 0 : tokens.get(lineStart).newlinesBefore() - 1));
        lineStart = i;
      }
    }
  }

  String print() {
    analyze();
    return emit();
  }

  // ---------------------------------------------------------------------------------------------
  // Analysis: scope tracking, line indents, token roles.
  // ---------------------------------------------------------------------------------------------

  private void analyze() {
    Deque<Scope> stack = new ArrayDeque<>();
    stack.push(new Scope('B', 0, 0));
    List<Integer> pendingComments = new ArrayList<>();

    for (int li = 0; li < lines.size(); li++) {
      Line line = lines.get(li);
      if (isCommentOnly(line)) {
        pendingComments.add(li);
        continue;
      }
      Scope top = stack.peek();
      Token first = tokens.get(line.firstToken());
      int indent;
      if (isCloser(first)) {
        indent = scopeFor(stack, first).closeIndent;
      } else if (top.elementOpen) {
        indent = top.elementStartIndent + INDENT;
      } else {
        indent = top.contentIndent;
        if (top.kind == 'S' && !first.is("case") && !first.is("default")) {
          indent += INDENT;
        }
      }
      lineIndent[li] = indent;
      int bodyIndent = isCloser(first) ? scopeFor(stack, first).contentIndent : indent;
      for (int ci : pendingComments) {
        lineIndent[ci] = tokens.get(lines.get(ci).firstToken()).atColumn0() ? 0 : bodyIndent;
      }
      pendingComments.clear();
      walkLine(stack, line, indent);
    }
    for (int ci : pendingComments) {
      lineIndent[ci] = tokens.get(lines.get(ci).firstToken()).atColumn0() ? 0 : stack.peek().contentIndent;
    }
  }

  private boolean isCommentOnly(Line line) {
    for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
      if (!tokens.get(i).isComment()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isCloser(Token t) {
    return t.is("}") || t.is(")") || t.is("]");
  }

  /// The scope a closing token returns to: normally the top of the stack; on unbalanced input,
  /// the innermost scope of the matching kind.
  private static Scope scopeFor(Deque<Scope> stack, Token closer) {
    char kind = closer.is("}") ? '{' : closer.is(")") ? '(' : '[';
    for (Scope s : stack) {
      boolean matches = switch (kind) {
        case '{' -> s.kind == 'B' || s.kind == 'S' || s.kind == 'E' || s.kind == 'A';
        case '(' -> s.kind == 'P';
        default -> s.kind == 'K';
      };
      if (matches) {
        return s;
      }
    }
    return stack.peekLast();
  }

  private void walkLine(Deque<Scope> stack, Line line, int indent) {
    for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
      Token t = tokens.get(i);
      if (t.isComment()) {
        continue;
      }
      Scope top = stack.peek();
      if (!top.elementOpen && !isCloser(t)) {
        top.elementOpen = true;
        top.elementStartIndent = indent;
        top.caseLabel = top.kind == 'S' && (t.is("case") || t.is("default"));
      }
      analyzeToken(stack, i, indent);
    }
    endOfLine(stack, line);
  }

  private void analyzeToken(Deque<Scope> stack, int i, int indent) {
    Token t = tokens.get(i);
    Scope top = stack.peek();
    updateAnnotationState(top, t);

    switch (t.text()) {
      case "(" -> {
        Scope scope = new Scope('P', indent + INDENT, indent);
        scope.forParen = wordBefore(i, "for") || wordBefore(i, "try");
        stack.push(scope);
        return;
      }
      case "[" -> {
        stack.push(new Scope('K', indent + INDENT, indent));
        return;
      }
      case "{" -> {
        stack.push(openBrace(stack, i, indent));
        return;
      }
      case ")", "]" -> {
        Scope closed = stack.peek();
        if (stack.size() > 1) {
          stack.pop();
        }
        if (t.is(")") && isCast(closed, i)) {
          marks[i] |= CAST_CLOSE;
        }
        afterContentToken(stack.peek(), t.is("]")); // `]` can end an array type in a cast
        return;
      }
      case "}" -> {
        if (stack.size() > 1) {
          stack.pop();
        }
        return;
      }
      case ";" -> {
        if (top.kind == 'P' || top.kind == 'K') {
          resetElement(top); // for-loop sections and try-with-resources
        } else {
          closeElement(top);
        }
        return;
      }
      case "," -> {
        if (top.generic == 0 && (top.kind == 'P' || top.kind == 'K' || top.kind == 'A' || top.kind == 'E')) {
          resetElement(top);
        }
        return;
      }
      case "<" -> {
        if ((marks[i] & GENERIC_ANGLE) == 0 && genericFits(i)) {
          markTypeArguments(i);
        }
        if ((marks[i] & GENERIC_ANGLE) != 0) {
          top.generic++;
        } else {
          afterContentToken(top, false);
        }
        return;
      }
      case ">", ">>", ">>>" -> {
        if ((marks[i] & GENERIC_ANGLE) != 0) {
          top.generic = Math.max(0, top.generic - t.text().length());
        } else {
          afterContentToken(top, false);
        }
        return;
      }
      case "?" -> {
        if (top.generic > 0) {
          marks[i] |= WILDCARD;
        } else {
          top.ternary++;
          afterContentToken(top, false);
        }
        return;
      }
      case ":" -> {
        analyzeColon(stack, i, top);
        return;
      }
      case "+", "-", "++", "--", "!", "~" -> {
        if (isUnaryPosition(i)) {
          marks[i] |= UNARY;
        }
        afterContentToken(top, false);
        return;
      }
      default -> {
        if (t.is("switch")) {
          top.sawSwitch = true;
        } else if (t.is("enum")) {
          top.sawEnum = true;
        } else if (t.is("assert")) {
          top.sawAssert = true;
        }
        if (PRIMITIVES.contains(t.text())) {
          top.sawPrimitive = true;
        }
        boolean word = t.kind() != Kind.PUNCT;
        boolean typeToken = t.kind() == Kind.IDENT
          && (!JavaLexer.KEYWORDS.contains(t.text())
            || PRIMITIVES.contains(t.text()) || t.is("extends") || t.is("super"))
          || t.is(".") || t.is("@")
          || t.is("&") && top.generic > 0;
        if (word && top.lastWasWord && top.generic == 0) {
          top.typeLike = false; // two adjacent words at top level cannot be a cast type
        }
        afterContentToken(top, typeToken);
        top.lastWasWord = word;
        return;
      }
    }
  }

  private void updateAnnotationState(Scope top, Token t) {
    switch (top.annotationState) {
      case 0 -> top.annotationState = t.is("@") ? 1 : -1;
      case 1 -> top.annotationState = t.kind() == Kind.IDENT ? 2 : -1;
      case 2 -> top.annotationState = t.is("@") ? 1 : t.is(".") ? 1 : t.is("(") ? 2 : -1;
      default -> {}
    }
  }

  /// Marks content flowing through `scope`; `typeToken` keeps cast-type detection alive.
  private void afterContentToken(Scope scope, boolean typeToken) {
    scope.hasContent = true;
    if (!typeToken) {
      scope.typeLike = false;
    }
  }

  private boolean wordBefore(int i, String word) {
    Token prev = prevCode(i);
    return prev != null && prev.is(word);
  }

  private Scope openBrace(Deque<Scope> stack, int i, int indent) {
    Scope top = stack.peek();
    Token prev = prevCode(i);
    if (prev != null && (prev.is("=") || prev.is(",") || prev.is("{") || prev.is("(") || prev.is("]"))) {
      return new Scope('A', indent + INDENT, indent);
    }
    if (top.sawSwitch && prev != null && prev.is(")")) {
      top.sawSwitch = false;
      return new Scope('S', indent + INDENT, indent);
    }
    if (top.sawEnum) {
      top.sawEnum = false;
      return new Scope('E', indent + INDENT, indent);
    }
    return new Scope('B', indent + INDENT, indent);
  }

  private void analyzeColon(Deque<Scope> stack, int i, Scope top) {
    if (top.ternary > 0) {
      top.ternary--;
      afterContentToken(top, false);
      return;
    }
    if (top.kind == 'P' && top.forParen) {
      afterContentToken(top, false); // enhanced-for colon, spaced both sides
      return;
    }
    if (top.kind == 'S' && top.caseLabel) {
      marks[i] |= COLON_NO_SPACE_BEFORE;
      closeElement(top);
      return;
    }
    if (top.sawAssert) {
      afterContentToken(top, false);
      return;
    }
    marks[i] |= COLON_NO_SPACE_BEFORE; // labeled statement
    closeElement(top);
  }

  private void closeElement(Scope scope) {
    scope.elementOpen = false;
    resetElement(scope);
  }

  private void resetElement(Scope scope) {
    scope.caseLabel = false;
    scope.sawSwitch = false;
    scope.sawEnum = false;
    scope.sawAssert = false;
    scope.generic = 0;
    scope.ternary = 0;
    scope.typeLike = true;
    scope.sawPrimitive = false;
    scope.hasContent = false;
    scope.lastWasWord = false;
    scope.annotationState = 0;
    if (scope.kind == 'P' || scope.kind == 'K' || scope.kind == 'A' || scope.kind == 'E') {
      scope.elementOpen = false;
    }
  }

  private void endOfLine(Deque<Scope> stack, Line line) {
    Token last = null;
    for (int i = line.firstToken() + line.tokenCount() - 1; i >= line.firstToken(); i--) {
      if (!tokens.get(i).isComment()) {
        last = tokens.get(i);
        break;
      }
    }
    if (last == null) {
      return;
    }
    Scope top = stack.peek();
    if (last.is("}")) {
      closeElement(top);
    } else if (top.elementOpen && top.annotationState == 2) {
      closeElement(top); // annotation-only line: next line starts fresh at the same indent
    }
  }

  private boolean isCast(Scope paren, int closeIndex) {
    if (!paren.typeLike || !paren.hasContent) {
      return false;
    }
    Token beforeOpen = prevCode(matchingOpen(closeIndex));
    // A cast's `(` cannot follow a name or a closing bracket (that would be a call or index).
    if (beforeOpen != null
      && (beforeOpen.kind() == Kind.IDENT && !JavaLexer.KEYWORDS.contains(beforeOpen.text())
        || beforeOpen.is(")") || beforeOpen.is("]"))) {
      return false;
    }
    Token next = nextCode(closeIndex);
    if (next == null) {
      return false;
    }
    return switch (next.text()) {
      case "+", "-", "++", "--" -> paren.sawPrimitive;
      case "(", "!", "~", "this", "super", "new" -> true;
      default -> next.kind() != Kind.PUNCT && !JavaLexer.KEYWORDS.contains(next.text())
        || next.kind() == Kind.NUMBER || next.kind() == Kind.STRING || next.kind() == Kind.CHAR
        || next.kind() == Kind.TEXT_BLOCK;
    };
  }

  private int matchingOpen(int closeIndex) {
    int depth = 0;
    for (int i = closeIndex; i >= 0; i--) {
      Token t = tokens.get(i);
      if (t.is(")")) {
        depth++;
      } else if (t.is("(")) {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return 0;
  }

  private boolean isUnaryPosition(int i) {
    Token prev = prevCode(i);
    if (prev == null) {
      return true;
    }
    if (prev.kind() == Kind.NUMBER || prev.kind() == Kind.STRING || prev.kind() == Kind.CHAR
      || prev.kind() == Kind.TEXT_BLOCK) {
      return false;
    }
    if (prev.kind() == Kind.IDENT) {
      return JavaLexer.KEYWORDS.contains(prev.text()) && !prev.is("this") && !prev.is("super")
        && !prev.is("true") && !prev.is("false") && !prev.is("null");
    }
    if (prev.is(")")) {
      return (marks[indexOfPrevCode(i)] & CAST_CLOSE) != 0;
    }
    return !prev.is("]") && !prev.is("++") && !prev.is("--");
  }

  // --- generic type-argument disambiguation ---

  private boolean genericFits(int open) {
    Token prev = prevCode(open);
    if (prev == null) {
      return false;
    }
    boolean plausiblePrev = prev.kind() == Kind.IDENT && !JavaLexer.KEYWORDS.contains(prev.text())
      || prev.is(".") || prev.is(",") || prev.is("(") || prev.is("<") || prev.is("{")
      || prev.is("&") || prev.is("|") || prev.is("=") || prev.is("return") || prev.is("new")
      || prev.is("extends") || prev.is("super") || prev.is("implements") || prev.is("instanceof")
      || prev.is("case") || prev.is("yield") || prev.is("->") || prev.is("::") || prev.is("?") || prev.is(":")
      // Type-parameter declarations: `public <T> T foo(..)`, `interface Foo<T>`, `<T> T foo(..)`.
      || prev.is("public") || prev.is("private") || prev.is("protected") || prev.is("static")
      || prev.is("final") || prev.is("default") || prev.is("abstract") || prev.is("class")
      || prev.is("interface") || prev.is("record")
      || prev.is(";") || prev.is("{") || prev.is("}");
    if (!plausiblePrev) {
      return false;
    }
    int end = scanTypeArguments(open);
    if (end < 0) {
      return false;
    }
    Token follower = nextCode(end);
    if (follower == null) {
      return true;
    }
    return follower.kind() == Kind.IDENT
      || follower.is("(") || follower.is(")") || follower.is(",") || follower.is(".")
      || follower.is("::") || follower.is(";") || follower.is("[") || follower.is("{")
      || follower.is(">") || follower.is(">>") || follower.is(">>>") || follower.is("...")
      || follower.is("&") || follower.is("->") || follower.is("=") || follower.is("@");
  }

  /// Returns the index of the token that closes the type-argument list opened at `open`,
  /// or -1 if the token run cannot be one.
  private int scanTypeArguments(int open) {
    int depth = 1;
    for (int i = open + 1; i < tokens.size() && i - open < TYPE_ARG_SCAN_LIMIT; i++) {
      Token t = tokens.get(i);
      if (t.isComment()) {
        continue;
      }
      if (t.is("<")) {
        depth++;
      } else if (t.is(">") || t.is(">>") || t.is(">>>")) {
        depth -= t.text().length();
        if (depth <= 0) {
          return depth == 0 ? i : -1;
        }
      } else if (t.kind() == Kind.IDENT) {
        if (JavaLexer.KEYWORDS.contains(t.text()) && !TYPE_ARG_PUNCT.contains(t.text())
          && !PRIMITIVES.contains(t.text())) {
          return -1;
        }
      } else if (t.kind() != Kind.PUNCT || !TYPE_ARG_PUNCT.contains(t.text())) {
        return -1;
      }
    }
    return -1;
  }

  private void markTypeArguments(int open) {
    int end = scanTypeArguments(open);
    marks[open] |= GENERIC_ANGLE;
    for (int i = open + 1; i <= end; i++) {
      Token t = tokens.get(i);
      if (t.is("<") || t.is(">") || t.is(">>") || t.is(">>>")) {
        marks[i] |= GENERIC_ANGLE;
      } else if (t.is("?")) {
        marks[i] |= WILDCARD;
      }
    }
  }

  private Token prevCode(int i) {
    int idx = indexOfPrevCode(i);
    return idx < 0 ? null : tokens.get(idx);
  }

  private int indexOfPrevCode(int i) {
    for (int j = i - 1; j >= 0; j--) {
      if (!tokens.get(j).isComment()) {
        return j;
      }
    }
    return -1;
  }

  private Token nextCode(int i) {
    for (int j = i + 1; j < tokens.size(); j++) {
      if (!tokens.get(j).isComment()) {
        return tokens.get(j);
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // Emit.
  // ---------------------------------------------------------------------------------------------

  private String emit() {
    StringBuilder out = new StringBuilder();
    for (int li = 0; li < lines.size(); li++) {
      Line line = lines.get(li);
      if (li > 0) {
        out.append('\n');
        if (keepBlank(li)) {
          out.append('\n');
        }
      }
      out.append(" ".repeat(lineIndent[li]));
      for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
        if (i > line.firstToken() && spaceBetween(i - 1, i)) {
          out.append(' ');
        }
        appendToken(out, i, lineIndent[li]);
      }
    }
    out.append('\n');
    return out.toString();
  }

  private boolean keepBlank(int li) {
    if (lines.get(li).blanksBefore() == 0) {
      return false;
    }
    Line prev = lines.get(li - 1);
    Token prevLast = tokens.get(prev.firstToken() + prev.tokenCount() - 1);
    if (prevLast.is("{") || prevLast.is("(")) {
      return false;
    }
    Token first = tokens.get(lines.get(li).firstToken());
    return !first.is("}") && !first.is(")");
  }

  private void appendToken(StringBuilder out, int i, int indent) {
    Token t = tokens.get(i);
    if (t.kind() == Kind.LINE_COMMENT) {
      out.append(t.text().stripTrailing());
      return;
    }
    if (t.kind() == Kind.BLOCK_COMMENT && t.text().indexOf('\n') >= 0) {
      String[] parts = t.text().split("\n", -1);
      out.append(parts[0].stripTrailing());
      for (int p = 1; p < parts.length; p++) {
        String raw = parts[p].endsWith("\r") ? parts[p].substring(0, parts[p].length() - 1) : parts[p];
        String stripped = raw.stripLeading();
        out.append('\n');
        if (stripped.startsWith("*")) {
          out.append(" ".repeat(indent + 1)).append(stripped.stripTrailing());
        } else {
          out.append(raw);
        }
      }
      return;
    }
    out.append(t.text());
  }

  private boolean spaceBetween(int prevIndex, int nextIndex) {
    Token prev = tokens.get(prevIndex);
    Token next = tokens.get(nextIndex);

    if (prev.isComment() || next.isComment()) {
      return true;
    }
    if (next.is(";") || next.is(",")) {
      return false;
    }
    if (prev.is("(") || prev.is("[")) {
      return false;
    }
    if (next.is(")") || next.is("]")) {
      return false;
    }
    if (prev.is(".") || next.is(".") || prev.is("::") || next.is("::")) {
      return false;
    }
    if (prev.is("@")) {
      return false;
    }
    if (next.is("...")) {
      return false;
    }
    if (prev.is("...")) {
      return true;
    }
    if (prev.is("->") || next.is("->")) {
      return true;
    }
    if ((marks[nextIndex] & UNARY) != 0) {
      return spaceBeforePrefix(prevIndex);
    }
    if ((marks[prevIndex] & UNARY) != 0) {
      return false;
    }
    if ((marks[prevIndex] & CAST_CLOSE) != 0) {
      return true;
    }
    // Generic angle brackets bind tightly; a space follows only a list-closing `>` before a word.
    if ((marks[nextIndex] & GENERIC_ANGLE) != 0) {
      // Type-parameter declarations keep a space after the modifier: `public <T> T get(..)`.
      return next.is("<") && MODIFIER_KEYWORDS.contains(prev.text());
    }
    if ((marks[prevIndex] & GENERIC_ANGLE) != 0) {
      return !prev.is("<") && (next.kind() != Kind.PUNCT || next.is("{") || next.is("@"));
    }
    if ((marks[prevIndex] & WILDCARD) != 0) {
      return next.kind() != Kind.PUNCT;
    }
    if ((marks[nextIndex] & WILDCARD) != 0) {
      return true; // after `<` or `,`; `<` was handled above
    }
    if (next.is(":")) {
      return (marks[nextIndex] & COLON_NO_SPACE_BEFORE) == 0;
    }
    if (prev.is(":")) {
      return true;
    }
    if (prev.is("{") && next.is("}")) {
      return false;
    }
    if (next.is("{") || prev.is("{") || next.is("}")) {
      return true;
    }
    if (prev.is("}")) {
      return !next.is("(") && !next.is("[");
    }
    if (next.is("(")) {
      return prev.kind() == Kind.IDENT && PAREN_KEYWORDS.contains(prev.text())
        || prev.is("do") || prev.is("else")
        || BINARY_OPERATORS.contains(prev.text()) && (marks[prevIndex] & GENERIC_ANGLE) == 0
        || prev.is(";") || prev.is(",");
    }
    if (next.is("[")) {
      return false;
    }
    if (prev.is(";") || prev.is(",")) {
      return true;
    }
    if (BINARY_OPERATORS.contains(prev.text()) || BINARY_OPERATORS.contains(next.text())) {
      return true;
    }
    boolean prevWord = prev.kind() != Kind.PUNCT;
    boolean nextWord = next.kind() != Kind.PUNCT;
    if (prevWord && nextWord) {
      return true;
    }
    if (next.is("@")) {
      return prevWord;
    }
    if (prev.is(")") || prev.is("]")) {
      return nextWord;
    }
    if (prevWord && (next.is("!") || next.is("~"))) {
      return true; // e.g. `return !x` when the operator was not marked (defensive)
    }
    return false;
  }

  private boolean spaceBeforePrefix(int prevIndex) {
    Token prev = tokens.get(prevIndex);
    if (prev.is("(") || prev.is("[") || prev.is("!") || prev.is("~") || prev.is("@")
      || prev.is(".") || prev.is("::")) {
      return false;
    }
    if ((marks[prevIndex] & UNARY) != 0) {
      return prev.is("+") || prev.is("-"); // keep `- -x` apart so it cannot re-lex as `--`
    }
    return true;
  }
}
