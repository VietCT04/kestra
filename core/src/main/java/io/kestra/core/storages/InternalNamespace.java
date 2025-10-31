package io.kestra.core.storages;

import io.kestra.core.models.FetchVersion;
import io.kestra.core.models.QueryFilter;
import io.kestra.core.models.namespaces.files.NamespaceFileMetadata;
import io.kestra.core.repositories.ArrayListTotal;
import io.kestra.core.repositories.NamespaceFileMetadataRepositoryInterface;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.data.model.Pageable;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.kestra.core.utils.Rethrow.throwConsumer;
import static io.kestra.core.utils.Rethrow.throwFunction;

/**
 * The default {@link Namespace} implementation.
 * This class acts as a facade to the {@link StorageInterface} for manipulating namespace files.
 *
 * @see Storage#namespace()
 * @see Storage#namespace(String)
 */
public class InternalNamespace implements Namespace {

    private static final Logger LOG = LoggerFactory.getLogger(InternalNamespace.class);

    private final String namespace;
    private final String tenant;
    private final StorageInterface storage;
    private final NamespaceFileMetadataRepositoryInterface namespaceFileMetadataRepository;
    private final Logger logger;

    /**
     * Creates a new {@link InternalNamespace} instance.
     *
     * @param namespace The namespace
     * @param storage   The storage.
     */
    public InternalNamespace(@Nullable final String tenant, final String namespace, final StorageInterface storage, final NamespaceFileMetadataRepositoryInterface namespaceFileMetadataRepository) {
        this(LOG, tenant, namespace, storage, namespaceFileMetadataRepository);
    }

