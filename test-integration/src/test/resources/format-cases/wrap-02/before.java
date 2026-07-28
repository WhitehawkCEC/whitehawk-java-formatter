package example;

public class WrappedTypeWithGenerics() {
  public static final Fields5<
    ClientMembersRecord,
    ClientId,
    UserId,
    ClientMemberRole,
    Instant,
    Instant> INSERT = FieldsN.of(
      TABLE,
      TABLE.CLIENT_ID,
      TABLE.MEMBER_USER_ID,
      TABLE.ROLE,
      TABLE.CREATED_AT,
      TABLE.UPDATED_AT
    );
}
