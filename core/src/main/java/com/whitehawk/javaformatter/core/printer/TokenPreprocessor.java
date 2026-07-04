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
    return insertMissingBraces(
      expandLambdaParams(removeUnusedImports(terminateEnumConstants(tokens)))
    );
  }

  /// Canonical style terminates an enum's constant list with a trailing comma followed by `;`. A
  /// list ending in a bare trailing comma (no `;`) gets the missing `;` appended; a list already
  /// terminated by `;` but lacking the trailing comma gets one inserted before the `;`. A bare
  /// constant (no comma, no `;`) is left untouched.
  private static List<Token> terminateEnumConstants(List<Token> in) {
    int n = in.size();
    int[] close = matchAllBrackets(in);
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
}
