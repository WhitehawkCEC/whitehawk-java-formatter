package com.whitehawk.javaformatter.core;

import com.whitehawk.javaformatter.core.lexer.JavaLexer;
import com.whitehawk.javaformatter.core.printer.Printer;

import jakarta.inject.Singleton;

import org.jspecify.annotations.NullMarked;

import java.util.List;

@Singleton
@NullMarked
public final class Formatter {
  public String format(String source) {
    List<Token> tokens = new JavaLexer(source).tokenize();
    if (tokens.isEmpty()) {
      return "";
    }
    return new Printer(tokens).print();
  }
}
