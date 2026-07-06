package example;

import java.util.List;
import java.util.ArrayList;

public final class SwitchWithComment {
  private boolean check(Something value) {
    List<String> result = new ArrayList<>();
    switch (value) {
      case ABC:
        // fallthrough
      case DEF:
        result.put("okay");
        break;
      case GHI:
        result.put("bar");
        result.put("baz");
        break;
      default:
        throw new AssertionError("bleh");
    }
    return result;

  }
}
