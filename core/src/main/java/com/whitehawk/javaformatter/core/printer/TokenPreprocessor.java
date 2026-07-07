package com.whitehawk.javaformatter.core.printer;

import com.whitehawk.javaformatter.core.Kind;
import com.whitehawk.javaformatter.core.Token;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Rewrites the token stream into canonical form before any layout, so the printer only ever sees
/// normalized input: unused imports dropped, implicit lambda params given `var`, control-flow
/// bodies braced.
@NullMarked
final class TokenPreprocessor {
  private TokenPreprocessor() {}

  static List<Token> preprocess(List<Token> tokens) {
    Brackets brackets = new Brackets();
    List<Token> out = terminateEnumConstants(tokens, brackets);
    out = removeUnusedImports(out);
    out = expandLambdaParams(out);
    out = insertMissingBraces(out, brackets);
    out = parenthesizeSwitchOperands(out, brackets);
    out = parenthesizeChainOperands(out, brackets);
    return out;
  }

  /// Memoizes the last bracket-match pass on token-list identity. Stages run in sequence and each
  /// either returns its input unchanged or a fresh list, so a stage that leaves the list alone lets
  /// the next reuse the same O(n) scan instead of recomputing it.
  private static final class Brackets {
    private @Nullable List<Token> forList;
    private int[] close = {};

    int[] close(List<Token> in) {
      if (forList != in) {
        close = matchAllBrackets(in);
        forList = in;
      }
      return close;
    }
  }

  /// An expression combined with one of these reads as an operand and gets parenthesized;
  /// assignment, `->`, and the ternary `?`/`:` are excluded so `x = ..`, `case y -> ..`, and
  /// `c ? .. : ..` keep their bare form (as do `return`/`yield`, which aren't operators).
  private static final Set<String> BINARY_OPERAND_OPERATORS = Set.of(
    "||",
    "&&",
    "|",
    "&",
    "^",
    "==",
    "!=",
    "<",
    ">",
    "<=",
    ">=",
    "+",
    "-",
    "*",
    "/",
    "%",
    "<<",
    ">>",
    ">>>"
  );

  /// Canonical style wraps a `switch` expression used as a binary operand in parens, so it reads as
  /// a nested group instead of running into the operators around it.
  private static List<Token> parenthesizeSwitchOperands(List<Token> in, Brackets brackets) {
    int n = in.size();
    int[] close = brackets.close(in);
    List<int[]> wraps = new ArrayList<>(); // {switchIndex, bodyCloseInclusive}
    for (int i = 0; i < n; i++) {
      if (!in.get(i).is("switch")) {
        continue;
      }
      int paren = nextCodeIndex(in, i);
      if (paren < 0 || !in.get(paren).is("(") || close[paren] < 0) {
        continue;
      }
      int brace = nextCodeIndex(in, close[paren]);
      if (brace < 0 || !in.get(brace).is("{") || close[brace] < 0) {
        continue;
      }
      if (isBinaryOperand(in, i, close[brace])) {
        wraps.add(new int[] { i, close[brace] });
      }
    }
    if (wraps.isEmpty()) {
      return in;
    }
    return applyParenWraps(in, wraps);
  }

  private static boolean isBinaryOperand(List<Token> in, int start, int endInclusive) {
    int prev = prevCodeIndex(in, start);
    if (prev >= 0 && BINARY_OPERAND_OPERATORS.contains(in.get(prev).text())) {
      return true;
    }
    int next = nextCodeIndex(in, endInclusive);
    return next >= 0 && BINARY_OPERAND_OPERATORS.contains(in.get(next).text());
  }

