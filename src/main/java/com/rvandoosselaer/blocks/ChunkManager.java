package com.rvandoosselaer.blocks;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.collision.CollisionResult;
import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * The ChunkManager is responsible for the administration of chunks and the maintenance of the underlying ChunkCache.
 * The ChunkManager is thread safe, but the lifecycle methods {@link #initialize()}, {@link #update()} and
 * {@link #cleanup()} should be called from the same thread. It is good practice to let the lifecycle of the
 * ChunkManager be handled by the {@link ChunkManagerState}.
 * <p>
 * A chunk can be retrieved by using the {@link #getChunk(Vec3i)} method. When the chunk isn't available it can be
 * requested with the {@link #requestChunk(Vec3i)} method.
 * The ChunkManager will first try to load the requested chunk using the {@link ChunkRepository}. When this is not
 * successful it will try to generate the chunk using the {@link ChunkGenerator}. When this also fails, an empty
 * chunk will be created. The requested chunk is placed in the cache and can be retrieved with the {@link #getChunk(Vec3i)}
 * method.
 * <p>
 * Applications can register a {@link ChunkManagerListener} to the ChunkManager to get notified when a chunk is
 * available in the cache or when a chunk is updated.
 *
 * @author: rvandoosselaer
 */
@Slf4j
public class ChunkManager {

    private final int cacheSize;
    @Getter
    private boolean initialized = false;
    private ChunkCache cache;
    private Queue<Vec3i> loadingQueue = new ConcurrentLinkedQueue<>();
    private Queue<Vec3i> generatorQueue = new ConcurrentLinkedQueue<>();
    private Queue<Chunk> meshQueue = new ConcurrentLinkedQueue<>();
    private List<Future<LoadingResult>> loadingResults = new ArrayList<>();
    private List<Future<Chunk>> generatorResults = new ArrayList<>();
    private List<Future<Chunk>> meshResults = new ArrayList<>();
    private ChunkRepository repository;
    private ChunkGenerator generator;
    private ChunkMeshGenerator meshGenerator;
    /**
     * Time between cache maintenance operations in milliseconds
     */
    @Getter
    @Setter
    private int cacheMaintenanceInterval = 1000;
    private long lastCacheMaintenanceTimestamp = -1;
    private int repositoryPoolSize = 1;
    private int generatorPoolSize = 1;
    private int meshPoolSize = 1;
    private ExecutorService repositoryExecutor;
    private ExecutorService generatorExecutor;
    private ExecutorService meshExecutor;
    private final List<ChunkManagerListener> listeners = new CopyOnWriteArrayList<>();
    /**
     * The ChunkManager triggers mesh updates on adjacent chunks when they are added or updated. This is because the
     * face checking algorithm takes neighbouring chunks in to account. It is possible however that a chunk is rendered
     * while it's neighbour was not yet available, resulting in a too detailed mesh. That's why we trigger the mesh update
     * again when the neighbour is available. We should take care however to not get into an infinite loop: Chunk A
     * triggers chunk B, chunk B triggers chunk A, chunk A trigger chunk B again...
     * That's why we keep a registry of chunk update triggers to keep track of who updated who. This registry is checked
     * before an update is triggered to make sure that chunks don't keep triggering each other. Values in the registry
     * are only removed when the structure (blocks) of a chunk changes. When addBlock or removeBlock are called.
     */
    private final Set<Map.Entry<Chunk, Chunk>> chunkUpdateTriggersRegistry = new HashSet<>();
    @Getter
    @Setter
    private boolean triggerAdjacentChunkUpdates;

    public ChunkManager() {
        this(0);
    }

    public ChunkManager(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    @Builder
    private ChunkManager(int cacheSize, ChunkRepository repository, int repositoryPoolSize, ChunkGenerator generator, int generatorPoolSize, int meshPoolSize, int cacheMaintenanceInterval, boolean triggerAdjacentChunkUpdates) {
        this.cacheSize = cacheSize;
        this.repository = repository;
        this.repositoryPoolSize = repositoryPoolSize;
        this.generator = generator;
        this.generatorPoolSize = generatorPoolSize;
        this.meshPoolSize = meshPoolSize;
        this.cacheMaintenanceInterval = cacheMaintenanceInterval;
        this.triggerAdjacentChunkUpdates = triggerAdjacentChunkUpdates;
    }

    /**
     * Calculate the location of the chunk that contains the given location.
     *
     * @param location
     * @return location of the chunk
     */
    public static Vec3i getChunkLocation(@NonNull Vector3f location) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        Vector3f scaledLocation = getScaledBlockLocation(location);
        // Math.floor() rounds the decimal part down; 4.13 => 4.0, 4.98 => 4.0, -7.82 => -8.0
        // downcasting double to int removes the decimal part
        return new Vec3i((int) Math.floor(scaledLocation.x / chunkSize.x), (int) Math.floor(scaledLocation.y / chunkSize.y), (int) Math.floor(scaledLocation.z / chunkSize.z));
    }

    public static Vec3i getBlockLocation(@NonNull Vector3f location) {
        return toVec3i(getScaledBlockLocation(location));
    }

    public static Vec3i getBlockLocation(@NonNull CollisionResult collisionResult) {
        return toVec3i(getAdjustedContactPoint(collisionResult.getContactPoint(), collisionResult.getContactNormal()));
    }

    public static Vec3i getNeighbourBlockLocation(@NonNull Vector3f location, Direction direction) {
        return toVec3i(getNeighbourBlockLocation(location, direction.getVector().toVector3f()));
    }

    public static Vec3i getNeighbourBlockLocation(@NonNull CollisionResult collisionResult) {
        return toVec3i(getNeighbourBlockLocation(collisionResult.getContactPoint(), collisionResult.getContactNormal()));
    }

    /**
     * Calculate the center location of a block.
     *
     * @param location
     * @return center of the block
     */
    public static Vector3f getBlockCenterLocation(@NonNull Vec3i location) {
        return location.toVector3f().addLocal(0.5f, 0.5f, 0.5f).multLocal(BlocksConfig.getInstance().getBlockScale());
    }

    public Optional<Chunk> getChunk(Vec3i location) {
        assertInitialized();

        return location == null ? Optional.empty() : cache.get(location);
    }

    public void setChunk(Chunk chunk) {
        assertInitialized();

        if (chunk != null) {
            getChunk(chunk.getLocation()).ifPresent(this::removeChunk);
            addToCache(chunk);
        }
    }

    public void requestChunk(Vec3i location) {
        assertInitialized();

        if (!getChunk(location).isPresent()) {
            addElementToQueue(new Vec3i(location), loadingQueue);
        }
    }

    public void requestChunkMeshUpdate(Vec3i location) {
        assertInitialized();

        getChunk(location).ifPresent(chunk -> addElementToQueue(chunk, meshQueue));
    }

    public void requestChunkMeshUpdate(Chunk chunk) {
        assertInitialized();

        if (chunk != null) {
            addElementToQueue(chunk, meshQueue);
        }
    }

    /**
     * @param location of the chunk
     * @see #removeChunk(Chunk)
     */
    public void removeChunk(Vec3i location) {
        assertInitialized();

        getChunk(location).ifPresent(this::removeChunk);
    }

    /**
     * Inform the ChunkManager that you no longer need access to the chunk and it can perform cleanup operations on it.
     *
     * @param chunk
     */
    public void removeChunk(Chunk chunk) {
        assertInitialized();

        if (chunk != null) {
            cancelAllPendingOperations(chunk);

            cache.evict(chunk.getLocation());
        }
    }

    public void addBlock(Vector3f location, Block block) {
        assertInitialized();

        if (location == null) {
            return;
        }

        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        getChunk(chunkLocation).ifPresent(chunk -> addBlockToChunk(location, block, chunk));
    }

    public void removeBlock(Vector3f location) {
        assertInitialized();

        if (location == null) {
            return;
        }

        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        getChunk(chunkLocation).ifPresent(chunk -> removeBlockFromChunk(location, chunk));
    }

    public Optional<Block> getBlock(Vector3f location) {
        assertInitialized();

        if (location == null) {
            return Optional.empty();
        }

        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        return getChunk(chunkLocation).map(chunk -> {
            Vec3i localBlockLocation = chunk.toLocalLocation(toVec3i(location));
            return chunk.getBlock(localBlockLocation);
        });
    }

    public Optional<Block> getBlock(CollisionResult collisionResult) {
        assertInitialized();

        if (collisionResult == null) {
            return Optional.empty();
        }

        Vector3f adjustedContactPoint = getAdjustedContactPoint(collisionResult.getContactPoint(), collisionResult.getContactNormal());
        return getBlock(adjustedContactPoint);
    }

    public Optional<Block> getNeighbourBlock(Vector3f location, Direction direction) {
        assertInitialized();

        if (location == null || direction == null) {
            return Optional.empty();
        }

        Vector3f neighbourBlockLocation = getNeighbourBlockLocation(location, direction.getVector().toVector3f());
        return getBlock(neighbourBlockLocation);
    }

    public Optional<Block> getNeighbourBlock(CollisionResult collisionResult) {
        assertInitialized();

        if (collisionResult == null) {
            return Optional.empty();
        }

        Vector3f adjustedContactPoint = getAdjustedContactPoint(collisionResult.getContactPoint(), collisionResult.getContactNormal());
        return getNeighbourBlock(adjustedContactPoint, Direction.fromVector(collisionResult.getContactNormal()));
    }

    public void initialize() {
        if (log.isTraceEnabled()) {
            log.trace("{} - initialize", getClass().getSimpleName());
        }

        // create cache
        cache = new ChunkCache(cacheSize);

        // create executors
        if (repository != null) {
            repositoryExecutor = createNamedFixedThreadPool(Math.max(1, repositoryPoolSize), "chunk-repository-%d");
        } else {
            log.info("No ChunkRepository set.");
        }

        if (generator != null) {
            generatorExecutor = createNamedFixedThreadPool(Math.max(1, generatorPoolSize), "chunk-generator-%d");
        } else {
            log.info("No ChunkGenerator set.");
        }

        meshGenerator = BlocksConfig.getInstance().getChunkMeshGenerator();
        meshExecutor = createNamedFixedThreadPool(Math.max(1, meshPoolSize), "chunk-mesh-%d");

        initialized = true;
    }

    public void update() {
        assertInitialized();

        handleLoadResults();
        handleGenerationResults();
        handleMeshGenerationResults();

        performLoading();
        performGeneration();
        performMeshGeneration();

        performCacheMaintenance();
    }

    public void cleanup() {
        assertInitialized();

        if (log.isTraceEnabled()) {
            log.trace("{} - cleanup", getClass().getSimpleName());
        }

        // stop executors
        if (repositoryExecutor != null) {
            repositoryExecutor.shutdownNow();
        }
        if (generatorExecutor != null) {
            generatorExecutor.shutdownNow();
        }
        meshExecutor.shutdownNow();

        // clear queues and cache
        loadingQueue.clear();
        loadingResults.clear();
        generatorQueue.clear();
        generatorResults.clear();
        meshQueue.clear();
        meshResults.clear();
        cache.evictAll();

        initialized = false;
    }

    public void addListener(@NonNull ChunkManagerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull ChunkManagerListener listener) {
        listeners.remove(listener);
    }

    public ChunkResolver getChunkResolver() {
        return cache;
    }

    private static Vec3i toVec3i(Vector3f location) {
        return new Vec3i((int) Math.floor(location.x), (int) Math.floor(location.y), (int) Math.floor(location.z));
    }

    private static Vector3f getScaledBlockLocation(Vector3f location) {
        return location.mult(1f / BlocksConfig.getInstance().getBlockScale());
    }

    private static Vector3f getAdjustedContactPoint(Vector3f contactPoint, Vector3f contactNormal) {
        // add a small offset to the contact point, so we point a bit more 'inward' into the block
        Vector3f adjustedContactPoint = contactPoint.add(contactNormal.negate().multLocal(0.05f));
        return getScaledBlockLocation(adjustedContactPoint);
    }

    private static Vector3f getNeighbourBlockLocation(Vector3f location, Vector3f normal) {
        Vector3f neighbourDirection = normal.mult(0.75f);
        return getScaledBlockLocation(location).add(neighbourDirection);
    }

    private void addBlockToChunk(Vector3f location, Block block, Chunk chunk) {
        Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
        Block previousBlock = chunk.addBlock(blockLocationInsideChunk, block);
        if (!Objects.equals(previousBlock, block)) {
            chunk.update();
            chunkUpdateTriggersRegistry.removeIf(entry -> entry.getKey().equals(chunk));
            addElementToQueue(chunk, meshQueue);
        }
    }

    private void removeBlockFromChunk(Vector3f location, Chunk chunk) {
        Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
        Block block = chunk.removeBlock(blockLocationInsideChunk);
        if (block != null) {
            chunk.update();
            chunkUpdateTriggersRegistry.removeIf(entry -> entry.getKey().equals(chunk));
            addElementToQueue(chunk, meshQueue);
        }
    }

    private void performLoading() {
        if (loadingQueue.isEmpty()) {
            return;
        }

        Vec3i location = loadingQueue.poll();
        loadChunk(location);
    }

    private void loadChunk(Vec3i location) {
        if (repository == null) {
            addElementToQueue(location, generatorQueue);
            return;
        }

        Future<LoadingResult> loadingResult = repositoryExecutor.submit(new LoadingCallable(location, repository));
        loadingResults.add(loadingResult);
    }

    private void performGeneration() {
        if (generatorQueue.isEmpty()) {
            return;
        }

        Vec3i location = generatorQueue.poll();
        generateChunk(location);
    }

    private void generateChunk(Vec3i location) {
        if (generator == null) {
            createChunk(location);
            return;
        }

        Future<Chunk> generatorResult = generatorExecutor.submit(new GeneratorCallable(location, generator));
        generatorResults.add(generatorResult);
    }

    private void performMeshGeneration() {
        if (meshQueue.isEmpty()) {
            return;
        }

        Chunk chunk = meshQueue.poll();
        generateMesh(chunk);
    }

    private void generateMesh(Chunk chunk) {
        Future<Chunk> meshResult = meshExecutor.submit(new MeshCallable(chunk, meshGenerator));
        meshResults.add(meshResult);
    }

    private void handleLoadResults() {
        if (loadingResults.isEmpty()) {
            return;
        }

        Optional<Future<LoadingResult>> loadingResult = loadingResults.stream().filter(Future::isDone).findFirst();
        if (loadingResult.isPresent()) {
            Future<LoadingResult> loadingResultFuture = loadingResult.get();
            try {
                handleLoadResult(loadingResultFuture.get());
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            loadingResults.remove(loadingResultFuture);
        }

        loadingResults.removeIf(Future::isCancelled);
    }

    private void handleLoadResult(LoadingResult loadingResult) {
        if (loadingResult.hasChunk()) {
            addElementToQueue(loadingResult.getChunk(), meshQueue);
        } else {
            addElementToQueue(loadingResult.getLocation(), generatorQueue);
        }

    }

    private void handleGenerationResults() {
        if (generatorResults.isEmpty()) {
            return;
        }

        Optional<Future<Chunk>> generatorResult = generatorResults.stream().filter(Future::isDone).findFirst();
        if (generatorResult.isPresent()) {
            Future<Chunk> generatorResultFuture = generatorResult.get();
            try {
                handleGenerationResult(generatorResultFuture.get());
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            generatorResults.remove(generatorResultFuture);
        }

        generatorResults.removeIf(Future::isCancelled);
    }

    private void handleGenerationResult(Chunk chunk) {
        addElementToQueue(chunk, meshQueue);
    }

    private void handleMeshGenerationResults() {
        if (meshResults.isEmpty()) {
            return;
        }

        Optional<Future<Chunk>> meshResult = meshResults.stream().filter(Future::isDone).findFirst();
        if (meshResult.isPresent()) {
            Future<Chunk> meshResultFuture = meshResult.get();
            try {
                handleMeshGenerationResult(meshResultFuture.get());
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
            meshResults.remove(meshResultFuture);
        }

        meshResults.removeIf(Future::isCancelled);
    }

    private void handleMeshGenerationResult(Chunk chunk) {
        if (getChunk(chunk.getLocation()).isPresent()) {
            triggerListenerChunkUpdated(chunk);
        } else {
            addToCache(chunk);
        }

        if (isTriggerAdjacentChunkUpdates()) {
            updateAdjacentChunks(chunk);
        }
    }

    private void updateAdjacentChunks(Chunk chunk) {
        Set<Vec3i> adjacentChunkLocations = getAdjacentChunkLocations(chunk);

        for (Vec3i chunkLocation : adjacentChunkLocations) {
            getChunk(chunkLocation).ifPresent(neighbour -> chunkWantsToUpdateNeighbour(chunk, neighbour));
        }
    }

    private void chunkWantsToUpdateNeighbour(Chunk chunk, Chunk neighbour) {
        if (isChunkAllowedToUpdateNeighbour(chunk, neighbour)) {
            requestChunkMeshUpdate(neighbour);
            chunkUpdateTriggersRegistry.add(new AbstractMap.SimpleImmutableEntry<>(chunk, neighbour));
        }
    }

    private Set<Vec3i> getAdjacentChunkLocations(Chunk chunk) {
        return Arrays.stream(Direction.values())
                .map(direction -> chunk.getLocation().add(direction.getVector()))
                .collect(Collectors.toSet());
    }

    private boolean isChunkAllowedToUpdateNeighbour(Chunk chunk, Chunk neighbour) {
        return !chunkUpdateTriggersRegistry.contains(new AbstractMap.SimpleImmutableEntry<>(chunk, neighbour));
    }

    private void triggerListenerChunkUpdated(Chunk chunk) {
        listeners.forEach(listener -> listener.onChunkUpdated(chunk));
    }

    private void triggerListenerChunkAvailable(Chunk chunk) {
        listeners.forEach(listener -> listener.onChunkAvailable(chunk));
    }

    private void createChunk(Vec3i location) {
        Chunk chunk = Chunk.createAt(location);
        addElementToQueue(chunk, meshQueue);
    }

    private void addToCache(Chunk chunk) {
        cache.put(chunk);
        chunk.setChunkResolver(cache);
        triggerListenerChunkAvailable(chunk);
    }

    private void assertInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException(getClass().getSimpleName() + " is not initialized.");
        }
    }

    private void performCacheMaintenance() {
        boolean shouldPerformCacheMaintenance = cacheMaintenanceInterval > 0 &&
                System.currentTimeMillis() >= lastCacheMaintenanceTimestamp + cacheMaintenanceInterval;
        if (shouldPerformCacheMaintenance) {
            cache.maintain();
            lastCacheMaintenanceTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Cancel all pending operations for this chunk. When invoked, the chunk will be remove from all queues.
     *
     * @param chunk to cancel operations for
     */
    private void cancelAllPendingOperations(Chunk chunk) {
        if (log.isDebugEnabled()) {
            log.debug("Stopping pending tasks for {}", chunk);
        }
        loadingQueue.remove(chunk.getLocation());
        generatorQueue.remove(chunk.getLocation());
        meshQueue.remove(chunk);
    }

    /**
     * Helper method to add an element to a queue, making sure we don't add the same element multiple times.
     *
     * @param <T>
     * @param element
     * @param queue
     * @return true if the element was added to the queue, false if the element was already present in the queue.
     */
    private <T> boolean addElementToQueue(T element, Queue<T> queue) {
        if (!queue.contains(element)) {
            queue.add(element);
            return true;
        }

        return false;
    }

    private static ExecutorService createNamedFixedThreadPool(int size, String name) {
        return Executors.newFixedThreadPool(size, new ThreadFactoryBuilder().setNameFormat(name).build());
    }

    @Getter
    @RequiredArgsConstructor
    private static class LoadingResult {

        private final Vec3i location;
        private final Chunk chunk;

        public boolean hasChunk() {
            return chunk != null;
        }

    }

    @RequiredArgsConstructor
    private static class LoadingCallable implements Callable<LoadingResult> {

        private final Vec3i location;
        private final ChunkRepository repository;

        @Override
        public LoadingResult call() {
            return new LoadingResult(location, repository.load(location));
        }

    }

    @RequiredArgsConstructor
    private static class GeneratorCallable implements Callable<Chunk> {

        private final Vec3i location;
        private final ChunkGenerator generator;

        @Override
        public Chunk call() {
            Chunk chunk = generator.generate(location);
            chunk.update();
            return chunk;
        }

    }

    @RequiredArgsConstructor
    private static class MeshCallable implements Callable<Chunk> {

        private final Chunk chunk;
        private final ChunkMeshGenerator meshGenerator;

        @Override
        public Chunk call() {
            meshGenerator.createAndSetNodeAndCollisionMesh(chunk);
            return chunk;
        }

    }

}
