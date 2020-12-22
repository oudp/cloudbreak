-- // CB-9746: Use new loadbalancer endpoints for DL services
-- Migration SQL that makes the change goes here.

ALTER TABLE loadbalancer ADD COLUMN IF NOT EXISTS fqdn character varying(255) DEFAULT 'No Info';
UPDATE loadbalancer SET fqdn = 'No Info' WHERE fqdn IS NULL;

-- //@UNDO
-- SQL to undo the change goes here.

ALTER TABLE loadbalancer DROP COLUMN IF EXISTS fqdn;
