package com.example.manualsdk.offline;

/**
 * Resource limits enforced while reading an offline package. Every limit is
 * checked against the bytes actually read from the ZIP stream, never against
 * the (attacker controlled) sizes declared by ZIP headers or the manifest.
 *
 * @param maxDocuments  maximum number of declared document entries
 * @param maxEntryBytes maximum number of uncompressed bytes for one entry
 * @param maxTotalBytes maximum total number of uncompressed document bytes
 * @param maxZipEntries maximum number of ZIP entries (including metadata)
 */
public record PackageLimits(int maxDocuments, long maxEntryBytes, long maxTotalBytes, int maxZipEntries) {

    public static final int DEFAULT_MAX_DOCUMENTS = 100_000;
    public static final long DEFAULT_MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    public static final int DEFAULT_MAX_ZIP_ENTRIES = 200_010;

    public PackageLimits {
        if (maxDocuments <= 0 || maxEntryBytes <= 0 || maxTotalBytes <= 0 || maxZipEntries <= 0) {
            throw new IllegalArgumentException("all package limits must be positive");
        }
        if (maxTotalBytes < maxEntryBytes) {
            throw new IllegalArgumentException("maxTotalBytes must be >= maxEntryBytes");
        }
    }

    public static PackageLimits defaults() {
        return new PackageLimits(DEFAULT_MAX_DOCUMENTS, DEFAULT_MAX_ENTRY_BYTES,
                DEFAULT_MAX_TOTAL_BYTES, DEFAULT_MAX_ZIP_ENTRIES);
    }
}
