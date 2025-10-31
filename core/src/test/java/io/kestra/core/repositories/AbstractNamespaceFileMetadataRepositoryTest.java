package io.kestra.core.repositories;

import io.kestra.core.models.FetchVersion;
import io.kestra.core.models.QueryFilter;
import io.kestra.core.models.kv.PersistedKvMetadata;
import io.kestra.core.models.namespaces.files.NamespaceFileMetadata;
import io.kestra.core.utils.TestsUtils;
import io.kestra.core.junit.annotations.KestraTest;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@KestraTest
public abstract class AbstractNamespaceFileMetadataRepositoryTest {
    @Inject
    protected NamespaceFileMetadataRepositoryInterface namespaceFileMetadataRepositoryInterface;

    @Test
    void findNamespaceFileMetadataByPath() throws IOException {
        String tenantId = TestsUtils.randomTenant();
        String namespace = TestsUtils.randomNamespace();
        String path = "test/ns/file";
        NamespaceFileMetadata metadata = NamespaceFileMetadata.builder()
            .tenantId(tenantId)
            .namespace(namespace)
            .path(path)
            .version(1)
            .size(1L)
            .build();

        namespaceFileMetadataRepositoryInterface.save(metadata);

        namespaceFileMetadataRepositoryInterface.save(metadata.toBuilder().version(2).build());

        Optional<NamespaceFileMetadata> found = namespaceFileMetadataRepositoryInterface.findByPath(
            tenantId,
            namespace,
            path
        );

        assertThat(found).isPresent();
        assertThat(found.get().getPath()).isEqualTo(path);
        assertThat(found.get().getVersion()).isEqualTo(2);
        assertThat(found.get().isLast()).isTrue();
        assertThat(found.get().isDeleted()).isFalse();
    }

    @Test
    void deleteMetadata() throws IOException {
        String tenantId = TestsUtils.randomTenant();
        String namespace = TestsUtils.randomNamespace();
        String path = "test/ns/file";
        NamespaceFileMetadata metadata = NamespaceFileMetadata.builder()
            .tenantId(tenantId)
            .namespace(namespace)
            .path(path)
            .version(1)
            .size(1L)
            .build();

        namespaceFileMetadataRepositoryInterface.save(metadata);

        Optional<NamespaceFileMetadata> found = namespaceFileMetadataRepositoryInterface.findByPath(
            tenantId,
            namespace,
            path
        );

        assertThat(found).isPresent();
        assertThat(found.get().getPath()).isEqualTo(path);
        assertThat(found.get().isLast()).isTrue();
        assertThat(found.get().isDeleted()).isFalse();
        Instant beforeDeleteUpdateDate = found.get().getUpdated();

        namespaceFileMetadataRepositoryInterface.delete(found.get());

        found = namespaceFileMetadataRepositoryInterface.findByPath(
            tenantId,
            namespace,
            path
        );

        assertThat(found).isPresent();
        assertThat(found.get().getPath()).isEqualTo(path);
        // Soft delete
        assertThat(found.get().getVersion()).isEqualTo(1);
        assertThat(found.get().isLast()).isTrue();
        assertThat(found.get().isDeleted()).isTrue();
        assertThat(found.get().getUpdated()).isAfter(beforeDeleteUpdateDate);
    }

