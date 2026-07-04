Opinionated `.java` formatter with zero config.

## Before/After Examples

See [Test cases](./test-integration/src/test/resources/format-cases/).

## Usage (CLI)

```
wh-java-formatter [--changed] [PATH]
```

Formats every git-tracked `.java` file in place, rewriting each file with its formatted contents.

| Argument     | Description                                                                                                   |
| ------------ | ------------------------------------------------------------------------------------------------------------- |
| `PATH`       | A file or directory whose containing working tree is formatted. Defaults to the current directory (`.`).      |
| `--changed`  | Only format files git reports as changed (modified tracked files plus new untracked ones), skipping the rest. |

`git` must be on `PATH`; the file set is resolved via git.

```sh
# Format the whole working tree
wh-java-formatter

# Format only what you've changed
wh-java-formatter --changed
```
