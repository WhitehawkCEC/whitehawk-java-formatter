package com.whitehawk.javaformatter.integration;

import com.whitehawk.javaformatter.core.Formatter;
import io.avaje.inject.BeanScope;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class FormatterIntegrationTest {
  @Test
  void beanScopeWiresCoreComponents() {
    try (BeanScope scope = BeanScope.builder().build()) {
      assertThat(scope.get(Formatter.class)).isNotNull();
    }
  }
}
