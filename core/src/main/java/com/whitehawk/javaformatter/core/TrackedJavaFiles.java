package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;
import java.util.stream.Stream;

/// Enumerates the git-tracked `.java` files of a working tree that live under a Maven source root
/// (`**/src/main/java/**.java` or `**/src/test/java/**.java`).
@NullMarked
public interface TrackedJavaFiles {
  /// A stream of absolute paths of every git-tracked source-root `.java` file in the working tree
  /// that contains `path`.
  ///
  /// The stream is lazy and may hold an open handle to the repository; callers must close it
  /// (e.g. with try-with-resources), as with [java.nio.file.Files#list].
  Stream<Path> list(Path path);

  /// A stream of absolute paths of every source-root `.java` file that git reports as changed in
  /// the working tree that contains `path`: modified tracked files plus newly-created untracked
  /// files, minus anything git ignores. Files git reports but that no longer exist (e.g.
  /// deletions) are omitted.
  ///
  /// The stream is lazy and may hold an open handle to the repository; callers must close it
  /// (e.g. with try-with-resources), as with [java.nio.file.Files#list].
  Stream<Path> changed(Path path);
}
