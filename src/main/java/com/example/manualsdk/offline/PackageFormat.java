package com.example.manualsdk.offline;

/** Constants describing the signed offline package layout. */
final class PackageFormat {

    private PackageFormat() {
    }

    /** Only this manifest format version is accepted by the receiver. */
    static final int FORMAT_VERSION = 1;

    static final String MANIFEST_ENTRY = "manifest.json";
    static final String SIGNATURE_ENTRY = "manifest.sig";
    static final String README_ENTRY = "README.md";
    static final String DOCUMENT_PREFIX = "docs/";
    static final String SIGNATURE_ALGORITHM = "Ed25519";

    static final String README_TEXT = """
            Signed Offline Repair Manual Package
            ===================================

            This ZIP is a self-contained, offline transfer of repair manual
            documents produced by the manual-search SDK.

            Layout:
              manifest.json  UTF-8 JSON: {"version":1,"keyId":...,"documents":[
                               {"id","path","bytes","sha256"}, ...]}
                             Documents are ordered by id. "bytes" is the exact
                             uncompressed length and "sha256" the lowercase
                             hex SHA-256 of the document JSON file bytes.
              manifest.sig   Raw Ed25519 signature over the exact bytes of
                             manifest.json (not over its re-serialization).
              docs/*.json    One UTF-8 JSON document per hit:
                             {"id":...,"title":...,"body":...}
              README.md      This file.

            Verification rules on the receiving side:
              * The Ed25519 public key is selected by the manifest keyId from
                a caller-provided trust table; keys inside the package are
                ignored.
              * The signature is verified before any manifest field is used.
              * Every declared entry must exist exactly once; no undeclared
              * files, absolute paths or ".." traversal are allowed.
              * Actual uncompressed length and SHA-256 must match; limits on
                document count, per-entry and total bytes are enforced while
                streaming, regardless of ZIP header declarations.
              * Only manifest version 1 is accepted.

            The private signing key never leaves the publisher and is never
            stored inside this package.
            """;
}
