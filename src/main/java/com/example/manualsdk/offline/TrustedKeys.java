package com.example.manualsdk.offline;

import java.security.PublicKey;
import java.util.Map;
import java.util.Optional;

/**
 * Lookup of trusted Ed25519 public keys by keyId, supplied by the calling
 * application. A package is only accepted when its manifest references a
 * keyId present here; public keys carried inside a package are never used.
 */
@FunctionalInterface
public interface TrustedKeys {

    Optional<PublicKey> find(String keyId);

    static TrustedKeys of(Map<String, PublicKey> keys) {
        Map<String, PublicKey> copy = Map.copyOf(keys);
        return keyId -> Optional.ofNullable(copy.get(keyId));
    }
}
