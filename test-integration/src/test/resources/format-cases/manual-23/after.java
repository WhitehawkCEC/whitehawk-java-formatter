package example;

import org.assertj.core.api.Assertions;

import java.util.Map;

public final class WrappedMap {
  void someTest() {
    Assertions
      .assertThat(Whatever.names())
      .isEqualTo(
        Map.of(
          "#n_0",
          "a",
          "#n_1",
          "b",
          "#n_2",
          "c"
        )
      );
  }
}
