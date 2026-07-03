package com.whitehawk.javaformatter.core;

import com.whitehawk.javaformatter.core.JavaLexer.Token;

import jakarta.inject.Singleton;

import org.jspecify.annotations.NullMarked;

import java.util.List;

@Singleton
@NullMarked
public final class Formatter {
  public String format(String source) {
    List<Token> tokens = JavaLexer.lex(source);
    if (tokens.isEmpty()) {
      return "";
    }
    return new Printer(tokens).print();
  }
}
