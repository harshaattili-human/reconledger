CREATE INDEX recon_batch_tenant_currency_sequence
    ON recon_batch(tenant_id, currency, list_sequence);
