package example;

import java.util.List;
import java.util.stream.Stream;

public record WrappingWithImplements() {
  private record CompaniesResult(SerializedData json, List<FooBarHerpDerpCompanyId> ids)
    implements Companies {
    public Stream<FooBarHerpDerpCompanyId> companyIds() {
      return ids.stream();
    }
  }
}
