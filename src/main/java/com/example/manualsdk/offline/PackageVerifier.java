package com.example.manualsdk.offline;

import com.example.manualsdk.model.ManualDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Receiver side. Streams a package once, enforcing all resource limits on
 * the bytes actually inflated (ZIP header sizes are never trusted), then
 * verifies the raw-manifest Ed25519 signature with a caller supplied key
 * table and cross-checks every document. Nothing about a failed package is
 * persisted.
 */
public final class PackageVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private PackageVerifier() {
    }

    /** Verifies with the default resource limits. */
    public static VerifiedPackage verify(Path packageFile, TrustedKeys trustedKeys) throws PackageException {
        return verify(packageFile, trustedKeys, PackageLimits.defaults());
    }

    public static VerifiedPackage verify(Path packageFile, TrustedKeys trustedKeys, PackageLimits limits)
            throws PackageException {
        if (trustedKeys == null || limits == null) {
            throw new PackageException("trustedKeys and limits are required");
        }
        RawPackage raw = readPackage(packageFile, limits);

        final JsonNode manifest;
        try {
            manifest = JSON.readTree(raw.manifest);
        } catch (IOException e) {
            throw new PackageException("manifest is not valid JSON", e);
        }
        if (manifest == null || !manifest.isObject()) {
            throw new PackageException("manifest must be a JSON object");
        }
        JsonNode versionNode = manifest.get("version");
        if (versionNode == null || !versionNode.canConvertToInt() || versionNode.asInt() != PackageFormat.FORMAT_VERSION) {
            throw new PackageException("unsupported or missing manifest version");
        }
        JsonNode keyIdNode = manifest.get("keyId");
        if (keyIdNode == null || !keyIdNode.isTextual() || keyIdNode.asText().isBlank()) {
            throw new PackageException("manifest keyId missing or invalid");
        }
        String keyId = keyIdNode.asText();
        Optional<PublicKey> trustedKey = trustedKeys.find(keyId);
        if (trustedKey == null || trustedKey.isEmpty()) {
            throw new PackageException("unknown or untrusted keyId: " + keyId);
        }
        verifySignature(raw.manifest, raw.signature, trustedKey.get());

        List<ManifestEntry> entries = parseManifestEntries(manifest, limits);
        if (entries.size() != raw.documents.size()) {
            throw new PackageException("declared and packaged document sets differ");
        }

        List<ManualDocument> documents = new ArrayList<>(entries.size());
        for (ManifestEntry entry : entries) {
            byte[] payload = raw.documents.get(entry.path());
            if (payload == null) {
                throw new PackageException("declared file missing from package: " + entry.path());
            }
            if (payload.length != entry.bytes()) {
                throw new PackageException("length mismatch for " + entry.id());
            }
            if (!PackageExporter.sha256Hex(payload).equals(entry.sha256())) {
                throw new PackageException("sha256 mismatch for " + entry.id());
            }
            documents.add(parseDocument(entry, payload));
        }
        for (String packagedPath : raw.documents.keySet()) {
            if (entries.stream().noneMatch(e -> e.path().equals(packagedPath))) {
                throw new PackageException("file present in package but not declared in manifest: " + packagedPath);
            }
        }
        return new VerifiedPackage(keyId, documents);
    }

    private static void verifySignature(byte[] manifest, byte[] signature, PublicKey publicKey) throws PackageException {
        try {
            java.security.Signature verifier = java.security.Signature.getInstance(PackageFormat.SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(manifest);
            if (!verifier.verify(signature)) {
                throw new PackageException("manifest signature verification failed");
            }
        } catch (SignatureException | java.security.InvalidKeyException | NoSuchAlgorithmException e) {
            throw new PackageException("manifest signature verification failed: " + e.getMessage(), e);
        }
    }

    private static List<ManifestEntry> parseManifestEntries(JsonNode manifest, PackageLimits limits) throws PackageException {
        JsonNode documentsNode = manifest.get("documents");
        if (documentsNode == null || !documentsNode.isArray()) {
            throw new PackageException("manifest documents array missing or invalid");
        }
        if (documentsNode.size() > limits.maxDocuments()) {
            throw new PackageException("document count exceeds limit of " + limits.maxDocuments());
        }
        List<ManifestEntry> entries = new ArrayList<>(documentsNode.size());
        String previousId = null;
        for (JsonNode node : documentsNode) {
            if (node == null || !node.isObject()) {
                throw new PackageException("manifest entry must be a JSON object");
            }
            JsonNode idNode = node.get("id");
            JsonNode pathNode = node.get("path");
            JsonNode bytesNode = node.get("bytes");
            JsonNode shaNode = node.get("sha256");
            if (idNode == null || !idNode.isTextual() || idNode.asText().isBlank()) {
                throw new PackageException("manifest entry id missing or invalid");
            }
            String id = idNode.asText();
            if (pathNode == null || !pathNode.isTextual()) {
                throw new PackageException("manifest entry path missing for " + id);
            }
            if (bytesNode == null || !bytesNode.isIntegralNumber() || bytesNode.asLong() < 0) {
                throw new PackageException("manifest entry bytes missing or invalid for " + id);
            }
            if (shaNode == null || !shaNode.isTextual()) {
                throw new PackageException("manifest entry sha256 missing for " + id);
            }
            String path = pathNode.asText();
            String expectedPath = PackageFormat.DOCUMENT_PREFIX + id + ".json";
            if (!path.equals(expectedPath)) {
                throw new PackageException("path does not match declared id: " + path);
            }
            if (bytesNode.asLong() > limits.maxEntryBytes()) {
                throw new PackageException("declared entry exceeds per-entry limit: " + id);
            }
            String sha = shaNode.asText();
            if (!SHA256_PATTERN.matcher(sha).matches()) {
                throw new PackageException("manifest entry sha256 malformed for " + id);
            }
            if (previousId != null && id.compareTo(previousId) <= 0) {
                throw new PackageException("manifest entries must be unique and sorted by id");
            }
            previousId = id;
            entries.add(new ManifestEntry(id, path, bytesNode.asLong(), sha));
        }
        return entries;
    }

    private static ManualDocument parseDocument(ManifestEntry entry, byte[] payload) throws PackageException {
        final JsonNode node;
        try {
            node = JSON.readTree(payload);
        } catch (IOException e) {
            throw new PackageException("document is not valid JSON: " + entry.id(), e);
        }
        if (node == null || !node.isObject()) {
            throw new PackageException("document must be a JSON object: " + entry.id());
        }
        JsonNode idNode = node.get("id");
        JsonNode titleNode = node.get("title");
        JsonNode bodyNode = node.get("body");
        if (idNode == null || !idNode.isTextual() || !idNode.asText().equals(entry.id())) {
            throw new PackageException("document id does not match manifest: " + entry.path());
        }
        if (titleNode == null || !titleNode.isTextual() || bodyNode == null || !bodyNode.isTextual()) {
            throw new PackageException("document title/body missing or invalid: " + entry.id());
        }
        return new ManualDocument(idNode.asText(), titleNode.asText(), bodyNode.asText());
    }

    private static RawPackage readPackage(Path packageFile, PackageLimits limits) throws PackageException {
        RawPackage raw = new RawPackage();
        long totalInflated = 0;
        int entryCount = 0;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(packageFile));
             ZipInputStream zip = new ZipInputStream(in, java.nio.charset.StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > limits.maxZipEntries()) {
                    throw new PackageException("ZIP entry count exceeds limit of " + limits.maxZipEntries());
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    throw new PackageException("directory entries are not allowed: " + name);
                }
                validateEntryName(name);
                if (raw.seen.putIfAbsent(name, Boolean.TRUE) != null) {
                    throw new PackageException("duplicate ZIP entry: " + name);
                }
                byte[] payload = readBounded(zip, limits, totalInflated, name);
                totalInflated += payload.length;
                if (totalInflated < 0) {
                    throw new PackageException("total uncompressed size overflow");
                }
                classify(name, payload, raw);
                zip.closeEntry();
            }
        } catch (ZipException e) {
            throw new PackageException("corrupt ZIP archive: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PackageException("cannot read package: " + e.getMessage(), e);
        }
        if (raw.manifest == null) {
            throw new PackageException("package is missing " + PackageFormat.MANIFEST_ENTRY);
        }
        if (raw.signature == null) {
            throw new PackageException("package is missing " + PackageFormat.SIGNATURE_ENTRY);
        }
        if (raw.readme == null) {
            throw new PackageException("package is missing " + PackageFormat.README_ENTRY);
        }
        if (raw.documents.size() > limits.maxDocuments()) {
            throw new PackageException("document count exceeds limit of " + limits.maxDocuments());
        }
        return raw;
    }

    private static void validateEntryName(String name) throws PackageException {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith(PackageFormat.DOCUMENT_PREFIX + "/")) {
            throw new PackageException("illegal entry name: " + name);
        }
        if (name.contains("\\") || name.contains("//")) {
            throw new PackageException("illegal entry name: " + name);
        }
        String[] segments = name.split("/");
        for (String segment : segments) {
            if (segment.equals("..") || segment.isEmpty()) {
                throw new PackageException("path traversal or empty segment in: " + name);
            }
        }
        boolean metadata = name.equals(PackageFormat.MANIFEST_ENTRY)
                || name.equals(PackageFormat.SIGNATURE_ENTRY)
                || name.equals(PackageFormat.README_ENTRY);
        boolean document = segments.length == 2
                && segments[0].equals("docs")
                && segments[1].endsWith(".json")
                && segments[1].length() > ".json".length();
        if (!metadata && !document) {
            throw new PackageException("undeclared or unexpected entry: " + name);
        }
    }

    private static byte[] readBounded(InputStream in, PackageLimits limits, long totalSoFar, String name)
            throws IOException, PackageException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            read += n;
            if (read > limits.maxEntryBytes()) {
                throw new PackageException("entry exceeds uncompressed size limit: " + name);
            }
            if (totalSoFar + read > limits.maxTotalBytes()) {
                throw new PackageException("package exceeds total uncompressed size limit");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private static void classify(String name, byte[] payload, RawPackage raw) throws PackageException {
        switch (name) {
            case PackageFormat.MANIFEST_ENTRY -> raw.manifest = payload;
            case PackageFormat.SIGNATURE_ENTRY -> raw.signature = payload;
            case PackageFormat.README_ENTRY -> raw.readme = payload;
            default -> {
                if (name.startsWith(PackageFormat.DOCUMENT_PREFIX)) {
                    raw.documents.put(name, payload);
                }
            }
        }
    }

    private static final class RawPackage {
        final Map<String, Boolean> seen = new HashMap<>();
        final Map<String, byte[]> documents = new HashMap<>();
        byte[] manifest;
        byte[] signature;
        byte[] readme;
    }
}
