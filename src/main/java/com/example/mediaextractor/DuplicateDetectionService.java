package com.example.mediaextractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DuplicateDetectionService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionService.class);

    private final Set<String> seenFullDigests = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Set<String>> sizeToSparseHashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, AtomicInteger> baseFileCounters = new ConcurrentHashMap<>();
    private final AtomicBoolean preIndexed = new AtomicBoolean(false);

    public void clear() {
        seenFullDigests.clear();
        sizeToSparseHashes.clear();
        baseFileCounters.clear();
        preIndexed.set(false);
    }

    /**
     * Pre-indexes existing files in the output directory so subsequent runs are idempotent.
     */
    public void indexExistingDirectory(Path baseMemoriesDir) {
        if (!Files.exists(baseMemoriesDir) || !preIndexed.compareAndSet(false, true)) {
            return;
        }

        log.info("Indexing existing memories directory for cross-run deduplication: {}", baseMemoriesDir);
        long start = System.currentTimeMillis();
        AtomicInteger indexedCount = new AtomicInteger(0);

        try {
            Files.walkFileTree(baseMemoriesDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.getFileName() != null) {
                        String name = dir.getFileName().toString();
                        if (name.equalsIgnoreCase("quarantine") || MediaExtractorService.isSystemOrRecycleDirectory(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Skipping inaccessible path during deduplication indexing: {} ({})", file, exc.getMessage());
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !file.getFileName().toString().startsWith(".")) {
                        long size = attrs.size();
                        if (size > 0) {
                            String digest = computeFullDigest(file);
                            if (digest != null) {
                                registerDigest(digest, size, file);
                                indexedCount.incrementAndGet();
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Completed indexing {} existing files in {}ms", indexedCount.get(), System.currentTimeMillis() - start);
        } catch (IOException e) {
            log.warn("Unable to completely index existing memories directory: {}", e.getMessage());
        }
    }

    public boolean isDuplicate(String fullDigest) {
        return fullDigest != null && seenFullDigests.contains(fullDigest);
    }

    public record DeduplicationResult(
            Set<Path> duplicates,
            Set<Path> validFiles,
            Map<Path, String> fullDigests
    ) {}

    public DeduplicationResult detectDuplicatesMultiTier(Collection<Path> files) {
        try (ExecutorService vExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            return detectDuplicatesMultiTier(files, vExecutor);
        }
    }

    /**
     * Multi-tier zero-I/O duplicate detection across a collection of candidate files:
     * Tier 1: Group by exact file size. Files with unique sizes require ZERO file I/O.
     * Tier 2: For files sharing identical size, compute sparse hashes (head + tail 16KB).
     * Tier 3: Escalate to full SHA-256 only for files matching both size and sparse hash.
     */
    public DeduplicationResult detectDuplicatesMultiTier(Collection<Path> files, Executor executor) {
        Set<Path> duplicates = ConcurrentHashMap.newKeySet();
        Set<Path> validFiles = ConcurrentHashMap.newKeySet();
        Map<Path, String> fullDigests = new ConcurrentHashMap<>();

        if (files == null || files.isEmpty()) {
            return new DeduplicationResult(duplicates, validFiles, fullDigests);
        }

        // Tier 1: Exact file size grouping (Zero I/O - relies only on file metadata)
        Map<Long, List<Path>> sizeBuckets = new ConcurrentHashMap<>();
        for (Path file : files) {
            try {
                long size = Files.size(file);
                if (size <= 0) {
                    duplicates.add(file);
                    continue;
                }
                sizeBuckets.computeIfAbsent(size, k -> new CopyOnWriteArrayList<>()).add(file);
            } catch (IOException e) {
                log.debug("Could not read size for {}: {}", file, e.getMessage());
            }
        }

        List<Map.Entry<Long, List<Path>>> multiFileBuckets = new ArrayList<>();
        for (Map.Entry<Long, List<Path>> entry : sizeBuckets.entrySet()) {
            List<Path> bucketFiles = entry.getValue();
            if (bucketFiles.size() == 1) {
                Path singleFile = bucketFiles.get(0);
                if (isDuplicate(singleFile)) {
                    duplicates.add(singleFile);
                } else {
                    validFiles.add(singleFile);
                }
            } else {
                multiFileBuckets.add(entry);
            }
        }

        if (multiFileBuckets.isEmpty()) {
            return new DeduplicationResult(duplicates, validFiles, fullDigests);
        }

        // Tier 2: Sparse hashing for files in multi-file buckets
        List<CompletableFuture<Void>> tier2Futures = new ArrayList<>();
        for (Map.Entry<Long, List<Path>> entry : multiFileBuckets) {
            long size = entry.getKey();
            List<Path> bucketFiles = entry.getValue();

            tier2Futures.add(CompletableFuture.runAsync(() -> {
                Map<String, List<Path>> sparseBuckets = new ConcurrentHashMap<>();
                List<CompletableFuture<Void>> sparseFutures = new ArrayList<>();

                for (Path file : bucketFiles) {
                    sparseFutures.add(CompletableFuture.runAsync(() -> {
                        String sparseHash = computeSparseHash(file, size);
                        if (sparseHash != null) {
                            sparseBuckets.computeIfAbsent(sparseHash, k -> new CopyOnWriteArrayList<>()).add(file);
                        } else {
                            validFiles.add(file);
                        }
                    }, executor));
                }
                CompletableFuture.allOf(sparseFutures.toArray(CompletableFuture[]::new)).join();

                // Tier 3: SHA-256 escalation
                List<CompletableFuture<Void>> tier3GroupFutures = new ArrayList<>();
                for (Map.Entry<String, List<Path>> sparseEntry : sparseBuckets.entrySet()) {
                    List<Path> sameSparseFiles = sparseEntry.getValue();
                    if (sameSparseFiles.size() == 1) {
                        Path uniqueSparseFile = sameSparseFiles.get(0);
                        if (isDuplicate(uniqueSparseFile)) {
                            duplicates.add(uniqueSparseFile);
                        } else {
                            validFiles.add(uniqueSparseFile);
                        }
                    } else {
                        tier3GroupFutures.add(CompletableFuture.runAsync(() -> {
                            Map<String, List<Path>> digestBuckets = new ConcurrentHashMap<>();
                            List<CompletableFuture<Void>> fileDigestFutures = new ArrayList<>();

                            for (Path file : sameSparseFiles) {
                                fileDigestFutures.add(CompletableFuture.runAsync(() -> {
                                    String fullDigest = computeFullDigest(file);
                                    if (fullDigest != null) {
                                        fullDigests.put(file, fullDigest);
                                        digestBuckets.computeIfAbsent(fullDigest, k -> new CopyOnWriteArrayList<>()).add(file);
                                    } else {
                                        validFiles.add(file);
                                    }
                                }, executor));
                            }
                            CompletableFuture.allOf(fileDigestFutures.toArray(CompletableFuture[]::new)).join();

                            for (Map.Entry<String, List<Path>> digestEntry : digestBuckets.entrySet()) {
                                String digest = digestEntry.getKey();
                                List<Path> identicalFiles = new ArrayList<>(digestEntry.getValue());
                                identicalFiles.sort(Comparator.comparing(Path::toString));
                                boolean first = true;
                                for (Path file : identicalFiles) {
                                    if (first && !isDuplicate(digest)) {
                                        validFiles.add(file);
                                        registerDigest(digest, size, file);
                                        first = false;
                                    } else {
                                        duplicates.add(file);
                                    }
                                }
                            }
                        }, executor));
                    }
                }
                CompletableFuture.allOf(tier3GroupFutures.toArray(CompletableFuture[]::new)).join();
            }, executor));
        }

        CompletableFuture.allOf(tier2Futures.toArray(CompletableFuture[]::new)).join();
        return new DeduplicationResult(duplicates, validFiles, fullDigests);
    }

    /**
     * Checks whether a source file is a duplicate using multi-tier checking:
     * 1. Size check: if size has never been seen, it's not a duplicate.
     * 2. Sparse hash check: head + tail 16KB.
     * 3. Full SHA-256 check.
     */
    public boolean isDuplicate(Path file) {
        try {
            long size = Files.size(file);
            if (size <= 0) return true;

            Set<String> sparseSet = sizeToSparseHashes.get(size);
            if (sparseSet == null || sparseSet.isEmpty()) {
                return false; // Size never seen before -> cannot be duplicate
            }

            // Size has been seen, compute sparse hash
            String sparseHash = computeSparseHash(file, size);
            if (sparseHash == null || !sparseSet.contains(sparseHash)) {
                return false; // Sparse hash differs -> not duplicate
            }

            // Sparse hash matches, perform full digest verification
            String fullDigest = computeFullDigest(file);
            return fullDigest != null && seenFullDigests.contains(fullDigest);
        } catch (IOException e) {
            log.debug("Error checking duplicate for {}: {}", file, e.getMessage());
            return false;
        }
    }

    public boolean registerDigest(String fullDigest, long size, Path file) {
        if (fullDigest == null) return false;
        boolean added = seenFullDigests.add(fullDigest);
        if (size > 0) {
            String sparseHash = computeSparseHash(file, size);
            if (sparseHash != null) {
                sizeToSparseHashes.computeIfAbsent(size, k -> ConcurrentHashMap.newKeySet()).add(sparseHash);
            }
        }
        return added;
    }

    public boolean registerDigest(String fullDigest) {
        if (fullDigest == null) return false;
        return seenFullDigests.add(fullDigest);
    }

    public String computeFullDigest(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; // 64 KB buffer for high I/O performance
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHexString(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            log.debug("Unable to compute full digest for {}: {}", file, e.getMessage());
            return null;
        }
    }

    public String computeSparseHash(Path file, long size) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int sampleSize = (int) Math.min(size, 16384); // 16 KB

            // Read head
            ByteBuffer buffer = ByteBuffer.allocate(sampleSize);
            channel.read(buffer);
            buffer.flip();
            md.update(buffer);

            // Read tail if file is larger than 32 KB
            if (size > 32768) {
                buffer.clear();
                channel.position(size - sampleSize);
                channel.read(buffer);
                buffer.flip();
                md.update(buffer);
            }

            return toHexString(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if target file already exists with identical content.
     */
    public boolean targetMatchesContent(Path targetFile, String sourceDigest) {
        if (!Files.exists(targetFile) || sourceDigest == null) {
            return false;
        }
        String targetDigest = computeFullDigest(targetFile);
        return sourceDigest.equals(targetDigest);
    }

    /**
     * Resolves unique filename in O(1) time using an in-memory counter cache.
     */
    public Path getUniqueFileName(Path targetDir, String fileName) {
        String baseName = fileName;
        String extension = "";

        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            baseName = fileName.substring(0, lastDot);
            extension = fileName.substring(lastDot);
        }

        Path baseKey = targetDir.resolve(fileName);
        AtomicInteger counter = baseFileCounters.computeIfAbsent(baseKey, k -> new AtomicInteger(1));

        Path uniquePath;
        do {
            int currentSuffix = counter.getAndIncrement();
            String newFileName = baseName + "_" + currentSuffix + extension;
            uniquePath = targetDir.resolve(newFileName);
        } while (Files.exists(uniquePath));

        return uniquePath;
    }

    private static String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

