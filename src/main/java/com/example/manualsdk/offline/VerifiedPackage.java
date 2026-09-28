package com.example.manualsdk.offline;

import com.example.manualsdk.model.ManualDocument;

import java.util.List;

/**
 * Result of a successful read-only verification: the trusted keyId and the
 * fully checked documents, ordered by id as declared in the manifest.
 */
public record VerifiedPackage(String keyId, List<ManualDocument> documents) {

    public VerifiedPackage {
        documents = List.copyOf(documents);
    }
}
