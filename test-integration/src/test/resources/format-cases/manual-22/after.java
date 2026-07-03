package example;

public final class ReallyLongCreationWithBuildersAndMethodChaining {
  public static final String GSI_1_INDEX_NAME = "gsi-1";
  public static final String GSI_2_INDEX_NAME = "gsi-2";
  public static final String GSI_3_INDEX_NAME = "gsi-3";
  public static final String GSI_4_INDEX_NAME = "gsi-4";

  public static final String TYPE_INDEX_NAME = "type";

  public static final String HASH = "h";
  public static final String RANGE = "r";

  public static final String GSI_1_HASH = "g1h";
  public static final String GSI_1_RANGE = "g1r";

  public static final String GSI_2_HASH = "g2h";
  public static final String GSI_2_RANGE = "g2r";

  public static final String GSI_3_HASH = "g3h";
  public static final String GSI_3_RANGE = "g3r";

  public static final String GSI_4_HASH = "g4h";
  public static final String GSI_4_RANGE = "g4r";

  public static final String TYPE = "t";
  public static final String DATA = "d";

  public static final String CREATED_AT = "c";
  public static final String UPDATED_AT = "u";

  CreateTableRequest createTableRequest() {
    Projection dataAndTypeOnly = Projection
      .builder()
      .projectionType(ProjectionType.INCLUDE)
      .nonKeyAttributes(TYPE, DATA, CREATED_AT, UPDATED_AT)
      .build();
    return CreateTableRequest
      .builder()
      .tableName(tableName)
      .attributeDefinitions(
        def(HASH, ScalarAttributeType.S),
        def(RANGE, ScalarAttributeType.S),
        def(TYPE, ScalarAttributeType.S),
        def(GSI_1_HASH, ScalarAttributeType.S),
        def(GSI_1_RANGE, ScalarAttributeType.S),
        def(GSI_2_HASH, ScalarAttributeType.S),
        def(GSI_2_RANGE, ScalarAttributeType.S),
        def(GSI_3_HASH, ScalarAttributeType.S),
        def(GSI_3_RANGE, ScalarAttributeType.S),
        def(GSI_4_HASH, ScalarAttributeType.S),
        def(GSI_4_RANGE, ScalarAttributeType.S)
      )
      .keySchema(
        keySchema(HASH, KeyType.HASH),
        keySchema(RANGE, KeyType.RANGE)
      )
      .globalSecondaryIndexes(
        GlobalSecondaryIndex
          .builder()
          .indexName(TYPE_INDEX_NAME)
          .keySchema(
            keySchema(TYPE, KeyType.HASH)
          )
          .projection(
            Projection
              .builder()
              .projectionType(ProjectionType.ALL)
              .build()
          )
          .build(),
        GlobalSecondaryIndex
          .builder()
          .indexName(GSI_1_INDEX_NAME)
          .keySchema(
            keySchema(GSI_1_HASH, KeyType.HASH),
            keySchema(GSI_1_RANGE, KeyType.RANGE)
          )
          .projection(dataAndTypeOnly)
          .build(),
        GlobalSecondaryIndex
          .builder()
          .indexName(GSI_2_INDEX_NAME)
          .keySchema(
            keySchema(GSI_2_HASH, KeyType.HASH),
            keySchema(GSI_2_RANGE, KeyType.RANGE)
          )
          .projection(dataAndTypeOnly)
          .build(),
        GlobalSecondaryIndex
          .builder()
          .indexName(GSI_3_INDEX_NAME)
          .keySchema(
            keySchema(GSI_3_HASH, KeyType.HASH),
            keySchema(GSI_3_RANGE, KeyType.RANGE)
          )
          .projection(dataAndTypeOnly)
          .build(),
        GlobalSecondaryIndex
          .builder()
          .indexName(GSI_4_INDEX_NAME)
          .keySchema(
            keySchema(GSI_4_HASH, KeyType.HASH),
            keySchema(GSI_4_RANGE, KeyType.RANGE)
          )
          .projection(dataAndTypeOnly)
          .build()
      )
      .streamSpecification(
        StreamSpecification
          .builder()
          .streamEnabled(true)
          .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
          .build()
      )
      .billingMode(BillingMode.PAY_PER_REQUEST)
      .build();
  }
}
