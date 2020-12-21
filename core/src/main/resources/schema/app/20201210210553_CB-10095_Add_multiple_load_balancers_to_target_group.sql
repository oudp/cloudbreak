-- // CB-10095 Add multiple load balancers to target group
-- Migration SQL that makes the change goes here.

DROP INDEX IF EXISTS targetgroup_loadbalancer_id;

CREATE TABLE IF NOT EXISTS targetgroup_loadbalancer (
    targetgroups_id     bigint NOT NULL,
    loadbalancers_id    bigint NOT NULL
);

ALTER TABLE ONLY targetgroup_loadbalancer ADD CONSTRAINT targetgroup_loadbalancer_pkey PRIMARY KEY (targetgroups_id, loadbalancers_id);
ALTER TABLE ONLY targetgroup_loadbalancer ADD CONSTRAINT fk_targetgroup_loadbalancer_targetgroup_id FOREIGN KEY (targetgroups_id) REFERENCES targetgroup(id);
ALTER TABLE ONLY targetgroup_loadbalancer ADD CONSTRAINT fk_targetgroup_loadbalancer_loadbalancer_id FOREIGN KEY (loadbalancers_id) REFERENCES loadbalancer(id);

INSERT INTO targetgroup_loadbalancer (SELECT id, loadbalancer_id FROM targetgroup);

ALTER TABLE targetgroup DROP COLUMN IF EXISTS loadbalancer_id;

-- //@UNDO
-- SQL to undo the change goes here.

ALTER TABLE targetgroup ADD COLUMN IF NOT EXISTS loadbalancer_id bigint;

ALTER TABLE ONLY targetgroup
    ADD CONSTRAINT fk_targetgroup_loadbalancer_id FOREIGN KEY (loadbalancer_id) REFERENCES loadbalancer(id);

UPDATE targetgroup SET loadbalancer_id =
    (SELECT targetgroup_loadbalancer.loadbalancers_id
    FROM targetgroup_loadbalancer
    WHERE targetgroup_loadbalancer.targetgroups_id = targetgroup.id limit 1);

CREATE INDEX IF NOT EXISTS targetgroup_loadbalancer_id ON targetgroup (loadbalancer_id);

DROP TABLE IF EXISTS targetgroup_loadbalancer;