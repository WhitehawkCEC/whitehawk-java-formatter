package com.whitehawk.javaformatter.core.printer;

import com.whitehawk.javaformatter.core.ArraySmallEnumSet;
import com.whitehawk.javaformatter.core.Kind;
import com.whitehawk.javaformatter.core.Token;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/// Renders a token stream back to source under a canonical style. Line breaks largely follow
/// what the input already did — a bracket group the input split stays split, one it kept together
/// may be re-joined — so output is stabilized rather than reflowed from scratch, keeping diffs
/// small and idempotent.
@NullMarked
public final class Printer {
  private static final int INDENT = 2;
  private static final int MAX_WIDTH = 100;

  private static final int TYPE_ARG_SCAN_LIMIT = 500;

  private record Line(int firstToken, int tokenCount, int blanksBefore) {}

  private final List<Token> tokens;
  private final List<Line> lines = new ArrayList<>();
  private final ArraySmallEnumSet<Mark> marks;
  /// Starts true so the first pass computes the state derived from the marks.
  private boolean marksChanged = true;
  private int[] lineIndent;
  private final boolean[] breakBefore;
  /// A forced break (bracket isolation, chain breaks) is never joined away.
  private final boolean[] forcedBreak;
  /// Blank lines are ignored, so equal values mean two tokens shared an input line.
  private final int[] tokenLine;
  /// `matchClose` at each opener, `matchOpen` at each closer; -1 elsewhere and at unbalanced brackets.
  private final int[] matchOpen;
  private final int[] matchClose;
  /// At a closer, the opener it closes; -1 at top level.
  private final int[] enclosingOpen;
  /// -1 for a multiline token (text block, block comment).
  private final int[] tokenWidth;
  private final ArraySmallEnumSet<Classification> tokenClasses;
  private final Sym[] tokenSym;
  /// Recomputed only by an [#analyze] pass that added a mark bit; later passes reuse it.
  private final boolean[] spaceBefore;
  /// Allocated once because the pass re-runs every wrap iteration.
  private final int[] openerStack;
  /// Prefix sums giving any range's width in O(1); a multiline token counts as zero width.
  /// Rebuilt whenever [#spaceBefore] is.
  private final int[] prefixWidth;
  private final int[] prefixMultiline;
  /// Recycled across [#analyze] passes instead of reallocated per bracket per pass.
  private final List<Scope> scopePool = new ArrayList<>();
  private int scopesUsed;
  /// Reused across [#analyze] passes instead of reallocated each pass.
  private final Deque<Scope> analyzeStack = new ArrayDeque<>();
  private final List<Integer> pendingComments = new ArrayList<>();

  public Printer(List<Token> tokens) {
    this.tokens = TokenPreprocessor.preprocess(tokens);
    int n = this.tokens.size();
    this.marks = new ArraySmallEnumSet<>(Mark.class, n);
    this.breakBefore = new boolean[n];
    this.forcedBreak = new boolean[n];
    this.tokenLine = new int[n];
    this.matchOpen = new int[n];
    this.matchClose = new int[n];
    this.enclosingOpen = new int[n];
    this.tokenWidth = new int[n];
    this.tokenClasses = new ArraySmallEnumSet<>(Classification.class, n);
    this.tokenSym = new Sym[n];
    this.spaceBefore = new boolean[n];
    this.openerStack = new int[n];
    this.prefixWidth = new int[n + 1];
    this.prefixMultiline = new int[n + 1];
    for (int i = 0; i < n; i++) {
      Token t = this.tokens.get(i);
      String text = t.text();
      tokenWidth[i] = text.indexOf('\n') >= 0 ? -1 : text.length();
      prefixMultiline[i + 1] = prefixMultiline[i] + (tokenWidth[i] < 0 ? 1 : 0);
      Classification.classify(tokenClasses, i, t);
      tokenSym[i] = Sym.of(text);
    }
    computeBracketMatches();
    computeBreaks();
    buildLines();
    this.lineIndent = new int[lines.size()];
  }

  private void mark(int i, Mark mark) {
    marksChanged |= marks.set(i, mark);
  }

  private boolean consumeMarksChanged() {
    boolean was = marksChanged;
    marksChanged = false;
    return was;
  }

