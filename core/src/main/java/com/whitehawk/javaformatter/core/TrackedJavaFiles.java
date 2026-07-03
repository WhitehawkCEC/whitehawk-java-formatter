package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;
import java.util.stream.Stream;

/// Enumerates the git-tracked `.java` files of a working tree.
@NullMarked
public interface TrackedJavaFiles {
  /// A stream of absolute paths of every git-tracked `.java` file in the working tree that
  /// contains `path`.
  ///
  /// The stream is lazy and may hold an open handle to the repository; callers must close it
  /// (e.g. with try-with-resources), as with [java.nio.file.Files#list].
  Stream<Path> list(Path path);
}
