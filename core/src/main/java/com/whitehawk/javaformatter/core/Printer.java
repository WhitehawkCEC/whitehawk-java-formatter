package com.whitehawk.javaformatter.core;

import com.whitehawk.javaformatter.core.JavaLexer.Kind;
import com.whitehawk.javaformatter.core.JavaLexer.Token;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/// Renders a token stream back to source. Line breaks are normalized: a bracket group that spans
/// more than one input line has its opener and closer isolated on their own lines (and each of a
/// paren group's top-level comma-separated elements then starts its own line), and a method
/// chain (two or more `.name(..)` calls) that spans more than one input line breaks before every
/// call. A line wider than the line limit is wrapped the same way: its last outermost bracket
/// group gets the opener and closer isolated, repeatedly until every line fits (or no group is
/// left to break). A wrapped statement whose remaining breaks are all soft (none of the above) is
/// joined back onto one line when it fits within the line limit. Everything else is recomputed
/// too:
/// indentation, spacing between tokens on a line, blank-line counts (runs collapse to one; none
/// directly after a `{`/`(` line nor before a `}`/`)` line), and line endings (LF, single final
/// newline).
@NullMarked
final class Printer {
  private static final int INDENT = 2;
  private static final int MAX_WIDTH = 100;

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
  /// Type-argument disambiguation already ran at this `<`; a failed scan is never retried.
  private static final byte ANGLE_SCANNED = 32;

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
    /// Branch-line indent of each open ternary in the current element, innermost first.
    final Deque<Integer> ternaryIndents = new ArrayDeque<>();
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
  private int[] lineIndent;
  /// True where a line break must precede the token; seeded from input newlines, then normalized.
  private final boolean[] breakBefore;
  /// True where the break was forced by canonical style (bracket isolation, chain breaks); such
  /// breaks are never joined away.
  private final boolean[] forcedBreak;
  /// Input line index per token (blank lines ignored), used to decide what "spans multiple lines".
  private final int[] tokenLine;
  /// Matching-bracket index per token: `matchClose` at each opener, `matchOpen` at each closer;
  /// -1 elsewhere and at unbalanced brackets.
  private final int[] matchOpen;
  private final int[] matchClose;
  /// Output width per token, or -1 for a multiline token (text block, block comment).
  private final int[] tokenWidth;
  /// Whether a space separates each token from its predecessor when they share a line. Depends
  /// only on tokens and marks, so each [#analyze] pass leaves it fresh for width checks and emit.
  private final boolean[] spaceBefore;

  Printer(List<Token> tokens) {
    this.tokens = expandLambdaParams(tokens);
    int n = this.tokens.size();
    this.marks = new byte[n];
    this.breakBefore = new boolean[n];
    this.forcedBreak = new boolean[n];
    this.tokenLine = new int[n];
    this.matchOpen = new int[n];
    this.matchClose = new int[n];
    this.tokenWidth = new int[n];
    this.spaceBefore = new boolean[n];
    for (int i = 0; i < n; i++) {
      String text = this.tokens.get(i).text();
      tokenWidth[i] = text.indexOf('\n') >= 0 ? -1 : text.length();
    }
    computeBracketMatches();
    computeBreaks();
    buildLines();
    this.lineIndent = new int[lines.size()];
  }

