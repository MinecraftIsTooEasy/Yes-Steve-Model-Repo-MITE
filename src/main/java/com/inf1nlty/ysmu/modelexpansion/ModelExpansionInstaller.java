package com.inf1nlty.ysmu.modelexpansion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import moddedmite.rustedironcore.api.util.FabricUtil;

final class ModelExpansionInstaller {

    private static final String MANIFEST_RESOURCE = "/assets/ysmu_model_expansion/models.tsv";
    private static final String RESOURCE_PREFIX = "/assets/ysmu_model_expansion/";
    private static final int MAX_MANIFEST_ENTRIES = 10_000;
    private static final Path YSM_CONFIG = FabricUtil.getConfigDirectory().resolve("ysmu");
    private static final Path CUSTOM = YSM_CONFIG.resolve("custom");
    private static final Path STATE_FILE = YSM_CONFIG.resolve("MODEL_EXPANSION_FILES");

    static InstallResult install() throws IOException {
        Map<String, List<ModelResource>> resourcesByModel = readManifest();
        Files.createDirectories(CUSTOM);

        Set<String> previousDestinations = readState(STATE_FILE);
        Set<String> currentDestinations = new LinkedHashSet<>();
        for (List<ModelResource> resources : resourcesByModel.values()) {
            for (ModelResource resource : resources) currentDestinations.add(resource.destination());
        }

        Set<String> pathsToRemove = new LinkedHashSet<>(previousDestinations);
        pathsToRemove.addAll(currentDestinations);
        for (String destination : pathsToRemove) removeManagedDestination(destination);

        Set<String> installedDestinations = new LinkedHashSet<>();
        int installedModels = 0;
        for (Map.Entry<String, List<ModelResource>> model : resourcesByModel.entrySet()) {
            List<String> copied = new ArrayList<>();
            try {
                for (ModelResource resource : model.getValue()) {
                    copyResource(resource);
                    copied.add(resource.destination());
                }
                installedDestinations.addAll(copied);
                installedModels++;
            } catch (IOException exception) {
                for (String destination : copied) removeManagedDestination(destination);
                YsmModelExpansionAddon.LOG.warn(
                    "Skipped incomplete expansion model " + model.getKey(), exception);
            }
        }

        writeState(installedDestinations);
        return new InstallResult(installedModels, installedDestinations.size());
    }

