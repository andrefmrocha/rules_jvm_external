// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package rules.jvm.external.jar;

import rules.jvm.external.ByteStreams;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.zip.Deflater.BEST_COMPRESSION;
import static java.util.zip.ZipOutputStream.DEFLATED;
import static rules.jvm.external.jar.DuplicateEntryStrategy.LAST_IN_WINS;


public class MergeJars {

  // File time is taken from the epoch (1970-01-01T00:00:00Z), but zip files
  // have a different epoch. Oof. Make something sensible up.
  private static final FileTime DOS_EPOCH = FileTime.from(Instant.parse("1985-02-01T00:00:00.00Z"));

  public static void main(String[] args) throws IOException {
    Path out = null;
    // Insertion order may matter
    Set<Path> sources = new LinkedHashSet<>();
    DuplicateEntryStrategy onDuplicate = LAST_IN_WINS;
    String mainClass = null;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--compression":
        case "--normalize":
          // ignore
          break;

        case "--duplicates":
          onDuplicate = DuplicateEntryStrategy.fromShortName(args[++i]);
          break;

        case "--output":
          out = Paths.get(args[++i]);
          break;

        case "--main_class":
          mainClass = args[++i];
          break;

        case "--sources":
          Path path = Paths.get(args[++i]);
          if (!Files.exists(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("Source must a readable file: " + path);
          }
          sources.add(path);
          break;

        default:
          throw new IllegalArgumentException("Unable to parse command line: " + Arrays.toString(args));
      }
    }

    Objects.requireNonNull(out, "Output path must be set.");
    if (sources.isEmpty()) {
      // Just write an empty jar and leave
      try (OutputStream fos = Files.newOutputStream(out);
           JarOutputStream jos = new JarOutputStream(fos)) {
      }
      return;
    }

    // To keep things simple, we expand all the inputs jars into a single directory,
    // merge the manifests, and then create our own zip.
    Path temp = Files.createTempDirectory("mergejars");

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

    Map<String, Set<String>> allServices = new TreeMap<>();

    Map<Path, SortedMap<Path, Path>> allPaths = new TreeMap<>();

    for (Path source : sources) {
      try (InputStream fis = Files.newInputStream(source);
           ZipInputStream zis = new ZipInputStream(fis)) {

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
          if ("META-INF/MANIFEST.MF".equals(entry.getName())) {
            Manifest other = new Manifest(zis);
            manifest = merge(manifest, other);
            continue;
          }

          if (entry.isDirectory()) {
            skipEntry(zis);
            continue;
          }

          if (entry.getName().startsWith("META-INF/services/")) {
            String servicesName = entry.getName().substring("META-INF/services/".length());
            Set<String> services = allServices.computeIfAbsent(servicesName, key -> new TreeSet<>());
            String content = new String(ByteStreams.toByteArray(zis));
            services.addAll(Arrays.asList(content.split("\n")));
            continue;
          }

          Path outPath = temp.resolve(entry.getName()).normalize();
          if (!outPath.startsWith(temp)) {
            throw new IllegalStateException("Attempt to write jar entry somewhere weird: " + outPath);
          }
          if (!Files.exists(outPath.getParent())) {
            Files.createDirectories(outPath.getParent());
          }

          if (Files.exists(outPath)) {
            // Write the file out to a temporary location, and then figure out what to do
            onDuplicate.resolve(outPath, zis);
          } else {
            try (OutputStream os = Files.newOutputStream(outPath)) {
              ByteStreams.copy(zis, os);
            }

            SortedMap<Path, Path> dirPaths = allPaths.computeIfAbsent(
              temp.relativize(outPath.getParent()),
              path -> new TreeMap<>());
            dirPaths.put(temp.relativize(outPath), outPath);
          }
        }
      }
    }

    manifest.getMainAttributes().put(new Attributes.Name("Created-By"), "mergejars");
    if(mainClass != null)
      manifest.getMainAttributes().put(new Attributes.Name("Main-Class"), mainClass);

    Set<String> seen = new HashSet<>(Arrays.asList("META-INF/", "META-INF/MANIFEST.MF"));

    // Now create the output jar
    try (OutputStream os = Files.newOutputStream(out);
         JarOutputStream jos = new JarOutputStream(os)) {
      jos.setMethod(DEFLATED);
      jos.setLevel(BEST_COMPRESSION);

      // Write the manifest by hand to ensure the date is good
      JarEntry entry = new JarEntry("META-INF/");
      entry = resetTime(entry);
      jos.putNextEntry(entry);
      jos.closeEntry();

      ByteArrayOutputStream bos = new ByteArrayOutputStream();

      if (!allServices.isEmpty()) {
        entry = new JarEntry("META-INF/services/");
        entry = resetTime(entry);
        jos.putNextEntry(entry);
        jos.closeEntry();
        for (Map.Entry<String, Set<String>> kv : allServices.entrySet()) {
          entry = new JarEntry("META-INF/services/" + kv.getKey());
          entry = resetTime(entry);
          bos = new ByteArrayOutputStream();
          bos.write(String.join("\n", kv.getValue()).getBytes());
          entry.setSize(bos.size());
          jos.putNextEntry(entry);
          jos.write(bos.toByteArray());
          jos.closeEntry();
        }
      }

      allPaths.forEach((dir, entries) -> {
        try {
          String name = dir.toString().replace('\\', '/') + "/";
          if (seen.add(name)) {
            JarEntry je = new JarEntry(name);
            je = resetTime(je);
            jos.putNextEntry(je);
            jos.closeEntry();
          }

          for (Map.Entry<Path, Path> me : entries.entrySet()) {
            name = me.getKey().toString().replace('\\', '/');

            if (seen.add(name)) {
              JarEntry je = new JarEntry(name);
              je = resetTime(je);
              jos.putNextEntry(je);
              try (InputStream fis = Files.newInputStream(me.getValue())) {
                ByteStreams.copy(fis, jos);
              }
              jos.closeEntry();
            }
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    }

    delete(temp);
  }

  @SuppressWarnings("CheckReturnValue")
  private static void skipEntry(ZipInputStream zis) throws IOException {
    ByteStreams.toByteArray(zis);
  }

  private static void delete(Path toDelete) throws IOException {
    Files.walk(toDelete)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
  }

  private static JarEntry resetTime(JarEntry entry) {
    entry.setTime(DOS_EPOCH.toMillis());
    return entry;
  }

  private static Manifest merge(Manifest into, Manifest from) {
    Attributes attributes = from.getMainAttributes();
    if (attributes != null) {
      attributes.forEach((key, value) -> into.getMainAttributes().put(key, value));
    }

    from.getEntries().forEach((key, value) -> {
      Attributes attrs = into.getAttributes(key);
      if (attrs == null) {
        attrs = new Attributes();
        into.getEntries().put(key, attrs);
      }
      attrs.putAll(value);
    });

    return into;
  }
}
