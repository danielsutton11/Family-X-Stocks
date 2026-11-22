create table stock (
    id          int primary key,
    acronym     varchar(8)  not null unique,
    name        text        not null,
    price       double precision,
    updated_ts  bigint
);

-- One price per stock per minute, from the Torn API. Pruned after a couple of weeks.
create table price_tick (
    stock_id  int    not null references stock (id),
    ts        bigint not null,
    price     double precision not null,
    primary key (stock_id, ts)
);

-- Hourly OHLC bars: built live from ticks and backfilled from Tornsy. Strategies run on these.
create table bar_hourly (
    stock_id  int    not null references stock (id),
    ts        bigint not null,
    open      double precision not null,
    high      double precision not null,
    low       double precision not null,
    close     double precision not null,
    primary key (stock_id, ts)
);

-- The strategy the selector chose for each stock, with the evidence behind the choice.
create table strategy_assignment (
    stock_id          int primary key references stock (id),
    strategy_id       text   not null,
    is_trades         int    not null,
    is_win_rate       double precision not null,
    is_avg_net        double precision not null,
    is_profit_factor  double precision not null,
    is_total_return   double precision not null,
    is_t_stat         double precision not null,
    oos_trades        int    not null,
    oos_total_return  double precision not null,
    selected_ts       bigint not null
);

-- The model portfolio everyone follows. Units are fractions of a starting equity of 1.0, so any budget scales.
create table model_position (
    id              bigserial primary key,
    stock_id        int    not null references stock (id),
    strategy_id     text   not null,
    status          varchar(8) not null,
    entry_ts        bigint not null,
    entry_price     double precision not null,
    units           double precision not null,
    alloc           double precision not null,
    target          double precision,
    stop            double precision,
    trail_pct       double precision,
    high_water      double precision not null,
    max_hold_until  bigint not null,
    reason          text,
    exit_ts         bigint,
    exit_price      double precision,
    exit_reason     varchar(16),
    net_return      double precision
);
create unique index model_position_one_open_per_stock on model_position (stock_id) where status = 'OPEN';

create table portfolio_state (
    id            int primary key check (id = 1),
    cash          double precision not null,
    inception_ts  bigint not null
);

create table equity_snapshot (
    ts      bigint primary key,
    equity  double precision not null
);