  /// Canonical style wraps a multiline method chain used as a binary operand in parens, so the
  /// chain reads as a nested group indented under its own line instead of hanging off the operator.
  /// Only a chain that *follows* an operator (`&&`/`||`/..) is wrapped; a leading operand chain
  /// starts on the statement's own line and reads fine bare. A single-line chain (one that would not
  /// break anyway) keeps its bare form, as does a chain in a non-operand position (`return`/`=`/`->`/
  /// ternary branch).
  private static List<Token> parenthesizeChainOperands(List<Token> in, Brackets brackets) {
    int n = in.size();
    int[] close = brackets.close(in);
    int[] open = new int[n];
    Arrays.fill(open, -1);
    for (int i = 0; i < n; i++) {
      if (close[i] >= 0) {
        open[close[i]] = i;
      }
    }
    boolean[] callDot = new boolean[n];
    int[] nextCall = new int[n];
    boolean[] linked = new boolean[n];
    Arrays.fill(nextCall, -1);
    for (int p = 0; p < n; p++) {
      if (!isCallDot(in, close, p)) {
        continue;
      }
      callDot[p] = true;
      int paren = nextCodeIndex(in, nextCodeIndex(in, p));
      int next = nextCodeIndex(in, close[paren]);
      if (next >= 0 && isCallDot(in, close, next)) {
        nextCall[p] = next;
        linked[next] = true;
      }
    }
    List<int[]> wraps = new ArrayList<>(); // {chainStart, chainCloseInclusive}
    for (int p = 0; p < n; p++) {
      if (!callDot[p] || linked[p]) {
        continue; // not the head of a chain
      }
      int last = p;
      int size = 0;
      for (int c = p; c >= 0; c = nextCall[c]) {
        last = c;
        size++;
      }
      if (size < 2) {
        continue;
      }
      int chainEnd = close[nextCodeIndex(in, nextCodeIndex(in, last))];
      if (chainEnd < 0 || !spansLines(in, p, chainEnd)) {
        continue; // a single-line chain would not break, so needs no parens
      }
      int chainStart = chainReceiverStart(in, open, p);
      int prev = prevCodeIndex(in, chainStart);
      if (prev >= 0 && BINARY_OPERAND_OPERATORS.contains(in.get(prev).text())) {
        wraps.add(new int[] { chainStart, chainEnd });
      }
    }
    if (wraps.isEmpty()) {
      return in;
    }
    return applyParenWraps(in, wraps);
  }

  private static boolean isCallDot(List<Token> in, int[] close, int p) {
    if (!in.get(p).is(".")) {
      return false;
    }
    int name = nextCodeIndex(in, p);
    if (name < 0 || in.get(name).kind() != Kind.IDENT) {
      return false;
    }
    int paren = nextCodeIndex(in, name);
    return paren >= 0 && in.get(paren).is("(") && close[paren] >= 0;
  }

  /// Walks back from a chain's head `.call` over its receiver — a run of identifiers, `.`, matched
  /// bracket groups (`foo().bar`, `a.b[i].c`), and explicit type witnesses (`foo.<T> bar()`) — so
  /// the wrap encloses the whole operand rather than starting after a witness's closing `>`.
  private static int chainReceiverStart(List<Token> in, int[] open, int headDot) {
    int start = headDot;
    for (int r = prevCodeIndex(in, headDot); r >= 0; r = prevCodeIndex(in, r)) {
      Token t = in.get(r);
      if (t.is(")") || t.is("]")) {
        if (open[r] < 0) {
          break;
        }
        start = open[r];
        r = open[r];
      } else if (t.is(">") || t.is(">>") || t.is(">>>")) {
        int lt = typeWitnessOpen(in, r);
        int dot = lt < 0 ? -1 : prevCodeIndex(in, lt);
        if (dot < 0 || !in.get(dot).is(".")) {
          break;
        }
        start = dot;
        r = dot;
      } else if (t.kind() == Kind.IDENT || t.is(".")) {
        start = r;
      } else {
        break;
      }
    }
    return start;
  }

  /// Backward-matches the `<` opening the type witness a `>`/`>>`/`>>>` closes, or -1 if this isn't
  /// a witness close. Type-argument lists never contain `(`/`{`/`;`/`=`, so those bound the scan.
  private static int typeWitnessOpen(List<Token> in, int gt) {
    int depth = 0;
    for (int j = gt; j >= 0; j = prevCodeIndex(in, j)) {
      Token t = in.get(j);
      if (t.is(">")) {
        depth++;
      } else if (t.is(">>")) {
        depth += 2;
      } else if (t.is(">>>")) {
        depth += 3;
      } else if (t.is("<")) {
        if (--depth == 0) {
          return j;
        }
      } else if (isTypeArgBoundary(t)) {
        return -1;
      }
    }
    return -1;
  }

