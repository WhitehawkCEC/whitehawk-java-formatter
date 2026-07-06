package example;

public final class OpWithChainedMethods extends Helper {
  private boolean isActiveEntity(WhateverId id, int entityId) {
    return entities
      .exec(
        new Repo__foos__0__bars__0__entities.ReadRequest(
          id.fooId(),
          id.barId(),
          String.valueOf(entityId)
        )
      )
      .found()
      .isPresent()
      || mappedEntities
      .repo()
      .list(id)
      .anyMatch((var e) -> e.companyId() == entityId);
  }
}
