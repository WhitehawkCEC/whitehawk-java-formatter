package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

@NullMarked
public enum Kind {
  IDENT,
  NUMBER,
  STRING,
  CHAR,
  TEXT_BLOCK,
  LINE_COMMENT,
  BLOCK_COMMENT,
  PUNCT,
  ;
}