  private void computeBracketMatches() {
    Arrays.fill(matchOpen, -1);
    Arrays.fill(matchClose, -1);
    Deque<Integer> openers = new ArrayDeque<>();
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.is("(") || t.is("[") || t.is("{")) {
        openers.push(i);
      } else if ((t.is(")") || t.is("]") || t.is("}")) && !openers.isEmpty()) {
        int o = openers.pop();
        matchClose[o] = i;
        matchOpen[i] = o;
      }
    }
  }

  /// Canonical style gives every implicit lambda parameter a `var`: an unparenthesized `x ->`
  /// becomes `(var x) ->`, and a parenthesized untyped list `(x, y) ->` becomes
  /// `(var x, var y) ->`. Parameters that already carry a type (or `var`) and switch-arrow labels
  /// (`case X ->`) are left untouched.
  private static List<Token> expandLambdaParams(List<Token> in) {
    int n = in.size();
    boolean[] wrap = new boolean[n]; // bare param: wrap in `(var ...)`
    boolean[] prefixVar = new boolean[n]; // parenthesized implicit param: prepend `var`
    boolean any = false;
    for (int a = 0; a < n; a++) {
      if (!in.get(a).is("->")) {
        continue;
      }
      int prev = prevCodeIndex(in, a);
      if (prev < 0) {
        continue;
      }
      if (in.get(prev).is(")")) {
        int open = matchOpenParen(in, prev);
        List<Integer> idents = open < 0 ? null : implicitParamIdents(in, open, prev);
        if (idents != null) {
          for (int idx : idents) {
            prefixVar[idx] = true;
            any = true;
          }
        }
      } else if (isBareLambdaParam(in, prev)) {
        wrap[prev] = true;
        any = true;
      }
    }
    if (!any) {
      return in;
    }

    List<Token> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Token t = in.get(i);
      if (wrap[i]) {
        out.add(new Token(Kind.PUNCT, "(", t.start(), t.start(), t.newlinesBefore(), t.atColumn0()));
        out.add(new Token(Kind.IDENT, "var", t.start(), t.start(), 0, false));
        out.add(new Token(t.kind(), t.text(), t.start(), t.end(), 0, false));
        out.add(new Token(Kind.PUNCT, ")", t.end(), t.end(), 0, false));
      } else if (prefixVar[i]) {
        out.add(new Token(Kind.IDENT, "var", t.start(), t.start(), t.newlinesBefore(), t.atColumn0()));
        out.add(new Token(t.kind(), t.text(), t.start(), t.end(), 0, false));
      } else {
        out.add(t);
      }
    }
    return out;
  }

  /// True when `in[i]` is a bare (unparenthesized) single lambda parameter: a non-keyword
  /// identifier immediately followed by `->`, not reached from a `case` label.
  private static boolean isBareLambdaParam(List<Token> in, int i) {
    Token t = in.get(i);
    if (t.kind() != Kind.IDENT || JavaLexer.KEYWORDS.contains(t.text())) {
      return false;
    }
    int arrow = nextCodeIndex(in, i);
    if (arrow < 0 || !in.get(arrow).is("->")) {
      return false;
    }
    // Walk back over a label/qualifier list, including generic and array type tokens so a type
    // pattern (`case Map<?, ?> map ->`) still reaches its `case`; a leading `case` marks a switch
    // arrow, not a lambda.
    for (int j = prevCodeIndex(in, i); j >= 0; j = prevCodeIndex(in, j)) {
      Token p = in.get(j);
      if (p.is("case")) {
        return false;
      }
      boolean labelPart = p.is(".") || p.is(",")
        || p.is("<") || p.is(">") || p.is(">>") || p.is(">>>") || p.is("?") || p.is("&")
        || p.is("[") || p.is("]") || p.is("extends") || p.is("super")
        || p.kind() == Kind.IDENT && !JavaLexer.KEYWORDS.contains(p.text());
      if (!labelPart) {
        break;
      }
    }
    return true;
  }

  /// If the parenthesized list `open`..`close` is a non-empty implicit lambda parameter list
  /// (every element a lone non-keyword identifier, so no explicit type and no `var`), returns
  /// those identifier indices; otherwise null. Typed lists, existing `var`, empty `()`, and
  /// record-deconstruction patterns all fail the lone-identifier test.
  private static @Nullable List<Integer> implicitParamIdents(List<Token> in, int open, int close) {
    List<Integer> idents = new ArrayList<>();
    int elementTokens = 0;
    int lastIdent = -1;
    for (int i = open + 1; i < close; i++) {
      Token t = in.get(i);
      if (t.isComment()) {
        continue;
      }
      if (t.is(",")) {
        if (elementTokens != 1) {
          return null;
        }
        idents.add(lastIdent);
        elementTokens = 0;
        continue;
      }
      if (++elementTokens > 1 || t.kind() != Kind.IDENT || JavaLexer.KEYWORDS.contains(t.text())) {
        return null;
      }
      lastIdent = i;
    }
    if (elementTokens != 1) {
      return null;
    }
    idents.add(lastIdent);
    return idents;
  }

  private static int matchOpenParen(List<Token> in, int close) {
    int depth = 0;
    for (int i = close; i >= 0; i--) {
      Token t = in.get(i);
      if (t.is(")")) {
        depth++;
      } else if (t.is("(") && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private static int nextCodeIndex(List<Token> in, int i) {
    for (int j = i + 1; j < in.size(); j++) {
      if (!in.get(j).isComment()) {
        return j;
      }
    }
    return -1;
  }

  private static int prevCodeIndex(List<Token> in, int i) {
    for (int j = i - 1; j >= 0; j--) {
      if (!in.get(j).isComment()) {
        return j;
      }
    }
    return -1;
  }

  /// Seeds [#breakBefore] from input line terminators, then forces the breaks canonical style
  /// requires around multiline brackets and method chains.
  private void computeBreaks() {
    int n = tokens.size();
    int line = 0;
    for (int i = 0; i < n; i++) {
      if (i > 0 && tokens.get(i).newlinesBefore() > 0) {
        line++;
        breakBefore[i] = true;
      }
      tokenLine[i] = line;
    }

    // A bracket group spanning more than one line isolates its opener and closer.
    for (int o = 0; o < n; o++) {
      int c = matchClose[o];
      if (c > o + 1 && tokenLine[o] != tokenLine[c]) {
        breakBefore[o + 1] = true;
        breakBefore[c] = true;
        forcedBreak[o + 1] = true;
        forcedBreak[c] = true;
      }
    }

    forceChainBreaks();

    // Canonical style never breaks before a method declaration's `throws` clause: join it to the
    // signature, or to the isolated `)` closer of a multiline parameter list. Skip when the
    // preceding token is a line comment, which would otherwise swallow the rest of the line.
    for (int i = 1; i < n; i++) {
      if (tokens.get(i).is("throws") && tokens.get(i - 1).kind() != Kind.LINE_COMMENT) {
        breakBefore[i] = false;
      }
    }
  }

  /// Breaks before every `.name(` call in each method chain that spans more than one input line.
  private void forceChainBreaks() {
    int n = tokens.size();
    int[] nextCall = new int[n];
    boolean[] callDot = new boolean[n];
    boolean[] linked = new boolean[n];
    Arrays.fill(nextCall, -1);
    for (int p = 0; p < n; p++) {
      if (!isCallDot(p)) {
        continue;
      }
      callDot[p] = true;
      int paren = indexOfNextCode(indexOfNextCode(p));
      int next = indexOfNextCode(matchClose[paren]);
      if (next >= 0 && isCallDot(next)) {
        nextCall[p] = next;
        linked[next] = true;
      }
    }
    for (int p = 0; p < n; p++) {
      if (!callDot[p] || linked[p]) {
        continue; // not the head of a chain
      }
      List<Integer> chain = new ArrayList<>();
      for (int c = p; c >= 0; c = nextCall[c]) {
        chain.add(c);
      }
      if (chain.size() < 2) {
        continue;
      }
      int last = chain.get(chain.size() - 1);
      int lastClose = matchClose[indexOfNextCode(indexOfNextCode(last))];
      if (tokenLine[p] != tokenLine[lastClose]) {
        for (int dot : chain) {
          breakBefore[dot] = true;
          forcedBreak[dot] = true;
        }
      }
    }
  }

  /// A `.` that begins a method call: `. name (`.
  private boolean isCallDot(int p) {
    if (!tokens.get(p).is(".")) {
      return false;
    }
    int name = indexOfNextCode(p);
    if (name < 0 || tokens.get(name).kind() != Kind.IDENT) {
      return false;
    }
    int paren = indexOfNextCode(name);
    return paren >= 0 && tokens.get(paren).is("(");
  }

  private void buildLines() {
    int lineStart = 0;
    for (int i = 1; i <= tokens.size(); i++) {
      if (i == tokens.size() || breakBefore[i]) {
        int nb = lineStart == 0 ? 0 : tokens.get(lineStart).newlinesBefore();
        lines.add(new Line(lineStart, i - lineStart, nb > 1 ? nb - 1 : 0));
        lineStart = i;
      }
    }
  }

  String print() {
    analyze(null);
    while (wrapLongLines() | breakGroupElements()) {
      lines.clear();
      buildLines();
      lineIndent = new int[lines.size()];
      analyze(null);
    }
    // An isolated control-flow condition paren (`if`/`while`/...) whose whole header fits on one
    // line collapses back, reversing the forced isolation that a soft join cannot cross.
    if (collapseControlFlowHeaders()) {
      lines.clear();
      buildLines();
      lineIndent = new int[lines.size()];
      analyze(null);
    }
    // A soft-broken continuation that gets joined back (e.g. a single `.call(` unwrapped onto the
    // assignment line) opens its bracket scope on the joined line: re-indent so that scope flows
    // from the join target, not from the now-discarded continuation indent.
    boolean[] joinWithPrev = computeJoins();
    analyze(joinWithPrev);
    return emit(joinWithPrev);
  }

  /// Every top-level element of a multiline paren group starts on its own line: forces a break
  /// after each top-level comma of a group whose opener is isolated. Commas nested in inner
  /// brackets or generic type-argument lists don't count. Returns whether any break was added.
  private boolean breakGroupElements() {
    boolean changed = false;
    // A marked type-argument list never contains `(`, so any angle open at a comma opened after
    // the comma's enclosing paren: a running depth replaces a per-comma rescan.
    int generic = 0;
    Deque<Integer> openers = new ArrayDeque<>();
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if ((marks[i] & GENERIC_ANGLE) != 0) {
        generic += t.is("<") ? 1 : -t.text().length(); // `>`, `>>`, `>>>`
        continue;
      }
      if (t.is("(") || t.is("[") || t.is("{")) {
        openers.push(i);
      } else if ((t.is(")") || t.is("]") || t.is("}")) && !openers.isEmpty()) {
        openers.pop();
      } else if (t.is(",") && generic == 0 && !openers.isEmpty()) {
        int o = openers.peek();
        if (tokens.get(o).is("(") && breakBefore[o + 1] && !breakBefore[i + 1]) {
          breakBefore[i + 1] = true;
          forcedBreak[i + 1] = true;
          changed = true;
        }
      }
    }
    return changed;
  }

  /// Forces breaks that wrap each line wider than [#MAX_WIDTH]: the last bracket group on the
  /// line that is outermost within it and non-empty gets its opener and closer isolated, the same
  /// shape as a group that was already multiline in the input. Returns whether any break was
  /// added; the caller then re-derives lines and indents and tries again, so a remainder that is
  /// still too wide wraps at its next group.
  private boolean wrapLongLines() {
    boolean changed = false;
    for (int li = 0; li < lines.size(); li++) {
      if (lineWidth(li) <= MAX_WIDTH) {
        continue;
      }
      Line line = lines.get(li);
      int open = -1;
      int close = -1;
      int end = line.firstToken() + line.tokenCount();
      for (int i = line.firstToken(); i < end; i++) {
        Token t = tokens.get(i);
        if (!t.is("(") && !t.is("[") && !t.is("{")) {
          continue;
        }
        int c = matchClose[i];
        if (c < 0 || c >= end) {
          break; // the rest of the line is nested inside this group
        }
        if (c > i + 1) {
          open = i;
          close = c;
        }
        i = c; // skip the contents: only groups outermost within the line count
      }
      if (open >= 0 && !breakBefore[open + 1]) {
        breakBefore[open + 1] = true;
        forcedBreak[open + 1] = true;
        breakBefore[close] = true;
        forcedBreak[close] = true;
        changed = true;
      }
    }
    return changed;
  }

  /// Collapses each control-flow condition paren (`if`, `while`, `for`, `switch`, `catch`,
  /// `synchronized`, `try`) that was isolated because it spanned multiple input lines but whose
  /// whole header — from the keyword's line start through the opening `{` — fits on one line.
  /// Reverses the forced opener/closer/element breaks that a soft join cannot cross. Returns
  /// whether any break was removed.
  private boolean collapseControlFlowHeaders() {
    boolean changed = false;
    for (int open = 0; open < tokens.size(); open++) {
      if (!tokens.get(open).is("(") || !breakBefore[open + 1]) {
        continue; // not an isolated paren
      }
      int close = matchClose[open];
      Token keyword = prevCode(open);
      if (close < 0 || keyword == null || !PAREN_KEYWORDS.contains(keyword.text())) {
        continue;
      }
      int brace = indexOfNextCode(close);
      if (brace < 0 || !tokens.get(brace).is("{")) {
        continue; // not a block header (excludes `return (..)`, `throw (..)`, etc.)
      }
      int li = lineIndexOf(open);
      int start = lines.get(li).firstToken();
      int width = lineIndent[li];
      boolean multiline = false;
      for (int i = start; i <= brace; i++) {
        if (tokenWidth[i] < 0) {
          multiline = true;
          break;
        }
        if (i > start && spaceBefore[i]) {
          width++;
        }
        width += tokenWidth[i];
      }
      if (multiline || width > MAX_WIDTH) {
        continue;
      }
      for (int i = open + 1; i <= close; i++) {
        if (breakBefore[i]) {
          breakBefore[i] = false;
          forcedBreak[i] = false;
          changed = true;
        }
      }
    }
    return changed;
  }

  /// Lines partition the tokens in order, so binary search by token index.
  private int lineIndexOf(int token) {
    int lo = 0;
    int hi = lines.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      Line line = lines.get(mid);
      if (token < line.firstToken()) {
        hi = mid - 1;
      } else if (token >= line.firstToken() + line.tokenCount()) {
        lo = mid + 1;
      } else {
        return mid;
      }
    }
    return -1;
  }

  /// Output width of line `li`, or 0 when it contains a multiline token (text block, block
  /// comment) and cannot be usefully measured or wrapped.
  private int lineWidth(int li) {
    Line line = lines.get(li);
    int width = lineIndent[li];
    for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
      if (tokenWidth[i] < 0) {
        return 0;
      }
      if (i > line.firstToken() && spaceBefore[i]) {
        width++;
      }
      width += tokenWidth[i];
    }
    return width;
  }

  // ---------------------------------------------------------------------------------------------
  // Analysis: scope tracking, line indents, token roles.
  // ---------------------------------------------------------------------------------------------

  /// Assigns each line its indent and updates token roles. When `joinWithPrev` is non-null, a line
  /// it flags as joined onto the previous one reuses the join run's head indent, so any bracket
  /// scope opened on that line flows from the joined line rather than a continuation indent.
  private void analyze(boolean @Nullable [] joinWithPrev) {
    Deque<Scope> stack = new ArrayDeque<>();
    stack.push(new Scope('B', 0, 0));
    List<Integer> pendingComments = new ArrayList<>();
    int prevIndent = 0;
    int headIndent = 0;

    for (int li = 0; li < lines.size(); li++) {
      Line line = lines.get(li);
      if (isCommentOnly(line)) {
        pendingComments.add(li);
        continue;
      }
      Scope top = stack.peek();
      Token first = tokens.get(line.firstToken());
      int indent;
      if (joinWithPrev != null && joinWithPrev[li]) {
        indent = headIndent;
      } else if (isCloser(first)) {
        indent = scopeFor(stack, first).closeIndent;
      } else if (top.elementOpen) {
        indent = continuationIndent(top, line.firstToken(), prevIndent);
      } else {
        indent = top.contentIndent;
        if (top.kind == 'S' && !first.is("case") && !first.is("default")) {
          indent += INDENT;
        }
      }
      lineIndent[li] = indent;
      prevIndent = indent;
      if (joinWithPrev == null || !joinWithPrev[li]) {
        headIndent = indent;
      }
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
    for (int i = 1; i < tokens.size(); i++) {
      spaceBefore[i] = spaceBetween(i - 1, i);
    }
  }

  /// Indent of a wrapped element's continuation line, normally one level past the element start.
  /// A ternary's branch lines sit one level past the line holding the end of its condition — one
  /// level past the previous line for `?`, aligned with the matching `?` for `:`.
  private int continuationIndent(Scope top, int firstToken, int prevIndent) {
    Token first = tokens.get(firstToken);
    if (first.is("?") && (marks[firstToken] & WILDCARD) == 0) {
      return prevIndent + INDENT;
    }
    if (first.is(":") && !top.ternaryIndents.isEmpty()) {
      return top.ternaryIndents.peek();
    }
    return top.elementStartIndent + INDENT;
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
        if ((marks[i] & (GENERIC_ANGLE | ANGLE_SCANNED)) == 0) {
          marks[i] |= ANGLE_SCANNED;
          int end = typeArgumentsEnd(i);
          if (end >= 0) {
            markTypeArguments(i, end);
          }
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
          top.ternaryIndents.push(breakBefore[i] ? indent : top.elementStartIndent + INDENT);
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
    if (!top.ternaryIndents.isEmpty()) {
      top.ternaryIndents.pop();
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
    scope.ternaryIndents.clear();
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
    Token beforeOpen = prevCode(matchOpen[closeIndex]);
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

  /// Index of the token closing the plausible type-argument list opened at `open`, or -1 when
  /// the `<` cannot open one.
  private int typeArgumentsEnd(int open) {
    Token prev = prevCode(open);
    if (prev == null) {
      return -1;
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
      return -1;
    }
    int end = scanTypeArguments(open);
    if (end < 0) {
      return -1;
    }
    Token follower = nextCode(end);
    if (follower == null) {
      return end;
    }
    boolean plausibleFollower = follower.kind() == Kind.IDENT
      || follower.is("(") || follower.is(")") || follower.is(",") || follower.is(".")
      || follower.is("::") || follower.is(";") || follower.is("[") || follower.is("{")
      || follower.is(">") || follower.is(">>") || follower.is(">>>") || follower.is("...")
      || follower.is("&") || follower.is("->") || follower.is("=") || follower.is("@");
    return plausibleFollower ? end : -1;
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

  private void markTypeArguments(int open, int end) {
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

  private @Nullable Token prevCode(int i) {
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

  private @Nullable Token nextCode(int i) {
    int idx = indexOfNextCode(i);
    return idx < 0 ? null : tokens.get(idx);
  }

  private int indexOfNextCode(int i) {
    if (i < 0) {
      return -1;
    }
    for (int j = i + 1; j < tokens.size(); j++) {
      if (!tokens.get(j).isComment()) {
        return j;
      }
    }
    return -1;
  }

  // ---------------------------------------------------------------------------------------------
  // Emit.
  // ---------------------------------------------------------------------------------------------

  /// Joins each run of soft-broken lines back onto one line when it fits within [#MAX_WIDTH] and
  /// the run is either a whole wrapped statement (it ends with `;`) or a statement prefix cut off
  /// by a forced break (e.g. `var b =` rejoins its chain's first receiver while the chain stays
  /// broken). Runs never cross a forced break, a blank line, a statement edge (`;`/`{`/`}`), a
  /// case or label colon, an annotation line, or a trailing line comment.
  private boolean[] computeJoins() {
    boolean[] joinWithPrev = new boolean[lines.size()];
    for (int li = 0; li < lines.size(); ) {
      int end = li;
      while (end + 1 < lines.size() && joinable(end + 1)) {
        end++;
      }
      boolean joinsStatement = lastToken(end).is(";")
        || end + 1 < lines.size() && forcedBreak[lines.get(end + 1).firstToken()];
      if (end > li && joinsStatement && fitsJoined(li, end)) {
        for (int j = li + 1; j <= end; j++) {
          joinWithPrev[j] = true;
        }
      }
      li = end + 1;
    }
    return joinWithPrev;
  }

  /// Whether line `li` may be joined onto the line before it.
  private boolean joinable(int li) {
    Line line = lines.get(li);
    if (line.blanksBefore() > 0 || forcedBreak[line.firstToken()]) {
      return false;
    }
    // A standalone comment line stays on its own line rather than trailing the previous one.
    if (isCommentOnly(line)) {
      return false;
    }
    Token first = tokens.get(line.firstToken());
    if (first.is("}") || first.is("@")) {
      return false;
    }
    // A wrapped ternary keeps its `?`/`:` branch lines.
    if (first.is("?") || first.is(":")) {
      return false;
    }
    Line prev = lines.get(li - 1);
    if (tokens.get(prev.firstToken()).is("@")) {
      return false;
    }
    int prevLastIndex = prev.firstToken() + prev.tokenCount() - 1;
    Token prevLast = tokens.get(prevLastIndex);
    if (prevLast.isComment() || prevLast.is(";") || prevLast.is("{") || prevLast.is("}") || prevLast.is(",")) {
      return false;
    }
    // `.call()` on an invocation result (`foo(..)` or `arr[..]`) is a chain wrap point and stays
    // broken; only a `.call()` on a plain name (`FooConfig`) joins.
    if (first.is(".") && (prevLast.is(")") || prevLast.is("]"))) {
      return false;
    }
    // A case-label or labeled-statement colon keeps its statement on the next line.
    return !(prevLast.is(":") && (marks[prevLastIndex] & COLON_NO_SPACE_BEFORE) != 0);
  }

  private Token lastToken(int li) {
    Line line = lines.get(li);
    return tokens.get(line.firstToken() + line.tokenCount() - 1);
  }

  private boolean fitsJoined(int startLine, int endLine) {
    int first = lines.get(startLine).firstToken();
    Line last = lines.get(endLine);
    int end = last.firstToken() + last.tokenCount();
    int width = lineIndent[startLine];
    for (int i = first; i < end; i++) {
      if (tokenWidth[i] < 0) {
        return false; // text block or multiline comment
      }
      if (i > first && spaceBefore[i]) {
        width++;
      }
      width += tokenWidth[i];
    }
    return width <= MAX_WIDTH;
  }

  private String emit(boolean[] joinWithPrev) {
    StringBuilder out = new StringBuilder();
    for (int li = 0; li < lines.size(); li++) {
      Line line = lines.get(li);
      boolean joined = li > 0 && joinWithPrev[li];
      if (li > 0 && !joined) {
        out.append('\n');
        if (keepBlank(li)) {
          out.append('\n');
        }
      }
      if (!joined) {
        out.repeat(" ", lineIndent[li]);
      }
      for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
        if ((i > line.firstToken() || joined) && spaceBefore[i]) {
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
    if (t.kind() == Kind.BLOCK_COMMENT && tokenWidth[i] < 0) {
      String text = t.text();
      int nl = text.indexOf('\n');
      out.append(text.substring(0, nl).stripTrailing());
      for (int from = nl + 1; from >= 0; ) {
        int next = text.indexOf('\n', from);
        String raw = next < 0 ? text.substring(from) : text.substring(from, next);
        if (raw.endsWith("\r")) {
          raw = raw.substring(0, raw.length() - 1);
        }
        String stripped = raw.stripLeading();
        out.append('\n');
        if (stripped.startsWith("*")) {
          out.repeat(" ", indent + 1).append(stripped.stripTrailing());
        } else {
          out.append(raw);
        }
        from = next < 0 ? -1 : next + 1;
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
