package com.example.manualsdk.offline;

/** One document line of {@code manifest.json}. */
record ManifestEntry(String id, String path, long bytes, String sha256) {
}
