package dev.harshaattili.reconledger;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.postgresql.PGStatement;

/** Keep this skew-sensitive query out of pgJDBC's named-statement plan history. */
final class BatchBrowseStatementPolicy {
    private BatchBrowseStatementPolicy() {}

    static void apply(PreparedStatement statement) throws SQLException {
        if (statement.isWrapperFor(PGStatement.class)) {
            statement.unwrap(PGStatement.class).setPrepareThreshold(0);
        }
    }
}