    @Test
    void findWithFilters() throws IOException {
        String tenantId = TestsUtils.randomTenant();
        String namespace = TestsUtils.randomNamespace();
        String path = "test/ns/file";
        NamespaceFileMetadata metadata = NamespaceFileMetadata.builder()
            .tenantId(tenantId)
            .namespace(namespace)
            .path(path)
            .size(1L)
            .build();

        assertThat(metadata.getVersion()).isNull();
        assertThat(namespaceFileMetadataRepositoryInterface.save(metadata).getVersion()).isEqualTo(1);
        // Resaving will increment version
        metadata = namespaceFileMetadataRepositoryInterface.save(metadata);
        assertThat(metadata.getVersion()).isEqualTo(2);

        String anotherNamespace = TestsUtils.randomNamespace();
        String anotherNamespaceDeletedPath = "test/another/ns/file";
        NamespaceFileMetadata anotherMetadata = NamespaceFileMetadata.builder()
            .tenantId(tenantId)
            .namespace(anotherNamespace)
            .path(anotherNamespaceDeletedPath)
            .size(1L)
            .build();

        namespaceFileMetadataRepositoryInterface.save(anotherMetadata);
        namespaceFileMetadataRepositoryInterface.delete(anotherMetadata);

        // It will only retrieve latest versions by default
        ArrayListTotal<NamespaceFileMetadata> found = namespaceFileMetadataRepositoryInterface.find(Pageable.from(1, 1), tenantId, Collections.emptyList(), true);
        assertThat(found).hasSize(1);
        assertThat(found.getTotal()).isEqualTo(2);

        // We get all versions if we put FetchVersion.ALL
        found = namespaceFileMetadataRepositoryInterface.find(Pageable.from(1, 10), tenantId, Collections.emptyList(), true, FetchVersion.ALL);
        assertThat(found).hasSize(3);
        assertThat(found.getTotal()).isEqualTo(3);
        List<NamespaceFileMetadata> versionsForKey = found.stream().filter(nsFileMetadata -> nsFileMetadata.getPath().equals(path)).toList();
        assertThat(versionsForKey.size()).isEqualTo(2);
        assertThat(versionsForKey.stream().map(NamespaceFileMetadata::getVersion)).containsExactlyInAnyOrder(1, 2);

        // We get all versions but latest if we put FetchVersion.OLD
        found = namespaceFileMetadataRepositoryInterface.find(Pageable.from(1, 10), tenantId, Collections.emptyList(), true, FetchVersion.OLD);
        assertThat(found).hasSize(1);
        assertThat(found.getTotal()).isEqualTo(1);
        assertThat(found.getFirst().getVersion()).isEqualTo(1);
        assertThat(found.getFirst().isLast()).isFalse();


        found = namespaceFileMetadataRepositoryInterface.find(
            Pageable.unpaged(),
            tenantId,
            List.of(QueryFilter.builder().field(QueryFilter.Field.NAMESPACE).operation(QueryFilter.Op.EQUALS).value(anotherNamespace).build()),
            true
        );
        assertThat(found.getTotal()).isEqualTo(1);
        assertThat(found.map(NamespaceFileMetadata::getPath)).containsExactlyInAnyOrder(anotherNamespaceDeletedPath);

        found = namespaceFileMetadataRepositoryInterface.find(
            Pageable.unpaged(),
            tenantId,
            List.of(QueryFilter.builder().field(QueryFilter.Field.NAMESPACE).operation(QueryFilter.Op.EQUALS).value(anotherNamespace).build()),
            false
        );
        assertThat(found.getTotal()).isEqualTo(0);

        found = namespaceFileMetadataRepositoryInterface.find(
            Pageable.unpaged(),
            tenantId,
            List.of(QueryFilter.builder().field(QueryFilter.Field.NAMESPACE).operation(QueryFilter.Op.EQUALS).value(anotherNamespace).build()),
            true
        );
        assertThat(found.getTotal()).isEqualTo(1);
        assertThat(found.getFirst().getPath()).isEqualTo(anotherNamespaceDeletedPath);

        found = namespaceFileMetadataRepositoryInterface.find(
            Pageable.unpaged(),
            tenantId,
            Collections.emptyList(),
            false
        );
        assertThat(found.getTotal()).isEqualTo(1);
        assertThat(found.getFirst().getPath()).isEqualTo(path);
    }

    @Test
    void purgeAllVersions() throws IOException {
        String tenantId = TestsUtils.randomTenant();
        String namespace = TestsUtils.randomNamespace();
        String path = "test/ns/file";
        NamespaceFileMetadata metadata = NamespaceFileMetadata.builder()
            .tenantId(tenantId)
            .namespace(namespace)
            .path(path)
            .size(1L)
            .build();

        assertThat(metadata.getVersion()).isNull();
        assertThat(namespaceFileMetadataRepositoryInterface.save(metadata).getVersion()).isEqualTo(1);
        metadata = namespaceFileMetadataRepositoryInterface.save(metadata);
        assertThat(metadata.getVersion()).isEqualTo(2);

        Integer purgedAmount = namespaceFileMetadataRepositoryInterface.purge(List.of(
            NamespaceFileMetadata.builder()
                .tenantId(tenantId)
                .namespace(namespace)
                .path(path).build()
        ));

        assertThat(purgedAmount).isEqualTo(2);

        assertThat(namespaceFileMetadataRepositoryInterface.findByPath(tenantId, namespace, path).isPresent()).isFalse();
    }
}
