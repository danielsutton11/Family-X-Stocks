-- Minute ticks were stored but never read: strategies run on hourly bars. Dropping the table frees its disk
-- space straight away and saves ~50k writes a day.
drop table if exists price_tick;
