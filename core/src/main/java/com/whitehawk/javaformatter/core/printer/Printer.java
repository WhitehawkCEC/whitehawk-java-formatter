package com.whitehawk.javaformatter.core.printer;

import com.whitehawk.javaformatter.core.ArraySmallEnumSet;
import com.whitehawk.javaformatter.core.Kind;
import com.whitehawk.javaformatter.core.Sym;
import com.whitehawk.javaformatter.core.Token;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/// Renders a token stream back to source under a canonical style. Line breaks largely follow
/// what the input already did — a bracket group the input split stays split, one it kept together
/// may be re-joined — so output is stabilized rather than reflowed from scratch, keeping diffs
/// small and idempotent.
@NullMarked
public final class Printer {
  private static final int INDENT = 2;
  private static final int MAX_WIDTH = 100;

  private record Line(int firstToken, int tokenCount, int blanksBefore) {}

  /// The normalized token stream and the per-token facts derived from it (syms, classes, bracket
  /// matches, code-neighbour indices, call chains), invariant after construction; the token list is
  /// reached as `ctx.tokens` and the metadata as `ctx.field[i]`.
  private final TokenContext ctx;
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
  /// Recomputed only by an [#analyze] pass that added a mark bit; later passes reuse it.
  private final boolean[] spaceBefore;
  /// Allocated once because the pass re-runs every wrap iteration.
  private final int[] openerStack;
  /// Prefix sums giving any range's width in O(1); a multiline token counts as zero width.
  /// Rebuilt whenever [#spaceBefore] is.
  private final int[] prefixWidth;
  /// Recycled across [#analyze] passes instead of reallocated per bracket per pass.
  private final List<Scope> scopePool = new ArrayList<>();
  private int scopesUsed;
  /// Reused across [#analyze] passes instead of reallocated each pass.
  private final Deque<Scope> analyzeStack = new ArrayDeque<>();
  private final List<Integer> pendingComments = new ArrayList<>();

  public Printer(TokenContext ctx) {
    this.ctx = ctx;
    int n = ctx.tokens.size();
    this.marks = new ArraySmallEnumSet<>(Mark.class, n);
    this.breakBefore = new boolean[n];
    this.forcedBreak = new boolean[n];
    this.tokenLine = new int[n];
    this.spaceBefore = new boolean[n];
    this.openerStack = new int[n];
    this.prefixWidth = new int[n + 1];
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

  private void computeBreaks() {
    int n = ctx.tokens.size();
    int line = 0;
    for (int i = 0; i < n; i++) {
      if (i > 0 && ctx.tokens.get(i).newlinesBefore() > 0) {
        line++;
        breakBefore[i] = true;
      }
      tokenLine[i] = line;
    }

    // A bracket group spanning more than one line isolates its opener and closer.
    for (int o = 0; o < n; o++) {
      int c = ctx.matchClose[o];
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
      if (ctx.matchOpen[c] == c - 1) {
        breakBefore[c] = false;
        forcedBreak[c] = false;
      }
    }

    forceChainBreaks();
    forceConcatBreaks();
    forceLogicalBreaks();
    forceEnumConstantBreaks();

    // Canonical style never breaks before a method declaration's `throws` clause: join it to the
    // signature, or to the isolated `)` closer of a multiline parameter list. Skip when the
    // preceding token is a line comment, which would otherwise swallow the rest of the line.
    for (int i = 1; i < n; i++) {
      if (ctx.tokens.get(i).sym() == Sym.THROWS && ctx.tokens.get(i - 1).kind() != Kind.LINE_COMMENT) {
        breakBefore[i] = false;
      }
    }
  }

  private void forceChainBreaks() {
    int n = ctx.tokens.size();
    int[] nextCall = new int[n];
    boolean[] linked = new boolean[n];
    Arrays.fill(nextCall, -1);
    for (int p = 0; p < n; p++) {
      if (!ctx.callDot[p]) {
        continue;
      }
      int next = ctx.indexOfNextCode(ctx.matchClose[ctx.callParen[p]]);
      if (next >= 0 && ctx.callDot[next]) {
        nextCall[p] = next;
        linked[next] = true;
      }
    }
    for (int p = 0; p < n; p++) {
      if (!ctx.callDot[p] || linked[p]) {
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
      int lastClose = ctx.matchClose[ctx.callParen[last]];
      if (tokenLine[p] != tokenLine[lastClose]) {
        for (int dot : chain) {
          breakBefore[dot] = true;
          forcedBreak[dot] = true;
        }
      }
    }
  }

  /// Keeps an already-broken string-concatenation `+`, so a piecewise-built literal (e.g. a regex
  /// split across lines) is not collapsed back onto one line. The break then spreads to every `+`
  /// of the chain, so a concatenation wrapped at one operand wraps at every operand — no sibling
  /// `+` (e.g. two calls whose operands are not themselves string literals) is left crammed onto a
  /// line while the rest break.
  private void forceConcatBreaks() {
    // An operator reached by an earlier operator's spread needs no scan of its own: the spread
    // already covered every `+` of the chain.
    boolean[] spread = new boolean[ctx.tokens.size()];
    for (int i = 0; i < ctx.tokens.size(); i++) {
      if (!breakBefore[i] || spread[i] || !isStringConcatPlus(i)) {
        continue;
      }
      forcedBreak[i] = true;
      for (int j = i - 1; j >= 0; j--) {
        if (ctx.tokenClasses.has(j, Classification.CLOSER) && ctx.matchOpen[j] >= 0) {
          j = ctx.matchOpen[j]; // a nested group is skipped whole
        } else if (endsOperatorElement(j)) {
          break;
        } else if (ctx.tokens.get(j).sym() == Sym.PLUS) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
        }
      }
      for (int j = i + 1; j < ctx.tokens.size(); j++) {
        if (ctx.tokenClasses.has(j, Classification.OPENER) && ctx.matchClose[j] >= 0) {
          j = ctx.matchClose[j];
        } else if (endsOperatorElement(j)) {
          break;
        } else if (ctx.tokens.get(j).sym() == Sym.PLUS) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
          spread[j] = true;
        }
      }
    }
  }

