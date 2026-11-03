package dev.openvm.container;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.view.LayoutInflater;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/** The Context a container app sees: its own package name, resources, class loader and private storage. */
public class PluginContext extends ContextWrapper {
    final ContainerApp app;
    private Resources.Theme theme;

    PluginContext(Context base, ContainerApp app) { super(base); this.app = app; }

    @Override public String getPackageName() { return app.pkg; }
    @Override public ApplicationInfo getApplicationInfo() { return app.ai; }
    @Override public Resources getResources() { return app.res; }
    @Override public AssetManager getAssets() { return app.res.getAssets(); }
    @Override public ClassLoader getClassLoader() { return app.cl; }
    @Override public String getPackageCodePath() { return app.ai.sourceDir; }
    @Override public String getPackageResourcePath() { return app.ai.sourceDir; }
    @Override public Context getApplicationContext() { return app.app != null ? app.app : this; }

    @Override public Resources.Theme getTheme() {
        if (theme == null) { theme = app.res.newTheme(); theme.applyStyle(app.themeRes(), true); }
        return theme;
    }
    @Override public void setTheme(int resid) { theme = app.res.newTheme(); theme.applyStyle(resid, true); }

    @Override public Context createPackageContext(String packageName, int flags) throws android.content.pm.PackageManager.NameNotFoundException {
        if (app.pkg.equals(packageName)) return this;
        return super.createPackageContext(packageName, flags);
    }

    @Override public Object getSystemService(String name) {
        if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) return LayoutInflater.from(getBaseContext()).cloneInContext(this);
        return super.getSystemService(name);
    }

    // ---------------------------------------------------------------- private storage
    private File dir(String n) { File f = new File(app.dataDir, n); f.mkdirs(); return f; }
    @Override public File getDataDir() { app.dataDir.mkdirs(); return app.dataDir; }
    @Override public File getFilesDir() { return dir("files"); }
    @Override public File getNoBackupFilesDir() { return dir("no_backup"); }
    @Override public File getCacheDir() { return dir("cache"); }
    @Override public File getCodeCacheDir() { return dir("code_cache"); }
    @Override public File getDir(String name, int mode) { return dir("app_" + name); }
    @Override public File getFileStreamPath(String name) { return new File(getFilesDir(), name); }
    @Override public String[] fileList() { String[] l = getFilesDir().list(); return l == null ? new String[0] : l; }
    @Override public boolean deleteFile(String name) { return getFileStreamPath(name).delete(); }
    @Override public FileInputStream openFileInput(String name) throws FileNotFoundException { return new FileInputStream(getFileStreamPath(name)); }
    @Override public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        return new FileOutputStream(getFileStreamPath(name), (mode & Context.MODE_APPEND) != 0);
    }
    @Override public File getExternalFilesDir(String type) {
        File b = super.getExternalFilesDir(null); if (b == null) return null;
        File f = new File(b, "capps/" + app.pkg + (type == null ? "" : "/" + type)); f.mkdirs(); return f;
    }
    @Override public File getExternalCacheDir() {
        File b = super.getExternalCacheDir(); if (b == null) return null;
        File f = new File(b, "capps/" + app.pkg); f.mkdirs(); return f;
    }
    @Override public File[] getExternalFilesDirs(String type) { File f = getExternalFilesDir(type); return f == null ? new File[0] : new File[]{f}; }
    @Override public File[] getExternalCacheDirs() { File f = getExternalCacheDir(); return f == null ? new File[0] : new File[]{f}; }

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences("capp." + app.pkg + "." + (name == null ? "null" : name), Context.MODE_PRIVATE);
    }
    @Override public boolean deleteSharedPreferences(String name) { return super.deleteSharedPreferences("capp." + app.pkg + "." + name); }

    @Override public File getDatabasePath(String name) {
        if (name.startsWith("/")) return new File(name);
        return new File(dir("databases"), name);
    }
    @Override public String[] databaseList() { String[] l = dir("databases").list(); return l == null ? new String[0] : l; }
    @Override public boolean deleteDatabase(String name) { return SQLiteDatabase.deleteDatabase(getDatabasePath(name)); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory f) {
        File p = getDatabasePath(name); p.getParentFile().mkdirs();
        return SQLiteDatabase.openOrCreateDatabase(p, f);
    }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory f, DatabaseErrorHandler h) {
        File p = getDatabasePath(name); p.getParentFile().mkdirs();
        return SQLiteDatabase.openDatabase(p.getPath(), f, SQLiteDatabase.CREATE_IF_NECESSARY, h);
    }

    // ---------------------------------------------------------------- services run inside OpenVM
    @Override public ComponentName startService(Intent service) {
        ComponentName c = ContainerManager.serviceTarget(this, service);
        return c != null ? app.services.start(c, service) : super.startService(service);
    }
    @Override public ComponentName startForegroundService(Intent service) { return startService(service); }
    @Override public boolean stopService(Intent service) {
        ComponentName c = ContainerManager.serviceTarget(this, service);
        return c != null ? app.services.stop(c) : super.stopService(service);
    }
    @Override public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        ComponentName c = ContainerManager.serviceTarget(this, service);
        return c != null ? app.services.bind(c, service, conn) : super.bindService(service, conn, flags);
    }
    @Override public void unbindService(ServiceConnection conn) {
        if (!app.services.unbind(conn)) super.unbindService(conn);
    }
}
