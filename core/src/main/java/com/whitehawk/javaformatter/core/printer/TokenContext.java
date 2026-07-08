package com.whitehawk.javaformatter.core.printer;

import com.whitehawk.javaformatter.core.ArraySmallEnumSet;
import com.whitehawk.javaformatter.core.Kind;
import com.whitehawk.javaformatter.core.Sym;
import com.whitehawk.javaformatter.core.Token;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/// Per-token facts derived once from the token stream and invariant thereafter: symbol, class,
/// width, bracket matches, nearest-code-neighbour indices, and call-chain metadata. [Printer]'s
/// layout passes read these many times per run, so they are computed up front rather than
/// rescanned on every pass.
///
/// The attributes and the code that fills them are kept apart: [Builder] does all the work and this
/// type is an immutable holder whose constructor only copies the finished arrays. Build [#from].
@NullMarked
public final class TokenContext {
  private static final int TYPE_ARG_SCAN_LIMIT = 500;

  final List<Token> tokens;
  /// -1 for a multiline token (text block, block comment).
  final int[] tokenWidth;
  final ArraySmallEnumSet<Classification> tokenClasses;
  final Sym[] tokenSym;
  /// `matchClose` at each opener, `matchOpen` at each closer; -1 elsewhere and at unbalanced brackets.
  final int[] matchOpen;
  final int[] matchClose;
  /// At a closer, the opener it closes; -1 at top level.
  final int[] enclosingOpen;
  /// Index of the nearest non-comment token before/after each position, or -1. Comment positions
  /// never change, so these are computed once rather than rescanned on every pass.
  final int[] prevCodeIndex;
  final int[] nextCodeIndex;
  /// Call-chain metadata: `callDot[i]` marks a `.name(` call dot, `callParen[i]` its argument-list
  /// `(` (-1 otherwise). Precomputed once because the print loop rescans chains — and any type
  /// witness — every pass.
  final boolean[] callDot;
  final int[] callParen;
  /// Prefix sums giving a multiline-token count over any range; a multiline token counts as one.
  final int[] prefixMultiline;

  private TokenContext(Builder b) {
    this.tokens = b.tokens;
    this.tokenWidth = b.tokenWidth;
    this.tokenClasses = b.tokenClasses;
    this.tokenSym = b.tokenSym;
    this.matchOpen = b.matchOpen;
    this.matchClose = b.matchClose;
    this.enclosingOpen = b.enclosingOpen;
    this.prevCodeIndex = b.prevCodeIndex;
    this.nextCodeIndex = b.nextCodeIndex;
    this.callDot = b.callDot;
    this.callParen = b.callParen;
    this.prefixMultiline = b.prefixMultiline;
  }

  /// Preprocesses the raw stream into canonical form, then derives the metadata over it; the
  /// resulting [#tokens] are the normalized tokens the printer renders.
  public static TokenContext from(List<Token> tokens) {
    return new TokenContext(new Builder(TokenPreprocessor.preprocess(tokens)).build());
  }

  // --- queries: read-only structural lookups the layout passes reuse ---

  int indexOfPrevCode(int i) {
    return codeIndex(prevCodeIndex, i);
  }

  int indexOfNextCode(int i) {
    return codeIndex(nextCodeIndex, i);
  }

  @Nullable
  Token prevCode(int i) {
    int idx = indexOfPrevCode(i);
    return idx < 0 ? null : tokens.get(idx);
  }

  @Nullable
  Token nextCode(int i) {
    int idx = indexOfNextCode(i);
    return idx < 0 ? null : tokens.get(idx);
  }

  /// Reads the [#callDot] table built once by [Builder]; safe for -1.
  boolean isCallDot(int p) {
    return p >= 0 && callDot[p];
  }