  /// The brackets and separators a type-argument list never contains, so reaching one means the
  /// `>` did not close a witness.
  private static boolean isTypeArgBoundary(Token t) {
    return switch (t.text()) {
      case "(", ")", "{", "}", ";", "=" -> true;
      default -> false;
    };
  }

  private static boolean spansLines(List<Token> in, int from, int toInclusive) {
    for (int i = from + 1; i <= toInclusive; i++) {
      if (in.get(i).newlinesBefore() > 0) {
        return true;
      }
    }
    return false;
  }

  private static List<Token> applyParenWraps(List<Token> in, List<int[]> wraps) {
    int n = in.size();
    int[] opensBefore = new int[n];
    int[] closesAfter = new int[n];
    for (int[] w : wraps) {
      opensBefore[w[0]]++;
      closesAfter[w[1]]++;
    }
    List<Token> out = new ArrayList<>(n + 2 * wraps.size());
    for (int i = 0; i < n; i++) {
      Token t = in.get(i);
      if (opensBefore[i] > 0) {
        // The `(` takes the switch's leading break so the switch stays on the paren's line; a
        // multiline body then isolates the `(`/`)` under the printer's usual bracket layout.
        for (int k = 0; k < opensBefore[i]; k++) {
          out.add(
            new Token(Kind.PUNCT, "(", t.start(), t.start(), t.newlinesBefore(), t.atColumn0())
          );
        }
        t = new Token(t.kind(), t.text(), t.start(), t.end(), 0, false);
      }
      out.add(t);
      for (int k = 0; k < closesAfter[i]; k++) {
        out.add(new Token(Kind.PUNCT, ")", t.end(), t.end(), 0, false));
      }
    }
    return out;
  }

  /// Canonical style terminates an enum's constant list with a trailing comma followed by `;`. A
  /// list ending in a bare trailing comma (no `;`) gets the missing `;` appended; a list already
  /// terminated by `;` but lacking the trailing comma gets one inserted before the `;`. A bare
  /// constant (no comma, no `;`) is left untouched.
  private static List<Token> terminateEnumConstants(List<Token> in, Brackets brackets) {
    int n = in.size();
    int[] close = brackets.close(in);
    Set<Integer> appendSemiAfter = new HashSet<>(); // append `;` after this comma
    Set<Integer> insertCommaBefore = new HashSet<>(); // insert `,` before this `;`
    for (int i = 0; i < n; i++) {
      if (!in.get(i).is("enum")) {
        continue;
      }
      int bodyOpen = i + 1;
      while (bodyOpen < n && !in.get(bodyOpen).is("{")) {
        bodyOpen++;
      }
      if (bodyOpen >= n || close[bodyOpen] < 0) {
        continue;
      }
      int semi = firstTopLevelSemicolon(in, close, bodyOpen);
      if (semi < 0) {
        int last = prevCodeIndex(in, close[bodyOpen]);
        if (last > bodyOpen && in.get(last).is(",")) {
          appendSemiAfter.add(last);
        }
        continue;
      }
      int prev = prevCodeIndex(in, semi);
      if (prev > bodyOpen && !in.get(prev).is(",")) {
        insertCommaBefore.add(semi);
      }
    }
    if (appendSemiAfter.isEmpty() && insertCommaBefore.isEmpty()) {
      return in;
    }
    List<Token> out = new ArrayList<>(n + appendSemiAfter.size() + insertCommaBefore.size());
    for (int i = 0; i < n; i++) {
      Token t = in.get(i);
      if (insertCommaBefore.contains(i)) {
        out.add(new Token(Kind.PUNCT, ",", t.start(), t.start(), 0, false));
        t = new Token(t.kind(), t.text(), t.start(), t.end(), 1, false);
      }
      out.add(t);
      if (appendSemiAfter.contains(i)) {
        out.add(new Token(Kind.PUNCT, ";", t.end(), t.end(), 1, false));
      }
    }
    return out;
  }

