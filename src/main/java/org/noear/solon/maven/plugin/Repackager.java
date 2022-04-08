package org.noear.solon.maven.plugin;


import javassist.ClassPool;
import javassist.CtClass;
import org.apache.maven.plugin.logging.Log;
import org.noear.solon.maven.plugin.tools.tool.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.noear.solon.maven.plugin.tools.Constant.*;


/**
 * @author hxm
 */
public class Repackager {

    private static final byte[] ZIP_FILE_HEADER = new byte[]{'P', 'K', 3, 4};

    private final File source;

    private Layout layout;
    private Log logger;

    public Repackager(File source, Log logger) {
        this.logger = logger;
        if (source == null) {
            throw new IllegalArgumentException("Source file must be provided");
        }
        if (!source.exists() || !source.isFile()) {
            throw new IllegalArgumentException("Source must refer to an existing file, "
                    + "got " + source.getAbsolutePath());
        }
        this.source = source.getAbsoluteFile();
    }

    /**
     * Repackage to the given destination so that it can be launched using '
     * {@literal java -jar}'.
     *
     * @param destination the destination file (may be the same as the source)
     * @param libraries   the libraries required to run the archive
     * @throws IOException if the file cannot be repackaged
     */
    public void repackage(File destination, Libraries libraries) throws IOException {
        if (destination == null || destination.isDirectory()) {
            throw new IllegalArgumentException("Invalid destination");
        }
        if (libraries == null) {
            throw new IllegalArgumentException("Libraries must not be null");
        }
        if (this.layout == null) {
            this.layout = getLayoutFactory().getLayout(this.source);
        }
        if (alreadyRepackaged()) {
            return;
        }
        destination = destination.getAbsoluteFile();
        File workingSource = this.source;
        if (this.source.equals(destination)) {
            workingSource = getBackupFile();
            workingSource.delete();
            renameFile(this.source, workingSource);
        }
        destination.delete();
        JarFile jarFileSource = new JarFile(workingSource);
        try {
            repackage(jarFileSource, destination, libraries);
        } finally {
            jarFileSource.close();
        }
    }

    private LayoutFactory getLayoutFactory() {
        return new DefaultLayoutFactory();
    }

    /**
     * Return the {@link File} to use to backup the original source.
     *
     * @return the file to use to backup the original source
     */
    public final File getBackupFile() {
        return new File(this.source.getParentFile(), this.source.getName() + ".original");
    }

    private boolean alreadyRepackaged() throws IOException {
        JarFile jarFile = new JarFile(this.source);
        try {
            Manifest manifest = jarFile.getManifest();
            return (manifest != null && manifest.getMainAttributes()
                    .getValue(JAR_TOOL_VALUE) != null);
        } finally {
            jarFile.close();
        }
    }

    private void repackage(JarFile sourceJar, File destination, Libraries libraries) throws IOException {
        JarWriter writer = new JarWriter(destination);
        try {
            final List<Library> unpackLibraries = new ArrayList<>();
            final List<Library> standardLibraries = new ArrayList<>();
            libraries.doWithLibraries(library -> {
                File file = library.getFile();
                if (isZip(file)) {
                    if (library.isUnpackRequired()) {
                        unpackLibraries.add(library);
                    } else {
                        standardLibraries.add(library);
                    }
                }
            });
            repackage(sourceJar, writer, unpackLibraries, standardLibraries);
        } finally {
            try {
                writer.close();
            } catch (Exception ex) {
                // Ignore
            }
        }
    }

    private void repackage(JarFile sourceJar, JarWriter writer,
                           final List<Library> unpackLibraries, final List<Library> standardLibraries)
            throws IOException {
        writer.writeManifest(buildManifest(sourceJar));
        Set<String> seen = new HashSet<>();
        writeNestedLibraries(unpackLibraries, seen, writer);
        if (this.layout instanceof RepackagingLayout) {
            writer.writeEntries(sourceJar, new RenamingEntryTransformer(
                    ((RepackagingLayout) this.layout).getRepackagedClassesLocation()));
        } else {
            writer.writeEntries(sourceJar);
        }
        writeNestedLibraries(standardLibraries, seen, writer);
    }

    private void writeNestedLibraries(List<Library> libraries, Set<String> alreadySeen,
                                      JarWriter writer) throws IOException {
        for (Library library : libraries) {
            String destination = Repackager.this.layout
                    .getLibraryDestination(library.getName(), library.getScope());
            if (destination != null) {
                if (!alreadySeen.add(destination + library.getName())) {
                    throw new IllegalStateException(
                            "Duplicate library " + library.getName());
                }
                writer.writeNestedLibrary(destination, library);
            }
        }
    }

