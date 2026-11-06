package com.ravenemu.app.platform;

import com.ravenemu.app.data.library.FolderImport;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** A folder chosen with ACTION_OPEN_DOCUMENT_TREE, as FolderImport reads
 * it. The picker's grant covers this import only: the ROMs are copied, no
 * persistent folder permission is kept. */
public final class DocumentTree implements FolderImport.Tree {
    private static final String[] COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};

    private final ContentResolver resolver;
    private final Uri tree;
    private final String root;

    public DocumentTree(ContentResolver resolver, Uri tree) throws IOException {
        this.resolver = resolver;
        this.tree = tree;
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (RuntimeException invalid) {
            id = null;
        }
        if (id == null || id.isEmpty()) throw new IOException("Dossier Android invalide.");
        root = id;
    }

    @Override public String root() { return root; }

    @Override public List<FolderImport.Child> children(String folderId) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId);
        List<FolderImport.Child> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, COLUMNS, null, null, null)) {
            if (cursor == null) throw new IOException("Le dossier n'est pas accessible.");
            while (cursor.moveToNext()) {
                result.add(new FolderImport.Child(cursor.getString(0), cursor.getString(1),
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)),
                        cursor.isNull(3) ? -1 : cursor.getLong(3)));
            }
        } catch (SecurityException | IllegalArgumentException refused) {
            throw new IOException("Le dossier n'est pas accessible.", refused);
        }
        return result;
    }

    @Override public InputStream open(String documentId) throws IOException {
        InputStream in = resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, documentId));
        if (in == null) throw new IOException("ROM illisible");
        return in;
    }
}
