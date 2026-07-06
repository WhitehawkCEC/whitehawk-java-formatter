package example;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

@NullMarked
public record SomeKindOfEntityWithMultipleAnnotations(
  String id,
  String name,
  @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable double score,
  @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String yep,
  @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String date
) {}
