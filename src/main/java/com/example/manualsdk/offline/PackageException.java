package com.example.manualsdk.offline;

/** Raised when an offline package cannot be created, verified or imported. */
public class PackageException extends Exception {

    public PackageException(String message) {
        super(message);
    }

    public PackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
