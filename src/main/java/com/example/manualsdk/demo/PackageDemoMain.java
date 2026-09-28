package com.example.manualsdk.demo;

import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.offline.ImportMode;
import com.example.manualsdk.offline.PackageException;
import com.example.manualsdk.offline.PackageExporter;
import com.example.manualsdk.offline.PackageVerifier;
import com.example.manualsdk.offline.TrustedKeys;
import com.example.manualsdk.offline.VerifiedPackage;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * End-to-end offline handover demo:
 * 1) a publisher exports every hit of a query into a signed ZIP package,
 * 2) an air-gapped receiver verifies the package read-only using its own
 *    keyId -> public key table and imports it atomically,
 * 3) imported documents are immediately searchable, and
 * 4) a tampered package is rejected.
 *
 * Key responsibility: the Ed25519 private key stays with the publisher and is
 * never written into the package; the receiver trusts only its locally
 * configured public keys. Index/reader resources follow the try-with
 * resources lifecycle of {@link ManualIndex} and the search sessions.
 */
public final class PackageDemoMain {

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("manual-package-demo");
        Path sourceDir = work.resolve("source-index");
        Path targetDir = work.resolve("target-index");
        Path packageFile = work.resolve("engine-kb.zip");

        // Publisher generates / holds the Ed25519 key pair. The private key is
        // used only in memory while signing.
        KeyPair publisherKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String keyId = "factory-publisher-2026";

        ManualQuery exportQuery = ManualQuery.or(
                ManualQuery.term(Field.TITLE, "engine"),
                ManualQuery.prefix(Field.ALL, "trans"));

        try (ManualIndex source = ManualIndex.open(sourceDir)) {
            source.addAll(List.of(
                    new ManualDocument("M-001", "Engine maintenance", "Replace the oil filter every 10000 km."),
                    new ManualDocument("M-002", "Transmission service", "The transmission fluid must be checked."),
                    new ManualDocument("M-003", "Engine cooling", "Inspect the engine coolant level weekly."),
                    new ManualDocument("M-004", "Brakes", "Brake pad wear inspection procedure.")));

            int exported = PackageExporter.export(source, exportQuery, packageFile,
                    keyId, publisherKeys.getPrivate());
            System.out.println("[publisher] exported " + exported + " documents -> " + packageFile);
        }

        System.out.println("[publisher] package entries:");
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(packageFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                System.out.println("   - " + entry.getName());
            }
        }

        // === Receiver side (air-gapped site) ===
        // Trust table is provided by the receiver; package contents cannot add keys.
        TrustedKeys trustedKeys = TrustedKeys.of(Map.of(keyId, publisherKeys.getPublic()));

        // Read-only verification without touching the target index.
        VerifiedPackage verified = PackageVerifier.verify(packageFile, trustedKeys);
        System.out.println("[receiver] read-only verify OK: keyId=" + verified.keyId()
                + ", documents=" + verified.documents().size());

        try (ManualIndex target = ManualIndex.open(targetDir)) {
            target.addAll(List.of(new ManualDocument("M-004", "Brakes (local notes)", "Locally kept document unrelated to the package.")));

            target.importPackage(packageFile, trustedKeys, ImportMode.OVERWRITE);
            System.out.println("[receiver] import committed atomically");

            System.out.println("[receiver] search for 'coolant' after import:");
            try (SearchSession session = target.openSession(ManualQuery.term(Field.BODY, "coolant"), 10)) {
                session.nextPage().hits().forEach(hit ->
                        System.out.printf("   %s: %s%n", hit.id(), hit.title()));
            }
            System.out.println("[receiver] unrelated local document still present:");
            try (SearchSession session = target.openSession(ManualQuery.term(Field.ALL, "kept"), 10)) {
                session.nextPage().hits().forEach(hit ->
                        System.out.printf("   %s: %s%n", hit.id(), hit.title()));
            }
        }

        // Tamper with one document byte and show rejection.
        Path tampered = work.resolve("tampered.zip");
        tamperOneByte(packageFile, tampered);
        try (ManualIndex target = ManualIndex.open(targetDir)) {
            try {
                target.importPackage(tampered, trustedKeys, ImportMode.OVERWRITE);
                System.out.println("[receiver] ERROR: tampered package was accepted");
            } catch (PackageException e) {
                System.out.println("[receiver] tampered package rejected: " + e.getMessage());
            }
            try (SearchSession session = target.openSession(ManualQuery.term(Field.ALL, "coolant"), 10)) {
                int hits = session.nextPage().hits().size();
                System.out.println("[receiver] searchable documents after failed import: coolant hits="
                        + hits + " (index untouched)");
            }
        }
    }

    private static void tamperOneByte(Path source, Path target) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                byte[] bytes = in.readAllBytes();
                if (entry.getName().startsWith("docs/") && bytes.length > 0) {
                    bytes[bytes.length - 1] ^= 0x01;
                }
                out.write(bytes);
                out.closeEntry();
            }
        }
    }
}
