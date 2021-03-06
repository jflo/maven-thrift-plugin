package org.apache.thrift.maven;

import com.google.common.collect.ImmutableSet;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.io.RawInputStreamFacade;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.google.common.base.Join.join;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.list;
import static org.codehaus.plexus.util.FileUtils.cleanDirectory;
import static org.codehaus.plexus.util.FileUtils.copyStreamToFile;
import static org.codehaus.plexus.util.FileUtils.getFiles;

/**
 * Abstract Mojo implementation.
 * <p/>
 * This class is extended by {@link org.apache.thrift.maven.ThriftCompileMojo} and
 * {@link org.apache.thrift.maven.ThriftTestCompileMojo} in order to override the specific configuration for
 * compiling the main or test classes respectively.
 *
 * @author Gregory Kick
 * @author David Trott
 * @author Brice Figureau
 */
abstract class AbstractThriftMojo extends AbstractMojo {

    private static final String THRIFT_FILE_SUFFIX = ".thrift";

    private static final String DEFAULT_INCLUDES = "**/*" + THRIFT_FILE_SUFFIX;

    /**
     * The current Maven project.
     *
     * @parameter default-value="${project}"
     * @readonly
     * @required
     */
    protected MavenProject project;

    /**
     * A helper used to add resources to the project.
     *
     * @component
     * @required
     */
    protected MavenProjectHelper projectHelper;

    /**
     * This is the path to the {@code thrift} executable. By default it will search the {@code $PATH}.
     *
     * @parameter default-value="thrift"
     * @required
     */
    private String thriftExecutable;

    /**
     * @parameter
     */
    private File[] additionalThriftPathElements = new File[]{};

    /**
     * Since {@code thrift} cannot access jars, thrift files in dependencies are extracted to this location
     * and deleted on exit. This directory is always cleaned during execution.
     *
     * @parameter expression="${project.build.directory}/thrift-dependencies"
     * @required
     */
    private File temporaryThriftFileDirectory;

    /**
     * This is the path to the local maven {@code repository}.
     *
     * @parameter default-value="${localRepository}"
     * @required
     */
    private DefaultArtifactRepository localRepository;

    /**
     * Set this to {@code false} to disable hashing of dependent jar paths.
     * <p/>
     * This plugin expands jars on the classpath looking for embedded .thrift files.
     * Normally these paths are hashed (MD5) to avoid issues with long file names on windows.
     * However if this property is set to {@code false} longer paths will be used.
     *
     * @parameter default-value="true"
     * @required
     */
    private boolean hashDependentPaths;

    /**
     * @parameter
     */
    private Set<String> includes = ImmutableSet.of(DEFAULT_INCLUDES);

    /**
     * @parameter
     */
    private Set<String> excludes = ImmutableSet.of();

    /**
     * Executes the mojo.
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkParameters();
        final File thriftSourceRoot = getThriftSourceRoot();
        if (thriftSourceRoot.exists()) {
            try {
                ImmutableSet<File> thriftFiles = findThriftFilesInDirectory(thriftSourceRoot);
                if (thriftFiles.isEmpty()) {
                    getLog().info("No thrift files to compile.");
                } else {
                    ImmutableSet<File> derivedThriftPathElements =
                            makeThriftPathFromJars(temporaryThriftFileDirectory, getDependencyArtifactFiles());
                    final File outputDirectory = getOutputDirectory();
                    outputDirectory.mkdirs();

                    // Quick fix to fix issues with two mvn installs in a row (ie no clean)
                    cleanDirectory(outputDirectory);

                    Thrift thrift = new Thrift.Builder(thriftExecutable, outputDirectory)
                            .addThriftPathElement(thriftSourceRoot)
                            .addThriftPathElements(derivedThriftPathElements)
                            .addThriftPathElements(asList(additionalThriftPathElements))
                            .addThriftFiles(thriftFiles)
                            .build();
                    final int exitStatus = thrift.compile();
                    if (exitStatus != 0) {
                        getLog().error("thrift failed output: " + thrift.getOutput());
                        getLog().error("thrift failed error: " + thrift.getError());
                        throw new MojoFailureException(
                                "thrift did not exit cleanly. Review output for more information.");
                    }
                    attachFiles();
                }
            } catch (IOException e) {
                throw new MojoExecutionException("An IO error occured", e);
            } catch (IllegalArgumentException e) {
                throw new MojoFailureException("thrift failed to execute because: " + e.getMessage(), e);
            } catch (CommandLineException e) {
                throw new MojoExecutionException("An error occurred while invoking thrift.", e);
            }
        } else {
            getLog().info(format("%s does not exist. Review the configuration or consider disabling the plugin.",
                    thriftSourceRoot));
        }
    }

    private void checkParameters() {
        checkNotNull(project, "project");
        checkNotNull(projectHelper, "projectHelper");
        checkNotNull(thriftExecutable, "thriftExecutable");
        final File thriftSourceRoot = getThriftSourceRoot();
        checkNotNull(thriftSourceRoot);
        checkArgument(!thriftSourceRoot.isFile(), "thriftSourceRoot is a file, not a diretory");
        checkNotNull(temporaryThriftFileDirectory, "temporaryThriftFileDirectory");
        checkState(!temporaryThriftFileDirectory.isFile(), "temporaryThriftFileDirectory is a file, not a directory");
        final File outputDirectory = getOutputDirectory();
        checkNotNull(outputDirectory);
        checkState(!outputDirectory.isFile(), "the outputDirectory is a file, not a directory");
    }

    protected abstract File getThriftSourceRoot();

    protected abstract List<Artifact> getDependencyArtifacts();

    protected abstract File getOutputDirectory();

    protected abstract void attachFiles();

    /**
     * Gets the {@link File} for each dependency artifact.
     *
     * @return A set of all dependency artifacts.
     */
    private ImmutableSet<File> getDependencyArtifactFiles() {
        Set<File> dependencyArtifactFiles = newHashSet();
        for (Artifact artifact : getDependencyArtifacts()) {
            dependencyArtifactFiles.add(artifact.getFile());
        }
        return ImmutableSet.copyOf(dependencyArtifactFiles);
    }