    /**
     * Creates a new {@link InternalNamespace} instance.
     *
     * @param logger    The logger to be used by this class.
     * @param namespace The namespace
     * @param tenant    The tenant.
     * @param storage   The storage.
     */
    public InternalNamespace(final Logger logger, @Nullable final String tenant, final String namespace, final StorageInterface storage, final NamespaceFileMetadataRepositoryInterface namespaceFileMetadataRepositoryInterface) {
        this.logger = Objects.requireNonNull(logger, "logger cannot be null");
        this.namespace = Objects.requireNonNull(namespace, "namespace cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.namespaceFileMetadataRepository = Objects.requireNonNull(namespaceFileMetadataRepositoryInterface, "namespaceFileMetadataRepository cannot be null");
        this.tenant = tenant;
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String tenantId() {
        return tenant;
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<NamespaceFile> all() throws IOException {
        return all(null);
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<NamespaceFile> all(final String containing, boolean includeDirectories) throws IOException {
        List<NamespaceFileMetadata> namespaceFilesMetadata = namespaceFileMetadataRepository.find(Pageable.UNPAGED, tenant, Stream.concat(
            Stream.of(QueryFilter.builder().field(QueryFilter.Field.NAMESPACE).operation(QueryFilter.Op.EQUALS).value(namespace).build()),
            Optional.ofNullable(containing).map(p -> QueryFilter.builder().field(QueryFilter.Field.QUERY).operation(QueryFilter.Op.EQUALS).value(p).build()).stream()
        ).toList(), false);

        if (!includeDirectories) {
            namespaceFilesMetadata = namespaceFilesMetadata.stream().filter(nsFileMetadata -> !nsFileMetadata.isDirectory()).toList();
        }

        return namespaceFilesMetadata.stream().map(nsFileMetadata -> NamespaceFile.of(namespace, Path.of(nsFileMetadata.getPath()), nsFileMetadata.getVersion())).toList();
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<NamespaceFileMetadata> children(String parentPath, boolean recursive) throws IOException {
        final String normalizedParentPath = NamespaceFile.normalize(Path.of(parentPath), true).toString();

        return namespaceFileMetadataRepository.find(Pageable.UNPAGED, tenant, List.of(
            QueryFilter.builder().field(QueryFilter.Field.NAMESPACE).operation(QueryFilter.Op.EQUALS).value(namespace).build(),
            QueryFilter.builder()
                .field(QueryFilter.Field.PARENT_PATH)
                .operation(recursive ? QueryFilter.Op.STARTS_WITH : QueryFilter.Op.EQUALS)
                .value(normalizedParentPath.endsWith("/") ? normalizedParentPath : normalizedParentPath + "/")
                .build()
        ), false);
    }

    @Override
    public void move(Path source, Path target) throws Exception {
        final Path normalizedSource = NamespaceFile.normalize(source, true);
        final Path normalizedTarget = NamespaceFile.normalize(target, true);

        if (findByPath(normalizedTarget).isPresent()) {
            throw new IOException(String.format(
                "File '%s' already exists in namespace '%s'.",
                normalizedTarget,
                namespace
            ));
        }

        ArrayListTotal<NamespaceFileMetadata> beforeRename = namespaceFileMetadataRepository.find(Pageable.UNPAGED, tenant, List.of(
            QueryFilter.builder().field(QueryFilter.Field.NAMESPACE).operation(QueryFilter.Op.EQUALS).value(namespace).build(),
            QueryFilter.builder().field(QueryFilter.Field.PATH).operation(QueryFilter.Op.IN).value(List.of(normalizedSource.toString(), normalizedSource + "/")).build()
        ), true, FetchVersion.ALL);
        beforeRename.sort(Comparator.comparing(NamespaceFileMetadata::getVersion));
        ArrayListTotal<NamespaceFileMetadata> afterRename = beforeRename
            .map(nsFileMetadata -> nsFileMetadata.toBuilder().path(normalizedTarget.toString()).build());

        afterRename.forEach(throwConsumer(nsFileMetadata -> {
            Path namespaceFilePath = NamespaceFile.of(namespace, normalizedSource, nsFileMetadata.getVersion()).storagePath();
            try (InputStream oldContent = storage.get(tenant, namespace, namespaceFilePath.toUri())){
                this.putFile(Path.of(nsFileMetadata.getPath()), oldContent, Conflicts.OVERWRITE);
            }

            this.purge(NamespaceFile.of(namespace, normalizedSource, nsFileMetadata.getVersion()));
        }));
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public NamespaceFile get(Path path) throws IOException {
        final Path normalizedPath = NamespaceFile.normalize(path, true);

        int version = findByPath(normalizedPath).map(NamespaceFileMetadata::getVersion).orElse(1);

        return NamespaceFile.of(namespace, normalizedPath, version);
    }

    public Path relativize(final URI uri) {
        return NamespaceFile.of(namespace)
            .storagePath()
            .relativize(Path.of(uri.getPath()));
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public List<NamespaceFile> findAllFilesMatching(final Predicate<Path> predicate) throws IOException {
        return all().stream().filter(it -> predicate.test(it.path(true))).toList();
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public InputStream getFileContent(Path path) throws IOException {
        final Path normalizedPath = NamespaceFile.normalize(path, true);

        NamespaceFileMetadata inRepository = findByPath(normalizedPath).orElseThrow(() -> fileNotFound(normalizedPath));

        Path namespaceFilePath = NamespaceFile.of(namespace, normalizedPath, inRepository.getVersion()).storagePath();
        return storage.get(tenant, namespace, namespaceFilePath.toUri());
    }

    @Override
    public FileAttributes getFileMetadata(Path path) throws IOException {
        final Path normalizedPath = NamespaceFile.normalize(path, true);

        return findByPath(normalizedPath).map(NamespaceFileAttributes::new).orElseThrow(() -> fileNotFound(normalizedPath));
    }

    private FileNotFoundException fileNotFound(Path path) {
        return new FileNotFoundException("File '" + path + "' not found in namespace '" + namespace + "'.");
    }

    private Optional<NamespaceFileMetadata> findByPath(Path path) throws IOException {
        final Path normalizedPath = NamespaceFile.normalize(path, true);

        return namespaceFileMetadataRepository.findByPath(tenant, namespace, normalizedPath.toString())
            .filter(namespaceFileMetadata -> !namespaceFileMetadata.isDeleted());
    }

    @Override
    public boolean exists(Path path) throws IOException {
        final Path normalizedPath = NamespaceFile.normalize(path, true);

        return findByPath(normalizedPath).isPresent();
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public NamespaceFile putFile(final Path path, final InputStream content, final Conflicts onAlreadyExist) throws IOException, URISyntaxException {
        final Path normalizedPath = NamespaceFile.normalize(path, true);

        Optional<NamespaceFileMetadata> inRepository = findByPath(normalizedPath);
        int currentVersion = inRepository.map(NamespaceFileMetadata::getVersion).orElse(0);
        NamespaceFile namespaceFile = NamespaceFile.of(namespace, normalizedPath, currentVersion + 1);
        Path storagePath = namespaceFile.storagePath();
        // Remove Windows letter
        URI cleanUri = new URI(storagePath.toUri().toString().replaceFirst("^file:///[a-zA-Z]:", ""));

        return switch (onAlreadyExist) {
            case OVERWRITE -> {
                storage.put(tenant, namespace, cleanUri, content);
                namespaceFileMetadataRepository.save(
                    inRepository.map(throwFunction(nsFileMetadata -> nsFileMetadata.toBuilder().size(storage.getAttributes(tenant, namespace, cleanUri).getSize()).build()))
                        .orElse(NamespaceFileMetadata.builder()
                            .tenantId(tenant)
                            .namespace(namespace)
                            .path(normalizedPath.toString())
                            .size(storage.getAttributes(tenant, namespace, cleanUri).getSize())
                            .build())
                );
                if (inRepository.isPresent()) {
                    logger.debug(String.format(
                        "File '%s' overwritten into namespace '%s'.",
                        normalizedPath,
                        namespace
                    ));
                } else {
                    logger.debug(String.format(
                        "File '%s' added to namespace '%s'.",
                        normalizedPath,
                        namespace
                    ));
                }
                yield namespaceFile;
            }
            case ERROR -> {
                if (inRepository.isEmpty()) {
                    storage.put(tenant, namespace, cleanUri, content);
                    namespaceFileMetadataRepository.save(
                        NamespaceFileMetadata.builder()
                            .tenantId(tenant)
                            .namespace(namespace)
                            .path(normalizedPath.toString())
                            .size(storage.getAttributes(tenant, namespace, cleanUri).getSize())
                            .build()
                    );
                    yield namespaceFile;
                } else {
                    throw new IOException(String.format(
                        "File '%s' already exists in namespace '%s' and conflict is set to %s",
                        normalizedPath,
                        namespace,
                        Conflicts.ERROR
                    ));
                }
            }
            case SKIP -> {
                if (inRepository.isEmpty()) {
                    storage.put(tenant, namespace, cleanUri, content);
                    namespaceFileMetadataRepository.save(
                        NamespaceFileMetadata.builder()
                            .tenantId(tenant)
                            .namespace(namespace)
                            .path(normalizedPath.toString())
                            .size(storage.getAttributes(tenant, namespace, cleanUri).getSize())
                            .build()
                    );
                    logger.debug(String.format(
                        "File '%s' added to namespace '%s'.",
                        normalizedPath,
                        namespace
                    ));
                    yield namespaceFile;
                } else {
                    logger.debug(String.format(
                        "File '%s' already exists in namespace '%s' and conflict is set to %s. Skipping.",
                        normalizedPath,
                        namespace,
                        Conflicts.SKIP
                    ));
                    yield namespaceFile;
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public URI createDirectory(Path path) throws IOException {
        final Path normalizedPath = NamespaceFile.normalize(path, true);

        namespaceFileMetadataRepository.save(
            NamespaceFileMetadata.builder()
                .tenantId(tenant)
                .namespace(namespace)
                .path(normalizedPath.toString().endsWith("/") ? normalizedPath.toString() : normalizedPath + "/")
                .size(0L)
                .build()
        );
        return storage.createDirectory(tenant, namespace, NamespaceFile.of(namespace, normalizedPath, 1).storagePath().toUri());
    }

    /**
     * {@inheritDoc}
     **/
    @Override
    public boolean delete(Path path) throws IOException {
        final Path normalizedPath = NamespaceFile.normalize(path, true);

        Optional<NamespaceFileMetadata> maybeNamespaceFileMetadata = namespaceFileMetadataRepository.find(Pageable.from(1, 1), tenant, List.of(
            QueryFilter.builder().field(QueryFilter.Field.NAMESPACE).operation(QueryFilter.Op.EQUALS).value(namespace).build(),
            QueryFilter.builder().field(QueryFilter.Field.PATH).operation(QueryFilter.Op.IN).value(List.of(normalizedPath.toString(), normalizedPath + "/")).build()
        ), false).stream().findFirst();

        this.children(normalizedPath.toString(), true).stream().map(NamespaceFileMetadata::toDeleted).forEach(namespaceFileMetadataRepository::save);

        maybeNamespaceFileMetadata.ifPresent(namespaceFileMetadata -> namespaceFileMetadataRepository.save(namespaceFileMetadata.toDeleted()));

        return true;
    }

    @Override
    public boolean purge(NamespaceFile namespaceFile) throws IOException {
        storage.delete(tenant, namespace, namespaceFile.storagePath().toUri());
        namespaceFileMetadataRepository.purge(List.of(NamespaceFileMetadata.of(tenant, namespaceFile)));
        return true;
    }
}
