package com.example.manualsdk.index;

import com.example.manualsdk.model.ManualDocument;
import com.example.manualsdk.offline.ImportMode;
import com.example.manualsdk.offline.PackageException;
import com.example.manualsdk.offline.PackageLimits;
import com.example.manualsdk.offline.PackageVerifier;
import com.example.manualsdk.offline.TrustedKeys;
import com.example.manualsdk.offline.VerifiedPackage;
import com.example.manualsdk.query.HitRangeExtractor;
import com.example.manualsdk.query.ManualQuery;
import com.example.manualsdk.query.QueryCompiler;
import com.example.manualsdk.session.SearchSession;
import com.example.manualsdk.terminology.TerminologyDictionary;
import com.example.manualsdk.terminology.TerminologyRule;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point of the SDK. Owns the Lucene index stored in a directory and
 * supports atomic batches of add / replace / delete operations. Writes and
 * queries may run in parallel inside one process; readers always see the
 * last committed batch.
 */
public final class ManualIndex implements AutoCloseable {

    static final String ID_FIELD = "id";
    static final String ID_SORT_FIELD = "idSort";

    private final Directory directory;
    private final Analyzer analyzer;
    private IndexWriter writer;
    private final SearcherManager searcherManager;
    private final QueryCompiler queryCompiler;
    private final HitRangeExtractor hitExtractor;
    private volatile TerminologyDictionary terminology = TerminologyDictionary.empty();
    private final Set<SearchSession> openSessions = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private ManualIndex(Directory directory) throws IOException {
        this.directory = directory;
        this.analyzer = new StandardAnalyzer();
        this.writer = createWriter();
        writer.commit();
        this.searcherManager = new SearcherManager(directory, null);
        searcherManager.maybeRefreshBlocking();
        this.queryCompiler = new QueryCompiler(analyzer);
        this.hitExtractor = new HitRangeExtractor(analyzer);
    }

