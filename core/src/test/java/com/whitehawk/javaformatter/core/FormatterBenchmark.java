package com.whitehawk.javaformatter.core;

import com.whitehawk.javaformatter.core.lexer.JavaLexer;
import com.whitehawk.javaformatter.core.printer.Printer;

import org.jspecify.annotations.NullMarked;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// Formats a large real-world source file ([Printer]'s own source by default; override with
/// `-Dformatter.bench.input=<path>`). `lex` and `print` isolate the two phases of `format`.
@NullMarked
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FormatterBenchmark {
  private String source = "";
  private List<Token> tokens = List.of();

  @Setup
  public void setup() throws IOException {
    Path input = Path.of(
      System.getProperty(
        "formatter.bench.input",
        "core/src/main/java/com/whitehawk/javaformatter/core/printer/Printer.java"
      )
    );
    source = Files.readString(input);
    tokens = new JavaLexer(source).tokenize();
  }

  @Benchmark
  public List<Token> lex() {
    return new JavaLexer(source).tokenize();
  }

  @Benchmark
  public String print() {
    return new Printer(tokens).print();
  }

  @Benchmark
  public String format() {
    return new Formatter().format(source);
  }
}
