package com.whitehawk.javaformatter.core.printer;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Deque;

/// Mutable and pooled: recycled across passes instead of reallocated per bracket per pass.
@NullMarked
final class Scope {
  enum Kind {
    BLOCK,
    SWITCH_BODY,
    ENUM_BODY,
    PAREN,
    BRACKET,
    ARRAY_INIT,
  }

  Kind kind;
  int contentIndent;
  int closeIndent;
  int elementStartIndent;
  boolean elementOpen;
  boolean forParen;
  boolean sawSwitch;
  /// Column of the `switch` keyword, so its body indents from the keyword when the header wraps
  /// onto a continuation line rather than from that line's indent.
  int sawSwitchColumn;
  boolean sawEnum;
  boolean sawAssert;
  boolean caseLabel;
  int generic;
  /// Innermost open ternary first. Lazy — most scopes never hold one.
  @Nullable Deque<Integer> ternaryIndents;
  // Cast detection: content so far could be a type reference.
  boolean typeLike;
  boolean sawPrimitive;
  boolean hasContent;
  boolean lastWasWord;
  // Annotation-only statement tracking: 0=start, 1=expect name part, 2=after name, -1=broken.
  int annotationState;

  Scope init(Kind kind, int contentIndent, int closeIndent) {
    this.kind = kind;
    this.contentIndent = contentIndent;
    this.closeIndent = closeIndent;
    elementStartIndent = 0;
    elementOpen = false;
    forParen = false;
    sawSwitch = false;
    sawSwitchColumn = 0;
    sawEnum = false;
    sawAssert = false;
    caseLabel = false;
    generic = 0;
    if (ternaryIndents != null) {
      ternaryIndents.clear();
    }
    typeLike = true;
    sawPrimitive = false;
    hasContent = false;
    lastWasWord = false;
    annotationState = 0;
    return this;
  }
}
