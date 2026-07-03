package example;

public final class UnwrapSingleMethodCall {
  private static void check() {
    ConditionExprOp expr = ConditionExprOp
      .attrSubexpression(
        new Attr("abc"),
        ConditionExprOp.and(
          ConditionExprOp.eq(new Attr("herp"), AttributeValues.n(10)),
          ConditionExprOp.gte(new Attr("derp"), AttributeValues.s("foo"))
        )
      );
  }
}