    private boolean isZip(File file) {
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            try {
                return isZip(fileInputStream);
            } finally {
                fileInputStream.close();
            }
        } catch (IOException ex) {
            return false;
        }
    }

    private boolean isZip(InputStream inputStream) throws IOException {
        for (int i = 0; i < ZIP_FILE_HEADER.length; i++) {
            if (inputStream.read() != ZIP_FILE_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    private Manifest buildManifest(JarFile source) throws IOException {
        Manifest manifest = source.getManifest();
        if (manifest == null) {
            manifest = new Manifest();
        }
        manifest = new Manifest(manifest);
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue(JAR_TOOL, JAR_TOOL_VALUE);
        manifest.getMainAttributes().putValue(START_CLASS, getStartClass());
        manifest.getMainAttributes().putValue("Main-Class", MAIN_CLASS);
        return manifest;
    }


    private String getStartClass() throws IOException {
        ClassPool pool = ClassPool.getDefault();
        File f = getBackupFile();
        URL url1 = f.toURI().toURL();
        URLClassLoader myClassLoader = new URLClassLoader(new URL[]{url1}, Thread.currentThread().getContextClassLoader());
        JarFile jar = new JarFile(f);
        Enumeration<JarEntry> enumFiles = jar.entries();

        while (enumFiles.hasMoreElements()) {
            JarEntry entry = enumFiles.nextElement();
            String classFullName = entry.getName();
            if (classFullName.endsWith(".class")) {
                try {
                    String className = classFullName.substring(0, classFullName.length() - 6).replace("/", ".");
                    Class<?> myclass = myClassLoader.loadClass(className);
                    Method[] declaredMethods = myclass.getDeclaredMethods();
                    for (Method declaredMethod : declaredMethods) {
                        if (declaredMethod.getName().equals("main") && declaredMethod.getParameterTypes().length == 1) {
                            if (declaredMethod.getParameterTypes()[0].equals(String[].class) && Modifier.isStatic(declaredMethod.getModifiers())) {
                                if (declaredMethod.getReturnType().getName().equals("void")) {

                                    //todo 带优化启动标识，目前检查主函数比较low

//                                    CtClass ctClass = pool.makeClass(jar.getInputStream(entry));
//                                    for (Object annotation : ctClass.getAnnotations()) {
//                                        if (annotation.toString().endsWith("HServerBoot")) {
                                            logger.info("启动类：" + myclass);
                                            return myclass.getName();
//                                        }
//                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        throw new IllegalStateException("找不到启动类,请使用@HServerBoot标记你的启动类");
    }

    private static Class<?> loadClass(String fullClzName) {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(fullClzName);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void renameFile(File file, File dest) {
        if (!file.renameTo(dest)) {
            throw new IllegalStateException(
                    "Unable to rename '" + file + "' to '" + dest + "'");
        }
    }

    private void deleteFile(File file) {
        if (!file.delete()) {
            throw new IllegalStateException("Unable to delete '" + file + "'");
        }
    }

    /**
     * An {@code EntryTransformer} that renames entries by applying a prefix.
     */
    private static final class RenamingEntryTransformer implements JarWriter.EntryTransformer {

        private final String namePrefix;

        private RenamingEntryTransformer(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public JarEntry transform(JarEntry entry) {
            if (entry.getName().equals("META-INF/INDEX.LIST")) {
                return null;
            }
            if ((entry.getName().startsWith("META-INF/")
                    && !entry.getName().equals("META-INF/aop.xml"))
                    || entry.getName().startsWith("BOOT-INF/")) {
                return entry;
            }
            JarEntry renamedEntry = new JarEntry(this.namePrefix + entry.getName());
            renamedEntry.setTime(entry.getTime());
            renamedEntry.setSize(entry.getSize());
            renamedEntry.setMethod(entry.getMethod());
            if (entry.getComment() != null) {
                renamedEntry.setComment(entry.getComment());
            }
            renamedEntry.setCompressedSize(entry.getCompressedSize());
            renamedEntry.setCrc(entry.getCrc());
            setCreationTimeIfPossible(entry, renamedEntry);
            if (entry.getExtra() != null) {
                renamedEntry.setExtra(entry.getExtra());
            }
            setLastAccessTimeIfPossible(entry, renamedEntry);
            setLastModifiedTimeIfPossible(entry, renamedEntry);
            return renamedEntry;
        }

        private void setCreationTimeIfPossible(JarEntry source, JarEntry target) {
            try {
                if (source.getCreationTime() != null) {
                    target.setCreationTime(source.getCreationTime());
                }
            } catch (NoSuchMethodError ex) {
                // Not running on Java 8. Continue.
            }
        }

        private void setLastAccessTimeIfPossible(JarEntry source, JarEntry target) {
            try {
                if (source.getLastAccessTime() != null) {
                    target.setLastAccessTime(source.getLastAccessTime());
                }
            } catch (NoSuchMethodError ex) {
                // Not running on Java 8. Continue.
            }
        }

        private void setLastModifiedTimeIfPossible(JarEntry source, JarEntry target) {
            try {
                if (source.getLastModifiedTime() != null) {
                    target.setLastModifiedTime(source.getLastModifiedTime());
                }
            } catch (NoSuchMethodError ex) {
                // Not running on Java 8. Continue.
            }
        }


    }

}