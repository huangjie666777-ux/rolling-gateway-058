package com.example.manualsdk.offline;

import com.example.manualsdk.index.DocumentOp;
import com.example.manualsdk.index.ManualIndex;
import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.query.Field;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.session.SearchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImportConcurrencyTest {

    @TempDir
    Path work;

    @Test
    void concurrentRejectImportsCannotBothSucceedAndOldSessionDoesNotDrift() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path sourceDir = work.resolve("source");
        Path targetDir = work.resolve("target");
        Path packageFile = work.resolve("pkg.zip");

        try (ManualIndex source = ManualIndex.open(sourceDir)) {
            source.addAll(List.of(new ManualDocument("D-001", "Engine part", "engine body")));
            PackageExporter.export(source, ManualQuery.term(Field.ALL, "engine"),
                    packageFile, "pub", pair.getPrivate());
        }
        TrustedKeys keys = TrustedKeys.of(Map.of("pub", pair.getPublic()));

        try (ManualIndex target = ManualIndex.open(targetDir);
             SearchSession before = target.openSession(ManualQuery.term(Field.ALL, "engine"), 10)) {
            assertEquals(0, before.nextPage().hits().size());

            int threads = 8;
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger rejections = new AtomicInteger();
            List<Thread> workers = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        target.importPackage(packageFile, keys, ImportMode.REJECT);
                        successes.incrementAndGet();
                    } catch (PackageException e) {
                        rejections.incrementAndGet();
                    }
                });
                workers.add(worker);
            }
            workers.forEach(Thread::start);
            for (Thread worker : workers) {
                worker.join();
            }
            assertEquals(1, successes.get());
            assertEquals(threads - 1, rejections.get());

            // Old snapshot session never drifts.
            assertEquals(0, before.nextPage().hits().size());

            try (SearchSession after = target.openSession(ManualQuery.term(Field.ALL, "engine"), 10)) {
                var hits = after.nextPage().hits();
                assertEquals(1, hits.size());
                assertEquals("D-001", hits.get(0).id());
            }
        }
    }

    @Test
    void rejectedImportLeavesNoStagedDocumentsBehind() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair strangerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path good = work.resolve("good.zip");
        Path bad = work.resolve("bad.zip");

        try (ManualIndex source = ManualIndex.open(work.resolve("source"))) {
            source.addAll(List.of(new ManualDocument("G-001", "Engine good", "engine")));
            PackageExporter.export(source, ManualQuery.term(Field.ALL, "engine"),
                    good, "pub", pair.getPrivate());
        }
        try (ManualIndex rogue = ManualIndex.open(work.resolve("rogue"))) {
            rogue.addAll(List.of(new ManualDocument("G-001", "Engine rogue", "rogue engine")));
            PackageExporter.export(rogue, ManualQuery.term(Field.ALL, "engine"),
                    bad, "pub", strangerKey.getPrivate());
        }

        try (ManualIndex target = ManualIndex.open(work.resolve("target"))) {
            // bad.zip claims keyId "pub" but was signed by a different key.
            assertThrows(PackageException.class, () -> target.importPackage(bad,
                    TrustedKeys.of(Map.of("pub", pair.getPublic())), ImportMode.OVERWRITE));

            // A subsequent good import must not carry any leftover staged document.
            target.importPackage(good, TrustedKeys.of(Map.of("pub", pair.getPublic())),
                    ImportMode.OVERWRITE);
            try (SearchSession session = target.openSession(ManualQuery.term(Field.ALL, "rogue"), 10)) {
                assertEquals(0, session.nextPage().hits().size());
            }
            try (SearchSession session = target.openSession(ManualQuery.term(Field.ALL, "engine"), 10)) {
                assertEquals(1, session.nextPage().hits().size());
            }
        }
    }
}
