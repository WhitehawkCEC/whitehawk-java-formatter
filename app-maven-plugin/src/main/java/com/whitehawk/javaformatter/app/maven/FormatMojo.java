package com.whitehawk.javaformatter.app.maven;

import com.whitehawk.javaformatter.core.input.TrackedJavaFiles;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;
import java.util.stream.Stream;

/// Formats every git-tracked source-root `.java` file of the project in place.
@Mojo(name = "format", defaultPhase = LifecyclePhase.NONE, threadSafe = true)
@NullMarked
public final class FormatMojo extends AbstractFormatMojo {
  @Override
  Stream<Path> files(TrackedJavaFiles trackedJavaFiles, Path path) {
    return trackedJavaFiles.list(path);
  }
}
