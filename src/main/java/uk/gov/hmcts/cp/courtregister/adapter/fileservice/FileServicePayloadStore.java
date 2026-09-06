package uk.gov.hmcts.cp.courtregister.adapter.fileservice;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.application.PayloadFileStore;
import uk.gov.hmcts.cp.courtregister.application.PayloadMetadata;
import uk.gov.hmcts.cp.courtregister.domain.PayloadStoreUnavailableException;

/**
 * The payload store, wired to the framework file service's own database.
 *
 * <p>Two inserts and nothing else, over the second datasource
 * ({@code FileServiceDataSourceConfig.JDBC_CLIENT}, the {@code fileServiceJdbcClient} bean, which is
 * reached by name because a second {@code JdbcClient} found by type would take the processed log's
 * away). They are the statements the framework's own repositories issue, character for character:
 *
 * <pre>{@code
 * INSERT INTO metadata(metadata, file_id) VALUES (to_json(?::json), ?);
 * INSERT INTO content(file_id, content, deleted) VALUES (?, ?, false);
 * }</pre>
 *
 * <p>The metadata is progression's five keys spelled progression's way. This is not this service's
 * database and systemdocgenerator is not this service's reader, so a sixth key or a different
 * spelling would be a change to somebody else's contract made without asking, and
 * {@code FileServicePayloadStoreIT} asserts that no other statement is issued at all.
 *
 * <p>Anything that leaves the payload not durably stored becomes
 * {@link PayloadStoreUnavailableException}: the batch fails PAYLOAD_STORE_UNAVAILABLE and its rows
 * stay RECORDED, so the next run re-assembles them rather than a night's registers being lost to a
 * database that was briefly away.
 *
 * <p><strong>Seam only.</strong> The adapter lands with T044; until then this throws, so that
 * {@code FileServicePayloadStoreIT} records a failing assertion rather than a compile error.
 */
public class FileServicePayloadStore implements PayloadFileStore {

    /**
     * The client the two inserts are issued through - the file service's, never the processed log's.
     */
    private final JdbcClient jdbcClient;

    /**
     * Binds the store to the file-service database and to no other.
     *
     * <p>The client arrives by name ({@code FileServiceDataSourceConfig.JDBC_CLIENT}) rather than by
     * type, because the type is ambiguous: this service holds two, and the one found by type is the
     * processed log's. A store that took that one would write a night's payloads into the register's
     * own database, where nothing would ever read them and the two tables do not exist.
     *
     * @param jdbcClient the file-service client, qualified by name
     */
    public FileServicePayloadStore(final JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "the file-service client is required");
    }

    @Override
    public void store(final UUID fileId, final JsonNode payload, final PayloadMetadata metadata)
            throws PayloadStoreUnavailableException {
        throw new UnsupportedOperationException("T044 issues the two inserts through " + jdbcClient);
    }
}
