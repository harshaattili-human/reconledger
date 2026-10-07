ALTER TABLE recon_batch ADD COLUMN list_sequence BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX recon_batch_list_sequence ON recon_batch(list_sequence);
CREATE INDEX recon_batch_date_currency_sequence ON recon_batch(business_date, currency, list_sequence);
