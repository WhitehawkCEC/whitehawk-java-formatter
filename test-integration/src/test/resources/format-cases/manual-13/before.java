package example;

import org.jspecify.annotations.NullMarked;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.beans.BeanIntrospector;
import jakarta.inject.Singleton;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

@Singleton
@NullMarked
public final class ObjectMapperConfig implements BeanCreatedEventListener<ObjectMapper> {


  @Override
  public ObjectMapper onCreated(BeanCreatedEvent<ObjectMapper> event) {
    return event
      .getBean()
      .rebuild()
      // Serialization
      .enable(SerializationFeature.INDENT_OUTPUT)
      .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
      .changeDefaultPropertyInclusion((var incl) -> incl.withValueInclusion(Include.ALWAYS))
      // Deserialization
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
      .disable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
      .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .build();
  }


}