    /** Opens (creating if needed) the index stored in {@code dir}. */
    public static ManualIndex open(Path dir) {
        try {
            return new ManualIndex(NIOFSDirectory.open(dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ManualIndex open(Directory directory) {
        try {
            return new ManualIndex(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Validates and applies a whole batch atomically. If validation fails an
     * {@link IllegalArgumentException} is thrown and the searchable content
     * is left unchanged; on success the entire batch becomes visible at once
     * and survives a close/reopen cycle.
     */
    public void applyBatch(List<DocumentOp> operations) {
        ensureOpen();
        validate(operations);
        synchronized (writeLock) {
            ensureOpen();
            try {
                for (DocumentOp op : operations) {
                    if (op instanceof DocumentOp.Add add) {
                        writer.updateDocument(idTerm(add.id()), toLuceneDocument(add.document()));
                    } else if (op instanceof DocumentOp.Replace replace) {
                        writer.updateDocument(idTerm(replace.id()), toLuceneDocument(replace.document()));
                    } else if (op instanceof DocumentOp.Delete delete) {
                        writer.deleteDocuments(idTerm(delete.id()));
                    }
                }
                writer.commit();
                searcherManager.maybeRefreshBlocking();
            } catch (IOException e) {
                try {
                    writer.rollback();
                } catch (IOException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                try {
                    writer = createWriter();
                } catch (IOException recreateError) {
                    e.addSuppressed(recreateError);
                }
                try {
                    searcherManager.maybeRefreshBlocking();
                } catch (IOException refreshError) {
                    e.addSuppressed(refreshError);
                }
                throw new UncheckedIOException(e);
            }
        }
    }

    public void addAll(List<ManualDocument> documents) {
        applyBatch(documents.stream().<DocumentOp>map(DocumentOp.Add::new).toList());
    }

    public void replaceAll(List<ManualDocument> documents) {
        applyBatch(documents.stream().<DocumentOp>map(DocumentOp.Replace::new).toList());
    }

    public void deleteAll(List<String> ids) {
        applyBatch(ids.stream().<DocumentOp>map(DocumentOp.Delete::new).toList());
    }

    /**
     * Fully verifies an offline package and, only after verification
     * succeeds, publishes it in one atomic commit. Verification never
     * touches the index. With {@link ImportMode#REJECT} the whole package is
     * rejected if any id already exists; with {@link ImportMode#OVERWRITE}
     * same-id documents are replaced and all other documents are kept.
     *
     * The conflict check runs inside the write lock against the latest
     * committed snapshot, so concurrent imports or batches cannot bypass it.
     * Any failure happens before the commit and leaves no staged documents
     * behind. Already open search sessions stay pinned to their snapshot.
     *
     * @return the verified package metadata and imported documents
     */
    public VerifiedPackage importPackage(java.nio.file.Path packageFile, TrustedKeys trustedKeys,
                                         ImportMode mode, PackageLimits limits) throws PackageException {
        ensureOpen();
        java.util.Objects.requireNonNull(mode, "mode");
        VerifiedPackage verified = PackageVerifier.verify(packageFile, trustedKeys, limits);
        synchronized (writeLock) {
            ensureOpen();
            List<DocumentOp> operations = new ArrayList<>(verified.documents().size());
            for (ManualDocument document : verified.documents()) {
                if (mode == ImportMode.REJECT && documentExists(document.id())) {
                    throw new PackageException("document id already exists, rejecting package: " + document.id());
                }
                operations.add(mode == ImportMode.OVERWRITE
                        ? DocumentOp.replace(document)
                        : DocumentOp.add(document));
            }
            applyBatch(operations);
        }
        return verified;
    }

    public VerifiedPackage importPackage(java.nio.file.Path packageFile, TrustedKeys trustedKeys, ImportMode mode)
            throws PackageException {
        return importPackage(packageFile, trustedKeys, mode, PackageLimits.defaults());
    }

    /** Must be called while holding {@link #writeLock}. */
    private boolean documentExists(String id) {
        try {
            IndexSearcher searcher = searcherManager.acquire();
            try {
                TopDocs hits = searcher.search(new TermQuery(idTerm(id)), 1);
                return hits.totalHits.value > 0;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Atomically replaces the in-memory dictionary; validation failure keeps the old one. */
    public void replaceTerminology(List<TerminologyRule> rules) {
        ensureOpen();
        terminology = TerminologyDictionary.publish(rules, analyzer);
    }

    /** Disables terminology expansion without rebuilding or touching the index. */
    public void clearTerminology() {
        ensureOpen();
        terminology = TerminologyDictionary.empty();
    }

    public TerminologyDictionary terminology() {
        return terminology;
    }

    private void validate(List<DocumentOp> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("batch must contain at least one operation");
        }
        for (DocumentOp op : operations) {
            if (op == null) {
                throw new IllegalArgumentException("batch contains a null operation");
            }
            // Record constructors already enforce non-blank ids and non-null documents.
        }
    }

    private IndexWriter createWriter() throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return new IndexWriter(directory, config);
    }

    /**
     * Opens a search session pinned to the current index snapshot. Updates
     * committed after this call are invisible to the session; new sessions
     * see the latest committed state.
     */
    public SearchSession openSession(ManualQuery query, int pageSize) {
        ensureOpen();
        try {
            SearchSession session = new SearchSession(this, query, pageSize);
            openSessions.add(session);
            return session;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void releaseSession(SearchSession session) {
        openSessions.remove(session);
    }

    public SearcherManager searcherManager() {
        return searcherManager;
    }

    public QueryCompiler queryCompiler() {
        return queryCompiler;
    }

    public HitRangeExtractor hitExtractor() {
        return hitExtractor;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("index is closed");
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private static Term idTerm(String id) {
        return new Term(ID_FIELD, id);
    }

    private static Document toLuceneDocument(ManualDocument doc) {
        Document document = new Document();
        document.add(new org.apache.lucene.document.StringField(ID_FIELD, doc.id(), Field.Store.YES));
        document.add(new SortedDocValuesField(ID_SORT_FIELD, new BytesRef(doc.id())));
        document.add(new TextField(QueryCompiler.TITLE_FIELD, doc.title(), Field.Store.YES));
        document.add(new TextField(QueryCompiler.BODY_FIELD, doc.body(), Field.Store.YES));
        return document;
    }

    /** Closes all open sessions and releases every index resource. */
    @Override
    public void close() {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            closed = true;
            for (SearchSession session : openSessions) {
                session.close();
            }
            openSessions.clear();
            try {
                searcherManager.close();
                writer.close();
                analyzer.close();
                directory.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
