-- The Discord message id of each position's buy alert, so the alert can be marked closed when the trade ends.
alter table model_position add column alert_message_id text;
