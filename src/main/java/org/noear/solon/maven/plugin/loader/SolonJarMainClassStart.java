package org.noear.solon.maven.plugin.loader;

import org.noear.solon.maven.plugin.loader.archive.Archive;
import org.noear.solon.maven.plugin.loader.util.AsciiBytes;

import java.util.List;


/**
 * @author hxm
 */
public class SolonJarMainClassStart extends ExecutableArchiveLauncher {

    private static final AsciiBytes LIB = new AsciiBytes("lib/");

    public SolonJarMainClassStart() {
    }

    protected SolonJarMainClassStart(Archive archive) {
        super(archive);
    }

    @Override
    protected boolean isNestedArchive(Archive.Entry entry) {
        return !entry.isDirectory() && entry.getName().startsWith(LIB);
    }

    @Override
    protected void postProcessClassPathArchives(List<Archive> archives) throws Exception {
        archives.add(0, getArchive());
    }

    public static void main(String[] args) {
        new JarLauncher().launch(args);
    }

}
