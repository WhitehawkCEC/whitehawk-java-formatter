package example;

import java.util.Map;

public final class TrailingComment {
  static final Map<String, Integer> SOME_KIND_OF_MAPPING = Map.ofEntries(
    Map.entry("Abc", 1),
    Map.entry("Def", 1),
    Map.entry("Foo", 1),
    Map.entry("Bar", 1),
    Map.entry("Herp", 1),
    Map.entry("Derp", 2), // this line is somehow special
    Map.entry("Okay", 1),
    Map.entry("Sure", 3)
  );

}
