package example;

import java.util.regex.Pattern;

public final class IgnoreIntentionallyWrappedConcat {

  public static final Pattern REGEX = Pattern.compile(
    ""
      + "([A-Z]{2})"
      + "\\."
      + "([A-Z]\\d)"
      + "-"
      + "([^.]++\\.[^.]++\\.[^.]++)"
  );
}
