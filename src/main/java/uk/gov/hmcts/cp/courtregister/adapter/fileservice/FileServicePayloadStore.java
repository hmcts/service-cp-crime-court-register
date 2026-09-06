package uk.gov.hmcts.cp.courtregister.adapter.fileservice;

import java.util.UUID;
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

    @Override
    public void store(final UUID fileId, final JsonNode payload, final PayloadMetadata metadata)
            throws PayloadStoreUnavailableException {
        throw new UnsupportedOperationException("T044");
    }
}
