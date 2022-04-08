package org.noear.solon.maven.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.noear.solon.maven.plugin.tools.tool.ArtifactsLibraries;
import org.noear.solon.maven.plugin.tools.tool.Libraries;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

@Mojo(name = "repackage", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class SolonPackagerMojo extends AbstractMojo {

    @Parameter(required = true)
    private String mainClass;

    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.build.finalName}", required = true)
    private String finalName;

    @Parameter
    private String classifier;

    @Component
    private MavenProject project;

    private Log logger = getLog();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        repackage();
        //处理loader
        try {
            CopyLoader.start(getTargetFile());
        } catch (Exception e) {
            throw new MojoExecutionException("write loader exception", e);
        }
    }

    private void repackage() throws MojoExecutionException, MojoFailureException {
        File sourceFile = project.getArtifact().getFile();
        Repackager repackager = new Repackager(sourceFile,logger,mainClass);
        File target = getTargetFile();
        Set<Artifact> artifacts = project.getArtifacts();
        Libraries libraries = new ArtifactsLibraries(artifacts, Collections.emptyList(), getLog());
        try {
            repackager.repackage(target, libraries);
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private File getTargetFile() {
        String classifier = (this.classifier != null ? this.classifier.trim() : "");
        if (classifier.length() > 0 && !classifier.startsWith("-")) {
            classifier = "-" + classifier;
        }
        if (!this.outputDirectory.exists()) {
            this.outputDirectory.mkdirs();
        }
        String name = this.finalName + classifier + "." + this.project.getArtifact().getArtifactHandler().getExtension();
        return new File(this.outputDirectory, name);
    }

}
