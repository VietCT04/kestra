package io.kestra.core.storages;

import io.kestra.core.models.namespaces.files.NamespaceFileMetadata;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

public class NamespaceFileAttributes implements FileAttributes {
    private final NamespaceFileMetadata namespaceFileMetadata;

    public NamespaceFileAttributes(NamespaceFileMetadata namespaceFileMetadata) {
        this.namespaceFileMetadata = namespaceFileMetadata;
    }

    @Override
    public String getFileName() {
        return new File(namespaceFileMetadata.getPath()).getName();
    }

    @Override
    public long getLastModifiedTime() {
        return Optional.ofNullable(namespaceFileMetadata.getUpdated()).map(Instant::toEpochMilli).orElse(0L);
    }

    @Override
    public long getCreationTime() {
        return Optional.ofNullable(namespaceFileMetadata.getCreated()).map(Instant::toEpochMilli).orElse(0L);
    }

    @Override
    public FileType getType() {
        return namespaceFileMetadata.getPath().endsWith("/") ? FileType.Directory : FileType.File;
    }

    @Override
    public long getSize() {
        return namespaceFileMetadata.getSize();
    }

    @Override
    public Map<String, String> getMetadata() throws IOException {
        return Collections.emptyMap();
    }
}
