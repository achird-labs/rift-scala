package rift.bridge

/** Re-exports the model type used directly in this module's public API (`RiftConnector.create`,
  * `.replaceAll`, `ImposterConnector.definition`), so callers referencing `rift.bridge` don't also
  * need a separate `rift.model` import for the one type that crosses the boundary by value. The
  * `export` forwards both the type and its companion (`.fromJson`) in one clause.
  */
export rift.model.ImposterDefinition