  /// The `;` that terminates an enum's constant list, or -1 if there is none: the first `;` at the
  /// top level of the enum body (nested brackets — constant bodies, argument lists — are skipped).
  private static int firstTopLevelSemicolon(List<Token> in, int[] close, int bodyOpen) {
    int end = close[bodyOpen];
    for (int i = bodyOpen + 1; i < end; i++) {
      Token t = in.get(i);
      if (t.is("{") || t.is("(") || t.is("[")) {
        if (close[i] < 0) {
          return -1;
        }
        i = close[i];
        continue;
      }
      if (t.is(";")) {
        return i;
      }
    }
    return -1;
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

    // Only the simple name an import introduces can mark it used, so track membership against that
    // small set rather than collecting every identifier in the file. A wildcard is always kept and
    // contributes no trackable name.
    String[] importName = new String[imports.size()];
    Set<String> tracked = new HashSet<>();
    for (int k = 0; k < imports.size(); k++) {
      int[] range = imports.get(k);
      if (in.get(range[1] - 1).is("*")) {
        continue;
      }
      for (int i = range[1] - 1; i > range[0]; i--) {
        Token t = in.get(i);
        if (t.kind() == Kind.IDENT && !t.isKeyword()) {
          importName[k] = t.text();
          tracked.add(t.text());
          break;
        }
      }
    }
    if (tracked.isEmpty()) {
      return in; // only wildcard or nameless imports: nothing to drop
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
        if (tracked.contains(t.text())) {
          used.add(t.text());
        }
      } else if (t.isComment()) {
        addTrackedWords(used, tracked, t.text());
      }
    }

    boolean[] drop = new boolean[n];
    boolean any = false;
    for (int k = 0; k < imports.size(); k++) {
      String name = importName[k];
      if (name != null && !used.contains(name)) {
        int[] range = imports.get(k);
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

  /// Marks used any identifier word in `text` (e.g. a javadoc `{@link Foo}`) that names an import.
  private static void addTrackedWords(Set<String> used, Set<String> tracked, String text) {
    int len = text.length();
    for (int i = 0; i < len;) {
      if (Character.isJavaIdentifierStart(text.charAt(i))) {
        int j = i + 1;
        while (j < len && Character.isJavaIdentifierPart(text.charAt(j))) {
          j++;
        }
        String word = text.substring(i, j);
        if (tracked.contains(word)) {
          used.add(word);
        }
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
      if (!isLabelPart(p)) {
        break;
      }
    }
    return true;
  }

  /// A token that continues a lambda parameter's label/qualifier list: a bare name, or the
  /// punctuation and keywords a generic or array type pattern can carry.
  private static boolean isLabelPart(Token t) {
    return switch (t.text()) {
      case ".", ",", "<", ">", ">>", ">>>", "?", "&", "[", "]", "extends", "super" -> true;
      default -> t.kind() == Kind.IDENT && !t.isKeyword();
    };
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
  private static List<Token> insertMissingBraces(List<Token> in, Brackets brackets) {
    int n = in.size();
    int[] close = brackets.close(in);
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
    if (isParenControlFlow(t)) {
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

  /// Control-flow keywords whose body follows a parenthesized clause (`if (..) ..`).
  private static boolean isParenControlFlow(Token t) {
    return switch (t.text()) {
      case "if", "while", "for", "switch", "synchronized" -> true;
      default -> false;
    };
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
      // Brackets are single-char, so a length gate skips the switch for every longer token.
      String text = in.get(i).text();
      if (text.length() != 1) {
        continue;
      }
      switch (text.charAt(0)) {
        case '(', '[', '{' -> openers[depth++] = i;
        case ')', ']', '}' -> {
          if (depth > 0) {
            mc[openers[--depth]] = i;
          }
        }
        default -> {}
      }
    }
    return mc;
  }
}