    private static Map<String, List<ModelResource>> readManifest() throws IOException {
        InputStream stream = ModelExpansionInstaller.class.getResourceAsStream(MANIFEST_RESOURCE);
        if (stream == null) throw new IOException("Missing addon model manifest " + MANIFEST_RESOURCE);

        Map<String, List<ModelResource>> resourcesByModel = new LinkedHashMap<>();
        Set<String> destinations = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            int entryCount = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (++entryCount > MAX_MANIFEST_ENTRIES) {
                    throw new IOException("Addon model manifest has too many entries");
                }
                String[] fields = line.split("\t", -1);
                if (fields.length != 5) throw new IOException("Invalid addon model manifest row");
                String modelId = fields[0];
                String type = fields[1];
                String resource = fields[2];
                String destination = fields[3];
                if (!isModelId(modelId) || !("ysm".equals(type) || "folder".equals(type))
                    || !isResourcePath(resource) || !isDestination(modelId, type, destination)) {
                    throw new IOException("Unsafe addon model manifest row for " + modelId);
                }
                if (!destinations.add(destination)) {
                    throw new IOException("Duplicate addon model destination " + destination);
                }
                resourcesByModel.computeIfAbsent(modelId, ignored -> new ArrayList<>())
                    .add(new ModelResource(modelId, resource, destination));
            }
        }
        return resourcesByModel;
    }

    private static Set<String> readState(Path stateFile) throws IOException {
        Set<String> destinations = new LinkedHashSet<>();
        if (!Files.isRegularFile(stateFile)) return destinations;
        for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
            String destination = line.trim()
                .replace('\\', '/');
            if (isStandaloneYsmDestination(destination) || isFolderDestination(destination)) {
                destinations.add(destination);
            } else if (!destination.isEmpty() && !destination.startsWith("#")) {
                YsmModelExpansionAddon.LOG.warn("Ignored unsafe model expansion state path: {}", destination);
            }
        }
        return destinations;
    }

    private static void copyResource(ModelResource resource) throws IOException {
        Path destination = resolveDestination(resource.destination());
        Path parent = destination.getParent();
        if (parent == null) throw new IOException("Model expansion destination has no parent");
        Files.createDirectories(parent);
        try (InputStream input = ModelExpansionInstaller.class.getResourceAsStream(
            RESOURCE_PREFIX + resource.resource())) {
            if (input == null) throw new IOException("Missing model expansion resource " + resource.resource());
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void removeManagedDestination(String destination) throws IOException {
        if (!(isStandaloneYsmDestination(destination) || isFolderDestination(destination))) return;
        Path path = resolveDestination(destination);
        Files.deleteIfExists(path);
        Path parent = path.getParent();
        Path normalizedCustom = CUSTOM.toAbsolutePath()
            .normalize();
        while (parent != null && !parent.equals(normalizedCustom) && parent.startsWith(normalizedCustom)) {
            try {
                Files.delete(parent);
            } catch (IOException ignored) {
                break;
            }
            parent = parent.getParent();
        }
    }

    private static Path resolveDestination(String destination) throws IOException {
        Path normalizedCustom = CUSTOM.toAbsolutePath()
            .normalize();
        Path resolved = normalizedCustom.resolve(destination)
            .normalize();
        if (!resolved.startsWith(normalizedCustom) || resolved.equals(normalizedCustom)) {
            throw new IOException("Model expansion path leaves the custom directory: " + destination);
        }
        return resolved;
    }

    private static void writeState(Set<String> destinations) throws IOException {
        List<String> sorted = new ArrayList<>(destinations);
        Collections.sort(sorted);
        Files.createDirectories(STATE_FILE.getParent());
        Path temporary = STATE_FILE.resolveSibling(STATE_FILE.getFileName() + ".tmp");
        Files.write(temporary, sorted, StandardCharsets.UTF_8);
        try {
            Files.move(
                temporary,
                STATE_FILE,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, STATE_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isModelId(String modelId) {
        return modelId != null && modelId.length() <= 128 && !modelId.contains("..")
            && modelId.matches("exp_[a-z0-9][a-z0-9_.-]*");
    }

    private static boolean isResourcePath(String resource) {
        return resource != null && resource.length() <= 256 && resource.startsWith("models/")
            && !resource.startsWith("/") && !resource.contains("\\") && !resource.contains(":")
            && !resource.contains("../") && !resource.contains("/../");
    }

    private static boolean isDestination(String modelId, String type, String destination) {
        if ("ysm".equals(type)) return destination.equals(modelId + ".ysm");
        return isFolderDestination(destination) && destination.startsWith(modelId + "/");
    }

    private static boolean isStandaloneYsmDestination(String destination) {
        if (destination == null || !destination.endsWith(".ysm") || destination.contains("/")) return false;
        return isModelId(destination.substring(0, destination.length() - 4));
    }

    private static boolean isFolderDestination(String destination) {
        if (destination == null || destination.contains("\\") || destination.contains(":")
            || destination.startsWith("/") || destination.contains("..")) return false;
        String[] segments = destination.split("/", -1);
        if (segments.length != 2 || !isModelId(segments[0])) return false;
        String fileName = segments[1];
        if (!fileName.matches("[a-z0-9][a-z0-9_.-]*")) return false;
        return fileName.equals("main.json") || fileName.equals("arm.json")
            || fileName.equals("main.animation.json") || fileName.equals("arm.animation.json")
            || fileName.equals("extra.animation.json") || fileName.endsWith(".png");
    }

    record InstallResult(int modelCount, int fileCount) {}

    private record ModelResource(String modelId, String resource, String destination) {}
}
