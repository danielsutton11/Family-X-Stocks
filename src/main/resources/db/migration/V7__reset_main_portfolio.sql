-- Start the live rotation portfolio from scratch. Any position still open in 'main' from the dip strategy is
-- closed at the current price, then all earlier 'main' history moves to a 'legacy' book (kept for the record,
-- shown nowhere). Main restarts at equity 1.0 from now, and the first rebalance runs straight away.
update model_position mp
   set status = 'CLOSED', exit_ts = extract(epoch from now())::bigint, exit_price = s.price, exit_reason = 'RESET',
       net_return = (s.price * 0.999 - mp.entry_price) / mp.entry_price
  from stock s
 where s.id = mp.stock_id and mp.book = 'main' and mp.status = 'OPEN';
update model_position set book = 'legacy' where book = 'main';
update equity_snapshot set book = 'legacy' where book = 'main';
update portfolio_state set cash = 1.0, inception_ts = extract(epoch from now())::bigint where book = 'main';
delete from bot_setting where key in ('next_rebalance_ts', 'rotation_started', 'last_rebalance_message_id');
