package example;

import java.util.Objects;

public final class AssignmentWhenTypeAlreadyWrapped {
  private static void check(WhateverInfo info) {
    var builder = Whatever
      .newBuilder()
      .setId(Objects.requireNonNullElse(info.id(), ""));
  }
}
