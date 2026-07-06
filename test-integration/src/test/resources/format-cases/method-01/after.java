package example;

import java.util.HashMap;
import java.util.Map;

public class MethodChainsWithGenerics {
  private Map<String, String> summary(Response response) {
    Map<String, String> result = new HashMap<>();

    response
      .<FooType> answerTo(FooBarEnum.VALUE)
      .map(Answer::getValue)
      .map(FooType::description)
      .ifPresent((var value) -> result.put("Value", value));

    return result;
  }
}
