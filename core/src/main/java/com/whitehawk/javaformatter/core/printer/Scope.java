package com.whitehawk.javaformatter.core.printer;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Deque;

/// Mutable and pooled: [Printer#analyze] runs once per wrap iteration, so scopes are recycled
/// across passes (see [Printer#newScope]) instead of reallocated per bracket per pass.
@NullMarked
final class Scope {
  char kind; // B=block, S=switch body, E=enum body, P=paren, K=bracket, A=array initializer
  int contentIndent;
  int closeIndent;
  int elementStartIndent;
  boolean elementOpen;
  boolean forParen;
  boolean sawSwitch;
  boolean sawEnum;
  boolean sawAssert;
  boolean caseLabel;
  int generic;
  /// Branch-line indent of each open ternary in the current element, innermost first. Lazy:
  /// most scopes never hold a ternary.
  @Nullable Deque<Integer> ternaryIndents;
  // Cast detection: content so far could be a type reference.
  boolean typeLike;
  boolean sawPrimitive;
  boolean hasContent;
  boolean lastWasWord;
  // Annotation-only statement tracking: 0=start, 1=expect name part, 2=after name, -1=broken.
  int annotationState;

  Scope init(char kind, int contentIndent, int closeIndent) {
    this.kind = kind;
    this.contentIndent = contentIndent;
    this.closeIndent = closeIndent;
    elementStartIndent = 0;
    elementOpen = false;
    forParen = false;
    sawSwitch = false;
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
