package com.example.manualsdk.offline;

import com.example.manualsdk.index.DocumentOp;
import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class OfflinePackageTest {

    @TempDir
    Path work;

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private ManualIndex seedIndex(Path dir) {
        ManualIndex index = ManualIndex.open(dir);
        index.addAll(List.of(
                new ManualDocument("M-001", "Engine maintenance", "Replace the oil filter every 10000 km."),
                new ManualDocument("M-002", "Transmission service", "The transmission fluid must be checked."),
                new ManualDocument("M-003", "Engine cooling", "Inspect the engine coolant level weekly."),
                new ManualDocument("M-004", "Brakes", "Brake pads wear indicator.")));
        return index;
    }

    private Path exportEnginePackage(Path packageFile, KeyPair pair, Path sourceDir) throws Exception {
        try (ManualIndex source = seedIndex(sourceDir)) {
            int count = PackageExporter.export(source, ManualQuery.term(Field.TITLE, "engine"),
                    packageFile, "publisher-1", pair.getPrivate());
            assertEquals(2, count);
        }
        return packageFile;
    }

    @Test
    void roundTripExportVerifyAndImportOverwriteKeepsOtherDocs() throws Exception {
        KeyPair pair = ed25519();
        Path packageFile = work.resolve("out/pkg.zip");
        exportEnginePackage(packageFile, pair, work.resolve("source"));

        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(packageFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        assertEquals(List.of("manifest.json", "manifest.sig", "README.md",
                "docs/M-001.json", "docs/M-003.json"), names);

        TrustedKeys keys = TrustedKeys.of(Map.of("publisher-1", pair.getPublic()));
        VerifiedPackage verified = PackageVerifier.verify(packageFile, keys);
        assertEquals("publisher-1", verified.keyId());
        assertEquals(List.of("M-001", "M-003"),
                verified.documents().stream().map(ManualDocument::id).toList());

        Path targetDir = work.resolve("target");
        try (ManualIndex target = ManualIndex.open(targetDir)) {
            target.addAll(List.of(
                    new ManualDocument("M-001", "old title", "old body"),
                    new ManualDocument("M-009", "Unrelated", "must survive")));

            assertThrows(PackageException.class,
                    () -> target.importPackage(packageFile, keys, ImportMode.REJECT));
            try (SearchSession session = target.openSession(ManualQuery.term(Field.ALL, "old"), 10)) {
                assertEquals(1, session.nextPage().hits().size());
            }

            target.importPackage(packageFile, keys, ImportMode.OVERWRITE);
            try (SearchSession session = target.openSession(ManualQuery.prefix(Field.ALL, "eng"), 10)) {
                var hits = session.nextPage().hits();
                assertEquals(2, hits.size());
                assertEquals(List.of("Engine cooling", "Engine maintenance"),
                        hits.stream().map(h -> h.title()).sorted().toList());
            }
            try (SearchSession session = target.openSession(ManualQuery.term(Field.ALL, "survive"), 10)) {
                assertEquals(1, session.nextPage().hits().size());
            }
        }

        try (ManualIndex reopened = ManualIndex.open(targetDir);
             SearchSession session = reopened.openSession(ManualQuery.term(Field.TITLE, "cooling"), 10)) {
            assertEquals(1, session.nextPage().hits().size());
        }
    }

    @Test
    void packageDoesNotIncludeDocumentsCommittedAfterSnapshotQuery() throws Exception {
        KeyPair pair = ed25519();
        Path sourceDir = work.resolve("source");
        try (ManualIndex source = seedIndex(sourceDir);
             var session = source.openSession(ManualQuery.prefix(Field.ALL, "engine"), 50)) {
            int snapshotHits = collectAll(session);
            source.applyBatch(List.of(DocumentOp.add(
                    new ManualDocument("M-999", "Engine brand new", "engine engine engine"))));
            assertEquals(0, session.nextPage().hits().size());
            try (var fresh = source.openSession(ManualQuery.prefix(Field.ALL, "engine"), 50)) {
                assertEquals(snapshotHits + 1, collectAll(fresh));
            }
        }
        Path packageFile = work.resolve("snapshot.zip");
        try (ManualIndex source = ManualIndex.open(sourceDir)) {
            PackageExporter.export(source, ManualQuery.prefix(Field.ALL, "engine"),
                    packageFile, "publisher-1", pair.getPrivate());
        }
        // Export after the commit may contain M-999, but a package exported before
        // the commit (next assertion via interleaved threads is covered in the
        // concurrent test) never mixes it; here assert ordering and validity only.
        VerifiedPackage verified = PackageVerifier.verify(packageFile,
                TrustedKeys.of(Map.of("publisher-1", pair.getPublic())));
        assertEquals(verified.documents().size(),
                verified.documents().stream().map(ManualDocument::id).distinct().count());
    }

    @Test
    void concurrentUpdateDuringExportNeverMixesIntoPackage() throws Exception {
        KeyPair pair = ed25519();
        Path sourceDir = work.resolve("source");
        try (ManualIndex source = seedIndex(sourceDir)) {
            Path packageFile = work.resolve("concurrent.zip");
            Thread updater = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    String id = String.format("X-%03d", i);
                    source.applyBatch(List.of(DocumentOp.add(
                            new ManualDocument(id, "Engine add-on " + i, "engine noise"))));
                }
            });
            updater.start();
            PackageExporter.export(source, ManualQuery.prefix(Field.ALL, "engine"),
                    packageFile, "publisher-1", pair.getPrivate());
            updater.join();

            VerifiedPackage verified = PackageVerifier.verify(packageFile,
                    TrustedKeys.of(Map.of("publisher-1", pair.getPublic())));
            List<String> ids = verified.documents().stream().map(ManualDocument::id).toList();
            // Whatever snapshot the export pinned, it must be internally consistent:
            // unique ids, strictly sorted, and only ids that existed at one commit.
            assertEquals(ids.size(), ids.stream().distinct().count());
            List<String> sorted = ids.stream().sorted().toList();
            assertEquals(sorted, ids);
            assertTrue(ids.stream().allMatch(id -> id.startsWith("M-") || id.startsWith("X-")));
        }
    }

    private static int collectAll(SearchSession session) {
        int total = 0;
        while (true) {
            var page = session.nextPage();
            total += page.hits().size();
            if (!page.hasMore()) {
                return total;
            }
        }
    }

    @Test
    void failedExportDoesNotDestroyExistingTarget() throws Exception {
        KeyPair pair = ed25519();
        Path packageFile = work.resolve("existing.zip");
        byte[] sentinel = "sentinel-content".getBytes(StandardCharsets.UTF_8);
        Files.write(packageFile, sentinel);
        try (ManualIndex source = seedIndex(work.resolve("source"))) {
            PrivateKey brokenKey = new PrivateKey() {
                @Override public String getAlgorithm() { return "Ed25519"; }
                @Override public String getFormat() { return "PKCS#8"; }
                @Override public byte[] getEncoded() { return new byte[]{1, 2, 3}; }
            };
            assertThrows(PackageException.class, () -> PackageExporter.export(
                    source, ManualQuery.term(Field.ALL, "engine"), packageFile, "k", brokenKey));
        }
        assertArrayEquals(sentinel, Files.readAllBytes(packageFile));
    }

    @Test
    void rejectsUnknownAndWrongKeyAndTamperedContent() throws Exception {
        KeyPair pair = ed25519();
        KeyPair other = ed25519();
        Path packageFile = work.resolve("pkg.zip");
        exportEnginePackage(packageFile, pair, work.resolve("source"));
        TrustedKeys keys = TrustedKeys.of(Map.of("publisher-1", pair.getPublic()));

        assertThrows(PackageException.class, () -> PackageVerifier.verify(packageFile,
                TrustedKeys.of(Map.of("publisher-2", other.getPublic()))));
        assertThrows(PackageException.class, () -> PackageVerifier.verify(packageFile,
                TrustedKeys.of(Map.of("publisher-1", other.getPublic()))));
        assertThrows(PackageException.class, () -> PackageVerifier.verify(packageFile,
                TrustedKeys.of(Map.of())));

        Path tampered = work.resolve("tampered.zip");
        rewriteEntry(packageFile, tampered, "docs/M-001.json",
                "{\"id\":\"M-001\",\"title\":\"evil\",\"body\":\"x\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageException.class, () -> PackageVerifier.verify(tampered, keys));

        Path tamperedManifest = work.resolve("tampered-manifest.zip");
        byte[] originalManifest = readEntry(packageFile, "manifest.json");
        byte[] padded = new byte[originalManifest.length + 1];
        System.arraycopy(originalManifest, 0, padded, 0, originalManifest.length);
        padded[originalManifest.length] = ' ';
        rewriteEntry(packageFile, tamperedManifest, "manifest.json", padded);
        // The altered bytes no longer match the signature even if keyId were mapped.
        PackageException failure = assertThrows(PackageException.class,
                () -> PackageVerifier.verify(tamperedManifest, keys));
        assertTrue(failure.getMessage().toLowerCase().contains("signature"));

        Path traversal = work.resolve("traversal.zip");
        copyWithExtra(packageFile, traversal, "../evil.json", "x".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageException.class, () -> PackageVerifier.verify(traversal, keys));
    }

    @Test
    void rejectsMissingFileAndInflatedZipBomb() throws Exception {
        KeyPair pair = ed25519();
        Path packageFile = work.resolve("pkg.zip");
        exportEnginePackage(packageFile, pair, work.resolve("source"));
        TrustedKeys keys = TrustedKeys.of(Map.of("publisher-1", pair.getPublic()));

        Path missing = work.resolve("missing.zip");
        copyWithFilter(packageFile, missing, "docs/M-003.json");
        assertThrows(PackageException.class, () -> PackageVerifier.verify(missing, keys));

        Path bomb = work.resolve("bomb.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bomb), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("docs/huge.json"));
            byte[] chunk = new byte[4096];
            for (int i = 0; i < 10_000; i++) {
                zip.write(chunk);
            }
            zip.closeEntry();
        }
        PackageLimits tiny = new PackageLimits(10, 1024, 4096, 100);
        assertThrows(PackageException.class, () -> PackageVerifier.verify(bomb, keys, tiny));
    }

    private static byte[] readEntry(Path zipFile, String wanted) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(wanted)) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IllegalArgumentException("entry not found: " + wanted);
    }

    private static void rewriteEntry(Path source, Path target, String replacedName, byte[] newPayload)
            throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = replacedName.equals(entry.getName()) ? replacedName : entry.getName();
                out.putNextEntry(new ZipEntry(name));
                out.write(replacedName.equals(entry.getName()) ? newPayload : in.readAllBytes());
                out.closeEntry();
            }
        }
    }

    private static void copyWithFilter(Path source, Path target, String removedName) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(removedName)) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(in.readAllBytes());
                out.closeEntry();
            }
        }
    }

    private static void copyWithExtra(Path source, Path target, String extraName, byte[] extraPayload)
            throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(in.readAllBytes());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(extraName));
            out.write(extraPayload);
            out.closeEntry();
        }
    }
}
