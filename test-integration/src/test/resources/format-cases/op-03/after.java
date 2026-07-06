package example;

public final class OpNested extends Helper {
  public String check(SomethingAccess somethingAccess) {
    return wrapped(
      "User: "
        + somethingAccess.user().id()
        + ", email: "
        + (
            (
              somethingAccess.user().email() != null
                && somethingAccess.user().email().length() > 0
            )
              ? somethingAccess.user().email()
              : "UNKNOWN"
        )
        + " was activated"
    );
  }
}
