package example;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public class Dynamodb {
  static AttributeValue toAttributeValue(@Nullable Object value) {
    return switch (value) {
      case null -> AttributeValue.fromNul(true);
      case Map<?, ?> map -> {
        Map<String, AttributeValue> converted = new LinkedHashMap<>();
        map.forEach((key, child) -> converted.put((String) key, toAttributeValue(child)));
        yield AttributeValue.fromM(converted);
      }
      case List<?> list -> {
        List<AttributeValue> converted = new ArrayList<>(list.size());
        list.forEach(child -> converted.add(toAttributeValue(child)));
        yield AttributeValue.fromL(converted);
      }
      case String string -> AttributeValue.fromS(string);
      case Boolean bool -> AttributeValue.fromBool(bool);
      case Number number -> AttributeValue.fromN(number.toString());
      default -> throw new IllegalStateException("Unexpected value: " + value);
    };
  }
}
