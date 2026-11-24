-- Two model portfolios ("books"): 'main' is the live rotation strategy members follow; 'dip' is the earlier
-- dip strategy kept running on paper so the two can be compared. Existing history stays in 'main'.
alter table model_position add column book text not null default 'main';
drop index model_position_one_open_per_stock;
create unique index model_position_one_open_per_book_stock on model_position (book, stock_id) where status = 'OPEN';

alter table portfolio_state drop constraint if exists portfolio_state_id_check;
alter table portfolio_state add column book text;
update portfolio_state set book = 'main' where id = 1;
alter table portfolio_state alter column book set not null;
create unique index portfolio_state_book on portfolio_state (book);

alter table equity_snapshot add column book text not null default 'main';
alter table equity_snapshot drop constraint equity_snapshot_pkey;
alter table equity_snapshot add primary key (book, ts);
