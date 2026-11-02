/* -*- Mode: Java; tab-width: 4; indent-tabs-mode: nil; c-basic-offset: 4 -*- */
/*
 * This file is part of the LibreOffice project.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.libreoffice.kit;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.util.Log;

import java.nio.ByteBuffer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

// Native methods in this class are all implemented in
// sal/android/lo-bootstrap.c as the lo-bootstrap library is loaded with
// System.loadLibrary() and Android's JNI works only to such libraries, it
// seems.
public final class LibreOfficeKit
{
    private static String LOGTAG = LibreOfficeKit.class.getSimpleName();
    private static AssetManager mgr;

    // private constructor because instantiating would be meaningless
    private LibreOfficeKit() {
    }

    // Trigger library initialization - as this is done automatically by executing the "static" block, this method remains empty. However we need this to manually (at the right time) can force library initialization.
    public static void initializeLibrary() {
    }

    // Trigger initialization on the JNI - LOKit side.
    private static native boolean initializeNative(String dataDir, String cacheDir, String apkFile, AssetManager mgr);

    public static native ByteBuffer getLibreOfficeKitHandle();

    // Wrapper for putenv()
    public static native void putenv(String string);

    // A method that starts a thread to redirect stdout and stderr writes to
    // the Android logging mechanism, or stops the redirection.
    public static native void redirectStdio(boolean state);

    static boolean initializeDone = false;

    public static synchronized boolean isInitialized() {
        return initializeDone;
    }

    /**
     * Files under assets/unpack are runtime files which LibreOffice expects on
     * the writable filesystem. In particular, unorc resolves UNO_TYPES under
     * $APP_DATA_DIR/program, so extract this tree before native initialization.
     */
    private static void copyUnpackedAssetTree(AssetManager assets, String assetPath, File targetDir)
            throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Cannot create LibreOffice runtime directory: " + targetDir);
        }
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            for (String child : children) {
                copyUnpackedAssetTree(assets, assetPath + "/" + child, new File(targetDir, child));
            }
            return;
        }

        File parent = targetDir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create LibreOffice runtime directory: " + parent);
        }
        File temporary = new File(parent, targetDir.getName() + ".lo-tmp");
        try (InputStream input = assets.open(assetPath);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException failure) {
            temporary.delete();
            throw failure;
        }
        if (targetDir.exists() && !targetDir.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace LibreOffice runtime file: " + targetDir);
        }
        if (!temporary.renameTo(targetDir)) {
            temporary.delete();
            throw new IOException("Cannot install LibreOffice runtime file: " + targetDir);
        }
    }

    private static void copyRequiredAsset(AssetManager assets, String assetPath, File destination)
            throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create LibreOffice program directory: " + parent);
        }
        File temporary = new File(parent, destination.getName() + ".verified-tmp");
        try (InputStream input = assets.open(assetPath);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException failure) {
            temporary.delete();
            throw new IOException("Cannot read required APK runtime asset " + assetPath, failure);
        }
        if (!temporary.isFile() || temporary.length() == 0L) {
            temporary.delete();
            throw new IOException("Required APK runtime asset is empty: " + assetPath);
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace LibreOffice runtime file: " + destination);
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Cannot install LibreOffice runtime file: " + destination);
        }
    }

    private static void prepareUnpackedRuntime(AssetManager assets, String dataDir) {
        File root = new File(dataDir);
        try {
            copyUnpackedAssetTree(assets, "unpack", root);
            File programDir = new File(root, "program");
            String[] required = {"udkapi.rdb", "offapi.rdb", "oovbaapi.rdb"};
            // Extract the three UNO registries explicitly as well. This avoids
            // relying on recursive AssetManager directory enumeration for the
            // files that the bootstrap loader must resolve from APP_DATA_DIR.
            for (String name : required) {
                copyRequiredAsset(assets, "unpack/program/" + name, new File(programDir, name));
            }
            for (String name : required) {
                File registry = new File(programDir, name);
                if (!registry.isFile() || registry.length() == 0L || !registry.canRead()) {
                    throw new IOException("UNO type registry is unavailable after extraction: " + registry);
                }
                Log.i(LOGTAG, "UNO registry ready: " + registry.getAbsolutePath()
                        + " (" + registry.length() + " bytes)");
            }
            Log.i(LOGTAG, "LibreOffice UNO runtime prepared in " + root.getAbsolutePath());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to prepare LibreOffice UNO runtime files", failure);
        }
    }

    // This init() method should be called from the upper Java level of
    // LO-based apps.
    public static synchronized void init(Activity activity)
    {
        init((Context) activity);
    }

    // Context-based entry point allows initialization in an isolated Android
    // service process without manufacturing or retaining an Activity.
    public static synchronized void init(Context context)
    {
        if (initializeDone) {
            return;
        }

        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        mgr = appContext.getResources().getAssets();

        ApplicationInfo applicationInfo = appContext.getApplicationInfo();
        String dataDir = applicationInfo.dataDir;
        prepareUnpackedRuntime(mgr, dataDir);
        Log.i(LOGTAG, String.format("Initializing LibreOfficeKit, dataDir=%s\n", dataDir));

        redirectStdio(true);

        String cacheDir = appContext.getCacheDir().getAbsolutePath();
        String apkFile = appContext.getPackageResourcePath();

        if (!initializeNative(dataDir, cacheDir, apkFile, mgr)) {
            Log.e(LOGTAG, "Initialize native failed!");
            return;
        }
        initializeDone = true;
    }

    // Now with static loading we always have all native code in one native
    // library which we always call liblo-native-code.so, regardless of the
    // app. The library has already been unpacked into /data/data/<app
    // name>/lib at installation time by the package manager.
    static {
        NativeLibLoader.load();
    }
}

class NativeLibLoader {
        private static boolean done = false;

        protected static synchronized void load() {
            if (done)
                return;
            System.loadLibrary("nspr4");
            System.loadLibrary("plds4");
            System.loadLibrary("plc4");
            System.loadLibrary("nssutil3");
            System.loadLibrary("freebl3");
            System.loadLibrary("sqlite3");
            System.loadLibrary("softokn3");
            System.loadLibrary("nss3");
            System.loadLibrary("nssckbi");
            System.loadLibrary("nssdbm3");
            System.loadLibrary("smime3");
            System.loadLibrary("ssl3");

            System.loadLibrary("c++_shared");
            System.loadLibrary("lo-native-code");
            done = true;
        }
}

/* vim:set shiftwidth=4 softtabstop=4 expandtab: */