    /**
     * @throws IOException
     */
    ImmutableSet<File> makeThriftPathFromJars(File temporaryThriftFileDirectory, Iterable<File> classpathElementFiles)
            throws IOException, MojoExecutionException {
        checkNotNull(classpathElementFiles, "classpathElementFiles");
        // clean the temporary directory to ensure that stale files aren't used
        if (temporaryThriftFileDirectory.exists()) {
            cleanDirectory(temporaryThriftFileDirectory);
        }
        Set<File> thriftDirectories = newHashSet();
        for (File classpathElementFile : classpathElementFiles) {
            if (classpathElementFile.isFile() && classpathElementFile.canRead()) {
                // create the jar file. the constructor validates.
                JarFile classpathJar;
                try {
                    classpathJar = new JarFile(classpathElementFile);
                } catch (IOException e) {
                    throw new IllegalArgumentException(format(
                            "%s was not a readable artifact", classpathElementFile));
                }
                for (JarEntry jarEntry : list(classpathJar.entries())) {
                    final String jarEntryName = jarEntry.getName();
                    if (jarEntry.getName().endsWith(THRIFT_FILE_SUFFIX)) {
                        final File uncompressedCopy =
                                new File(new File(temporaryThriftFileDirectory,
                                        truncatePath(classpathJar.getName())), jarEntryName);
                        uncompressedCopy.getParentFile().mkdirs();
                        copyStreamToFile(new RawInputStreamFacade(classpathJar
                                .getInputStream(jarEntry)), uncompressedCopy);
                        thriftDirectories.add(uncompressedCopy.getParentFile());
                    }
                }
            } else if (classpathElementFile.isDirectory()) {
                File[] thriftFiles = classpathElementFile.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.endsWith(THRIFT_FILE_SUFFIX);
                    }
                });

                if (thriftFiles.length > 0) {
                    thriftDirectories.add(classpathElementFile);
                }
            }
        }
        return ImmutableSet.copyOf(thriftDirectories);
    }

    ImmutableSet<File> findThriftFilesInDirectory(File directory) throws IOException {
        checkNotNull(directory);
        checkArgument(directory.isDirectory(), "%s is not a directory", directory);
        // TODO(gak): plexus-utils needs generics
        @SuppressWarnings("unchecked")
        List<File> thriftFilesInDirectory = getFiles(directory, join(",", includes), join(",", excludes));
        return ImmutableSet.copyOf(thriftFilesInDirectory);
    }

    ImmutableSet<File> findThriftFilesInDirectories(Iterable<File> directories) throws IOException {
        checkNotNull(directories);
        Set<File> thriftFiles = newHashSet();
        for (File directory : directories) {
            thriftFiles.addAll(findThriftFilesInDirectory(directory));
        }
        return ImmutableSet.copyOf(thriftFiles);
    }

    /**
     * Truncates the path of jar files so that they are relative to the local repository.
     *
     * @param jarPath the full path of a jar file.
     * @return the truncated path relative to the local repository or root of the drive.
     */
    String truncatePath(final String jarPath) throws MojoExecutionException {

        if (hashDependentPaths) {
            try {
                return toHexString(MessageDigest.getInstance("MD5").digest(jarPath.getBytes()));
            } catch (NoSuchAlgorithmException e) {
                throw new MojoExecutionException("Failed to expand dependent jar", e);
            }
        }

        String repository = localRepository.getBasedir().replace('\\', '/');
        if (!repository.endsWith("/")) {
            repository += "/";
        }

        String path = jarPath.replace('\\', '/');
        int repositoryIndex = path.indexOf(repository);
        if (repositoryIndex != -1) {
            path = path.substring(repositoryIndex + repository.length());
        }

        // By now the path should be good, but do a final check to fix windows machines.
        int colonIndex = path.indexOf(':');
        if (colonIndex != -1) {
            // 2 = :\ in C:\
            path = path.substring(colonIndex + 2);
        }

        return path;
    }

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    public static String toHexString(byte[] byteArray) {
        final StringBuilder hexString = new StringBuilder(2 * byteArray.length);
        for (final byte b : byteArray) {
            hexString.append(HEX_CHARS[(b & 0xF0) >> 4]).append(HEX_CHARS[b & 0x0F]);
        }
        return hexString.toString();
    }
}
