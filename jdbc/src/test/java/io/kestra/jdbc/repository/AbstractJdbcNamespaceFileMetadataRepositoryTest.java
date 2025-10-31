package io.kestra.jdbc.repository;

import io.kestra.core.repositories.AbstractNamespaceFileMetadataRepositoryTest;
import io.kestra.jdbc.JdbcTestUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

public abstract class AbstractJdbcNamespaceFileMetadataRepositoryTest extends AbstractNamespaceFileMetadataRepositoryTest {
    @Inject
    JdbcTestUtils jdbcTestUtils;

    @BeforeEach
    protected void init() {
        jdbcTestUtils.drop();
        jdbcTestUtils.migrate();
    }
}
