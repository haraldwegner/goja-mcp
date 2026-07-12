# vNEXT — accumulated unreleased changes (rename to the version file at release)

## Removed

- **The legacy `goja.*` names are gone, as announced.** The `goja.*` system-property
  fallbacks (`goja.experience.store`, `goja.workspace.root`, `goja.memory.roots`,
  `goja.experience.shared.dir`) and the `GOJA_*` environment-variable twins
  (`GOJA_TIMEOUT_SECONDS`, `GOJA_ABSOLUTE_PATHS`) are no longer read — promised in
  v2.7.1, delivered here. Use the `jawata` names. A value supplied only under a legacy
  name is now ignored (tested). Unaffected: the one-time store migration that rewrites
  goja-era data (package anchors, the old shared-store directory) still runs — old
  stores keep upgrading cleanly.
