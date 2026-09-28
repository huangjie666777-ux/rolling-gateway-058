package com.example.manualsdk.offline;

import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.query.ManualQuery;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Publisher side: runs a query against a fixed index/dictionary snapshot,
 * serializes every hit and writes a signed offline ZIP. The signing private
 * key is used in memory only and is never written into the package.
 */
public final class PackageExporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();

    private PackageExporter() {
    }

    /**
     * Exports all hits of {@code query}. The query runs inside one snapshot
     * search session, so documents committed during the export never mix in.
     * The ZIP is assembled in a temporary file and atomically moved to
     * {@code target}; a failure leaves a pre-existing target untouched.
     *
     * @param index      source index
     * @param query      query whose complete result set is exported
     * @param target     destination ZIP file
     * @param keyId      publisher key identifier recorded in the manifest
     * @param privateKey Ed25519 private key matching the published keyId
     * @return the number of documents written
     */
    public static int export(ManualIndex index, ManualQuery query, Path target,
                             String keyId, PrivateKey privateKey) throws PackageException {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(privateKey, "privateKey");
        if (keyId.isBlank()) {
            throw new PackageException("keyId must not be blank");
        }

        List<ManualDocument> documents = collectSnapshot(index, query);
        documents.sort(Comparator.comparing(ManualDocument::id));

        List<byte[]> documentPayloads = new ArrayList<>(documents.size());
        List<ManifestEntry> entries = new ArrayList<>(documents.size());
        for (ManualDocument document : documents) {
            byte[] payload = writeDocumentJson(document);
            String path = PackageFormat.DOCUMENT_PREFIX + document.id() + ".json";
            entries.add(new ManifestEntry(document.id(), path, payload.length, sha256Hex(payload)));
            documentPayloads.add(payload);
        }

        byte[] manifest = writeManifest(keyId, entries);
        byte[] signature = signManifest(manifest, privateKey);

        try {
            writeAtomic(target, out -> {
                try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out), StandardCharsets.UTF_8)) {
                    putEntry(zip, PackageFormat.MANIFEST_ENTRY, manifest);
                    putEntry(zip, PackageFormat.SIGNATURE_ENTRY, signature);
                    putEntry(zip, PackageFormat.README_ENTRY,
                            PackageFormat.README_TEXT.getBytes(StandardCharsets.UTF_8));
                    for (int i = 0; i < entries.size(); i++) {
                        putEntry(zip, entries.get(i).path(), documentPayloads.get(i));
                    }
                }
            });
        } catch (IOException e) {
            throw new PackageException("failed to write package " + target, e);
        }
        return documents.size();
    }

    private static List<ManualDocument> collectSnapshot(ManualIndex index, ManualQuery query) {
        List<ManualDocument> documents = new ArrayList<>();
        try (var session = index.openSession(query, 500)) {
            while (true) {
                var page = session.nextPage();
                page.hits().forEach(hit -> documents.add(new ManualDocument(hit.id(), hit.title(), hit.body())));
                if (!page.hasMore() || page.hits().isEmpty()) {
                    break;
                }
            }
        }
        return documents;
    }

    private static byte[] writeDocumentJson(ManualDocument document) throws PackageException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            var generator = JSON.getFactory().createGenerator(buffer);
            generator.writeStartObject();
            generator.writeStringField("id", document.id());
            generator.writeStringField("title", document.title());
            generator.writeStringField("body", document.body());
            generator.writeEndObject();
            generator.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new PackageException("failed to serialize document " + document.id(), e);
        }
    }

    private static byte[] writeManifest(String keyId, List<ManifestEntry> entries) throws PackageException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            var generator = JSON.getFactory().createGenerator(buffer);
            generator.writeStartObject();
            generator.writeNumberField("version", PackageFormat.FORMAT_VERSION);
            generator.writeStringField("keyId", keyId);
            generator.writeArrayFieldStart("documents");
            for (ManifestEntry entry : entries) {
                generator.writeStartObject();
                generator.writeStringField("id", entry.id());
                generator.writeStringField("path", entry.path());
                generator.writeNumberField("bytes", entry.bytes());
                generator.writeStringField("sha256", entry.sha256());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeEndObject();
            generator.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new PackageException("failed to serialize manifest", e);
        }
    }

    private static byte[] signManifest(byte[] manifest, PrivateKey privateKey) throws PackageException {
        try {
            java.security.Signature signature = java.security.Signature.getInstance(PackageFormat.SIGNATURE_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(manifest);
            return signature.sign();
        } catch (InvalidKeyException | SignatureException | java.security.NoSuchAlgorithmException e) {
            throw new PackageException("failed to sign manifest: " + e.getMessage(), e);
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] payload) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(payload);
        zip.closeEntry();
    }

    /**
     * Writes to a temporary file in the target directory and atomically moves
     * it into place only after a complete, successful write.
     */
    private static void writeAtomic(Path target, ZipWriter writer) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".pkg-", ".tmp");
        boolean done = false;
        try {
            try (java.io.OutputStream out = Files.newOutputStream(temp)) {
                writer.write(out);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            done = true;
        } finally {
            if (!done) {
                Files.deleteIfExists(temp);
            }
        }
    }

    static String sha256Hex(byte[] payload) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(payload));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @FunctionalInterface
    private interface ZipWriter {
        void write(java.io.OutputStream out) throws IOException;
    }
}