  private boolean isStringConcatPlus(int i) {
    if (ctx.tokens.get(i).sym() != Sym.PLUS) {
      return false;
    }
    Token prev = ctx.prevCode(i);
    Token next = ctx.nextCode(i);
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
    boolean[] spread = new boolean[ctx.tokens.size()];
    for (int i = 0; i < ctx.tokens.size(); i++) {
      if (!breakBefore[i] || spread[i] || !isLogicalOp(i)) {
        continue;
      }
      forcedBreak[i] = true;
      for (int j = i - 1; j >= 0; j--) {
        if (ctx.tokenClasses.has(j, Classification.CLOSER) && ctx.matchOpen[j] >= 0) {
          j = ctx.matchOpen[j]; // a nested group is skipped whole
        } else if (endsOperatorElement(j)) {
          break;
        } else if (isLogicalOp(j)) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
        }
      }
      for (int j = i + 1; j < ctx.tokens.size(); j++) {
        if (ctx.tokenClasses.has(j, Classification.OPENER) && ctx.matchClose[j] >= 0) {
          j = ctx.matchClose[j];
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
    return ctx.tokens.get(i).sym() == Sym.AMP_AMP || ctx.tokens.get(i).sym() == Sym.BAR_BAR;
  }

  /// Enum constants are siblings: once one constant's argument list is broken across lines, every
  /// constant's argument list breaks too, so a list is never left with one constant crammed inline
  /// while its neighbours wrap. Constant bodies and the methods after the list-terminating `;` are
  /// untouched.
  private void forceEnumConstantBreaks() {
    int n = ctx.tokens.size();
    for (int e = 0; e < n; e++) {
      if (ctx.tokens.get(e).sym() != Sym.ENUM) {
        continue;
      }
      int bodyOpen = e + 1;
      while (bodyOpen < n && ctx.tokens.get(bodyOpen).sym() != Sym.LBRACE) {
        bodyOpen++;
      }
      if (bodyOpen >= n || ctx.matchClose[bodyOpen] < 0) {
        continue;
      }
      int bodyClose = ctx.matchClose[bodyOpen];
      List<Integer> parens = new ArrayList<>();
      boolean anyBroken = false;
      for (int i = bodyOpen + 1; i < bodyClose; i++) {
        if (ctx.tokens.get(i).sym() == Sym.SEMI) {
          break; // the constant list ends; the methods after it are not siblings
        }
        if (!ctx.tokenClasses.has(i, Classification.OPENER)) {
          continue;
        }
        int c = ctx.matchClose[i];
        if (c < 0) {
          break;
        }
        if (ctx.tokens.get(i).sym() == Sym.LPAREN && isEnumConstantParen(i)) {
          parens.add(i);
          anyBroken |= c > i + 1 && breakBefore[i + 1];
        }
        i = c; // skip nested content (constant bodies, argument lists)
      }
      if (!anyBroken) {
        continue;
      }
      for (int open : parens) {
        int c = ctx.matchClose[open];
        if (c > open + 1 && !breakBefore[open + 1]) {
          breakBefore[open + 1] = true;
          forcedBreak[open + 1] = true;
          breakBefore[c] = true;
          forcedBreak[c] = true;
        }
      }
    }
  }

  /// A `(` opening an enum constant's argument list: preceded by the constant-name identifier and
  /// not the argument list of an annotation on that constant.
  private boolean isEnumConstantParen(int open) {
    int name = ctx.indexOfPrevCode(open);
    if (name < 0 || ctx.tokens.get(name).kind() != Kind.IDENT) {
      return false;
    }
    return ctx.matchClose[open] >= 0 && !ctx.closesAnnotation(ctx.matchClose[open]);
  }

  /// Arrow-style `case`/`default` arms of a switch are siblings: once any arm's body spans more than
  /// one line — a block, or an expression that had to wrap — every other arrow arm breaks after its
  /// `->` too, so a switch never leaves one arm crammed onto its label line while its neighbours
  /// wrap. Block arms keep `-> {` on the label line; colon-style arms are untouched.
  private boolean forceSwitchArrowBreaks() {
    boolean changed = false;
    int n = ctx.tokens.size();
    for (int s = 0; s < n; s++) {
      if (ctx.tokens.get(s).sym() != Sym.SWITCH) {
        continue;
      }
      int lparen = ctx.indexOfNextCode(s);
      if (lparen < 0 || ctx.tokens.get(lparen).sym() != Sym.LPAREN || ctx.matchClose[lparen] < 0) {
        continue;
      }
      int bodyOpen = ctx.indexOfNextCode(ctx.matchClose[lparen]);
      if (bodyOpen < 0 || ctx.tokens.get(bodyOpen).sym() != Sym.LBRACE || ctx.matchClose[bodyOpen] < 0) {
        continue;
      }
      int bodyClose = ctx.matchClose[bodyOpen];
      List<Integer> arrowBodies = new ArrayList<>(); // body start of each non-block arrow arm
      boolean anyMultiline = false;
      int i = bodyOpen + 1;
      while (i < bodyClose) {
        if (ctx.tokens.get(i).sym() != Sym.CASE && ctx.tokens.get(i).sym() != Sym.DEFAULT) {
          i = ctx.tokenClasses.has(i, Classification.OPENER) && ctx.matchClose[i] >= 0
            ? ctx.matchClose[i] + 1
            : i + 1;
          continue;
        }
        int arrow = arrowOfCaseLabel(i, bodyClose);
        if (arrow < 0) {
          i++; // a colon-style label; its fallthrough statements are ordinary tokens
          continue;
        }
        int body = ctx.indexOfNextCode(arrow);
        if (body < 0 || body >= bodyClose) {
          break;
        }
        int end;
        if (ctx.tokens.get(body).sym() == Sym.LBRACE && ctx.matchClose[body] >= 0) {
          end = ctx.matchClose[body]; // block arm: its `-> {` never breaks
        } else {
          end = armExpressionEnd(body, bodyClose);
          arrowBodies.add(body);
        }
        for (int k = body; k <= end; k++) {
          if (breakBefore[k]) {
            anyMultiline = true;
            break;
          }
        }
        i = end + 1;
      }
      if (!anyMultiline) {
        continue;
      }
      for (int body : arrowBodies) {
        if (!breakBefore[body] || !forcedBreak[body]) {
          breakBefore[body] = true;
          forcedBreak[body] = true;
          changed = true;
        }
      }
    }
    return changed;
  }

  /// The `->` of an arrow-style case label, or -1 when the label is colon-style (or malformed).
  /// Nested bracket groups are skipped whole so a `,` inside a type pattern is not mistaken for the
  /// label's end.
  private int arrowOfCaseLabel(int label, int limit) {
    for (int j = label + 1; j < limit; j++) {
      if (ctx.tokenClasses.has(j, Classification.OPENER) && ctx.matchClose[j] >= 0) {
        j = ctx.matchClose[j];
        continue;
      }
      switch (ctx.tokens.get(j).sym()) {
        case ARROW -> {
          return j;
        }
        case COLON, CASE, DEFAULT, SEMI -> {
          return -1;
        }
        default -> {}
      }
    }
    return -1;
  }

  /// The terminating `;` of an arrow arm's expression or statement body, skipping nested groups.
  private int armExpressionEnd(int body, int limit) {
    for (int j = body; j < limit; j++) {
      if (ctx.tokenClasses.has(j, Classification.OPENER) && ctx.matchClose[j] >= 0) {
        j = ctx.matchClose[j];
        continue;
      }
      if (ctx.tokens.get(j).sym() == Sym.SEMI) {
        return j;
      }
    }
    return limit - 1;
  }

  private boolean endsOperatorElement(int i) {
    return ctx.tokenClasses.has(i, Classification.OPENER)
      || ctx.tokenClasses.has(i, Classification.CLOSER)
      || switch (ctx.tokens.get(i).sym()) {
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
    return t.sym() == Sym.RPAREN
      || t.sym() == Sym.RBRACKET
      || isLiteral(t)
      || t.kind() == Kind.IDENT
      && !t.isKeyword();
  }

  private void buildLines() {
    int lineStart = 0;
    for (int i = 1; i <= ctx.tokens.size(); i++) {
      if (i == ctx.tokens.size() || breakBefore[i]) {
        int nb = lineStart == 0 ? 0 : ctx.tokens.get(lineStart).newlinesBefore();
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
    while (wrapLongLines() | breakGroupElements() | forceSwitchArrowBreaks()) {
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
    for (int i = 0; i < ctx.tokens.size(); i++) {
      if (marks.has(i, Mark.GENERIC_ANGLE)) {
        generic += ctx.angleDepthDelta(i);
        continue;
      }
      if (ctx.tokenClasses.has(i, Classification.OPENER)) {
        openerStack[depth++] = i;
      } else if (ctx.tokenClasses.has(i, Classification.CLOSER) && depth > 0) {
        depth--;
      } else if (ctx.tokens.get(i).sym() == Sym.COMMA && generic == 0 && depth > 0) {
        int o = openerStack[depth - 1];
        if (ctx.tokens.get(o).sym() == Sym.LPAREN && breakBefore[o + 1]) {
          // The break starts the next element, but a trailing comment on the comma's line stays
          // put: skip past it so the comment is not pushed onto its own line.
          int target = i + 1;
          while (target < ctx.tokens.size() && ctx.tokens.get(target).isComment() && !breakBefore[target]) {
            target++;
          }
          if (target < ctx.tokens.size() && !breakBefore[target]) {
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
      // A too-long method chain re-lays every `.call(` onto its own line rather than isolating the
      // last call's argument, which would strand a single argument while leaving the chain crammed.
      List<Integer> chain = topLevelChainDots(
        line.firstToken(),
        line.firstToken() + line.tokenCount() - 1
      );
      if (chain.size() >= 2 && !breakBefore[chain.get(0)]) {
        for (int b : chain) {
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
        if (!ctx.tokenClasses.has(i, Classification.OPENER)) {
          continue;
        }
        int c = ctx.matchClose[i];
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
    for (int i = 1; i < ctx.tokens.size(); i++) {
      if (!breakBefore[i] || forcedBreak[i] || ctx.tokens.get(i - 1).sym() != Sym.ASSIGN) {
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
    for (int j = i; j < ctx.tokens.size(); j++) {
      Sym sym = ctx.tokens.get(j).sym();
      if (
        depth == 0
          && generic == 0
          && (ctx.tokenClasses.has(j, Classification.CLOSER) || sym == Sym.SEMI || sym == Sym.COMMA)
      ) {
        return j - 1;
      }
      if (j > i && breakBefore[j] || ctx.tokenWidth[j] < 0) {
        return -1;
      }
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += ctx.angleDepthDelta(j);
      } else if (ctx.tokenClasses.has(j, Classification.OPENER)) {
        depth++;
      } else if (ctx.tokenClasses.has(j, Classification.CLOSER)) {
        depth--;
      }
    }
    return ctx.tokens.size() - 1;
  }

  /// The right-hand side starting at `i` keeps its head on one line and takes its first break at a
  /// top-level binary operator, so seating that head on the assignment line leaves a well-formed
  /// operator continuation behind.
  private boolean rhsBreaksAtOperator(int i) {
    int depth = 0;
    int generic = 0;
    for (int j = i; j < ctx.tokens.size(); j++) {
      Sym sym = ctx.tokens.get(j).sym();
      if (
        depth == 0
          && generic == 0
          && (ctx.tokenClasses.has(j, Classification.CLOSER) || sym == Sym.SEMI || sym == Sym.COMMA)
      ) {
        return false;
      }
      if (j > i && breakBefore[j]) {
        return depth == 0 && generic == 0 && leadsBinaryOperator(j);
      }
      if (ctx.tokenWidth[j] < 0) {
        return false;
      }
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += ctx.angleDepthDelta(j);
      } else if (ctx.tokenClasses.has(j, Classification.OPENER)) {
        depth++;
      } else if (ctx.tokenClasses.has(j, Classification.CLOSER)) {
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
    Token prev = ctx.prevCode(j);
    if (prev == null || !endsOperand(prev)) {
      return false;
    }
    return switch (ctx.tokens.get(j).sym()) {
      case PLUS, MINUS, STAR, SLASH, PERCENT, AMP, BAR, CARET, LT_LT, GT_GT, GT_GT_GT, LT, GT, LE,
          GE, EQ, NE, AMP_AMP, BAR_BAR, INSTANCEOF -> true;
      default -> false;
    };
  }

  /// A grouping paren (preceded by an operator, not a call name, argument opener, or control-flow
  /// keyword) whose sole top-level content is a conditional expression. Call and control-flow parens
  /// keep the single content indent.
  private boolean wrapsConditional(int open, int prev) {
    if (prev < 0
      || endsOperand(ctx.tokens.get(prev))
      || ctx.tokens.get(prev).sym() == Sym.COMMA
      || ctx.tokens.get(prev).sym() == Sym.LPAREN
      || ctx.tokenClasses.has(prev, Classification.PAREN_KEYWORD)) {
      return false;
    }
    int close = ctx.matchClose[open];
    return close > open + 1 && !topLevelTernaryOperators(open + 1, close - 1).isEmpty();
  }

  private List<Integer> topLevelTernaryOperators(int i, int end) {
    List<Integer> ops = new ArrayList<>();
    int depth = 0;
    int generic = 0;
    for (int j = i; j <= end; j++) {
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += ctx.angleDepthDelta(j);
      } else if (ctx.tokenClasses.has(j, Classification.OPENER)) {
        depth++;
      } else if (ctx.tokenClasses.has(j, Classification.CLOSER)) {
        depth--;
      } else if (
        depth == 0
          && generic == 0
          && !marks.has(j, Mark.WILDCARD)
          && (ctx.tokens.get(j).sym() == Sym.QUESTION || ctx.tokens.get(j).sym() == Sym.COLON && !ops.isEmpty())
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
        generic += ctx.angleDepthDelta(j);
      } else if (ctx.tokenClasses.has(j, Classification.OPENER)) {
        depth++;
      } else if (ctx.tokenClasses.has(j, Classification.CLOSER)) {
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
      if (ctx.tokenClasses.has(j, Classification.OPENER) && ctx.matchClose[j] > j) {
        j = ctx.matchClose[j];
      } else if (ctx.isCallDot(j)) {
        dot = j;
      }
    }
    List<Integer> dots = new ArrayList<>();
    while (dot >= 0 && dot <= end && ctx.isCallDot(dot)) {
      dots.add(dot);
      int close = ctx.matchClose[ctx.callParen[dot]];
      if (close < 0) {
        break;
      }
      dot = ctx.indexOfNextCode(close);
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
      if (!ctx.tokenClasses.has(j, Classification.OPENER)) {
        continue;
      }
      int c = ctx.matchClose[j];
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
    for (int dot = 0; dot < ctx.tokens.size(); dot++) {
      if (!breakBefore[dot] || !ctx.isCallDot(dot)) {
        continue; // only a call that chain-breaking put on its own line
      }
      int open = ctx.callParen[dot];
      int close = ctx.matchClose[open];
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
      boolean blocked = ctx.prefixMultiline[close + 1] > ctx.prefixMultiline[dot]
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
      if (ctx.tokens.get(i).sym() == Sym.LBRACE) {
        return true;
      }
    }
    return false;
  }

  /// One pass attributing each comma to its innermost opener, versus a rescan per collapse
  /// candidate.
  private boolean[] computeMultiArgParens() {
    boolean[] multiArg = new boolean[ctx.tokens.size()];
    int generic = 0;
    int depth = 0;
    for (int i = 0; i < ctx.tokens.size(); i++) {
      if (marks.has(i, Mark.GENERIC_ANGLE)) {
        generic += ctx.angleDepthDelta(i);
        continue;
      }
      if (ctx.tokenClasses.has(i, Classification.OPENER)) {
        openerStack[depth++] = i;
      } else if (ctx.tokenClasses.has(i, Classification.CLOSER) && depth > 0) {
        depth--;
      } else if (ctx.tokens.get(i).sym() == Sym.COMMA && generic == 0 && depth > 0) {
        multiArg[openerStack[depth - 1]] = true;
      }
    }
    return multiArg;
  }

  /// Such a call belongs to a chain the surrounding broken group already lays out multiline, so
  /// its arguments stay broken.
  private boolean nestedInBrokenParen(int dot, int close) {
    for (int o = ctx.enclosingOpen[dot]; o >= 0; o = ctx.enclosingOpen[o]) {
      if (ctx.tokens.get(o).sym() == Sym.LPAREN && ctx.matchClose[o] > close && breakBefore[ctx.matchClose[o]]) {
        return true;
      }
    }
    return false;
  }

  /// Collapsing the outer call would flatten a broken multi-argument inner call
  /// (`Map.of(\n  "a",\n  "b"\n)`) too, so the outer break is kept.
  private boolean containsBrokenMultiArgCall(int open, int close, boolean[] multiArg) {
    for (int i = open + 1; i < close; i++) {
      if (ctx.tokens.get(i).sym() == Sym.LPAREN && breakBefore[ctx.matchClose[i]] && multiArg[i]) {
        return true;
      }
    }
    return false;
  }

  /// Reverses the forced breaks isolating a control-flow condition paren when the whole header
  /// fits on one line — an unwind a soft join cannot do because it never crosses a forced break.
  private boolean collapseControlFlowHeaders() {
    boolean changed = false;
    for (int open = 0; open < ctx.tokens.size(); open++) {
      if (ctx.tokens.get(open).sym() != Sym.LPAREN || !breakBefore[open + 1]) {
        continue; // not an isolated paren
      }
      int close = ctx.matchClose[open];
      int keyword = ctx.indexOfPrevCode(open);
      if (close < 0 || keyword < 0 || !ctx.tokenClasses.has(keyword, Classification.PAREN_KEYWORD)) {
        continue;
      }
      int brace = ctx.indexOfNextCode(close);
      if (brace < 0 || ctx.tokens.get(brace).sym() != Sym.LBRACE) {
        continue; // not a block header (excludes `return (..)`, `throw (..)`, etc.)
      }
      // A try-with-resources listing multiple resources (a top-level `;`) keeps each resource on
      // its own line; only a single-resource header collapses.
      if (ctx.tokens.get(keyword).sym() == Sym.TRY && hasTopLevelSemicolon(open, close)) {
        continue;
      }
      int li = lineIndexOf(open);
      int start = lines.get(li).firstToken();
      boolean multiline = ctx.prefixMultiline[brace + 1] > ctx.prefixMultiline[start];
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
      if (ctx.tokenClasses.has(i, Classification.OPENER)) {
        depth++;
      } else if (ctx.tokenClasses.has(i, Classification.CLOSER)) {
        depth--;
      } else if (depth == 0 && ctx.tokens.get(i).sym() == Sym.SEMI) {
        return true;
      }
    }
    return false;
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
  /// [#hasMultilineToken] or [TokenContext#prefixMultiline]) before trusting the result.
  private int runWidth(int first, int endExclusive) {
    return prefixWidth[endExclusive] - prefixWidth[first] - (spaceBefore[first] ? 1 : 0);
  }

  /// 0 when the line holds a multiline token, which cannot be usefully measured or wrapped.
  private int lineWidth(int li) {
    Line line = lines.get(li);
    int end = line.firstToken() + line.tokenCount();
    if (ctx.prefixMultiline[end] > ctx.prefixMultiline[line.firstToken()]) {
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
      Sym firstSym = ctx.tokens.get(firstToken).sym();
      // `scopeFor` allocates a stack iterator, so resolve the closer's scope once and reuse it for
      // both the close indent and the body indent below. `top` is an unused non-null placeholder
      // when the line does not start with a closer.
      boolean firstIsCloser = ctx.tokenClasses.has(firstToken, Classification.CLOSER);
      Scope closerScope = firstIsCloser ? scopeFor(stack, firstSym) : top;
      int indent;
      boolean continuation = false;
      if (joinWithPrev != null && joinWithPrev[li]) {
        indent = headIndent;
      } else if (firstIsCloser) {
        indent = closerScope.closeIndent;
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
      int bodyIndent = firstIsCloser ? closerScope.contentIndent : indent;
      // A comment preceding a colon-style case label belongs to the previous case's fallthrough
      // body, so indent it one level deeper than the label. Arrow-style cases don't fall through,
      // so a comment there introduces the next case and aligns with the label.
      if (top.kind == Scope.Kind.SWITCH_BODY
        && (firstSym == Sym.CASE || firstSym == Sym.DEFAULT)
        && isColonCaseLine(line)) {
        bodyIndent += INDENT;
      }
      for (int ci : pendingComments) {
        lineIndent[ci] = ctx.tokens.get(lines.get(ci).firstToken()).atColumn0() ? 0 : bodyIndent;
      }
      pendingComments.clear();
      walkLine(stack, line, indent, continuation);
    }
    for (int ci : pendingComments) {
      lineIndent[ci] = ctx.tokens
        .get(lines.get(ci).firstToken())
        .atColumn0() ? 0 : stack.peek().contentIndent;
    }
    if (consumeMarksChanged()) {
      for (int i = 1; i < ctx.tokens.size(); i++) {
        spaceBefore[i] = spaceBetween(i - 1, i);
      }
      for (int i = 0; i < ctx.tokens.size(); i++) {
        prefixWidth[i + 1] = prefixWidth[i] + (spaceBefore[i] ? 1 : 0) + Math.max(ctx.tokenWidth[i], 0);
      }
    }
  }

  /// A ternary's `:` aligns with its matching `?` rather than taking the usual continuation indent.
  private int continuationIndent(Scope top, int firstToken, int prevIndent) {
    Sym sym = ctx.tokens.get(firstToken).sym();
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
      if (ctx.tokens.get(i).sym() == Sym.ARROW) {
        return false;
      }
    }
    return true;
  }

  private boolean isCommentOnly(Line line) {
    for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
      if (!ctx.tokens.get(i).isComment()) {
        return false;
      }
    }
    return true;
  }

  /// Falls back to the innermost scope of the matching kind on unbalanced input.
  private static Scope scopeFor(Deque<Scope> stack, Sym closer) {
    Set<Scope.Kind> expected = switch (closer) {
      case Sym.RBRACE -> EnumSet.of(
        Scope.Kind.BLOCK,
        Scope.Kind.SWITCH_BODY,
        Scope.Kind.ENUM_BODY,
        Scope.Kind.ARRAY_INIT
      );
      case Sym.RPAREN -> EnumSet.of(Scope.Kind.PAREN);
      default -> EnumSet.of(Scope.Kind.BRACKET);
    };
    for (Scope s : stack) {
      if (expected.contains(s.kind)) {
        return s;
      }
    }
    return stack.peekLast();
  }

  private void walkLine(Deque<Scope> stack, Line line, int indent, boolean continuation) {
    for (int i = line.firstToken(); i < line.firstToken() + line.tokenCount(); i++) {
      Token t = ctx.tokens.get(i);
      if (t.isComment()) {
        continue;
      }
      Scope top = stack.peek();
      if (!top.elementOpen && !ctx.tokenClasses.has(i, Classification.CLOSER)) {
        top.elementOpen = true;
        top.elementStartIndent = indent;
        top.caseLabel = top.kind == Scope.Kind.SWITCH_BODY
          && (ctx.tokens.get(i).sym() == Sym.CASE || ctx.tokens.get(i).sym() == Sym.DEFAULT);
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
    Token t = ctx.tokens.get(i);
    Scope top = stack.peek();
    Sym sym = ctx.tokens.get(i).sym();
    updateAnnotationState(top, t, sym);

    switch (sym) {
      case LPAREN -> {
        int prev = ctx.indexOfPrevCode(i);
        // A grouping paren wrapping a conditional gives its body a second indent level, so the
        // `?`/`:` branches sit below the opener rather than aligning with a call's arguments.
        int contentIndent = indent + INDENT;
        if (wrapsConditional(i, prev)) {
          contentIndent += INDENT;
        }
        Scope scope = newScope(Scope.Kind.PAREN, contentIndent, indent);
        scope.forParen = prev >= 0 && (ctx.tokens.get(prev).sym() == Sym.FOR || ctx.tokens.get(prev).sym() == Sym.TRY);
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
          top.generic = Math.max(0, top.generic + ctx.angleDepthDelta(i));
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
        int next = ctx.indexOfNextCode(i);
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
        if (ctx.tokenClasses.has(i, Classification.PRIMITIVE)) {
          top.sawPrimitive = true;
        }
        boolean word = t.kind() != Kind.PUNCT;
        boolean typeToken = t.kind() == Kind.IDENT
          && (
            !ctx.tokenClasses.has(i, Classification.KEYWORD)
              || ctx.tokenClasses.has(i, Classification.PRIMITIVE)
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
    int prev = ctx.indexOfPrevCode(i);
    Sym prevSym = prev < 0 ? Sym.OTHER : ctx.tokens.get(prev).sym();
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
      if (!ctx.tokens.get(i).isComment()) {
        last = i;
        break;
      }
    }
    if (last < 0) {
      return;
    }
    Scope top = stack.peek();
    if (ctx.tokens.get(last).sym() == Sym.RBRACE) {
      closeElement(top);
    } else if (top.elementOpen && top.annotationState == 2) {
      closeElement(top); // annotation-only line: next line starts fresh at the same indent
    }
  }

  private boolean isCast(Scope paren, int closeIndex) {
    if (!paren.typeLike || !paren.hasContent) {
      return false;
    }
    int beforeOpen = ctx.indexOfPrevCode(ctx.matchOpen[closeIndex]);
    // A cast's `(` cannot follow a name or a closing bracket (that would be a call or index).
    if (beforeOpen >= 0) {
      Token before = ctx.tokens.get(beforeOpen);
      if (
        before.kind() == Kind.IDENT
          && !ctx.tokenClasses.has(beforeOpen, Classification.KEYWORD)
          || ctx.tokens.get(beforeOpen).sym() == Sym.RPAREN
          || ctx.tokens.get(beforeOpen).sym() == Sym.RBRACKET
      ) {
        return false;
      }
    }
    int nextIndex = ctx.indexOfNextCode(closeIndex);
    if (nextIndex < 0) {
      return false;
    }
    Token next = ctx.tokens.get(nextIndex);
    return switch (ctx.tokens.get(nextIndex).sym()) {
      case PLUS, MINUS, INCREMENT, DECREMENT -> paren.sawPrimitive;
      case LPAREN, BANG, TILDE, THIS, SUPER, NEW -> true;
      default -> next.kind() != Kind.PUNCT
        && !ctx.tokenClasses.has(nextIndex, Classification.KEYWORD)
        || isLiteral(next);
    };
  }

  private boolean isUnaryPosition(int i) {
    int prevIndex = ctx.indexOfPrevCode(i);
    if (prevIndex < 0) {
      return true;
    }
    Token prev = ctx.tokens.get(prevIndex);
    if (isLiteral(prev)) {
      return false;
    }
    Sym prevSym = ctx.tokens.get(prevIndex).sym();
    if (prev.kind() == Kind.IDENT) {
      return ctx.tokenClasses.has(prevIndex, Classification.KEYWORD) && switch (prevSym) {
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
    int prevIndex = ctx.indexOfPrevCode(open);
    if (prevIndex < 0) {
      return -1;
    }
    boolean plausiblePrev = switch (ctx.tokens.get(prevIndex).sym()) {
      case DOT, COMMA, LPAREN, LT, LBRACE, AMP, BAR, ASSIGN, RETURN, NEW, EXTENDS, SUPER,
        IMPLEMENTS, INSTANCEOF, CASE, YIELD, ARROW, METHOD_REF, QUESTION, COLON,
        // Type-parameter declarations: `public <T> T foo(..)`, `interface Foo<T>`, `<T> T foo(..)`.
        PUBLIC, PRIVATE, PROTECTED, STATIC, FINAL, DEFAULT, ABSTRACT, CLASS, INTERFACE, RECORD,
        SEMI, RBRACE -> true;
      default -> {
        Token prev = ctx.tokens.get(prevIndex);
        yield prev.kind() == Kind.IDENT && !prev.isKeyword();
      }
    };
    if (!plausiblePrev) {
      return -1;
    }
    int end = ctx.scanTypeArguments(open);
    if (end < 0) {
      return -1;
    }
    int followerIndex = ctx.indexOfNextCode(end);
    if (followerIndex < 0) {
      return end;
    }
    boolean plausibleFollower = ctx.tokens.get(followerIndex).kind() == Kind.IDENT
      || switch (ctx.tokens.get(followerIndex).sym()) {
           case LPAREN, RPAREN, COMMA, DOT, METHOD_REF, SEMI, LBRACKET, LBRACE, GT, GT_GT, GT_GT_GT,
             ELLIPSIS, AMP, ARROW, ASSIGN, AT -> true;
           default -> false;
         };
    return plausibleFollower ? end : -1;
  }

  private void markTypeArguments(int open, int end) {
    mark(open, Mark.GENERIC_ANGLE);
    for (int i = open + 1; i <= end; i++) {
      switch (ctx.tokens.get(i).sym()) {
        case LT, GT, GT_GT, GT_GT_GT -> mark(i, Mark.GENERIC_ANGLE);
        case QUESTION -> mark(i, Mark.WILDCARD);
        default -> {}
      }
    }
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
      boolean joinsStatement = ctx.tokens.get(lastTokenIndex(end)).sym() == Sym.SEMI || endForced;
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
      if (ctx.isCallDot(lines.get(m).firstToken())) {
        return hasMultilineToken(startLine, m - 1) ? startLine : m - 1;
      }
    }
    boolean endChainDot = endForced && ctx.tokens.get(lines.get(end + 1).firstToken()).sym() == Sym.DOT;
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
    Sym first = ctx.tokens.get(line.firstToken()).sym();
    if (first == Sym.RBRACE || first == Sym.AT) {
      return false;
    }
    // A wrapped ternary keeps its `?`/`:` branch lines.
    if (first == Sym.QUESTION || first == Sym.COLON) {
      return false;
    }
    Line prev = lines.get(li - 1);
    if (ctx.tokens.get(prev.firstToken()).sym() == Sym.AT) {
      return false;
    }
    int prevLastIndex = prev.firstToken() + prev.tokenCount() - 1;
    Sym prevLast = ctx.tokens.get(prevLastIndex).sym();
    if (
      ctx.tokens.get(prevLastIndex).isComment()
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
    if (prevLast == Sym.RPAREN && ctx.closesAnnotation(prevLastIndex)) {
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
    return ctx.prefixMultiline[last.firstToken() + last.tokenCount()] > ctx.prefixMultiline[first];
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
    int capacity = ctx.tokens.isEmpty() ? 1 : ctx.tokens.get(ctx.tokens.size() - 1).end() + 1;
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
    Sym prevLast = ctx.tokens.get(prev.firstToken() + prev.tokenCount() - 1).sym();
    if (prevLast == Sym.LBRACE || prevLast == Sym.LPAREN) {
      return false;
    }
    Sym first = ctx.tokens.get(lines.get(li).firstToken()).sym();
    return first != Sym.RBRACE && first != Sym.RPAREN;
  }

  private void appendToken(StringBuilder out, int i, int indent) {
    Token t = ctx.tokens.get(i);
    if (t.kind() == Kind.LINE_COMMENT) {
      out.append(t.text().stripTrailing());
      return;
    }
    if (t.kind() == Kind.BLOCK_COMMENT && ctx.tokenWidth[i] < 0) {
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
    Token prev = ctx.tokens.get(prevIndex);
    Token next = ctx.tokens.get(nextIndex);
    Sym prevSym = ctx.tokens.get(prevIndex).sym();
    Sym nextSym = ctx.tokens.get(nextIndex).sym();

    if (prev.isComment() || next.isComment()) {
      return true;
    }
    if (noSpaceBefore(nextSym) || noSpaceAfter(prevSym)) {
      return false;
    }
    if (prevSym == Sym.ELLIPSIS || prevSym == Sym.ARROW || nextSym == Sym.ARROW) {
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
      return nextSym == Sym.LT && ctx.tokenClasses.has(prevIndex, Classification.MODIFIER);
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
    if (prevSym == Sym.LBRACE && nextSym == Sym.RBRACE) {
      return false;
    }
    if (
      prevSym == Sym.COLON
        || nextSym == Sym.LBRACE
        || prevSym == Sym.LBRACE
        || nextSym == Sym.RBRACE
    ) {
      return true;
    }
    if (prevSym == Sym.RBRACE) {
      return nextSym != Sym.LPAREN && nextSym != Sym.LBRACKET;
    }
    if (nextSym == Sym.LPAREN) {
      return ctx.tokenClasses.has(prevIndex, Classification.PAREN_KEYWORD)
        || prevSym == Sym.DO
        || prevSym == Sym.ELSE
        || ctx.tokenClasses.has(prevIndex, Classification.BINARY_OPERATOR)
        && !marks.has(prevIndex, Mark.GENERIC_ANGLE)
        || prevSym == Sym.SEMI
        || prevSym == Sym.COMMA;
    }
    if (nextSym == Sym.LBRACKET) {
      return false;
    }
    boolean prevWord = prev.kind() != Kind.PUNCT;
    boolean nextWord = next.kind() != Kind.PUNCT;
    if (
      prevSym == Sym.SEMI
        || prevSym == Sym.COMMA
        || ctx.tokenClasses.has(prevIndex, Classification.BINARY_OPERATOR)
        || ctx.tokenClasses.has(nextIndex, Classification.BINARY_OPERATOR)
        || (prevWord && nextWord)
    ) {
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
    Sym prevSym = ctx.tokens.get(prevIndex).sym();
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

  private static boolean noSpaceBefore(Sym sym) {
    return switch (sym) {
      case SEMI, COMMA, RPAREN, RBRACKET, DOT, METHOD_REF, ELLIPSIS -> true;
      default -> false;
    };
  }

  private static boolean noSpaceAfter(Sym sym) {
    return switch (sym) {
      case LPAREN, LBRACKET, DOT, METHOD_REF, AT -> true;
      default -> false;
    };
  }
}
