package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/// Formats every `.java` file under the given root (build output and node_modules excluded) and
/// prints one SHA-256 over all outputs — an equivalence check between formatter variants.
@NullMarked
public final class FormatAllHash {
  public static void main(String[] args) throws Exception {
    Formatter formatter = new Formatter();
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (Stream<Path> paths = Files.walk(Path.of(args[0]))) {
      List<Path> files = paths
        .filter(p -> p.toString().endsWith(".java"))
        .filter(p -> !p.toString().contains("/target"))
        .filter(p -> !p.toString().contains("node_modules"))
        .sorted()
        .toList();
      int formatted = 0;
      for (Path p : files) {
        String source;
        try {
          source = Files.readString(p);
        } catch (IOException e) {
          continue; // non-UTF-8 fixture: skip
        }
        digest.update(formatter.format(source).getBytes(StandardCharsets.UTF_8));
        formatted++;
      }
      System.out.println(formatted + " files " + HexFormat.of().formatHex(digest.digest()));
    }
  }
}
