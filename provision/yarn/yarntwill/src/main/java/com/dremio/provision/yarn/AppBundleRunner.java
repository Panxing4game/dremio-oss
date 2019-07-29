/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.provision.yarn;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.provision.yarn.AppBundleRunnable.Arguments;
import com.google.common.base.Preconditions;


/**
 * Runs a bundled jar generated by {@code AppBundleGenerator} and specified by jarPath.
 *
 * 1. Loads the bundled jar and its dependencies into a class loader
 *
 * 2. Instantiates an instance of the class {#mainClassName} and calls main({#args}) on it.
 */
public class AppBundleRunner implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(AppBundleRunner.class);

  private final File jarFile;
  private final Arguments arguments;

  private URLClassLoader bundleJarClassLoader;
  private Runnable runnable;

  public AppBundleRunner(File jarFile, Arguments arguments) {
    Preconditions.checkArgument(jarFile != null, "Jar file cannot be null");
    Preconditions.checkArgument(jarFile.exists(), "Jar file %s must exist", jarFile.getAbsolutePath());
    Preconditions.checkArgument(jarFile.canRead(), "Jar file %s must be readable", jarFile.getAbsolutePath());
    Preconditions.checkArgument(arguments.getMainClassName() != null, "Main class name cannot be null", jarFile.getAbsolutePath());

    this.jarFile = jarFile;
    this.arguments = arguments;
  }

  public URLClassLoader load() throws IOException, ReflectiveOperationException {
    final File inputJarFile = this.jarFile;
    final Path outputJarDir = Files.createTempDirectory("");

    logger.debug("Unpacking jar to {}", outputJarDir.toAbsolutePath());
    try(JarFile jarFile = new JarFile(inputJarFile)) {
      unJar(jarFile, outputJarDir);
    }

    logger.debug("Loading jars into ClassLoader");
    Path manifestPath = outputJarDir.resolve(JarFile.MANIFEST_NAME);
    Preconditions.checkArgument(Files.exists(manifestPath) && Files.isReadable(manifestPath), "Jar file %s must contain a valid manifest file", jarFile.getAbsolutePath());

    final String classPath;
    final String nativeLibraryPath;
    try(InputStream is = Files.newInputStream(manifestPath)) {
      final Manifest manifest = new Manifest(is);
      classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
      nativeLibraryPath = Optional.ofNullable(manifest.getMainAttributes().getValue(AppBundleGenerator.X_DREMIO_LIBRARY_PATH_MANIFEST_ATTRIBUTE)).orElse("");
    }

    // Convert the list of relative classpath URLs into absolute ones
    final List<URL> classPathUrls = Arrays.stream(classPath.split(" ")).map(s -> {
      final URI uri = URI.create(s);
      final Path finalPath = outputJarDir.resolve(uri.getPath());
      try {
        return finalPath.toUri().toURL();
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException(e);
      }
    }).collect(Collectors.toList());

    final URL[] classPathUrlArray = classPathUrls.toArray(new URL[classPathUrls.size()]);
    if (logger.isDebugEnabled()) {
      for (URL url : classPathUrlArray) {
        logger.debug("Loading jar: {}", url.getPath());
      }
    }

    // Convert the list of relative native library path URLs into absolute ones
    final List<Path> nativeLibraryPaths = Arrays.stream(nativeLibraryPath.split(" ")).map(s -> {
      final URI uri = URI.create(s);
      return outputJarDir.resolve(uri.getPath());
    }).collect(Collectors.toList());
    logger.debug("Native Library path: {}", nativeLibraryPaths);


    // Pick-up the classloader used as the parent to the System (application) classloader
    // so that JVM extension classloader is also included, but application classloader used
    // to bootstrap twill container is excluded.
    final ClassLoader parentClassLoader = ClassLoader.getSystemClassLoader() != null
        ? ClassLoader.getSystemClassLoader().getParent()
        : null;
    bundleJarClassLoader = new BundledDaemonClassLoader(classPathUrlArray, parentClassLoader, nativeLibraryPaths);

    Thread.currentThread().setContextClassLoader(bundleJarClassLoader);

    final String mainClassName = arguments.getMainClassName();
    logger.debug("Instantiating instance of {}", mainClassName);
    final Class<?> cls = bundleJarClassLoader.loadClass(mainClassName);

    Preconditions.checkArgument(Runnable.class.isAssignableFrom(cls), "{} does not implement `java.lang.Runnable` interface");
    Constructor<? extends Runnable> constructor = cls.asSubclass(Runnable.class).getConstructor(String[].class);

    runnable = constructor.newInstance(new Object[] { arguments.getMainArgs() });

    return bundleJarClassLoader;
  }

  public void run() throws Exception {
    Preconditions.checkNotNull(runnable, "Must call load() first");
    String mainClassName = arguments.getMainClassName();
    String[] args = arguments.getMainArgs();

    try {
      logger.info("Invoking {}.run({})", mainClassName, Arrays.toString(args));
      runnable.run();
    } catch (Throwable t) {
      logger.error("Error while trying to run {} within {}", mainClassName, jarFile.getAbsolutePath(), t);
      throw t;
    }
  }

  public void stop() throws Exception {
    Preconditions.checkNotNull(runnable, "Must call load() first");
    if (runnable instanceof AutoCloseable) {
      ((AutoCloseable) runnable).close();
    }
  }

  @Override
  public void close() throws IOException {
    if (bundleJarClassLoader != null) {
      bundleJarClassLoader.close();
    }
  }

  private void unJar(JarFile jarFile, Path targetDirectory) throws IOException {
    Enumeration<JarEntry> entries = jarFile.entries();
    while (entries.hasMoreElements()) {
      JarEntry entry = entries.nextElement();
      Path output = targetDirectory.resolve(entry.getName());

      if (entry.isDirectory()) {
        Files.createDirectories(output);
      } else {
        Files.createDirectories(output.getParent());

        try (InputStream is = jarFile.getInputStream(entry)) {
          Files.copy(is, output);
        }
      }
    }
  }
}
