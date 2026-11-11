# temper

Tools that turn working conventions into checks, and AI skills that help people learn and stay engaged rather than offload their thinking.

The first tools, `temper.okf.*`, check and maintain Markdown documents carrying
[OKF](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
+ RDF YAML frontmatter, driven by one EDN schema. They have a data-returning
API, and golden tests hold them to the behaviour of the scripts they replace.

- [Namespaces](#namespaces)
- [Command line](#command-line)
- [Configuration](#configuration)
- [Exit codes](#exit-codes)
- [Public functions](#public-functions)
- [Schema keys](#schema-keys)
- [Use as a dependency](#use-as-a-dependency)
- [Differences from the original scripts](#differences-from-the-original-scripts)
- [Carried-over behaviour](#carried-over-behaviour)
- [Security notes](#security-notes)
- [Testing](#testing)

## Namespaces

| Namespace | Replaces | Does |
|---|---|---|
| [`temper.okf.validate`](src/temper/okf/validate.clj) | `tools/validate_metadata.clj` | Checks each document's frontmatter, the bundle root `index.md`, and the JSON-LD context |
| [`temper.okf.links`](src/temper/okf/links.clj) | `tools/check_links.bb` | Checks relative links, anchors and access markers on internal URLs; optional glossary sweep |
| [`temper.okf.maturity`](src/temper/okf/maturity.clj) | `tools/gen_maturity_table.bb` | Renders `:review-maturity` into marked regions of prose files, or checks they are current |
| [`temper.okf.new-doc`](src/temper/okf/new_doc.clj) | `tools/new_doc.bb` | Writes a new document with valid frontmatter |
| [`temper.okf.config`](src/temper/okf/config.clj) | — | Path resolution, schema reading, configuration errors |
| [`temper.okf.schema`](src/temper/okf/schema.clj) | — | Views derived from the schema (maturity ladder, hints) |
| [`temper.okf.cli`](src/temper/okf/cli.clj) | — | Flag parsing and process exit for each `-main` |

No namespace reads the environment, touches the filesystem, prints or exits
when it is loaded. All of that happens inside `run` and `-main`.

## Command line

Each tool namespace has a `-main`. With this library on the classpath:

```sh
bb -m temper.okf.validate [flags]
bb -m temper.okf.links    [flags]
bb -m temper.okf.maturity [flags]
bb -m temper.okf.new-doc  [flags]
```

This repo's [`bb.edn`](bb.edn) also defines tasks `validate`, `links`,
`maturity` and `new-doc` that call the same `-main` functions (`bb validate
--docs-dir ...`); the [dependency snippet](#use-as-a-dependency) shows the same
tasks for a consuming project.

### Flags

| Tool | Flag | Meaning |
|---|---|---|
| all four | `--docs-dir DIR` | Docs directory. See [Configuration](#configuration). |
| all four | `--schema FILE` | Schema file. See [Configuration](#configuration). |
| `temper.okf.links` | `--quiet` | Omit `PASS` lines for files with no findings |
| `temper.okf.links` | `--glossary` | Also report glossary headings no document links to (warnings only; never changes the exit code) |
| `temper.okf.maturity` | `--check` | Report stale regions (`STALE`) and exit 1 instead of rewriting them |
| `temper.okf.maturity` | `--targets a.md,b.md` | Files to render into, comma-separated |
| `temper.okf.new-doc` | `--type T` | Required. Warns, but proceeds, if `T` is not in `:types` |
| `temper.okf.new-doc` | `--title T` | Required |
| `temper.okf.new-doc` | `--path P` | Required. Must start with `<docs-dir>/` |
| `temper.okf.new-doc` | `--description D` | Optional |
| `temper.okf.new-doc` | `--tags a,b` | Optional, comma-separated; written as a flow list |
| `temper.okf.new-doc` | `--source url,url` | Optional, comma-separated; one value is written as a scalar, several as a block list |
| `temper.okf.new-doc` | `--force` | Overwrite an existing file. Takes the next argument as its value only if that argument does not start with `--` |
| `temper.okf.new-doc` | `--ai-assisted M` | Marks the document AI-assisted; every key in `:provenance-required` then becomes a required flag |
| `temper.okf.new-doc` | `--session S`, `--tools a,b`, `--agents-skills url,url` | Provenance values |

Argument parsing:

- `temper.okf.validate`, `temper.okf.links`, `temper.okf.maturity`: a value flag takes the next
  argument; arguments not listed above are ignored, as the original scripts
  ignored all arguments they did not recognise.
- `temper.okf.new-doc`: the original script's parser, except for `--force` (see
  [difference 6](#differences-from-the-original-scripts)). Every other
  `--name` takes the next argument as its value, whatever it is, and a
  non-flag argument is skipped together with the argument after it. Any
  `--name value` is visible to the `:provenance-required` check, so a schema
  can require a flag this table does not list.

### Output

| Stream | Content |
|---|---|
| stdout | The report, byte-for-byte what the original script printed |
| stderr | Configuration errors (exit 2), one message per line, prefixed with the original script's name (`validate_metadata:`, `check_links:`, `gen_maturity_table:`, `new_doc:`) |

## Configuration

### Paths

| Setting | Option / flag | Env var | Default |
|---|---|---|---|
| Docs directory | `:docs-dir` / `--docs-dir` | `OKF_DOCS_DIR` | `docs` |
| Schema file | `:schema` / `--schema` | `OKF_SCHEMA` | `<docs-dir>/metadata-schema.edn` |
| Maturity targets | `:targets` / `--targets` | `OKF_MATURITY_TARGETS` | `README.md`, `CLAUDE.md`, `<docs-dir>/README.md` |

- Precedence: option or flag, then env var, then default. An empty string is
  treated as unset at every level.
- The default schema path uses whichever docs directory won.
- Relative paths resolve against the process working directory. So do the
  schema's path-valued keys (`:bundle-root`, `:context-file`,
  `:link-policy :verbatim-dirs / :untracked-dirs / :glossary`): they are
  relative to the repo root, not to the docs directory.
- Explicit maturity targets must be existing files (exit 2 otherwise). Default
  targets that do not exist are skipped.

### Environment variables

| Variable | Read by | Meaning |
|---|---|---|
| `OKF_DOCS_DIR` | all four tools | Docs directory |
| `OKF_SCHEMA` | all four tools | Schema file |
| `OKF_MATURITY_TARGETS` | `temper.okf.maturity` | Comma-separated target files; blank entries are dropped |
| `OKF_GOLDEN_REAL_BUNDLE` | tests only | working tree of a real OKF bundle for the real-bundle golden cases (default `/tmp/okf-merge`) |
| `OKF_GOLDEN_KEEP` | tests only | When set, keep the golden-test work directory and print its path |
| `OKF_TEST_BB` | tests only | babashka executable for child processes (default `bb` on `PATH`) |

## Exit codes

| Code | `temper.okf.validate` | `temper.okf.links` | `temper.okf.maturity` | `temper.okf.new-doc` |
|---|---|---|---|---|
| 0 | No document errors and no context gaps (warnings allowed) | No document errors (warnings allowed) | Write mode, or `--check` with nothing stale | File written |
| 1 | A document has an error, or a known key lacks a JSON-LD mapping | A document has an error | `--check` found a stale region | Usage error (missing or invalid flag, file exists) |
| 2 | Unreadable configuration | Unreadable configuration | Unreadable configuration | Unreadable schema |

Unreadable configuration means, per tool, checked in this order:

- `temper.okf.validate`: docs directory missing or unreadable; schema unreadable or
  not valid EDN; schema lacks `:review-staleness`, then `:provenance-required`,
  `:provenance-unknown`, `:context-file`; `:context-file` set but not a readable
  file.
- `temper.okf.links`: docs directory missing or unreadable; schema unreadable or not
  valid EDN; `:link-policy` absent, lacking `:internal-marker`,
  `:internal-hosts`, `:verbatim-dirs` or `:untracked-dirs`, or with a blank
  `:internal-marker`.
- `temper.okf.maturity`: schema unreadable or not valid EDN; an explicit target is not
  a file.
- `temper.okf.new-doc`: schema unreadable or not valid EDN.

The doctrine, from the original scripts: an absent key is an error, because a
default that means "check nothing" turns a rename or typo into a green run; a
present-but-empty key is a deliberate opt-out. Not every key is held to it —
see [Carried-over behaviour](#carried-over-behaviour).

An uncaught exception (for example invalid YAML in `index.md`) exits 1 with
babashka's error report on stderr, as in the originals.

## Public functions

Every `run` prints nothing and never exits; `report` renders a result as the
original's stdout; `-main` parses flags, calls `run`, prints `report`, and
exits. A configuration error makes `run` return
`{:exit 2 :config-errors [line ...]}`, and `report` return `""`. The docstrings
give the full result shapes.

### `temper.okf.validate`

| Function | Returns |
|---|---|
| `(run)`, `(run {:docs-dir :schema})` | `{:exit :results [{:path :errors :warnings}] :documents :errors :warnings :context-file :context-gaps :docs-dir :schema-file}` — `:errors` counts documents with at least one error; `:warnings` counts warnings |
| `(report result)` | stdout text |
| `(load-config opts)` | Resolved paths, schema and derived hints; ends the run with exit 2 on bad configuration |
| `(validate-concept cfg path content)` | `{:path :errors :warnings}` for one document |
| `(validate-bundle-root cfg content)` | The same, for the bundle root |
| `(validate-context cfg)` | Messages for known keys with no JSON-LD mapping |
| `(extract-frontmatter content)`, `(raw-value fm k)`, `(date-ok? s)` | Frontmatter helpers |

### `temper.okf.links`

| Function | Returns |
|---|---|
| `(run)`, `(run {:docs-dir :schema :quiet :glossary})` | `{:exit :results :documents :errors :warnings :orphans :quiet :glossary}` — `:orphans` is a vector of headings, or nil |
| `(report result)` | stdout text |
| `(load-config opts)`, `(validate-policy! schema-file policy)` | Configuration; exit 2 on bad policy |
| `(check-file cfg path)` | `{:path :errors :warnings}` |
| `(orphan-glossary-entries cfg)` | Glossary headings nothing links to |
| `(slug heading)`, `(strip-code s)`, `(headings path)`, `(link-re marker)`, `(url-decode s)`, `(verbatim? cfg path)`, `(untracked-target cfg file)` | Helpers |

### `temper.okf.maturity`

| Function | Returns |
|---|---|
| `(run)`, `(run {:docs-dir :schema :targets :check})` | `{:exit :files [{:path :kinds :stale? :after}] :levels :with-markers :stale :check}`; without `:check`, stale files are rewritten |
| `(report result)` | stdout text |
| `(resolve-targets opts docs-dir)`, `(resolve-targets opts docs-dir env)` | Target paths; `:targets` may be a comma-separated string or a collection |
| `(render-table levels)`, `(render-inline levels)`, `(transform levels content)`, `(apply-kind levels content kind)`, `(marker-re kind)`, `renderers` | Pure rendering |

### `temper.okf.new-doc`

| Function | Returns |
|---|---|
| `(run opts)` | `{:exit 0 :path :text :warnings :docs-dir}`, or `{:exit 1 :error :warnings :docs-dir}`. Options as the flags, keyword-named (`:ai-assisted`, `:agents-skills`, ...); `:tags`, `:tools`, `:agents-skills`, `:source` may be collections; `:force` is any truthy value; `:today` (API only) sets the date |
| `(report result)` | stdout text |
| `(frontmatter schema m)` | The new file's full text. Pure |
| `(parse-args args)` | The original parser's options map |
| `(yaml-scalar s)` | `s`, double-quoted if it contains `:` or `#` |

### `temper.okf.config`, `temper.okf.schema`, `temper.okf.cli`

| Function | Returns |
|---|---|
| `temper.okf.config/resolve-paths` `[opts]` `[opts env]` | `{:docs-dir :schema-file}` |
| `temper.okf.config/read-schema!` `[tool path]` | The schema; exit-2 error if unreadable |
| `temper.okf.config/require-docs-dir!` `[tool dir]` | nil; exit-2 error if missing |
| `temper.okf.config/config-error!` `[& lines]`, `temper.okf.config/catch-config-errors` `[f]` | Throw / catch the exit-2 condition |
| `temper.okf.config/md-files` `[docs-dir]`, `temper.okf.config/relpath` `[path]` | Sorted `*.md` paths, with one leading `./` removed |
| `temper.okf.schema/parse-maturity` `[v]` | `[level label-or-nil]` or nil |
| `temper.okf.schema/canonical-maturity` `[schema level]` | e.g. `"L2 (Human-reviewed)"` |
| `temper.okf.schema/reviewed-at-levels`, `maturity-levels`, `maturity-values`, `maturity-hint`, `maturity-by-level`, `types-hint` `[schema]` | Derived views |
| `temper.okf.cli/parse-flags` `[args {:value {flag key} :bool {flag key}}]` | Options map |
| `temper.okf.cli/exit!` `[result out]` | Prints and exits |

## Schema keys

[`examples/metadata-schema.edn`](examples/metadata-schema.edn) is a commented
starting point; [`examples/context.jsonld`](examples/context.jsonld) is the
matching JSON-LD context.

| Key | Type | Read by | If absent | If empty |
|---|---|---|---|---|
| `:required` | set of keywords | none | — | — |
| `:recommended` | set of keywords | none | — | — |
| `:types` | set of strings | validate, new-doc | Every `type` warns as not in the taxonomy | Same |
| `:review-maturity` | vector of `{:level :label :meaning}`, least to most reviewed | validate, maturity, new-doc | validate rejects every `review_maturity`; maturity renders an empty ladder; new-doc writes `L0 ()` | Same |
| `:date-keys` | set of keywords | validate | No date checks | No date checks |
| `:known-keys` | set of keywords | validate | Every key warns as unknown; no JSON-LD mapping expected | Same |
| `:review-staleness` | `{:from level}` | validate | **exit 2** | `:from` naming no level: `reviewed_at` required nowhere |
| `:provenance-required` | set of keywords | validate, new-doc | validate **exit 2**; new-doc treats it as `#{}` | Rule disabled |
| `:provenance-unknown` | string | validate, new-doc | validate **exit 2** | — |
| `:link-policy` | map | links | **exit 2** | — |
| `:link-policy :internal-marker` | string | links | **exit 2** | Blank: **exit 2** |
| `:link-policy :internal-hosts` | set of `host[:port]` strings, matched exactly | links | **exit 2** | No marker check |
| `:link-policy :verbatim-dirs` | set of path prefixes | links | **exit 2** | No verbatim directories |
| `:link-policy :untracked-dirs` | set of path prefixes | links | **exit 2** | No untracked directories |
| `:link-policy :glossary` | path | links (`--glossary`) | Sweep reports 0 entries | — |
| `:reserved-files` | set of file names | validate | `index.md`, `log.md` validated as concept documents | Same |
| `:bundle-root` | path | validate | No bundle-root check | — |
| `:okf-version` | string | validate | Every bundle root fails (`okf_version must be ""`) | — |
| `:context-file` | path or `""` | validate | **exit 2** | `""` or blank: JSON-LD check skipped. Set but not a readable file: **exit 2** |
| `:context-exempt` | set of keywords | validate | Uncaught exception (exit 1) when `:context-file` is set | No exemptions |

## Use as a dependency

The GitHub repository named below does not exist yet; replace `<sha>` with a
full 40-character commit SHA once it does.

`bb.edn` in a consuming project:

```clojure
{:deps {io.github.lambduhh/temper {:git/sha "<sha>"}}
 :tasks
 {validate {:requires ([temper.okf.validate]) :task (apply temper.okf.validate/-main *command-line-args*)}
  links    {:requires ([temper.okf.links])    :task (apply temper.okf.links/-main *command-line-args*)}
  maturity {:requires ([temper.okf.maturity]) :task (apply temper.okf.maturity/-main *command-line-args*)}
  new-doc  {:requires ([temper.okf.new-doc])  :task (apply temper.okf.new-doc/-main *command-line-args*)}}}
```

`bb -m temper.okf.validate` works in that project without the tasks.

`deps.edn`, for JVM Clojure. The library declares no dependencies because
babashka has clj-yaml and cheshire built in; on the JVM, add them:

```clojure
{:deps {io.github.lambduhh/temper {:git/sha "<sha>"}
        clj-commons/clj-yaml          {:mvn/version "1.0.29"}
        cheshire/cheshire             {:mvn/version "5.13.0"}}}
```

```sh
clojure -M -m temper.okf.validate
```

JVM support has been smoke-tested only (`temper.okf.validate` and `temper.okf.links` against
a real bundle, output identical to the originals); the test suite runs
under babashka. For local development against a checkout, use
`{:local/root "../temper"}` in place of `{:git/sha ...}`.

Command equivalents for a repo that used the scripts:

| Original | With this library |
|---|---|
| `bb tools/validate_metadata.clj` | `bb validate` |
| `bb tools/check_links.bb [--quiet] [--glossary]` | `bb links [--quiet] [--glossary]` |
| `bb tools/gen_maturity_table.bb [--check]` | `bb maturity [--check]` |
| `bb tools/new_doc.bb --type ... --title ... --path ...` | `bb new-doc --type ... --title ... --path ...` |
| `OKF_DOCS_DIR=k OKF_SCHEMA=s.edn bb tools/...` | Same env vars, or `--docs-dir k --schema s.edn` |

## Differences from the original scripts

The golden tests require identical stdout, stderr, exit code and resulting
files for every case except those below. Differences that change command-line
behaviour are encoded as expected-difference cases in
[`test/temper/okf/golden_test.clj`](test/temper/okf/golden_test.clj).

1. **Nothing runs at load time.** The originals resolved paths, read and
   checked the schema, and could print and `System/exit` while being loaded.
   Requiring a library namespace must not end the caller's process, so all of
   it moved into `run`. Not visible from the command line.
2. **New flags.** `--docs-dir` and `--schema` on all four tools, `--targets` on
   `temper.okf.maturity`, taking precedence over the env vars. The originals ignored
   these arguments.
3. **No partial stdout before an uncaught exception.** The originals printed
   from lazy sequences, so an exception part-way through (an absent
   `:context-exempt`; a link with a malformed `%` escape) left some
   `PASS`/`FAIL` lines on stdout before the stack trace. `run` builds the whole
   result before `report` renders it, so the library prints none. Exit code
   and exception agree; stack-trace locations name library files.
4. **One maturity-label lookup.** If `:review-maturity` lists a level twice,
   the original `new_doc.bb` took the first entry's label and
   `validate_metadata.clj` the last, so the scaffolder could write a label the
   validator rejects. Both now use `temper.okf.schema/canonical-maturity` (last entry
   wins). Only a schema that lists a level twice can observe this.
5. **API-only additions.** `run` returns data; `temper.okf.new-doc/run` accepts
   collections for list options and a `:today` date; `temper.okf.links` caches
   headings per run rather than per process. `--today` on the command line
   stays inert, as it was.
6. **`--force` can stand alone.** The usage line has always shown
   `[--force]`, but the original parser gave every flag the next argument: a
   trailing `--force` got nil and refused to overwrite, and `--force --type X`
   swallowed `--type`. `--force` now takes the next argument only when it does
   not start with `--`, so `--force yes`, the one form that worked before,
   parses as it did. This change is its own commit, so it can be dropped
   independently.

## Carried-over behaviour

Behaviour preserved for parity that a reviewer may want changed. Each item is
pinned by a golden case, so changing it is a visible, deliberate edit.

- **An adjacent marker pair is never filled.** The marker regex needs a line
  between `<!-- review-maturity:*:begin -->` and `...:end -->`; a pair pasted
  with nothing between is reported `skip ... (no markers)`, contrary to "adding
  a new target is just a matter of pasting the marker pair into it".
- **Custom provenance keys are demanded but not written.** A key such as
  `:model` in `:provenance-required` makes `--model` required, but the
  scaffold writes only `session` and `tools`, so the new document fails
  validation.
- **Absent schema keys outside the exit-2 list weaken rules silently** — see
  the "If absent" column in [Schema keys](#schema-keys). `temper.okf.new-doc` reads
  `(or (:provenance-required schema) #{})`, the pattern the validator's
  comments warn against.
- **Messages name the original repo's files.** Errors still say "see
  CLAUDE.md", "This is a bug in tools/new_doc.bb", "run `bb
  tools/gen_maturity_table.bb`", and the usage line says `bb tools/new_doc.bb`.
- **Internal hosts match `host[:port]` exactly**, so
  `https://intranet.example.com:8443/` is not treated as internal.
- **A malformed `%` escape in a link target is an uncaught exception** rather
  than a finding.

## Security notes

Threat model: a local or CI tool run by a developer over their own
repository. Inputs are the docs, the schema, command-line arguments and env
vars, all treated as trusted to the same degree as the repository itself.

- No network access, no shell invocation, no evaluation of document content.
  The schema is read with `clojure.edn` (not `read-string`); YAML with clj-yaml's
  `parse-string` with default options (its `:unsafe` mode is not enabled).
- No secrets are read or written. The only files written are the new document
  (`temper.okf.new-doc`, refusing to overwrite without `--force`) and the listed
  maturity targets (`temper.okf.maturity` without `--check`).
- **`--path` containment is a string-prefix check.** `docs/../escaped.md`
  passes it and writes outside the docs directory (pinned by the
  `new-doc/path-dot-dot` golden case). Acceptable for a developer's own
  command line; do not pass untrusted input (a web form, a bot) to
  `temper.okf.new-doc` without a canonical-path containment check.
- The glossary file name is interpolated into a regular expression unescaped,
  so metacharacters in it change what the glossary sweep matches. It affects
  warnings only.
- No third-party dependencies under babashka. The `:jvm` alias in
  [`deps.edn`](deps.edn) names clj-yaml and cheshire from Maven; a project that
  must resolve through an internal registry should do so there.

## Testing

```sh
bb test                      # all suites
bb test temper.okf.golden-test      # one namespace
```

Run from the repository root. Suites:

| Namespace | Covers |
|---|---|
| `temper.okf.golden-test` | Each case runs the original script ([vendored](test/fixtures/original-scripts)) and the library `-main` against identical copies of a bundle, and compares stdout, stderr, exit code and the resulting file tree. Each case also pins its expected exit code, and often an expected substring, so both sides failing the same wrong way does not pass. Fixture bundles: [`full`](test/fixtures/full) (every finding), [`clean`](test/fixtures/clean) (none), [`relocated`](test/fixtures/relocated) (non-default paths, longer marker); plus a real bundle, read-only in place and as a copy for writing tools |
| `temper.okf.api-test` | `run` from Clojure: result maps, nothing printed, exit-2 as data, a scaffolded document passing validation |
| `temper.okf.examples-test` | A bundle built from `examples/` passes every tool |
| `temper.okf.load-test` | Requiring every namespace with hostile env vars prints nothing, exits 0 and writes nothing |
| `temper.okf.config-test`, `temper.okf.schema-test` | Path precedence, target resolution, flag parsing, maturity parsing |

Real-bundle cases need a real bundle's working tree at
`OKF_GOLDEN_REAL_BUNDLE`; without it they are skipped with a notice.

## License

[EPL-1.0](LICENSE).
