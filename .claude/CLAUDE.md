Be terse and concise.

## java

All files: `@NullMarked`

## git

Pick the conventional-commit type by external-API impact, not by intent:

- `feat!` (breaking change)
  - Add a method to an interface
  - Delete a method
- `feat`
  - Add a method to a class
  - Change code to call a different method
- `refactor`
  - A class's external API is unchanged (e.g. renaming an internal/private method)

"Migrating" or "converting" something to a new method is still `feat` — it adds and/or switches
methods.

Prefer commits as small as possible: each commit does one reviewable thing and leaves the build
green.
When the same mechanical change spans many files, split it per file — one commit per file.
Likewise, adding several independent things in one commit (e.g. two unrelated new classes) is
multiple reviewable things — give each its own commit, even when they look similar or were created
together.

E.g. renaming a class is 3+ commits:

1. Create the class under the new name.
2. One commit per caller migrated to the new class.
3. Delete the old class.

Changing a method parameter or field type (e.g. a raw `Ulid` to a wrapper id) is the same
add/migrate/delete shape. Every commit must still compile — so when a naive per-file split
wouldn't, restructure the change so it can rather than collapsing it into one big commit. Add a new
overload (or a `default` interface method) so callers transition in smaller compiling chunks:

1. Add an overload taking the new type alongside the old; the old delegates to the new. One commit
   per declaring file.
2. One commit per caller switched to the new overload.
3. Delete the old overload. One commit per declaring file (`feat!`).

Avoid including unrelated untracked files in commits.
