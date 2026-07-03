package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;
import java.util.stream.Stream;

/// Enumerates the git-tracked `.java` files of a working tree.
@NullMarked
public interface TrackedJavaFiles {
  /// A stream of absolute paths of every git-tracked `.java` file in the working tree that
  /// contains `path`.
  Stream<Path> list(Path path);
}
