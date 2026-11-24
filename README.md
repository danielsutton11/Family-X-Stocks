# Family X Stocks — Torn stock trading bot

A Discord bot that watches every Torn stock, picks a trading strategy per stock from backtests, runs a model
portfolio, and posts buy and sell alerts. Members follow the alerts with their own money at whatever budget
they have. The Torn API is read-only, so the bot never trades; it tells you what to do and you click.

Dividends and benefit blocks are deliberately out of scope: this bot only buys and sells at the share price.

## How it decides

**Live strategy: rotation (what members follow).** Torn stock prices drift back towards their recent average over
1–3 weeks (week-to-week returns are negatively correlated, about -0.2), while hour-to-hour moves are noise and the
35 stocks move independently. So every 3 days, at 18:00 TCT, the bot:

1. ranks all 35 stocks by how far the price sits below its 4-week (672-hour) average;
2. sells any holding that now ranks outside the top 10;
3. fills free slots from the top 5, 20% of the portfolio each.

On a year of hourly prices (research in Nov 2026, tuned on the first half, tested on the second) this made about
24%/yr on the unseen half against about 2% for buy and hold, beat buy and hold in every quarter, and its worst
dip was about 1%. Acting up to 12 hours late barely changed the result. Past results don't guarantee future ones.

**Paper strategy: dips (for comparison).** The original dip strategies keep running on a separate paper
portfolio with no alerts, so the export can compare the two live. Each night they're re-selected per stock
(mean reversion, trend and breakout variants, walk-forward with t-stat and out-of-sample checks).

Data: one `GET /v2/torn/stocks` call a minute, rolled into hourly and 15-minute bars. On first start the bot
backfills 12 months of hourly bars from tornsy.com.

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
| Performance | Rotation portfolio return vs equal-weight buy and hold, trades, drawdown, recent trades, and the paper dip portfolio for comparison |
| Stock lookup | Pop-up asks for an acronym; price, 1h/24h/7d moves, gap to the 4-week average, rotation rank and status |
| Rankings | All 35 stocks ranked against their 4-week average, with held stocks, buy candidates and the sell line |
| Backtest | Pop-up asks for an acronym and optional strategy id; backtests the dip strategies on stored history |
| How it works | Plain-English guide to the model portfolio, alerts, strategy selection and the buttons |
| Export data | Owner only. Two private files for uploading to Claude to tune settings: a JSON file with settings, every trade, the equity curve and every strategy's backtest on every stock (with the rule each one failed), and a gzipped CSV of every stored hourly bar (`stock,ts,open,high,low,close`, about 3 MB) for portfolio simulations |

If someone deletes the panel, the next hourly refresh or restart posts a new one.

**Alerts channel.** One **Rebalance** message every 3 days listing what to sell, buy and keep, pinging role
`1574038019638296647` (override with `DISCORD_ALERT_ROLE_ID`) when anything changes. The previous rebalance
message is greyed out as **Superseded**, so the newest one is always the one to follow.

**Outage warnings.** If price collection fails for 15 minutes (Torn API down, key revoked), the bot sends the
owner (`DISCORD_OWNER_ID`) a direct message with the last error, and another when it recovers. Without an owner
id, or if DMs are closed, the notice goes to the alerts channel without a ping.

**Restarts.** The bot remembers the last hour it processed, so a restart just after the hour still runs that
hour's checks.

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

Rotation settings (defaults match the research): `ROTATION_LOOKBACK_HOURS` (672), `ROTATION_EVERY_HOURS` (72),
`ROTATION_HOLD` (5), `ROTATION_KEEP` (10), `ROTATION_ANCHOR_HOUR_UTC` (18).

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
engine/     rotation, indicators, dip strategies, backtester, selector (plain Java, no framework)
torn/       Torn API and Tornsy clients
data/       Postgres access (Flyway migration in resources/db/migration)
service/    collector, rotation (live), dip strategies (paper), daily selection, portfolio views, export
discord/    JDA bot, control panel buttons and forms, alerts, embeds
```