  boolean closesAnnotation(int closeIndex) {
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

  int angleDepthDelta(int i) {
    return angleDepthDelta(tokenSym, i);
  }

  int scanTypeArguments(int open) {
    return scanTypeArguments(tokens, tokenSym, tokenClasses, open);
  }

  // --- shared helpers: usable both while building (no instance yet) and at runtime ---

  private static int codeIndex(int[] index, int i) {
    return i < 0 ? -1 : index[i];
  }

  private static int angleDepthDelta(Sym[] tokenSym, int i) {
    return switch (tokenSym[i]) {
      case LT -> 1;
      case GT -> -1;
      case GT_GT -> -2;
      case GT_GT_GT -> -3;
      default -> 0;
    };
  }

  private static int scanTypeArguments(
    List<Token> tokens,
    Sym[] tokenSym,
    ArraySmallEnumSet<Classification> tokenClasses,
    int open
  ) {
    int depth = 1;
    for (int i = open + 1; i < tokens.size() && i - open < TYPE_ARG_SCAN_LIMIT; i++) {
      Token t = tokens.get(i);
      if (t.isComment()) {
        continue;
      }
      switch (tokenSym[i]) {
        case LT -> depth++;
        case GT, GT_GT, GT_GT_GT -> {
          depth += angleDepthDelta(tokenSym, i);
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

  /// Fills the metadata arrays from the token stream, then hands them to the [TokenContext]
  /// constructor. Mutating work lives here so the context itself is a plain immutable holder.
  private static final class Builder {
    private final List<Token> tokens;
    private final int[] tokenWidth;
    private final ArraySmallEnumSet<Classification> tokenClasses;
    private final Sym[] tokenSym;
    private final int[] matchOpen;
    private final int[] matchClose;
    private final int[] enclosingOpen;
    private final int[] prevCodeIndex;
    private final int[] nextCodeIndex;
    private final boolean[] callDot;
    private final int[] callParen;
    private final int[] prefixMultiline;

    Builder(List<Token> tokens) {
      int n = tokens.size();
      this.tokens = tokens;
      this.tokenWidth = new int[n];
      this.tokenClasses = new ArraySmallEnumSet<>(Classification.class, n);
      this.tokenSym = new Sym[n];
      this.matchOpen = new int[n];
      this.matchClose = new int[n];
      this.enclosingOpen = new int[n];
      this.prevCodeIndex = new int[n];
      this.nextCodeIndex = new int[n];
      this.callDot = new boolean[n];
      this.callParen = new int[n];
      this.prefixMultiline = new int[n + 1];
    }

    Builder build() {
      classifyTokens();
      linkCodeNeighbours();
      matchBrackets();
      linkCallChains();
      return this;
    }

    /// Per-token symbol, class, width, running multiline count, and the backward code-neighbour link.
    private void classifyTokens() {
      int lastCode = -1;
      for (int i = 0; i < tokens.size(); i++) {
        Token t = tokens.get(i);
        String text = t.text();
        tokenWidth[i] = text.indexOf('\n') >= 0 ? -1 : text.length();
        prefixMultiline[i + 1] = prefixMultiline[i] + (tokenWidth[i] < 0 ? 1 : 0);
        Classification.classify(tokenClasses, i, t);
        tokenSym[i] = Sym.of(text);
        prevCodeIndex[i] = lastCode;
        if (!t.isComment()) {
          lastCode = i;
        }
      }
    }

    private void linkCodeNeighbours() {
      int nextCode = -1;
      for (int i = tokens.size() - 1; i >= 0; i--) {
        nextCodeIndex[i] = nextCode;
        if (!tokens.get(i).isComment()) {
          nextCode = i;
        }
      }
    }

    private void matchBrackets() {
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

    /// The chain lookups read only token syms/kinds and the fixed bracket structure, so they are
    /// invariant. Tabulating them once lets the print loop's repeated chain rescans reuse the table
    /// instead of re-walking each `.name(` — and re-scanning any type witness — every pass.
    private void linkCallChains() {
      Arrays.fill(callParen, -1);
      for (int p = 0; p < tokens.size(); p++) {
        if (tokenSym[p] != Sym.DOT) {
          continue;
        }
        int name = callName(p);
        if (name < 0 || tokens.get(name).kind() != Kind.IDENT) {
          continue;
        }
        int paren = codeIndex(nextCodeIndex, name);
        if (paren >= 0 && tokenSym[paren] == Sym.LPAREN) {
          callDot[p] = true;
          callParen[p] = paren;
        }
      }
    }

    /// The method-name token of a call `.name(`, skipping an explicit type witness (`.<T> name(`),
    /// or -1 when the witness angle brackets don't close.
    private int callName(int dot) {
      int name = codeIndex(nextCodeIndex, dot);
      if (name >= 0 && tokenSym[name] == Sym.LT) {
        int witnessEnd = scanTypeArguments(tokens, tokenSym, tokenClasses, name);
        name = witnessEnd < 0 ? -1 : codeIndex(nextCodeIndex, witnessEnd);
      }
      return name;
    }
  }
}
