package com.whitehawk.javaformatter.core.printer;

import org.jspecify.annotations.NullMarked;

/// A per-token role resolved by the analysis pass.
@NullMarked
enum Mark {
  GENERIC_ANGLE,
  WILDCARD,
  UNARY,
  CAST_CLOSE,
  COLON_NO_SPACE_BEFORE,
  /// Type-argument disambiguation already ran at this `<`; a failed scan is never retried.
  ANGLE_SCANNED;
}
