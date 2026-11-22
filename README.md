# Family X Stocks — Torn stock trading bot

A Discord bot that watches every Torn stock, picks a trading strategy per stock from backtests, runs a model
portfolio, and posts buy and sell alerts. Members follow the alerts with their own money at whatever budget
they have. The Torn API is read-only, so the bot never trades; it tells you what to do and you click.

Dividends and benefit blocks are deliberately out of scope: this bot only buys and sells at the share price.

## How it decides

1. **Data.** Every minute (at :15 seconds) the bot makes one call to `GET /v2/torn/stocks` with the owner's key
   and stores each price, rolling them into hourly OHLC bars. Torn's per-stock endpoint only returns the last
   hour, so on first start the bot backfills up to 12 months of hourly bars from tornsy.com (no key needed).
2. **Strategies.** Ten variants across three families, all evaluated on hourly bars:
   - *Mean reversion* — buy when price is 1.5–2.5 standard deviations below its 72- or 168-hour average and the
     gap back is worth at least 0.5% after the fee; target the average; 3% stop; 14-day max hold.
   - *Trend following* — buy when the 12/72 or 24/168-hour EMAs cross upwards; sell on the cross back down or
     a 3–4% trailing stop.
   - *Breakout* — buy when price closes above its 72- or 168-hour high; sell below the 24- or 72-hour low or on
     a 3–5% trailing stop.
3. **Selection (daily, 00:10 TCT).** For each stock, every variant is backtested on the first 70% of its
   history. A variant qualifies only if it made at least 6 trades, a profit factor of 1.3+, an average trade of
   +0.2% after the 0.1% sell fee, and a t-stat of 2+ (so the edge is unlikely to be luck). The best qualifier
   must then make money on the last 30%, which it never saw. Stocks with nothing that passes are not traded.
   On synthetic data calibrated to Torn's ~0.1%/hour volatility, this picks a strategy for 1 in 20 pure random
   walks and finds 19 in 20 trends.
4. **Model portfolio.** Starts at an equity of 1.0. Each entry takes 10% of equity (if the cash is there).
   Stops and targets are checked every minute at the live price; indicator exits and new entries are checked
   when each hourly bar closes. All results include the 0.1% sell fee.

## Discord channels

The bot uses two channels and no slash commands.

**Control channel.** One panel message with a status strip (return, open positions, cash, live strategies)
and eight buttons. The bot posts and pins it on first start, then edits the same message on every restart and
once an hour. Every reply is visible only to the person who clicked and is deleted after about 15 minutes (14m50s, just inside
Discord's 15-minute limit for editing interaction replies), so the channel only ever shows the panel.

| Button | What it does |
|---|---|
| Open positions | Open positions with entry, current price and P/L after fee |
| Size for my budget | Pop-up asks for a budget (2b, 500m, 1,500,000), replies with share counts per position |
| Performance | Model portfolio return vs equal-weight buy and hold, trades, drawdown, recent trades |
| Stock lookup | Pop-up asks for an acronym; price, 1h/24h/7d moves, z-score, strategy and its evidence |
| Strategies | Every stock's assigned strategy with its backtest numbers |
| Backtest | Pop-up asks for an acronym and optional strategy id; backtests stored history |
| How it works | Plain-English guide to the model portfolio, alerts, strategy selection and the buttons |
| Export data | Owner only. Private JSON file with settings, every trade, the equity curve and every strategy's backtest on every stock (with the rule each one failed), for uploading to Claude to tune settings |

If someone deletes the panel, the next hourly refresh or restart posts a new one.

**Alerts channel.** Buy and sell calls, each pinging role `1574038019638296647` (override with
`DISCORD_ALERT_ROLE_ID`).

## Deploying on Railway

1. Create a new project with a **PostgreSQL** service.
2. Add a service from this repo. Railway builds it with the included `Dockerfile`.
3. On the bot service, set these variables:

| Variable | Value |
|---|---|
| `TORN_API_KEY` | Your Torn API key |
| `DISCORD_TOKEN` | Bot token from the Discord developer portal |
| `DISCORD_CONTROL_CHANNEL_ID` | Channel for the control panel |
| `DISCORD_ALERT_CHANNEL_ID` | Channel for buy/sell alerts |
| `DISCORD_ALERT_ROLE_ID` | Optional; defaults to `1574038019638296647` |
| `DISCORD_OWNER_ID` | Your Discord user id; only you can use Export data (blank = server administrators) |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | Reference variables from the Postgres service, e.g. `${{Postgres.PGHOST}}` |

Optional: `TORNSY_BACKFILL` (default `true`), `TORNSY_BACKFILL_MONTHS` (12), `TRADING_POSITION_SIZE` (0.10),
`TRADING_MIN_POSITION` (0.02), `JAVA_OPTS` (JVM flags; the Dockerfile sets a 192 MB heap).

Invite the bot with the `bot` scope. In both channels it needs View Channel, Send Messages, Embed Links and Read
Message History (to find and edit the panel). Manage Messages in the control channel lets it pin the panel;
without it the panel still works, unpinned. For the alert ping to notify anyone, either set the role to
"Allow anyone to @mention this role" or give the bot Mention @everyone, @here and All Roles. It needs no
privileged intents.

First start takes a few minutes while it backfills 35 stocks from Tornsy and runs selection. The logs end with
`Strategy selection done: N of 35 stocks have a strategy that beats the fee`.

## Things to know

- You can't trade in Torn while in hospital, in jail or travelling, and an alert can be stale by the time you
  act. The Open positions button always shows the current price against the entry.
- The model portfolio is shared; Size for my budget scales it to any budget using each position's current weight.
- Backtests are not promises. Treat the first few weeks of live alerts as the real test, and watch
  Performance against buy and hold.
- The bot uses one Torn API call a minute and three batched database writes a minute. It stores hourly bars
  (about 300k rows a year for all 35 stocks, roughly 25 MB), which the strategies use, and 15-minute bars
  (about 1.2M rows a year, roughly 100 MB), kept for testing faster strategies later. Neither is ever pruned.
- Running cost is mostly memory. The JVM heap is capped at 192 MB and Discord sends no gateway traffic beyond what
  buttons need, so the bot should sit around 200–250 MB in total.

## Layout

```
engine/     indicators, strategies, backtester, selector (plain Java, no framework)
torn/       Torn API and Tornsy clients
data/       Postgres access (Flyway migration in resources/db/migration)
service/    collector, trading loop, daily selection, portfolio views
discord/    JDA bot, control panel buttons and forms, alerts, embeds
```
