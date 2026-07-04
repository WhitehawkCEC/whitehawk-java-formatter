package com.whitehawk.javaformatter.core.printer;

import com.whitehawk.javaformatter.core.ArraySmallEnumSet;
import com.whitehawk.javaformatter.core.JavaLexer.Kind;
import com.whitehawk.javaformatter.core.JavaLexer.Token;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
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

  public Printer(List<Token> tokens) {
    this.tokens = insertMissingBraces(expandLambdaParams(removeUnusedImports(tokens)));
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

  private boolean hasClass(int i, Classification cls) {
    return tokenClasses.has(i, cls);
  }

  private void mark(int i, Mark mark) {
    marksChanged |= marks.set(i, mark);
  }

  private boolean consumeMarksChanged() {
    boolean was = marksChanged;
    marksChanged = false;
    return was;
  }

  private boolean isOpener(int i) {
    return hasClass(i, Classification.OPENER);
  }

  private boolean isCloser(int i) {
    return hasClass(i, Classification.CLOSER);
  }

  private void computeBracketMatches() {
    Arrays.fill(matchOpen, -1);
    Arrays.fill(matchClose, -1);
    int[] openers = new int[tokens.size()];
    int depth = 0;
    for (int i = 0; i < tokens.size(); i++) {
      enclosingOpen[i] = depth > 0 ? openers[depth - 1] : -1;
      if (isOpener(i)) {
        openers[depth++] = i;
      } else if (isCloser(i) && depth > 0) {
        int o = openers[--depth];
        matchClose[o] = i;
        matchOpen[i] = o;
      }
    }
  }

  /// A name mentioned only in a comment (e.g. a javadoc `{@link Foo}`) still counts as used.
  /// Wildcard imports are always kept: the names they contribute can't be resolved from tokens
  /// alone. A dropped import's leading blank line carries onto what follows so group spacing
  /// survives.
  private static List<Token> removeUnusedImports(List<Token> in) {
    int n = in.size();
    List<int[]> imports = new ArrayList<>(); // {importIndex, semicolonIndex}
    for (int i = 0; i < n; i++) {
      if (!in.get(i).is("import")) {
        continue;
      }
      int semi = i;
      while (semi < n && !in.get(semi).is(";")) {
        semi++;
      }
      if (semi < n) {
        imports.add(new int[] { i, semi });
      }
      i = semi;
    }
    if (imports.isEmpty()) {
      return in;
    }

    Set<String> used = new HashSet<>();
    int imp = 0;
    for (int i = 0; i < n; i++) {
      if (imp < imports.size() && i == imports.get(imp)[0]) {
        i = imports.get(imp)[1];
        imp++;
        continue;
      }
      Token t = in.get(i);
      if (t.kind() == Kind.IDENT) {
        used.add(t.text());
      } else if (t.isComment()) {
        addIdentifierWords(used, t.text());
      }
    }

    boolean[] drop = new boolean[n];
    boolean any = false;
    for (int[] range : imports) {
      if (in.get(range[1] - 1).is("*")) {
        continue; // wildcard: keep
      }
      String name = null;
      for (int i = range[1] - 1; i > range[0]; i--) {
        Token t = in.get(i);
        if (t.kind() == Kind.IDENT && !t.isKeyword()) {
          name = t.text();
          break;
        }
      }
      if (name != null && !used.contains(name)) {
        for (int i = range[0]; i <= range[1]; i++) {
          drop[i] = true;
        }
        any = true;
      }
    }
    if (!any) {
      return in;
    }

    List<Token> out = new ArrayList<>(n);
    int carriedNewlines = -1; // max newlinesBefore across a contiguous dropped run
    for (int i = 0; i < n; i++) {
      if (drop[i]) {
        carriedNewlines = Math.max(carriedNewlines, in.get(i).newlinesBefore());
        continue;
      }
      Token t = in.get(i);
      if (carriedNewlines > t.newlinesBefore()) {
        t = new Token(t.kind(), t.text(), t.start(), t.end(), carriedNewlines, t.atColumn0());
      }
      carriedNewlines = -1;
      out.add(t);
    }
    return out;
  }

  private static void addIdentifierWords(Set<String> used, String text) {
    int len = text.length();
    for (int i = 0; i < len;) {
      if (Character.isJavaIdentifierStart(text.charAt(i))) {
        int j = i + 1;
        while (j < len && Character.isJavaIdentifierPart(text.charAt(j))) {
          j++;
        }
        used.add(text.substring(i, j));
        i = j;
      } else {
        i++;
      }
    }
  }

  /// Canonical style gives every implicit lambda parameter an explicit `var`.
  private static List<Token> expandLambdaParams(List<Token> in) {
    int n = in.size();
    boolean[] wrap = new boolean[n]; // bare param: wrap in `(var ...)`
    boolean[] prefixVar = new boolean[n]; // parenthesized implicit param: prepend `var`
    boolean any = false;
    int[] parenOpen = null;
    for (int a = 0; a < n; a++) {
      if (!in.get(a).is("->")) {
        continue;
      }
      int prev = prevCodeIndex(in, a);
      if (prev < 0) {
        continue;
      }
      if (in.get(prev).is(")")) {
        if (parenOpen == null) {
          parenOpen = matchOpenParens(in);
        }
        int open = parenOpen[prev];
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
        out.add(
          new Token(Kind.PUNCT, "(", t.start(), t.start(), t.newlinesBefore(), t.atColumn0())
        );
        out.add(new Token(Kind.IDENT, "var", t.start(), t.start(), 0, false));
        out.add(new Token(t.kind(), t.text(), t.start(), t.end(), 0, false));
        out.add(new Token(Kind.PUNCT, ")", t.end(), t.end(), 0, false));
      } else if (prefixVar[i]) {
        out.add(
          new Token(Kind.IDENT, "var", t.start(), t.start(), t.newlinesBefore(), t.atColumn0())
        );
        out.add(new Token(t.kind(), t.text(), t.start(), t.end(), 0, false));
      } else {
        out.add(t);
      }
    }
    return out;
  }

  private static boolean isBareLambdaParam(List<Token> in, int i) {
    Token t = in.get(i);
    if (t.kind() != Kind.IDENT || t.isKeyword()) {
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
      boolean labelPart = p.is(".")
        || p.is(",")
        || p.is("<")
        || p.is(">")
        || p.is(">>")
        || p.is(">>>")
        || p.is("?")
        || p.is("&")
        || p.is("[")
        || p.is("]")
        || p.is("extends")
        || p.is("super")
        || p.kind() == Kind.IDENT && !p.isKeyword();
      if (!labelPart) {
        break;
      }
    }
    return true;
  }

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
      if (++elementTokens > 1 || t.kind() != Kind.IDENT || t.isKeyword()) {
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

  /// Parens only — all a lambda parameter list can nest in.
  private static int[] matchOpenParens(List<Token> in) {
    int n = in.size();
    int[] open = new int[n];
    Arrays.fill(open, -1);
    int[] stack = new int[n];
    int depth = 0;
    for (int i = 0; i < n; i++) {
      Token t = in.get(i);
      if (t.is("(")) {
        stack[depth++] = i;
      } else if (t.is(")") && depth > 0) {
        open[i] = stack[--depth];
      }
    }
    return open;
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

  /// Canonical style braces every control-flow body (`if`/`else`/`for`/`while`/`do`).
  private static List<Token> insertMissingBraces(List<Token> in) {
    int n = in.size();
    int[] close = matchAllBrackets(in);
    List<int[]> wraps = new ArrayList<>(); // {bodyStart, bodyEndInclusive}
    for (int i = 0; i < n; i++) {
      Token t = in.get(i);
      int bodyStart;
      if (t.is("if") || t.is("while") || t.is("for")) {
        int paren = nextCodeIndex(in, i);
        if (paren < 0 || !in.get(paren).is("(") || close[paren] < 0) {
          continue;
        }
        bodyStart = nextCodeIndex(in, close[paren]);
      } else if (t.is("do")) {
        bodyStart = nextCodeIndex(in, i);
      } else if (t.is("else")) {
        bodyStart = nextCodeIndex(in, i);
        if (bodyStart >= 0 && in.get(bodyStart).is("if")) {
          continue; // else-if chain keeps its unbraced `if`
        }
      } else {
        continue;
      }
      if (bodyStart < 0) {
        continue;
      }
      Token body = in.get(bodyStart);
      if (body.is("{") || body.is(";")) {
        continue; // already a block or an empty statement
      }
      int bodyEnd = statementEnd(in, close, bodyStart);
      if (bodyEnd >= bodyStart) {
        wraps.add(new int[] { bodyStart, bodyEnd });
      }
    }
    if (wraps.isEmpty()) {
      return in;
    }
    return applyWraps(in, wraps);
  }

  /// Inserted braces are indistinguishable, so only their count per token is tracked.
  private static List<Token> applyWraps(List<Token> in, List<int[]> wraps) {
    int n = in.size();
    int[] opensAt = new int[n];
    int[] closesAt = new int[n];
    for (int[] w : wraps) {
      opensAt[w[0]]++;
      closesAt[w[1]]++;
    }
    List<Token> out = new ArrayList<>(n + 2 * wraps.size());
    for (int i = 0; i < n; i++) {
      Token t = in.get(i);
      if (opensAt[i] > 0) {
        for (int k = 0; k < opensAt[i]; k++) {
          out.add(new Token(Kind.PUNCT, "{", t.start(), t.start(), 0, false));
        }
        // The controlled statement starts its own line so the block spans multiple lines.
        t = new Token(
          t.kind(),
          t.text(),
          t.start(),
          t.end(),
          Math.max(1, t.newlinesBefore()),
          t.atColumn0()
        );
      }
      out.add(t);
      for (int k = 0; k < closesAt[i]; k++) {
        out.add(new Token(Kind.PUNCT, "}", t.end(), t.end(), 1, false));
      }
    }
    return out;
  }

  private static int statementEnd(List<Token> in, int[] close, int start) {
    Token t = in.get(start);
    if (t.is("{")) {
      return close[start] < 0 ? scanToSemicolon(in, close, start) : close[start];
    }
    if (t.is(";")) {
      return start;
    }
    if (t.is("if") || t.is("while") || t.is("for") || t.is("switch") || t.is("synchronized")) {
      int paren = nextCodeIndex(in, start);
      if (paren < 0 || !in.get(paren).is("(") || close[paren] < 0) {
        return scanToSemicolon(in, close, start);
      }
      int body = nextCodeIndex(in, close[paren]);
      if (body < 0) {
        return close[paren];
      }
      int end = statementEnd(in, close, body);
      if (t.is("if")) {
        int els = nextCodeIndex(in, end);
        if (els >= 0 && in.get(els).is("else")) {
          int elseBody = nextCodeIndex(in, els);
          if (elseBody >= 0) {
            end = statementEnd(in, close, elseBody);
          }
        }
      }
      return end;
    }
    if (t.is("do")) {
      int body = nextCodeIndex(in, start);
      if (body < 0) {
        return start;
      }
      int end = statementEnd(in, close, body);
      int wh = nextCodeIndex(in, end);
      if (wh >= 0 && in.get(wh).is("while")) {
        int paren = nextCodeIndex(in, wh);
        if (paren >= 0 && in.get(paren).is("(") && close[paren] >= 0) {
          int semi = nextCodeIndex(in, close[paren]);
          return semi >= 0 && in.get(semi).is(";") ? semi : close[paren];
        }
      }
      return end;
    }
    return scanToSemicolon(in, close, start);
  }

  private static int scanToSemicolon(List<Token> in, int[] close, int from) {
    for (int j = from; j < in.size(); j++) {
      Token t = in.get(j);
      if ((t.is("(") || t.is("[") || t.is("{")) && close[j] > j) {
        j = close[j];
      } else if (t.is(";")) {
        return j;
      }
    }
    return in.size() - 1;
  }

  private static int[] matchAllBrackets(List<Token> in) {
    int n = in.size();
    int[] mc = new int[n];
    Arrays.fill(mc, -1);
    int[] openers = new int[n];
    int depth = 0;
    for (int i = 0; i < n; i++) {
      Token t = in.get(i);
      if (t.is("(") || t.is("[") || t.is("{")) {
        openers[depth++] = i;
      } else if ((t.is(")") || t.is("]") || t.is("}")) && depth > 0) {
        mc[openers[--depth]] = i;
      }
    }
    return mc;
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
    int name = indexOfNextCode(p);
    if (name < 0 || tokens.get(name).kind() != Kind.IDENT) {
      return false;
    }
    int paren = indexOfNextCode(name);
    return paren >= 0 && tokenSym[paren] == Sym.LPAREN;
  }

  /// Keeps an already-broken string-concatenation `+`, so a piecewise-built literal (e.g. a regex
  /// split across lines) is not collapsed back onto one line.
  private void forceConcatBreaks() {
    for (int i = 0; i < tokens.size(); i++) {
      if (breakBefore[i] && isStringConcatPlus(i)) {
        forcedBreak[i] = true;
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

  /// An already-broken `&&`/`||` spreads its break to every same-text operator in the element, so
  /// a condition wrapped at one operand wraps at every operand of that precedence.
  private void forceLogicalBreaks() {
    // An operator reached by an earlier operator's spread needs no scan of its own: the spread
    // already covered every same-text operator of the element.
    boolean[] spread = new boolean[tokens.size()];
    for (int i = 0; i < tokens.size(); i++) {
      Sym op = tokenSym[i];
      if (!breakBefore[i] || spread[i] || op != Sym.AMP_AMP && op != Sym.BAR_BAR) {
        continue;
      }
      forcedBreak[i] = true;
      for (int j = i - 1; j >= 0; j--) {
        if (isCloser(j) && matchOpen[j] >= 0) {
          j = matchOpen[j]; // a nested group is skipped whole
        } else if (endsOperatorElement(j)) {
          break;
        } else if (tokenSym[j] == op) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
        }
      }
      for (int j = i + 1; j < tokens.size(); j++) {
        if (isOpener(j) && matchClose[j] >= 0) {
          j = matchClose[j];
        } else if (endsOperatorElement(j)) {
          break;
        } else if (tokenSym[j] == op) {
          breakBefore[j] = true;
          forcedBreak[j] = true;
          spread[j] = true;
        }
      }
    }
  }

  private boolean endsOperatorElement(int i) {
    return isOpener(i) || isCloser(i) || switch (tokenSym[i]) {
      case COMMA, SEMI, QUESTION, COLON, ARROW, ASSIGN -> true;
      default -> false;
    };
  }

  private static boolean isStringLiteral(Token t) {
    return t.kind() == Kind.STRING || t.kind() == Kind.TEXT_BLOCK;
  }

  private static boolean endsOperand(Token t) {
    return t.is(")")
      || t.is("]")
      || t.kind() == Kind.STRING
      || t.kind() == Kind.TEXT_BLOCK
      || t.kind() == Kind.CHAR
      || t.kind() == Kind.NUMBER
      || t.kind() == Kind.IDENT && !t.isKeyword();
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
      if (isOpener(i)) {
        openerStack[depth++] = i;
      } else if (isCloser(i) && depth > 0) {
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
      int open = -1;
      int close = -1;
      int end = line.firstToken() + line.tokenCount();
      for (int i = line.firstToken(); i < end; i++) {
        if (!isOpener(i)) {
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
      if (depth == 0 && generic == 0 && (isCloser(j) || sym == Sym.SEMI || sym == Sym.COMMA)) {
        return j - 1;
      }
      if (j > i && breakBefore[j] || tokenWidth[j] < 0) {
        return -1;
      }
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += angleDepthDelta(j);
      } else if (isOpener(j)) {
        depth++;
      } else if (isCloser(j)) {
        depth--;
      }
    }
    return tokens.size() - 1;
  }

  private List<Integer> topLevelTernaryOperators(int i, int end) {
    List<Integer> ops = new ArrayList<>();
    int depth = 0;
    int generic = 0;
    for (int j = i; j <= end; j++) {
      if (marks.has(j, Mark.GENERIC_ANGLE)) {
        generic += angleDepthDelta(j);
      } else if (isOpener(j)) {
        depth++;
      } else if (isCloser(j)) {
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

  private List<Integer> topLevelChainDots(int i, int end) {
    int dot = -1;
    for (int j = i; j <= end && dot < 0; j++) {
      if (isOpener(j) && matchClose[j] > j) {
        j = matchClose[j];
      } else if (isCallDot(j)) {
        dot = j;
      }
    }
    List<Integer> dots = new ArrayList<>();
    while (dot >= 0 && dot <= end && isCallDot(dot)) {
      dots.add(dot);
      int close = matchClose[indexOfNextCode(indexOfNextCode(dot))];
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
      if (!isOpener(j)) {
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
      int name = indexOfNextCode(dot);
      int open = indexOfNextCode(name);
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
      if (isOpener(i)) {
        openerStack[depth++] = i;
      } else if (isCloser(i) && depth > 0) {
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
      if (close < 0 || keyword < 0 || !hasClass(keyword, Classification.PAREN_KEYWORD)) {
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
      if (isOpener(i)) {
        depth++;
      } else if (isCloser(i)) {
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
    Deque<Scope> stack = new ArrayDeque<>();
    stack.push(newScope('B', 0, 0));
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
      int firstToken = line.firstToken();
      Sym firstSym = tokenSym[firstToken];
      int indent;
      if (joinWithPrev != null && joinWithPrev[li]) {
        indent = headIndent;
      } else if (isCloser(firstToken)) {
        indent = scopeFor(stack, firstSym).closeIndent;
      } else if (top.elementOpen) {
        indent = continuationIndent(top, firstToken, prevIndent);
      } else {
        indent = top.contentIndent;
        if (top.kind == 'S' && firstSym != Sym.CASE && firstSym != Sym.DEFAULT) {
          indent += INDENT;
        }
      }
      lineIndent[li] = indent;
      prevIndent = indent;
      if (joinWithPrev == null || !joinWithPrev[li]) {
        headIndent = indent;
      }
      int bodyIndent = isCloser(firstToken) ? scopeFor(stack, firstSym).contentIndent : indent;
      for (int ci : pendingComments) {
        lineIndent[ci] = tokens.get(lines.get(ci).firstToken()).atColumn0() ? 0 : bodyIndent;
      }
      pendingComments.clear();
      walkLine(stack, line, indent);
    }
    for (int ci : pendingComments) {
      lineIndent[ci] = tokens.get(
        lines.get(ci).firstToken()
      ).atColumn0() ? 0 : stack.peek().contentIndent;
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

  /// Falls back to the innermost scope of the matching kind on unbalanced input.
  private static Scope scopeFor(Deque<Scope> stack, Sym closer) {
    char kind = closer == Sym.RBRACE ? '{' : closer == Sym.RPAREN ? '(' : '[';
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
      if (!top.elementOpen && !isCloser(i)) {
        top.elementOpen = true;
        top.elementStartIndent = indent;
        top.caseLabel = top.kind == 'S'
          && (tokenSym[i] == Sym.CASE || tokenSym[i] == Sym.DEFAULT);
      }
      analyzeToken(stack, i, indent);
    }
    endOfLine(stack, line);
  }

  private void analyzeToken(Deque<Scope> stack, int i, int indent) {
    Token t = tokens.get(i);
    Scope top = stack.peek();
    Sym sym = tokenSym[i];
    updateAnnotationState(top, t, sym);

    switch (sym) {
      case LPAREN -> {
        Scope scope = newScope('P', indent + INDENT, indent);
        int prev = indexOfPrevCode(i);
        scope.forParen = prev >= 0 && (tokenSym[prev] == Sym.FOR || tokenSym[prev] == Sym.TRY);
        stack.push(scope);
      }
      case LBRACKET -> stack.push(newScope('K', indent + INDENT, indent));
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
        if (top.kind == 'P' || top.kind == 'K') {
          resetElement(top); // for-loop sections and try-with-resources
        } else {
          closeElement(top);
        }
      }
      case COMMA -> {
        if (
          top.generic == 0 && (
            top.kind == 'P' || top.kind == 'K' || top.kind == 'A' || top.kind == 'E'
          )
        ) {
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
      case PLUS, MINUS, INCREMENT, DECREMENT, BANG, TILDE -> {
        if (isUnaryPosition(i)) {
          mark(i, Mark.UNARY);
        }
        afterContentToken(top, false);
      }
      default -> {
        if (sym == Sym.SWITCH) {
          top.sawSwitch = true;
        } else if (sym == Sym.ENUM) {
          top.sawEnum = true;
        } else if (sym == Sym.ASSERT) {
          top.sawAssert = true;
        }
        if (hasClass(i, Classification.PRIMITIVE)) {
          top.sawPrimitive = true;
        }
        boolean word = t.kind() != Kind.PUNCT;
        boolean typeToken = t.kind() == Kind.IDENT
          && (
            !hasClass(i, Classification.KEYWORD)
              || hasClass(i, Classification.PRIMITIVE)
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
      return newScope('A', indent + INDENT, indent);
    }
    if (top.sawSwitch && prevSym == Sym.RPAREN) {
      top.sawSwitch = false;
      return newScope('S', indent + INDENT, indent);
    }
    if (top.sawEnum) {
      top.sawEnum = false;
      return newScope('E', indent + INDENT, indent);
    }
    return newScope('B', indent + INDENT, indent);
  }

  private Scope newScope(char kind, int contentIndent, int closeIndent) {
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
    if (top.kind == 'P' && top.forParen) {
      afterContentToken(top, false); // enhanced-for colon, spaced both sides
      return;
    }
    if (top.kind == 'S' && top.caseLabel) {
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
    if (scope.kind == 'P' || scope.kind == 'K' || scope.kind == 'A' || scope.kind == 'E') {
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
        before.kind() == Kind.IDENT && !hasClass(beforeOpen, Classification.KEYWORD)
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
      default -> next.kind() != Kind.PUNCT && !hasClass(nextIndex, Classification.KEYWORD)
        || next.kind() == Kind.NUMBER
        || next.kind() == Kind.STRING
        || next.kind() == Kind.CHAR
        || next.kind() == Kind.TEXT_BLOCK;
    };
  }

  private boolean isUnaryPosition(int i) {
    int prevIndex = indexOfPrevCode(i);
    if (prevIndex < 0) {
      return true;
    }
    Token prev = tokens.get(prevIndex);
    if (
      prev.kind() == Kind.NUMBER
        || prev.kind() == Kind.STRING
        || prev.kind() == Kind.CHAR
        || prev.kind() == Kind.TEXT_BLOCK
    ) {
      return false;
    }
    Sym prevSym = tokenSym[prevIndex];
    if (prev.kind() == Kind.IDENT) {
      return hasClass(prevIndex, Classification.KEYWORD) && switch (prevSym) {
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
              || hasClass(i, Classification.KEYWORD) && !hasClass(i, Classification.PRIMITIVE)
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
      return nextSym == Sym.LT && hasClass(prevIndex, Classification.MODIFIER);
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
      return hasClass(prevIndex, Classification.PAREN_KEYWORD)
        || prevSym == Sym.DO
        || prevSym == Sym.ELSE
        || hasClass(prevIndex, Classification.BINARY_OPERATOR) && !marks.has(prevIndex, Mark.GENERIC_ANGLE)
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
      hasClass(prevIndex, Classification.BINARY_OPERATOR)
        || hasClass(nextIndex, Classification.BINARY_OPERATOR)
    ) {
      return true;
    }
    boolean prevWord = prev.kind() != Kind.PUNCT;
    boolean nextWord = next.kind() != Kind.PUNCT;
    if (prevWord && nextWord) {
      return true;
    }
    if (nextSym == Sym.AT) {
      return prevWord;
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
    if (
      prevSym == Sym.LPAREN
        || prevSym == Sym.LBRACKET
        || prevSym == Sym.BANG
        || prevSym == Sym.TILDE
        || prevSym == Sym.AT
        || prevSym == Sym.DOT
        || prevSym == Sym.METHOD_REF
    ) {
      return false;
    }
    if (marks.has(prevIndex, Mark.UNARY)) {
      // Keep `- -x` apart so it cannot re-lex as `--`.
      return prevSym == Sym.PLUS || prevSym == Sym.MINUS;
    }
    return true;
  }
}
