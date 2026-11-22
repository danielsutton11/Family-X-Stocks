-- 15-minute OHLC bars, kept for good. Not used by the current strategies; collected so faster strategies
-- (e.g. quicker exits) can be backtested on our own history later. About 1.2M rows a year for 35 stocks.
create table bar_15m (
    stock_id  int    not null references stock (id),
    ts        bigint not null,
    open      double precision not null,
    high      double precision not null,
    low       double precision not null,
    close     double precision not null,
    primary key (stock_id, ts)
);
