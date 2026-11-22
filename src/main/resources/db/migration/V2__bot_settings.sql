-- Small key/value store, e.g. the id of the control panel message so a restart edits it instead of reposting.
create table bot_setting (
    key    text primary key,
    value  text not null
);
