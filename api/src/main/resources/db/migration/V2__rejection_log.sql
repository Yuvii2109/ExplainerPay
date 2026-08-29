-- Beat 10 needs the rejected payload on screen, not a note that a rejection happened.
-- A safety rule visibly catching the model is worth more than any number of correct outputs,
-- and it is only worth anything if you can see what was thrown away.
alter table model_calls add column rejected_payload text;
