package db.migration;

import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V4__Tenant_scoped_batches extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        // V1 left this constraint unnamed; H2 and PostgreSQL generate different names.
        // Resolve the single-column unique constraint rather than guessing its name.
        var names = new ArrayList<String>();
        try (var query = connection.prepareStatement("""
            SELECT tc.constraint_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kc
              ON tc.constraint_schema = kc.constraint_schema AND tc.constraint_name = kc.constraint_name
             AND tc.table_name = kc.table_name
            WHERE tc.table_schema = ? AND LOWER(tc.table_name) = 'recon_batch'
              AND tc.constraint_type = 'UNIQUE'
            GROUP BY tc.constraint_name
            HAVING COUNT(*) = 1 AND MAX(LOWER(kc.column_name)) = 'idempotency_key'
            """)) {
            query.setString(1, connection.getSchema());
            try (var rows = query.executeQuery()) {
                while (rows.next()) names.add(rows.getString(1));
            }
        }
        if (names.size() != 1) throw new IllegalStateException("Expected one legacy idempotency constraint");
        String constraint = "\"" + names.get(0).replace("\"", "\"\"") + "\"";
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE recon_batch ADD COLUMN tenant_id VARCHAR(64) DEFAULT 'demo' NOT NULL");
            statement.execute("ALTER TABLE recon_batch ADD CONSTRAINT recon_batch_tenant_key UNIQUE (tenant_id, idempotency_key)");
            statement.execute("ALTER TABLE recon_batch DROP CONSTRAINT " + constraint);
            statement.execute("CREATE INDEX recon_batch_tenant_sequence ON recon_batch(tenant_id, list_sequence)");
        }
    }
}
