package com.whitehawk.javaformatter.adapter.eclipse.jgit;

import com.whitehawk.javaformatter.core.TrackedJavaFiles;

import jakarta.inject.Singleton;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// [TrackedJavaFiles] backed by JGit's index (`DirCache`).
@Singleton
@NullMarked
public final class TrackedJavaFilesJgit implements TrackedJavaFiles {
  @Override
  public List<Path> list(Path path) {
    try (Git git = Git.open(path.toFile())) {
      Repository repository = git.getRepository();
      Path workTree = repository.getWorkTree().toPath();
      DirCache index = repository.readDirCache();

      List<Path> files = new ArrayList<>();
      for (int i = 0; i < index.getEntryCount(); i++) {
        DirCacheEntry entry = index.getEntry(i);
        if (entry.getPathString().endsWith(".java")) {
          files.add(workTree.resolve(entry.getPathString()));
        }
      }
      return List.copyOf(files);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read git index at " + path, e);
    }
  }
}
