package com.example.manualsdk.offline;

/** How an import treats documents whose id already exists in the target index. */
public enum ImportMode {

    /** Reject the whole package (nothing is written) if any id already exists. */
    REJECT,

    /** Replace existing documents with the same id, keeping every other document. */
    OVERWRITE
}
