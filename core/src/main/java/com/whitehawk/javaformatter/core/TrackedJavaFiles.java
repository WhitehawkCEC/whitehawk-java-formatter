package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;
import java.util.List;

/// Enumerates the git-tracked `.java` files of a working tree.
@NullMarked
public interface TrackedJavaFiles {
  /// Absolute paths of every git-tracked `.java` file in the working tree that contains `path`.
  List<Path> list(Path path);
}
