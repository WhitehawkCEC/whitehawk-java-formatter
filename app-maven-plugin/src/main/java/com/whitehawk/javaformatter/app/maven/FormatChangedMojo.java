package com.whitehawk.javaformatter.app.maven;

import com.whitehawk.javaformatter.core.TrackedJavaFiles;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;
import java.util.stream.Stream;

/// Formats the source-root `.java` files git reports as changed in place: modified tracked files
/// plus new untracked ones.
@Mojo(name = "format-changed", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
@NullMarked
public final class FormatChangedMojo extends AbstractFormatMojo {
  @Override
  Stream<Path> files(TrackedJavaFiles trackedJavaFiles, Path path) {
    return trackedJavaFiles.changed(path);
  }
}
