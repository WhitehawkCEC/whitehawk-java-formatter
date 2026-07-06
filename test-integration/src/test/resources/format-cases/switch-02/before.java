package example;

public final class SwitchWithComment {
  public boolean allows(Resource resource, Action action) {
    if (resource instanceof TheUserResource user && user.userId().equals(userId)) {
      return switch (action) {
        case CREATE, UPDATE, DELETE, SAVE, SET ->
          user instanceof SomeReallyLong___________________________________Resource
          || user instanceof AnotherReallyLong___________________________________.Resource;
        case READ, LIST, GET -> true;
      };
    }

    return false;
  }
}
