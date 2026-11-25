# Using a Svod vault with Obsidian

A Svod vault is a folder of Markdown files with YAML frontmatter, kept in git. Obsidian works on a
folder of Markdown files too, so you can open the same folder in Obsidian and edit notes there while
agents read and write them through Svod. This page covers how the two fit together, how to bring an
existing Obsidian vault into Svod, and what Obsidian does not do here.

## Open your vault in Obsidian

In Obsidian choose **Open folder as vault** and pick the vault folder from your Svod config
(`vaultPath`). Nothing else is needed.

- **Your edits become commits.** The engine's file watcher sees files change on disk, waits a short
  moment to group a burst of saves, and commits the changed paths. These commits have the author
  `external`, so `git log` shows which changes you made by hand and which an agent made.
- **The index follows.** Each such commit is re-indexed, so search, `context_pack` and the link
  graph see your edit the next time an agent asks.
- **Engine folders.** `.git/` holds history and `.svod/` holds the search index and engine state.
  Both belong to the engine; the watcher ignores them. Do not edit them by hand.
- **Deleted notes.** When an agent or the App API deletes a note, it moves to `.trash/` (a soft
  delete) and can be restored from there. A note you delete in Obsidian is handled by Obsidian
  (its "Deleted files" setting decides where it goes), and the watcher commits the result as an
  `external` change. The old version stays in git history either way.
- **Obsidian's own settings.** Obsidian keeps its settings and window layout in `.obsidian/` inside
  the vault folder. The watcher commits the paths it sees change, and it does not read `.gitignore`,
  so changes Obsidian makes under `.obsidian/` (for example the workspace layout file it rewrites as
  you move around) are committed too, as `external`. Expect those commits in `git log`. They are not
  notes and do not appear in search. If the vault syncs between machines and Obsidian is open on
  more than one of them, a `.obsidian/` file changed on both is a non-Markdown file changed on both
  sides, so sync reports it as a conflict for you to resolve.

## Links and backlinks

Svod uses the same `[[wikilinks]]` as Obsidian, including `[[note|alias]]`, `[[note#heading]]` and
embeds such as `![[image.png]]`. Links you write in Obsidian and links an agent writes end up in the
same link graph.

- **Moving a note keeps its links.** The `move` tool renames a note and rewrites every wikilink that
  pointed to it, all in one commit. Its result lists the notes it changed in `rewrittenBacklinks`.
  The App API's file move does the same.
- **Reading the graph.** The `link` tool returns a note's `outlinks` (resolved and unresolved). The
  `graph_query` tool returns its one-hop neighbourhood: `outlinks` plus `backlinks`, the notes that
  link to it.
- **Across vaults.** With several vaults on one engine, a qualified link such as `[[work:roadmap]]`
  points at a note in another vault. A move made through the App API also rewrites these links in
  the other vaults you may write to. That is a best-effort update that commits each changed note on
  its own, so it is not one commit with the move; a link it could not rewrite is left dangling.
  The MCP `move` tool does not rewrite links in other vaults.

## Import an existing Obsidian vault

To copy an existing Obsidian vault into Svod, use the command line with the engine stopped (the
vault lock is exclusive):

```bash
svod-engine import <config.json> <obsidian-dir> [into]
```

`into` is an optional folder inside the Svod vault; leave it out to import at the top level. While
the engine is running, use the App API instead:

```bash
curl -X POST http://127.0.0.1:7517/api/v1/import \
  -H 'Content-Type: application/json' \
  -d '{"source": "/path/to/obsidian-vault", "format": "obsidian", "into": "", "followSymlinks": false}'
```

Import (and the sources routes below) need an engine admin. On your own machine with the default
`localAdmin` setting, a request from `127.0.0.1` without a key is the admin; on a shared engine add
`-H 'Authorization: Bearer <admin key>'`. With several vaults, add `?vault=<id>` to the URL to pick
the vault to import into.

What the import does:

- **Notes are copied as they are.** Frontmatter and wikilinks are kept verbatim.
- **Attachments are kept.** Images, PDFs and other files are copied byte for byte, so
  `![[image.png]]` still resolves.
- **`.obsidian/` is skipped**, as are other folders and files whose names start with a dot.
- **It is idempotent.** Running it again reports files that are already identical as unchanged and
  writes nothing for them.
- **It never overwrites.** If a note already in the vault differs from the one being imported, the
  import skips it and lists it as skipped, so edits made since the last import are safe.
- **Symlinks.** By default symlinked files and folders are skipped and listed as skipped. With
  `followSymlinks` set to true (App API only) they are copied in instead: a linked file as a file,
  a linked folder with its contents.

## Notes that live elsewhere

An import is a one-time copy. If some notes live outside the vault (in another project's
repository, say) and should keep coming in as they change, register that path as an external
source instead. See [ADR-0016](adr/0016-external-sources-and-symlink-import.md) for the design.

- `POST /api/v1/sources` registers an absolute `path`, with an optional `into` folder and these
  options (all off by default):
  - `followSymlinks`: copy symlinked files and folders in instead of skipping them.
  - `prune`: soft-delete vault copies of files removed from the source.
  - `autoSync`: watch the source path; a filesystem watcher re-syncs that source shortly after its
    files change, with no config and no API call needed (only while the path exists).
  - `writeBack`: two-way; a vault edit to a file that came from the source is written back to it,
    unless the source file changed too (then it is a conflict).

  `GET /api/v1/sources` lists the registered sources.
- `POST /api/v1/sources/sync` syncs every source, and `POST /api/v1/sources/{id}/sync` syncs one.
  Changes made at the source come in; a vault copy you edited since is reported as a conflict, not
  overwritten.
- To sync on a schedule, set `sourceSync` in the config: `sourceSync.onStartup` runs one sync when
  the engine starts, and `sourceSync.intervalMinutes` repeats it at that interval. With neither set,
  a source without `autoSync` syncs only when you call the API.

## What Obsidian does not do here

- **Obsidian plugins do not run in the engine.** Agents see the Markdown files only. Anything a
  plugin computes on the fly (Dataview queries, for example) is not in search or in the link graph,
  unless the plugin writes it to a file.
- **Renames made in Obsidian are ordinary file changes.** When you rename a note in Obsidian,
  Obsidian updates the links itself (if that setting is on), and the watcher commits the result as
  `external` edits. This does not go through the engine's `move`, so the rename and the link
  updates are not guaranteed to land in one commit (the watcher groups whatever changed within a
  short window), and there is no `rewrittenBacklinks` list. To rename with that guarantee, let an
  agent call `move` or use the App API.
- **No Obsidian Sync integration.** Svod syncs vaults between machines through git. Running
  Obsidian Sync on the same folder as well is not something Svod knows about or coordinates with.