  private void computeBracketMatches() {
    Arrays.fill(matchOpen, -1);
    Arrays.fill(matchClose, -1);
    int[] openers = new int[tokens.size()];
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      enclosingOpen[i] = depth > 0 ? openers[depth - 1] : -1;
      if (tokenClasses.has(i, Classification.OPENER)) {
        openers[depth++] = i;
      } else if (tokenClasses.has(i, Classification.CLOSER) && depth > 0) {
        int o = openers[--depth];
        matchClose[o] = i;
        matchOpen[i] = o;
      }
    }
  }

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

    // An empty bracket group (closer directly after its opener) collapses onto one line, even
    // when the input split it: `{\n}` becomes `{}`.
    for (int c = 0; c < n; c++) {
      if (matchOpen[c] == c - 1) {
        breakBefore[c] = false;
        forcedBreak[c] = false;
      }
    }

    forceChainBreaks();
    forceConcatBreaks();
    forceLogicalBreaks();

    // Canonical style never breaks before a method declaration's `throws` clause: join it to the
    // signature, or to the isolated `)` closer of a multiline parameter list. Skip when the
    // preceding token is a line comment, which would otherwise swallow the rest of the line.
    for (int i = 1; i < n; i++) {
      if (tokenSym[i] == Sym.THROWS && tokens.get(i - 1).kind() != Kind.LINE_COMMENT) {
        breakBefore[i] = false;
      }
    }
  }

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
      int paren = callParen(p);
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
      int lastClose = matchClose[callParen(last)];
      if (tokenLine[p] != tokenLine[lastClose]) {
        for (int dot : chain) {
          breakBefore[dot] = true;
          forcedBreak[dot] = true;
        }
      }
    }
  }

  private boolean closesAnnotation(int closeIndex) {
    int open = matchOpen[closeIndex];
    if (open < 0) {
      return false;
    }
    int name = indexOfPrevCode(open);
    if (name < 0 || tokens.get(name).kind() != Kind.IDENT) {
      return false;
    }
    for (int j = indexOfPrevCode(name); j >= 0;) {
      if (tokenSym[j] == Sym.AT) {
        return true;
      }
      if (tokenSym[j] != Sym.DOT) {
        return false;
      }
      int qualifier = indexOfPrevCode(j);
      if (qualifier < 0 || tokens.get(qualifier).kind() != Kind.IDENT) {
        return false;
      }
      j = indexOfPrevCode(qualifier);
    }
    return false;
  }

  private boolean isCallDot(int p) {
    if (tokenSym[p] != Sym.DOT) {
      return false;
    }
    int name = callName(p);
    if (name < 0 || tokens.get(name).kind() != Kind.IDENT) {
      return false;
    }
    int paren = indexOfNextCode(name);
    return paren >= 0 && tokenSym[paren] == Sym.LPAREN;
  }

  /// The method-name token of a call `.name(`, skipping an explicit type witness (`.<T> name(`),
  /// or -1 when the witness angle brackets don't close.
  private int callName(int dot) {
    int name = indexOfNextCode(dot);
    if (name >= 0 && tokenSym[name] == Sym.LT) {
      int witnessEnd = scanTypeArguments(name);
      name = witnessEnd < 0 ? -1 : indexOfNextCode(witnessEnd);
    }
    return name;
  }

  /// The `(` opening a chain call's argument list, skipping an explicit type witness.
  private int callParen(int dot) {
    return indexOfNextCode(callName(dot));
  }

  /// Keeps an already-broken string-concatenation `+`, so a piecewise-built literal (e.g. a regex
  /// split across lines) is not collapsed back onto one line. The break then spreads to every `+`
  /// of the chain, so a concatenation wrapped at one operand wraps at every operand — no sibling
  /// `+` (e.g. two calls whose operands are not themselves string literals) is left crammed onto a
  /// line while the rest break.
  private void forceConcatBreaks() {
    // An operator reached by an earlier operator's spread needs no scan of its own: the spread
    // already covered every `+` of the chain.
    boolean[] spread = new boolean[tokens.size()];
    for (int i = 0; i < tokens.size(); i++) {
      if (!breakBefore[i] || spread[i] || !isStringConcatPlus(i)) {
        continue;
      }
      forcedBreak[i] = true;
      for (int j = i - 1; j >= 0; j--) {
        if (tokenClasses.has(j, Classification.CLOSER) && matchOpen[j] >= 0) {
          j = matchOpen[j]; // a nested group is skipped whole
        } else if (endsOperatorElement(j)) {
          break;
        } else if (tokenSym[j] == Sym.PLUS) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
        }
      }
      for (int j = i + 1; j < tokens.size(); j++) {
        if (tokenClasses.has(j, Classification.OPENER) && matchClose[j] >= 0) {
          j = matchClose[j];
        } else if (endsOperatorElement(j)) {
          break;
        } else if (tokenSym[j] == Sym.PLUS) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
          spread[j] = true;
        }
      }
    }
  }

  private boolean isStringConcatPlus(int i) {
    if (tokenSym[i] != Sym.PLUS) {
      return false;
    }
    Token prev = prevCode(i);
    Token next = nextCode(i);
    if (prev == null || next == null || !endsOperand(prev)) {
      return false;
    }
    return isStringLiteral(prev) || isStringLiteral(next);
  }

  /// An already-broken `&&`/`||` spreads its break to every logical operator in the element, so a
  /// condition wrapped at one operand wraps at every `&&`/`||` operand — mixed precedences all
  /// break together rather than leaving one precedence crammed onto an overflowing line.
  private void forceLogicalBreaks() {
    // An operator reached by an earlier operator's spread needs no scan of its own: the spread
    // already covered every logical operator of the element.
    boolean[] spread = new boolean[tokens.size()];
    for (int i = 0; i < tokens.size(); i++) {
      if (!breakBefore[i] || spread[i] || !isLogicalOp(i)) {
        continue;
      }
      forcedBreak[i] = true;
      for (int j = i - 1; j >= 0; j--) {
        if (tokenClasses.has(j, Classification.CLOSER) && matchOpen[j] >= 0) {
          j = matchOpen[j]; // a nested group is skipped whole
        } else if (endsOperatorElement(j)) {
          break;
        } else if (isLogicalOp(j)) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
        }
      }
      for (int j = i + 1; j < tokens.size(); j++) {
        if (tokenClasses.has(j, Classification.OPENER) && matchClose[j] >= 0) {
          j = matchClose[j];
        } else if (endsOperatorElement(j)) {
          break;
        } else if (isLogicalOp(j)) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
          spread[j] = true;
        }
      }
    }
  }

  private boolean isLogicalOp(int i) {
    return tokenSym[i] == Sym.AMP_AMP || tokenSym[i] == Sym.BAR_BAR;
  }

  private boolean endsOperatorElement(int i) {
    return tokenClasses.has(i, Classification.OPENER)
      || tokenClasses.has(i, Classification.CLOSER)
      || switch (tokenSym[i]) {
           case COMMA, SEMI, QUESTION, COLON, ARROW, ASSIGN -> true;
           default -> false;
         };
  }

  private static boolean isStringLiteral(Token t) {
    return t.kind() == Kind.STRING || t.kind() == Kind.TEXT_BLOCK;
  }

  private static boolean isLiteral(Token t) {
    return switch (t.kind()) {
      case NUMBER, STRING, CHAR, TEXT_BLOCK -> true;
      default -> false;
    };
  }

  private static boolean endsOperand(Token t) {
    return t.is(")")
      || t.is("]")
      || isLiteral(t)
      || t.kind() == Kind.IDENT
      && !t.isKeyword();
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

  /// [#lineIndent] is reused when it still fits (the next [#analyze] reassigns every entry) and
  /// grown only when the line count outgrows it.
  private void rebuildLines() {
    lines.clear();
    buildLines();
    if (lineIndent.length < lines.size()) {
      lineIndent = new int[lines.size()];
    }
  }

  public String print() {
    analyze(null);
    // A soft break directly after `=` is never canonical: move it into the right-hand side before
    // wrapping, so the side re-breaks at its own structure.
    if (moveAssignmentBreaks()) {
      rebuildLines();
      analyze(null);
    }
    while (wrapLongLines() | breakGroupElements()) {
      rebuildLines();
      analyze(null);
    }
    // A chain call whose arguments were isolated only because the input crammed the chain onto one
    // line collapses back once the chain is re-broken, reversing forced isolation a join can't.
    if (collapseChainCallArguments()) {
      rebuildLines();
      analyze(null);
    }
    // An isolated control-flow condition paren (`if`/`while`/...) whose whole header fits on one
    // line collapses back, reversing the forced isolation that a soft join cannot cross.
    if (collapseControlFlowHeaders()) {
      rebuildLines();
      analyze(null);
    }
    // A soft-broken continuation that gets joined back (e.g. a single `.call(` unwrapped onto the
    // assignment line) opens its bracket scope on the joined line: re-indent so that scope flows
    // from the join target, not from the now-discarded continuation indent.
    boolean[] joinWithPrev = computeJoins();
    analyze(joinWithPrev);
    return emit(joinWithPrev);
  }

  /// Canonical style starts every top-level element of a multiline paren group on its own line.
  private boolean breakGroupElements() {
    boolean changed = false;
    // A marked type-argument list never contains `(`, so any angle open at a comma opened after
    // the comma's enclosing paren: a running depth replaces a per-comma rescan.
    int generic = 0;
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      if (marks.has(i, Mark.GENERIC_ANGLE)) {
        generic += angleDepthDelta(i);
        continue;
      }
      if (tokenClasses.has(i, Classification.OPENER)) {
        openerStack[depth++] = i;
      } else if (tokenClasses.has(i, Classification.CLOSER) && depth > 0) {
        depth--;
      } else if (tokenSym[i] == Sym.COMMA && generic == 0 && depth > 0) {
        int o = openerStack[depth - 1];
        if (tokenSym[o] == Sym.LPAREN && breakBefore[o + 1]) {
          // The break starts the next element, but a trailing comment on the comma's line stays
          // put: skip past it so the comment is not pushed onto its own line.
          int target = i + 1;
          while (target < tokens.size() && tokens.get(target).isComment() && !breakBefore[target]) {
            target++;
          }
          if (target < tokens.size() && !breakBefore[target]) {
            breakBefore[target] = true;
            forcedBreak[target] = true;
            changed = true;
          }
        }
      }
    }
    return changed;
  }

  /// Wraps each line wider than [#MAX_WIDTH] by isolating its last outermost non-empty bracket
  /// group. One group per pass: the caller re-derives lines and retries, so a still-too-wide
  /// remainder wraps at its next group.
  private boolean wrapLongLines() {
    boolean changed = false;
    for (int li = 0; li < lines.size(); li++) {
      if (lineWidth(li) <= MAX_WIDTH) {
        continue;
      }
      Line line = lines.get(li);
      // A too-long line whose base-depth structure is a ternary breaks at `?`/`:` rather than
      // isolating a bracket group nested in its condition.
      List<Integer> ternary = topLevelTernaryOperators(
        line.firstToken(),
        line.firstToken() + line.tokenCount() - 1
      );
      if (!ternary.isEmpty() && !breakBefore[ternary.get(0)]) {
        for (int b : ternary) {
          breakBefore[b] = true;
          forcedBreak[b] = true;
        }
        changed = true;
        continue;
      }
      // A too-long condition breaks at its logical operators rather than isolating a bracket group
      // nested in one operand; a single seeded break then spreads to every operator via
      // forceLogicalBreaks.
      List<Integer> logical = topLevelLogicalOperators(
        line.firstToken(),
        line.firstToken() + line.tokenCount() - 1
      );
      if (!logical.isEmpty() && !breakBefore[logical.get(0)]) {
        for (int b : logical) {
          breakBefore[b] = true;
          forcedBreak[b] = true;
        }
        changed = true;
        continue;
      }
      int open = -1;
      int close = -1;
      int end = line.firstToken() + line.tokenCount();
      for (int i = line.firstToken(); i < end; i++) {
        if (!tokenClasses.has(i, Classification.OPENER)) {
          continue;
        }
        int c = matchClose[i];
        if (c < 0 || c >= end) {
          break; // the rest of the line is nested inside this group
        }
        // A cast paren wraps to nothing useful: isolating it strands the type on its own line.
        if (c > i + 1 && !marks.has(c, Mark.CAST_CLOSE)) {
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

  /// A soft break directly after `=` is never canonical: the right-hand side breaks at its own
  /// structure (ternary, call chain) instead, or rejoins the assignment when it fits.
  private boolean moveAssignmentBreaks() {
    boolean changed = false;
    for (int i = 1; i < tokens.size(); i++) {
      if (!breakBefore[i] || forcedBreak[i] || tokenSym[i - 1] != Sym.ASSIGN) {
        continue;
      }
      int end = rhsEnd(i);
      if (end < i) {
        // The side already spans lines, but if its first break hangs a binary operator its own
        // structure carries the continuation: drop the break after `=` to seat the first operand
        // on the assignment line.
        if (rhsBreaksAtOperator(i) && joinAllowsWrap(i)) {
          breakBefore[i] = false;
          changed = true;
        }
        continue;
      }
      List<Integer> breaks = topLevelTernaryOperators(i, end);
      if (breaks.isEmpty()) {
        breaks = topLevelChainDots(i, end);
        if (breaks.size() < 2) {
          if (joinAllowsWrap(i)) {
            breakBefore[i] = false;
            changed = true;
          }
          continue;
        }
      }
      for (int b : breaks) {
        breakBefore[b] = true;
        forcedBreak[b] = true;
      }
      changed = true;
    }
    return changed;
  }

  /// Returns -1 when the side already spans lines or holds a multiline token: it cannot be
  /// re-wrapped from scratch.
  private int rhsEnd(int i) {
    int depth = 0;
    int generic = 0;
    for (int j = i; j < tokens.size(); j++) {
      Sym sym = tokenSym[j];
      if (
        depth == 0
          && generic == 0
          && (tokenClasses.has(j, Classification.CLOSER) || sym == Sym.SEMI || sym == Sym.COMMA)
      ) {
        return j - 1;
      }
      if (j > i && breakBefore[j] || tokenWidth[j] < 0) {
        return -1;
      }
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += angleDepthDelta(j);
      } else if (tokenClasses.has(j, Classification.OPENER)) {
        depth++;
      } else if (tokenClasses.has(j, Classification.CLOSER)) {
        depth--;
      }
    }
    return tokens.size() - 1;
  }

  /// The right-hand side starting at `i` keeps its head on one line and takes its first break at a
  /// top-level binary operator, so seating that head on the assignment line leaves a well-formed
  /// operator continuation behind.
  private boolean rhsBreaksAtOperator(int i) {
    int depth = 0;
    int generic = 0;
    for (int j = i; j < tokens.size(); j++) {
      Sym sym = tokenSym[j];
      if (
        depth == 0
          && generic == 0
          && (tokenClasses.has(j, Classification.CLOSER) || sym == Sym.SEMI || sym == Sym.COMMA)
      ) {
        return false;
      }
      if (j > i && breakBefore[j]) {
        return depth == 0 && generic == 0 && leadsBinaryOperator(j);
      }
      if (tokenWidth[j] < 0) {
        return false;
      }
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += angleDepthDelta(j);
      } else if (tokenClasses.has(j, Classification.OPENER)) {
        depth++;
      } else if (tokenClasses.has(j, Classification.CLOSER)) {
        depth--;
      }
    }
    return false;
  }

  /// A break lands on a hung binary operator: the previous code token finishes an operand and the
  /// break token is an infix operator, so the operator leads its continuation line.
  private boolean leadsBinaryOperator(int j) {
    if (marks.has(j, Mark.GENERIC_ANGLE) || marks.has(j, Mark.UNARY)) {
      return false;
    }
    Token prev = prevCode(j);
    if (prev == null || !endsOperand(prev)) {
      return false;
    }
    return switch (tokens.get(j).text()) {
      case "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>", "<", ">", "<=", ">=", "==",
          "!=", "&&", "||", "instanceof" -> true;
      default -> false;
    };
  }

  /// A grouping paren (preceded by an operator, not a call name, argument opener, or control-flow
  /// keyword) whose sole top-level content is a conditional expression. Call and control-flow parens
  /// keep the single content indent.
  private boolean wrapsConditional(int open, int prev) {
    if (prev < 0
      || endsOperand(tokens.get(prev))
      || tokenSym[prev] == Sym.COMMA
      || tokenSym[prev] == Sym.LPAREN
      || tokenClasses.has(prev, Classification.PAREN_KEYWORD)) {
      return false;
    }
    int close = matchClose[open];
    return close > open + 1 && !topLevelTernaryOperators(open + 1, close - 1).isEmpty();
  }

  private List<Integer> topLevelTernaryOperators(int i, int end) {
    List<Integer> ops = new ArrayList<>();
    int depth = 0;
    int generic = 0;
    for (int j = i; j <= end; j++) {
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += angleDepthDelta(j);
      } else if (tokenClasses.has(j, Classification.OPENER)) {
        depth++;
      } else if (tokenClasses.has(j, Classification.CLOSER)) {
        depth--;
      } else if (
        depth == 0
          && generic == 0
          && !marks.has(j, Mark.WILDCARD)
          && (tokenSym[j] == Sym.QUESTION || tokenSym[j] == Sym.COLON && !ops.isEmpty())
      ) {
        ops.add(j);
      }
    }
    return ops;
  }

  private List<Integer> topLevelLogicalOperators(int i, int end) {
    List<Integer> ops = new ArrayList<>();
    int depth = 0;
    int generic = 0;
    for (int j = i; j <= end; j++) {
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += angleDepthDelta(j);
      } else if (tokenClasses.has(j, Classification.OPENER)) {
        depth++;
      } else if (tokenClasses.has(j, Classification.CLOSER)) {
        depth--;
      } else if (depth == 0 && generic == 0 && isLogicalOp(j)) {
        ops.add(j);
      }
    }
    return ops;
  }

  private List<Integer> topLevelChainDots(int i, int end) {
    int dot = -1;
    for (int j = i; j <= end && dot < 0; j++) {
      if (tokenClasses.has(j, Classification.OPENER) && matchClose[j] > j) {
        j = matchClose[j];
      } else if (isCallDot(j)) {
        dot = j;
      }
    }
    List<Integer> dots = new ArrayList<>();
    while (dot >= 0 && dot <= end && isCallDot(dot)) {
      dots.add(dot);
      int close = matchClose[callParen(dot)];
      if (close < 0) {
        break;
      }
      dot = indexOfNextCode(close);
    }
    return dots;
  }

  private boolean joinAllowsWrap(int i) {
    int li = lineIndexOf(i);
    if (hasMultilineToken(li - 1, li)) {
      return false;
    }
    int start = lines.get(li - 1).firstToken();
    Line line = lines.get(li);
    int end = line.firstToken() + line.tokenCount();
    if (lineIndent[li - 1] + runWidth(start, end) <= MAX_WIDTH) {
      return true;
    }
    int open = -1;
    for (int j = start; j < end; j++) {
      if (!tokenClasses.has(j, Classification.OPENER)) {
        continue;
      }
      int c = matchClose[j];
      if (c < 0 || c >= end) {
        break; // the rest of the joined line is nested inside this group
      }
      if (c > j + 1) {
        open = j;
      }
      j = c; // skip the contents: only groups outermost within the joined line count
    }
    if (open < 0) {
      return false;
    }
    return lineIndent[li - 1] + runWidth(start, open + 1) <= MAX_WIDTH;
  }

  /// Canonical chain-breaking already re-lays every call onto its own line, so an argument list
  /// the input happened to wrap rejoins (`.get(\n  "x"\n)` becomes `.get("x")`) rather than
  /// staying isolated. The exclusions below each preserve a group that must keep its own lines.
  private boolean collapseChainCallArguments() {
    boolean changed = false;
    boolean[] multiArg = null;
    for (int dot = 0; dot < tokens.size(); dot++) {
      if (!breakBefore[dot] || !isCallDot(dot)) {
        continue; // only a call that chain-breaking put on its own line
      }
      int open = callParen(dot);
      int close = matchClose[open];
      if (close < open + 2 || !breakBefore[open + 1]) {
        continue; // empty or already-inline argument list
      }
      if (multiArg == null) {
        multiArg = computeMultiArgParens();
      }
      if (
        multiArg[open]
          || nestedInBrokenParen(dot, close)
          || containsBrokenMultiArgCall(open, close, multiArg)
      ) {
        continue; // multi-argument call, a call nested in a broken paren, or one wrapping a broken
        // multi-argument call, keeps its break
      }
      int li = lineIndexOf(dot);
      // Blocked by a multiline token, or a brace group that keeps its own lines.
      boolean blocked = prefixMultiline[close + 1] > prefixMultiline[dot]
        || hasBraceInside(open, close);
      if (blocked || lineIndent[li] + runWidth(dot, close + 1) > MAX_WIDTH) {
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

  private boolean hasBraceInside(int open, int close) {
    for (int i = open + 1; i < close; i++) {
      if (tokenSym[i] == Sym.LBRACE) {
        return true;
      }
    }
    return false;
  }

  /// One pass attributing each comma to its innermost opener, versus a rescan per collapse
  /// candidate.
  private boolean[] computeMultiArgParens() {
    boolean[] multiArg = new boolean[tokens.size()];
    int generic = 0;
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      if (marks.has(i, Mark.GENERIC_ANGLE)) {
        generic += angleDepthDelta(i);
        continue;
      }
      if (tokenClasses.has(i, Classification.OPENER)) {
        openerStack[depth++] = i;
      } else if (tokenClasses.has(i, Classification.CLOSER) && depth > 0) {
        depth--;
      } else if (tokenSym[i] == Sym.COMMA && generic == 0 && depth > 0) {
        multiArg[openerStack[depth - 1]] = true;
      }
    }
    return multiArg;
  }

  /// Such a call belongs to a chain the surrounding broken group already lays out multiline, so
  /// its arguments stay broken.
  private boolean nestedInBrokenParen(int dot, int close) {
    for (int o = enclosingOpen[dot]; o >= 0; o = enclosingOpen[o]) {
      if (tokenSym[o] == Sym.LPAREN && matchClose[o] > close && breakBefore[matchClose[o]]) {
        return true;
      }
    }
    return false;
  }

  /// Collapsing the outer call would flatten a broken multi-argument inner call
  /// (`Map.of(\n  "a",\n  "b"\n)`) too, so the outer break is kept.
  private boolean containsBrokenMultiArgCall(int open, int close, boolean[] multiArg) {
    for (int i = open + 1; i < close; i++) {
      if (tokenSym[i] == Sym.LPAREN && breakBefore[matchClose[i]] && multiArg[i]) {
        return true;
      }
    }
    return false;
  }

  /// Reverses the forced breaks isolating a control-flow condition paren when the whole header
  /// fits on one line — an unwind a soft join cannot do because it never crosses a forced break.
  private boolean collapseControlFlowHeaders() {
    boolean changed = false;
    for (int open = 0; open < tokens.size(); open++) {
      if (tokenSym[open] != Sym.LPAREN || !breakBefore[open + 1]) {
        continue; // not an isolated paren
      }
      int close = matchClose[open];
      int keyword = indexOfPrevCode(open);
      if (close < 0 || keyword < 0 || !tokenClasses.has(keyword, Classification.PAREN_KEYWORD)) {
        continue;
      }
      int brace = indexOfNextCode(close);
      if (brace < 0 || tokenSym[brace] != Sym.LBRACE) {
        continue; // not a block header (excludes `return (..)`, `throw (..)`, etc.)
      }
      // A try-with-resources listing multiple resources (a top-level `;`) keeps each resource on
      // its own line; only a single-resource header collapses.
      if (tokenSym[keyword] == Sym.TRY && hasTopLevelSemicolon(open, close)) {
        continue;
      }
      int li = lineIndexOf(open);
      int start = lines.get(li).firstToken();
      boolean multiline = prefixMultiline[brace + 1] > prefixMultiline[start];
      if (multiline || lineIndent[li] + runWidth(start, brace + 1) > MAX_WIDTH) {
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

  private boolean hasTopLevelSemicolon(int open, int close) {
    int depth = 0;
    for (int i = open + 1; i < close; i++) {
      if (tokenClasses.has(i, Classification.OPENER)) {
        depth++;
      } else if (tokenClasses.has(i, Classification.CLOSER)) {
        depth--;
      } else if (depth == 0 && tokenSym[i] == Sym.SEMI) {
        return true;
      }
    }
    return false;
  }

  private int angleDepthDelta(int i) {
    return switch (tokenSym[i]) {
      case LT -> 1;
      case GT -> -1;
      case GT_GT -> -2;
      case GT_GT_GT -> -3;
      default -> 0;
    };
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

  /// A multiline token counts as zero width, so callers must rule those out (via
  /// [#hasMultilineToken] or [#prefixMultiline]) before trusting the result.
  private int runWidth(int first, int endExclusive) {
    return prefixWidth[endExclusive] - prefixWidth[first] - (spaceBefore[first] ? 1 : 0);
  }

  /// 0 when the line holds a multiline token, which cannot be usefully measured or wrapped.
  private int lineWidth(int li) {
    Line line = lines.get(li);
    int end = line.firstToken() + line.tokenCount();
    if (prefixMultiline[end] > prefixMultiline[line.firstToken()]) {
      return 0;
    }
    return lineIndent[li] + runWidth(line.firstToken(), end);
  }

  // ---------------------------------------------------------------------------------------------
  // Analysis: scope tracking, line indents, token roles.
  // ---------------------------------------------------------------------------------------------

  /// A line `joinWithPrev` flags reuses the join run's head indent, so a bracket scope opened on
  /// it flows from the joined line rather than a discarded continuation indent.
  private void analyze(boolean @Nullable[] joinWithPrev) {
    scopesUsed = 0;
    Deque<Scope> stack = analyzeStack;
    stack.clear();
    stack.push(newScope(Scope.Kind.BLOCK, 0, 0));
    pendingComments.clear();
    int prevIndent = 0;
    int headIndent = 0;

    for (int li = 0; li < lines.size(); li++) {
      Line line = lines.get(li);
      if (isCommentOnly(line)) {
        pendingComments.add(li);
        continue;
      }
      Scope top = stack.peek();
      int firstToken = line.firstToken();
      Sym firstSym = tokenSym[firstToken];
      int indent;
      boolean continuation = false;
      if (joinWithPrev != null && joinWithPrev[li]) {
        indent = headIndent;
      } else if (tokenClasses.has(firstToken, Classification.CLOSER)) {
        indent = scopeFor(stack, firstSym).closeIndent;
      } else if (top.elementOpen) {
        indent = continuationIndent(top, firstToken, prevIndent);
        continuation = true;
      } else {
        indent = top.contentIndent;
        if (top.kind == Scope.Kind.SWITCH_BODY && firstSym != Sym.CASE && firstSym != Sym.DEFAULT) {
          indent += INDENT;
        }
      }
      lineIndent[li] = indent;
      prevIndent = indent;
      if (joinWithPrev == null || !joinWithPrev[li]) {
        headIndent = indent;
      }
      int bodyIndent = tokenClasses.has(firstToken, Classification.CLOSER)
        ? scopeFor(stack, firstSym).contentIndent
        : indent;
      // A comment preceding a colon-style case label belongs to the previous case's fallthrough
      // body, so indent it one level deeper than the label. Arrow-style cases don't fall through,
      // so a comment there introduces the next case and aligns with the label.
      if (top.kind == Scope.Kind.SWITCH_BODY
        && (firstSym == Sym.CASE || firstSym == Sym.DEFAULT)
        && isColonCaseLine(line)) {
        bodyIndent += INDENT;
      }
      for (int ci : pendingComments) {
        lineIndent[ci] = tokens.get(lines.get(ci).firstToken()).atColumn0() ? 0 : bodyIndent;
      }
      pendingComments.clear();
      walkLine(stack, line, indent, continuation);
    }
    for (int ci : pendingComments) {
      lineIndent[ci] = tokens
        .get(lines.get(ci).firstToken())
        .atColumn0() ? 0 : stack.peek().contentIndent;
    }
    if (consumeMarksChanged()) {
      for (int i = 1; i < tokens.size(); i++) {
        spaceBefore[i] = spaceBetween(i - 1, i);
      }
      for (int i = 0; i < tokens.size(); i++) {
        prefixWidth[i + 1] = prefixWidth[i] + (spaceBefore[i] ? 1 : 0) + Math.max(tokenWidth[i], 0);
      }
    }
  }

  /// A ternary's `:` aligns with its matching `?` rather than taking the usual continuation indent.
  private int continuationIndent(Scope top, int firstToken, int prevIndent) {
    Sym sym = tokenSym[firstToken];
    if (sym == Sym.QUESTION && !marks.has(firstToken, Mark.WILDCARD)) {
      return prevIndent + INDENT;
    }
    if (sym == Sym.COLON && top.ternaryIndents != null && !top.ternaryIndents.isEmpty()) {
      return top.ternaryIndents.peek();
    }
    // A broken `->` already pushed its body one level past the element start; logical operators in
    // that body hang one level deeper still, under the operand rather than aligning with it.
    if (top.arrowBodyBroken && isLogicalOp(firstToken)) {
      return top.elementStartIndent + 2 * INDENT;
    }
    return top.elementStartIndent + INDENT;
  }

  /// A case label line is colon-style unless it carries an arrow (`case X ->`); arrow cases don't
  /// fall through into the next label.
  private boolean isColonCaseLine(Line line) {
    for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
      if (tokenSym[i] == Sym.ARROW) {
        return false;
      }
    }
    return true;
  }

  private boolean isCommentOnly(Line line) {
    for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
      if (!tokens.get(i).isComment()) {
        return false;
      }
    }
    return true;
  }

  /// Falls back to the innermost scope of the matching kind on unbalanced input.
  private static Scope scopeFor(Deque<Scope> stack, Sym closer) {
    char kind = closer == Sym.RBRACE ? '{' : closer == Sym.RPAREN ? '(' : '[';
    for (Scope s : stack) {
      boolean matches = switch (kind) {
        case '{' -> s.kind == Scope.Kind.BLOCK
          || s.kind == Scope.Kind.SWITCH_BODY
          || s.kind == Scope.Kind.ENUM_BODY
          || s.kind == Scope.Kind.ARRAY_INIT;
        case '(' -> s.kind == Scope.Kind.PAREN;
        default -> s.kind == Scope.Kind.BRACKET;
      };
      if (matches) {
        return s;
      }
    }
    return stack.peekLast();
  }

  private void walkLine(Deque<Scope> stack, Line line, int indent, boolean continuation) {
    for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
      Token t = tokens.get(i);
      if (t.isComment()) {
        continue;
      }
      Scope top = stack.peek();
      if (!top.elementOpen && !tokenClasses.has(i, Classification.CLOSER)) {
        top.elementOpen = true;
        top.elementStartIndent = indent;
        top.caseLabel = top.kind == Scope.Kind.SWITCH_BODY
          && (tokenSym[i] == Sym.CASE || tokenSym[i] == Sym.DEFAULT);
      }
      // A `switch` on a wrapped continuation line sits past its line's indent; measure its column
      // so its body indents from the keyword rather than from the continuation indent.
      int column = continuation
        ? indent + runWidth(line.firstToken(), i) + (spaceBefore[i] ? 1 : 0)
        : indent;
      analyzeToken(stack, i, indent, column);
    }
    endOfLine(stack, line);
  }

  private void analyzeToken(Deque<Scope> stack, int i, int indent, int column) {
    Token t = tokens.get(i);
    Scope top = stack.peek();
    Sym sym = tokenSym[i];
    updateAnnotationState(top, t, sym);

    switch (sym) {
      case LPAREN -> {
        int prev = indexOfPrevCode(i);
        // A grouping paren wrapping a conditional gives its body a second indent level, so the
        // `?`/`:` branches sit below the opener rather than aligning with a call's arguments.
        int contentIndent = indent + INDENT;
        if (wrapsConditional(i, prev)) {
          contentIndent += INDENT;
        }
        Scope scope = newScope(Scope.Kind.PAREN, contentIndent, indent);
        scope.forParen = prev >= 0 && (tokenSym[prev] == Sym.FOR || tokenSym[prev] == Sym.TRY);
        stack.push(scope);
      }
      case LBRACKET -> stack.push(newScope(Scope.Kind.BRACKET, indent + INDENT, indent));
      case LBRACE -> stack.push(openBrace(stack, i, indent));
      case RPAREN, RBRACKET -> {
        Scope closed = stack.peek();
        if (stack.size() > 1) {
          stack.pop();
        }
        if (sym == Sym.RPAREN && isCast(closed, i)) {
          mark(i, Mark.CAST_CLOSE);
        }
        afterContentToken(stack.peek(), sym == Sym.RBRACKET); // `]` can end an array type in a cast
      }
      case RBRACE -> {
        if (stack.size() > 1) {
          stack.pop();
        }
      }
      case SEMI -> {
        if (top.kind == Scope.Kind.PAREN || top.kind == Scope.Kind.BRACKET) {
          resetElement(top); // for-loop sections and try-with-resources
        } else {
          closeElement(top);
        }
      }
      case COMMA -> {
        if (top.generic == 0 && top.kind.hasElements()) {
          resetElement(top);
        }
      }
      case LT -> {
        if (!marks.has(i, Mark.GENERIC_ANGLE) && !marks.has(i, Mark.ANGLE_SCANNED)) {
          mark(i, Mark.ANGLE_SCANNED);
          int end = typeArgumentsEnd(i);
          if (end >= 0) {
            markTypeArguments(i, end);
          }
        }
        if (marks.has(i, Mark.GENERIC_ANGLE)) {
          top.generic++;
        } else {
          afterContentToken(top, false);
        }
      }
      case GT, GT_GT, GT_GT_GT -> {
        if (marks.has(i, Mark.GENERIC_ANGLE)) {
          top.generic = Math.max(0, top.generic + angleDepthDelta(i));
        } else {
          afterContentToken(top, false);
        }
      }
      case QUESTION -> {
        if (top.generic > 0) {
          mark(i, Mark.WILDCARD);
        } else {
          if (top.ternaryIndents == null) {
            top.ternaryIndents = new ArrayDeque<>();
          }
          top.ternaryIndents.push(breakBefore[i] ? indent : top.elementStartIndent + INDENT);
          afterContentToken(top, false);
        }
      }
      case COLON -> analyzeColon(stack, i, top);
      case ARROW -> {
        int next = indexOfNextCode(i);
        top.arrowBodyBroken = next >= 0 && breakBefore[next];
        afterContentToken(top, false);
      }
      case PLUS, MINUS, INCREMENT, DECREMENT, BANG, TILDE -> {
        if (isUnaryPosition(i)) {
          mark(i, Mark.UNARY);
        }
        afterContentToken(top, false);
      }
      default -> {
        if (sym == Sym.SWITCH) {
          top.sawSwitch = true;
          top.sawSwitchColumn = column;
        } else if (sym == Sym.ENUM) {
          top.sawEnum = true;
        } else if (sym == Sym.ASSERT) {
          top.sawAssert = true;
        }
        if (tokenClasses.has(i, Classification.PRIMITIVE)) {
          top.sawPrimitive = true;
        }
        boolean word = t.kind() != Kind.PUNCT;
        boolean typeToken = t.kind() == Kind.IDENT
          && (
            !tokenClasses.has(i, Classification.KEYWORD)
              || tokenClasses.has(i, Classification.PRIMITIVE)
              || sym == Sym.EXTENDS
              || sym == Sym.SUPER
          )
          || sym == Sym.DOT
          || sym == Sym.AT
          || sym == Sym.AMP
          && top.generic > 0;
        if (word && top.lastWasWord && top.generic == 0) {
          top.typeLike = false; // two adjacent words at top level cannot be a cast type
        }
        afterContentToken(top, typeToken);
        top.lastWasWord = word;
      }
    }
  }

  private void updateAnnotationState(Scope top, Token t, Sym sym) {
    switch (top.annotationState) {
      case 0 -> top.annotationState = sym == Sym.AT ? 1 : -1;
      case 1 -> top.annotationState = t.kind() == Kind.IDENT ? 2 : -1;
      case 2 -> top.annotationState = sym == Sym.AT || sym == Sym.DOT ? 1
        : sym == Sym.LPAREN ? 2 : -1;
      default -> {}
    }
  }

  /// `typeToken` keeps this scope's cast-type detection alive.
  private void afterContentToken(Scope scope, boolean typeToken) {
    scope.hasContent = true;
    if (!typeToken) {
      scope.typeLike = false;
    }
  }

  private Scope openBrace(Deque<Scope> stack, int i, int indent) {
    Scope top = stack.peek();
    int prev = indexOfPrevCode(i);
    Sym prevSym = prev < 0 ? Sym.OTHER : tokenSym[prev];
    boolean arrayInit = switch (prevSym) {
      case ASSIGN, COMMA, LBRACE, LPAREN, RBRACKET -> true;
      default -> false;
    };
    if (arrayInit) {
      return newScope(Scope.Kind.ARRAY_INIT, indent + INDENT, indent);
    }
    if (top.sawSwitch && prevSym == Sym.RPAREN) {
      int base = top.sawSwitchColumn;
      top.sawSwitch = false;
      return newScope(Scope.Kind.SWITCH_BODY, base + INDENT, base);
    }
    if (top.sawEnum) {
      top.sawEnum = false;
      return newScope(Scope.Kind.ENUM_BODY, indent + INDENT, indent);
    }
    return newScope(Scope.Kind.BLOCK, indent + INDENT, indent);
  }

  private Scope newScope(Scope.Kind kind, int contentIndent, int closeIndent) {
    if (scopesUsed == scopePool.size()) {
      scopePool.add(new Scope());
    }
    return scopePool.get(scopesUsed++).init(kind, contentIndent, closeIndent);
  }

  private void analyzeColon(Deque<Scope> stack, int i, Scope top) {
    if (top.ternaryIndents != null && !top.ternaryIndents.isEmpty()) {
      top.ternaryIndents.pop();
      afterContentToken(top, false);
      return;
    }
    if (top.kind == Scope.Kind.PAREN && top.forParen) {
      afterContentToken(top, false); // enhanced-for colon, spaced both sides
      return;
    }
    if (top.kind == Scope.Kind.SWITCH_BODY && top.caseLabel) {
      mark(i, Mark.COLON_NO_SPACE_BEFORE);
      closeElement(top);
      return;
    }
    if (top.sawAssert) {
      afterContentToken(top, false);
      return;
    }
    mark(i, Mark.COLON_NO_SPACE_BEFORE); // labeled statement
    closeElement(top);
  }

  private void closeElement(Scope scope) {
    scope.elementOpen = false;
    resetElement(scope);
  }

  private void resetElement(Scope scope) {
    scope.caseLabel = false;
    scope.arrowBodyBroken = false;
    scope.sawSwitch = false;
    scope.sawEnum = false;
    scope.sawAssert = false;
    scope.generic = 0;
    if (scope.ternaryIndents != null) {
      scope.ternaryIndents.clear();
    }
    scope.typeLike = true;
    scope.sawPrimitive = false;
    scope.hasContent = false;
    scope.lastWasWord = false;
    scope.annotationState = 0;
    if (scope.kind.hasElements()) {
      scope.elementOpen = false;
    }
  }

  private void endOfLine(Deque<Scope> stack, Line line) {
    int last = -1;
    for (int i = line.firstToken() + line.tokenCount() - 1; i >= line.firstToken(); i--) {
      if (!tokens.get(i).isComment()) {
        last = i;
        break;
      }
    }
    if (last < 0) {
      return;
    }
    Scope top = stack.peek();
    if (tokenSym[last] == Sym.RBRACE) {
      closeElement(top);
    } else if (top.elementOpen && top.annotationState == 2) {
      closeElement(top); // annotation-only line: next line starts fresh at the same indent
    }
  }

  private boolean isCast(Scope paren, int closeIndex) {
    if (!paren.typeLike || !paren.hasContent) {
      return false;
    }
    int beforeOpen = indexOfPrevCode(matchOpen[closeIndex]);
    // A cast's `(` cannot follow a name or a closing bracket (that would be a call or index).
    if (beforeOpen >= 0) {
      Token before = tokens.get(beforeOpen);
      if (
        before.kind() == Kind.IDENT
          && !tokenClasses.has(beforeOpen, Classification.KEYWORD)
          || tokenSym[beforeOpen] == Sym.RPAREN
          || tokenSym[beforeOpen] == Sym.RBRACKET
      ) {
        return false;
      }
    }
    int nextIndex = indexOfNextCode(closeIndex);
    if (nextIndex < 0) {
      return false;
    }
    Token next = tokens.get(nextIndex);
    return switch (tokenSym[nextIndex]) {
      case PLUS, MINUS, INCREMENT, DECREMENT -> paren.sawPrimitive;
      case LPAREN, BANG, TILDE, THIS, SUPER, NEW -> true;
      default -> next.kind() != Kind.PUNCT
        && !tokenClasses.has(nextIndex, Classification.KEYWORD)
        || isLiteral(next);
    };
  }

  private boolean isUnaryPosition(int i) {
    int prevIndex = indexOfPrevCode(i);
    if (prevIndex < 0) {
      return true;
    }
    Token prev = tokens.get(prevIndex);
    if (isLiteral(prev)) {
      return false;
    }
    Sym prevSym = tokenSym[prevIndex];
    if (prev.kind() == Kind.IDENT) {
      return tokenClasses.has(prevIndex, Classification.KEYWORD) && switch (prevSym) {
        case THIS, SUPER, TRUE, FALSE, NULL -> false;
        default -> true;
      };
    }
    if (prevSym == Sym.RPAREN) {
      return marks.has(prevIndex, Mark.CAST_CLOSE);
    }
    return switch (prevSym) {
      case RBRACKET, INCREMENT, DECREMENT -> false;
      default -> true;
    };
  }

  // --- generic type-argument disambiguation ---

  private int typeArgumentsEnd(int open) {
    int prevIndex = indexOfPrevCode(open);
    if (prevIndex < 0) {
      return -1;
    }
    boolean plausiblePrev = switch (tokenSym[prevIndex]) {
      case DOT, COMMA, LPAREN, LT, LBRACE, AMP, BAR, ASSIGN, RETURN, NEW, EXTENDS, SUPER,
        IMPLEMENTS, INSTANCEOF, CASE, YIELD, ARROW, METHOD_REF, QUESTION, COLON,
        // Type-parameter declarations: `public <T> T foo(..)`, `interface Foo<T>`, `<T> T foo(..)`.
        PUBLIC, PRIVATE, PROTECTED, STATIC, FINAL, DEFAULT, ABSTRACT, CLASS, INTERFACE, RECORD,
        SEMI, RBRACE -> true;
      default -> {
        Token prev = tokens.get(prevIndex);
        yield prev.kind() == Kind.IDENT && !prev.isKeyword();
      }
    };
    if (!plausiblePrev) {
      return -1;
    }
    int end = scanTypeArguments(open);
    if (end < 0) {
      return -1;
    }
    int followerIndex = indexOfNextCode(end);
    if (followerIndex < 0) {
      return end;
    }
    boolean plausibleFollower = tokens.get(followerIndex).kind() == Kind.IDENT
      || switch (tokenSym[followerIndex]) {
           case LPAREN, RPAREN, COMMA, DOT, METHOD_REF, SEMI, LBRACKET, LBRACE, GT, GT_GT, GT_GT_GT,
             ELLIPSIS, AMP, ARROW, ASSIGN, AT -> true;
           default -> false;
         };
    return plausibleFollower ? end : -1;
  }

  private int scanTypeArguments(int open) {
    int depth = 1;
    for (int i = open + 1; i < tokens.size() && i - open < TYPE_ARG_SCAN_LIMIT; i++) {
      Token t = tokens.get(i);
      if (t.isComment()) {
        continue;
      }
      switch (tokenSym[i]) {
        case LT -> depth++;
        case GT, GT_GT, GT_GT_GT -> {
          depth += angleDepthDelta(i);
          if (depth <= 0) {
            return depth == 0 ? i : -1;
          }
        }
        case DOT, COMMA, QUESTION, AT, AMP, LBRACKET, RBRACKET, EXTENDS, SUPER -> {}
        default -> {
          if (
            t.kind() != Kind.IDENT
              || tokenClasses.has(i, Classification.KEYWORD)
              && !tokenClasses.has(i, Classification.PRIMITIVE)
          ) {
            return -1;
          }
        }
      }
    }
    return -1;
  }

  private void markTypeArguments(int open, int end) {
    mark(open, Mark.GENERIC_ANGLE);
    for (int i = open + 1; i <= end; i++) {
      switch (tokenSym[i]) {
        case LT, GT, GT_GT, GT_GT_GT -> mark(i, Mark.GENERIC_ANGLE);
        case QUESTION -> mark(i, Mark.WILDCARD);
        default -> {}
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

  /// Soft breaks only wrap a line to fit; once it fits again the run rejoins. A run joins only if
  /// it is a whole wrapped statement or a prefix cut off by a forced break (so `var b =` rejoins
  /// its chain's receiver while the chain stays broken).
  private boolean[] computeJoins() {
    boolean[] joinWithPrev = new boolean[lines.size()];
    for (int li = 0; li < lines.size();) {
      int end = li;
      while (end + 1 < lines.size() && joinable(end + 1)) {
        end++;
      }
      boolean endForced = end + 1 < lines.size() && forcedBreak[lines.get(end + 1).firstToken()];
      boolean joinsStatement = tokenSym[lastTokenIndex(end)] == Sym.SEMI || endForced;
      if (end > li && joinsStatement) {
        int joinEnd = joinEnd(li, end, endForced);
        for (int j = li + 1; j <= joinEnd; j++) {
          joinWithPrev[j] = true;
        }
      }
      li = end + 1;
    }
    return joinWithPrev;
  }

  /// An overflowing run still keeps a method-chain receiver on the head line: breaking after `=`
  /// cannot wrap the receiver, so it joins only up to the line before the first chain `.call(`.
  private int joinEnd(int startLine, int end, boolean endForced) {
    if (fitsJoined(startLine, end)) {
      return end;
    }
    for (int m = startLine + 1; m <= end; m++) {
      if (isCallDot(lines.get(m).firstToken())) {
        return hasMultilineToken(startLine, m - 1) ? startLine : m - 1;
      }
    }
    boolean endChainDot = endForced && tokenSym[lines.get(end + 1).firstToken()] == Sym.DOT;
    return endChainDot && !hasMultilineToken(startLine, end) ? end : startLine;
  }

  private boolean joinable(int li) {
    Line line = lines.get(li);
    if (line.blanksBefore() > 0 || forcedBreak[line.firstToken()]) {
      return false;
    }
    // A standalone comment line stays on its own line rather than trailing the previous one.
    if (isCommentOnly(line)) {
      return false;
    }
    Sym first = tokenSym[line.firstToken()];
    if (first == Sym.RBRACE || first == Sym.AT) {
      return false;
    }
    // A wrapped ternary keeps its `?`/`:` branch lines.
    if (first == Sym.QUESTION || first == Sym.COLON) {
      return false;
    }
    Line prev = lines.get(li - 1);
    if (tokenSym[prev.firstToken()] == Sym.AT) {
      return false;
    }
    int prevLastIndex = prev.firstToken() + prev.tokenCount() - 1;
    Sym prevLast = tokenSym[prevLastIndex];
    if (
      tokens.get(prevLastIndex).isComment()
        || prevLast == Sym.SEMI
        || prevLast == Sym.LBRACE
        || prevLast == Sym.RBRACE
        || prevLast == Sym.COMMA
    ) {
      return false;
    }
    // `.call()` on an invocation result (`foo(..)` or `arr[..]`) is a chain wrap point and stays
    // broken; only a `.call()` on a plain name (`FooConfig`) joins.
    if (first == Sym.DOT && (prevLast == Sym.RPAREN || prevLast == Sym.RBRACKET)) {
      return false;
    }
    // A multiline annotation stays broken from the declaration it annotates: its isolated `)`
    // closer never pulls the following modifier/type onto its line.
    if (prevLast == Sym.RPAREN && closesAnnotation(prevLastIndex)) {
      return false;
    }
    // A case-label or labeled-statement colon keeps its statement on the next line.
    return !(prevLast == Sym.COLON && marks.has(prevLastIndex, Mark.COLON_NO_SPACE_BEFORE));
  }

  private int lastTokenIndex(int li) {
    Line line = lines.get(li);
    return line.firstToken() + line.tokenCount() - 1;
  }

  /// A multiline token (text block, block comment) must never be joined onto a prior line.
  private boolean hasMultilineToken(int startLine, int endLine) {
    int first = lines.get(startLine).firstToken();
    Line last = lines.get(endLine);
    return prefixMultiline[last.firstToken() + last.tokenCount()] > prefixMultiline[first];
  }

  private boolean fitsJoined(int startLine, int endLine) {
    if (hasMultilineToken(startLine, endLine)) {
      return false; // text block or multiline comment
    }
    int first = lines.get(startLine).firstToken();
    Line last = lines.get(endLine);
    int width = lineIndent[startLine] + runWidth(first, last.firstToken() + last.tokenCount());
    return width <= MAX_WIDTH;
  }

  private String emit(boolean[] joinWithPrev) {
    // The last token's end offset approximates the source length, and the output stays close.
    int capacity = tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).end() + 1;
    StringBuilder out = new StringBuilder(capacity);
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
    Sym prevLast = tokenSym[prev.firstToken() + prev.tokenCount() - 1];
    if (prevLast == Sym.LBRACE || prevLast == Sym.LPAREN) {
      return false;
    }
    Sym first = tokenSym[lines.get(li).firstToken()];
    return first != Sym.RBRACE && first != Sym.RPAREN;
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
      for (int from = nl + 1; from >= 0;) {
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
    Sym prevSym = tokenSym[prevIndex];
    Sym nextSym = tokenSym[nextIndex];

    if (prev.isComment() || next.isComment()) {
      return true;
    }
    if (nextSym == Sym.SEMI || nextSym == Sym.COMMA) {
      return false;
    }
    if (prevSym == Sym.LPAREN || prevSym == Sym.LBRACKET) {
      return false;
    }
    if (nextSym == Sym.RPAREN || nextSym == Sym.RBRACKET) {
      return false;
    }
    if (
      prevSym == Sym.DOT
        || nextSym == Sym.DOT
        || prevSym == Sym.METHOD_REF
        || nextSym == Sym.METHOD_REF
    ) {
      return false;
    }
    if (prevSym == Sym.AT) {
      return false;
    }
    if (nextSym == Sym.ELLIPSIS) {
      return false;
    }
    if (prevSym == Sym.ELLIPSIS) {
      return true;
    }
    if (prevSym == Sym.ARROW || nextSym == Sym.ARROW) {
      return true;
    }
    if (marks.has(nextIndex, Mark.UNARY)) {
      return spaceBeforePrefix(prevIndex);
    }
    if (marks.has(prevIndex, Mark.UNARY)) {
      return false;
    }
    if (marks.has(prevIndex, Mark.CAST_CLOSE)) {
      return true;
    }
    // Generic angle brackets bind tightly; a space follows only a list-closing `>` before a word.
    if (marks.has(nextIndex, Mark.GENERIC_ANGLE)) {
      // Type-parameter declarations keep a space after the modifier: `public <T> T get(..)`.
      return nextSym == Sym.LT && tokenClasses.has(prevIndex, Classification.MODIFIER);
    }
    if (marks.has(prevIndex, Mark.GENERIC_ANGLE)) {
      return prevSym != Sym.LT
        && (next.kind() != Kind.PUNCT || nextSym == Sym.LBRACE || nextSym == Sym.AT);
    }
    if (marks.has(prevIndex, Mark.WILDCARD)) {
      return next.kind() != Kind.PUNCT;
    }
    if (marks.has(nextIndex, Mark.WILDCARD)) {
      return true; // after `<` or `,`; `<` was handled above
    }
    if (nextSym == Sym.COLON) {
      return !marks.has(nextIndex, Mark.COLON_NO_SPACE_BEFORE);
    }
    if (prevSym == Sym.COLON) {
      return true;
    }
    if (prevSym == Sym.LBRACE && nextSym == Sym.RBRACE) {
      return false;
    }
    if (nextSym == Sym.LBRACE || prevSym == Sym.LBRACE || nextSym == Sym.RBRACE) {
      return true;
    }
    if (prevSym == Sym.RBRACE) {
      return nextSym != Sym.LPAREN && nextSym != Sym.LBRACKET;
    }
    if (nextSym == Sym.LPAREN) {
      return tokenClasses.has(prevIndex, Classification.PAREN_KEYWORD)
        || prevSym == Sym.DO
        || prevSym == Sym.ELSE
        || tokenClasses.has(prevIndex, Classification.BINARY_OPERATOR)
        && !marks.has(prevIndex, Mark.GENERIC_ANGLE)
        || prevSym == Sym.SEMI
        || prevSym == Sym.COMMA;
    }
    if (nextSym == Sym.LBRACKET) {
      return false;
    }
    if (prevSym == Sym.SEMI || prevSym == Sym.COMMA) {
      return true;
    }
    if (
      tokenClasses.has(prevIndex, Classification.BINARY_OPERATOR)
        || tokenClasses.has(nextIndex, Classification.BINARY_OPERATOR)
    ) {
      return true;
    }
    boolean prevWord = prev.kind() != Kind.PUNCT;
    boolean nextWord = next.kind() != Kind.PUNCT;
    if (prevWord && nextWord) {
      return true;
    }
    if (nextSym == Sym.AT) {
      // A word, or a preceding annotation's `@Foo(..)` / `@Foo[..]`, is followed by a space.
      return prevWord || prevSym == Sym.RPAREN || prevSym == Sym.RBRACKET;
    }
    if (prevSym == Sym.RPAREN || prevSym == Sym.RBRACKET) {
      return nextWord;
    }
    if (prevWord && (nextSym == Sym.BANG || nextSym == Sym.TILDE)) {
      return true; // e.g. `return !x` when the operator was not marked (defensive)
    }
    return false;
  }

  private boolean spaceBeforePrefix(int prevIndex) {
    Sym prevSym = tokenSym[prevIndex];
    boolean tightBefore = switch (prevSym) {
      case LPAREN, LBRACKET, BANG, TILDE, AT, DOT, METHOD_REF -> true;
      default -> false;
    };
    if (tightBefore) {
      return false;
    }
    if (marks.has(prevIndex, Mark.UNARY)) {
      // Keep `- -x` apart so it cannot re-lex as `--`.
      return prevSym == Sym.PLUS || prevSym == Sym.MINUS;
    }
    return true;
  }
}
