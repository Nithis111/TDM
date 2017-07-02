package com.github.axet.torrentclient.app;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;

import de.innosystec.unrar.NativeStorage;

public class RarNativeStorageSAF extends NativeStorage {
    Storage storage;
    Uri u;
    Uri parent;
    RarNativeStorageSAF parentFolder;

    public RarNativeStorageSAF(Storage storage, Uri parent, Uri u) {
        super((File) null);
        this.storage = storage;
        this.u = u;
        this.parent = parent;
    }

    public RarNativeStorageSAF(Storage storage, RarNativeStorageSAF parent, Uri u) {
        super((File) null);
        this.storage = storage;
        this.u = u;
        this.parentFolder = parent;
        this.parent = parentFolder.u;
    }

    public RarNativeStorageSAF(RarNativeStorageSAF v) {
        super((File) null);
        u = Uri.parse(v.u.toString());
        storage = v.storage;
        parent = v.parent;
    }

    @Override
    public RarNativeFileSAF read() throws FileNotFoundException {
        return new RarNativeFileSAF(storage, u, "r");
    }

    @Override
    public RarNativeFileSAF write() throws FileNotFoundException {
        return new RarNativeFileSAF(storage, u, "rw");
    }

    @Override
    public NativeStorage open(String name) {
        return new RarNativeStorageSAF(storage, this, storage.child(parent, name));
    }

    @Override
    public boolean exists() {
        return storage.exists(u);
    }

    @Override
    public boolean canRead() {
        return true;
    }

    @Override
    public boolean canWrite() {
        return true;
    }

    @Override
    public boolean isHidden() {
        return false;
    }

    @Override
    public NativeStorage getParent() {
        return parentFolder;
    }

    @Override
    public String getName() {
        return Storage.getDocumentName(u);
    }

    @Override
    @TargetApi(21)
    public boolean isDirectory() {
        return !DocumentsContract.isDocumentUri(storage.getContext(), u);
    }

    @Override
    public long lastModified() {
        return storage.getLast(u);
    }

    @Override
    public long length() {
        return storage.getLength(u);
    }

    @Override
    public boolean renameTo(NativeStorage f) {
        String name = Storage.getDocumentName(((RarNativeStorageSAF) f).u);
        Uri m = storage.rename(u, name);
        return m != null;
    }

    @Override
    public void setLastModified(long l) {
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public boolean mkdirs() {
        Uri t = storage.createFolder(parent, Storage.getDocumentPath(u));
        return t != null;
    }

    @Override
    public boolean delete() {
        return storage.delete(u);
    }

    @Override
    @TargetApi(21)
    public NativeStorage[] listFiles() {
        ContentResolver resolver = storage.getContext().getContentResolver();
        Cursor c = resolver.query(u, null, null, null, null);
        if (c == null)
            return null;
        NativeStorage[] nn = new NativeStorage[c.getCount()];
        int i = 0;
        while (c.moveToNext()) {
            String id = c.getString(c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID));
            Uri f = DocumentsContract.buildChildDocumentsUriUsingTree(parent, id);
            nn[i] = new RarNativeStorageSAF(storage, parentFolder, f);
        }
        c.close();
        return nn;
    }

    @Override
    public String getPath() {
        return u.toString();
    }

}
