package com.familyx.stocks.discord;

import com.familyx.stocks.config.BotProperties;
import com.familyx.stocks.data.Models.Position;
import com.familyx.stocks.data.Models.StockRow;
import com.familyx.stocks.data.StockRepository;
import com.familyx.stocks.engine.BacktestResult;
import com.familyx.stocks.engine.Backtester;
import com.familyx.stocks.engine.Bar;
import com.familyx.stocks.engine.Indicators;
import com.familyx.stocks.engine.Strategies;
import com.familyx.stocks.engine.Strategy;
import com.familyx.stocks.service.PortfolioService;
import com.familyx.stocks.service.ExportService;
import com.familyx.stocks.service.TradeEvents;
import jakarta.annotation.PreDestroy;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Discord front end with two channels:
 * <ul>
 *   <li>a control channel holding one panel embed with buttons, edited in place on restart and every hour;</li>
 *   <li>an alerts channel where buy and sell calls are posted, pinging the alert role.</li>
 * </ul>
 * Button and form replies are private to the person who clicked and are deleted after about 15 minutes, so the
 * control channel only ever shows the panel.
 */
@Component
public class DiscordBot extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(DiscordBot.class);
    private static final String PANEL_SETTING = "panel_message_id";
    /**
     * Private replies are deleted after this long. Discord only lets a bot touch an interaction reply for 15
     * minutes, so this sits just inside that window rather than exactly on it.
     */
    private static final long PRIVATE_REPLY_SECONDS = 14 * 60 + 50;
    private static final String EXPIRY_NOTE = "-# Disappears in 15 minutes";
    private static final DateTimeFormatter EXPORT_NAME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC);

    private static final String BTN_SIGNALS = "panel:signals";
    private static final String BTN_SIZE = "panel:size";
    private static final String BTN_PERFORMANCE = "panel:performance";
    private static final String BTN_STOCK = "panel:stock";
    private static final String BTN_STRATEGIES = "panel:strategies";
    private static final String BTN_BACKTEST = "panel:backtest";
    private static final String BTN_HELP = "panel:help";
    private static final String BTN_EXPORT = "panel:export";
    private static final String MODAL_SIZE = "modal:size";
    private static final String MODAL_STOCK = "modal:stock";
    private static final String MODAL_BACKTEST = "modal:backtest";

    private final BotProperties.Discord cfg;
    private final StockRepository repo;
    private final PortfolioService portfolio;
    private final ExportService exports;
    private volatile JDA jda;

    public DiscordBot(BotProperties props, StockRepository repo, PortfolioService portfolio, ExportService exports) {
        this.cfg = props.discord();
        this.repo = repo;
        this.portfolio = portfolio;
        this.exports = exports;
    }

    /** Starts before the market backfill so the panel works while history loads. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void start() {
        if (blank(cfg.token())) {
            log.warn("DISCORD_TOKEN is not set; running without Discord");
            return;
        }
        // Buttons, forms and posting need no gateway intents, so Discord sends no message or presence traffic.
        jda = JDABuilder.createLight(cfg.token(), EnumSet.noneOf(GatewayIntent.class)).addEventListeners(this).build();
    }

    @PreDestroy
    public void stop() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        log.info("Discord bot ready as {}", event.getJDA().getSelfUser().getName());
        refreshPanel();
    }

    // ---------- control panel ----------

    private static List<ActionRow> panelButtons() {
        return List.of(
                ActionRow.of(
                        Button.primary(BTN_SIGNALS, "Open positions"),
                        Button.success(BTN_SIZE, "Size for my budget"),
                        Button.secondary(BTN_PERFORMANCE, "Performance")),
                ActionRow.of(
                        Button.secondary(BTN_STOCK, "Stock lookup"),
                        Button.secondary(BTN_STRATEGIES, "Strategies"),
                        Button.secondary(BTN_BACKTEST, "Backtest"),
                        Button.secondary(BTN_HELP, "How it works").withEmoji(Emoji.fromUnicode("❔")),
                        Button.secondary(BTN_EXPORT, "Export data").withEmoji(Emoji.fromUnicode("📥"))));
    }

    /** Edits the existing panel message, or posts a new one if it was deleted or never posted. Runs hourly. */
    @Scheduled(cron = "30 0 * * * *")
    public void refreshPanel() {
        if (jda == null || blank(cfg.controlChannelId())) {
            return;
        }
        TextChannel ch = jda.getTextChannelById(cfg.controlChannelId());
        if (ch == null) {
            log.warn("Control channel {} not found", cfg.controlChannelId());
            return;
        }
        MessageEmbed embed;
        try {
            embed = Embeds.panel(portfolio.snapshot(), repo.assignments().size(), cfg.alertChannelId());
        } catch (Exception e) {
            log.warn("Couldn't build the panel yet: {}", e.getMessage());
            return;
        }
        try {
            Optional<String> existing = repo.setting(PANEL_SETTING);
            if (existing.isPresent()) {
                ch.retrieveMessageById(existing.get()).queue(
                        msg -> msg.editMessageEmbeds(embed).setComponents(panelButtons()).queue(),
                        err -> postPanel(ch, embed));
            } else {
                postPanel(ch, embed);
            }
        } catch (InsufficientPermissionException e) {
            log.warn("Can't post the control panel in #{}: missing {}. Fix the channel permissions; "
                    + "the bot retries every hour and on restart.", ch.getName(), e.getPermission().getName());
        }
    }

    private void postPanel(TextChannel ch, MessageEmbed embed) {
        try {
            sendPanel(ch, embed);
        } catch (InsufficientPermissionException e) {
            log.warn("Can't post the control panel in #{}: missing {}. Fix the channel permissions; "
                    + "the bot retries every hour and on restart.", ch.getName(), e.getPermission().getName());
        }
    }

    private void sendPanel(TextChannel ch, MessageEmbed embed) {
        ch.sendMessageEmbeds(embed).setComponents(panelButtons()).queue(
                msg -> {
                    repo.saveSetting(PANEL_SETTING, msg.getId());
                    msg.pin().queue(null, err -> log.info("Couldn't pin the panel (needs Manage Messages): {}", err.getMessage()));
                },
                err -> log.warn("Couldn't post the panel: {}", err.getMessage()));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        switch (event.getComponentId()) {
            case BTN_SIGNALS -> answer(event.deferReply(true), () -> Embeds.signals(portfolio.snapshot()), event.getComponentId());
            case BTN_PERFORMANCE -> answer(event.deferReply(true), this::performance, event.getComponentId());
            case BTN_EXPORT -> export(event);
            case BTN_HELP -> answer(event.deferReply(true), Embeds::help, event.getComponentId());
            case BTN_STRATEGIES -> answer(event.deferReply(true),
                    () -> Embeds.strategies(repo.assignments(), portfolio.stocksById()), event.getComponentId());
            case BTN_SIZE -> event.replyModal(Modal.create(MODAL_SIZE, "Size for your budget")
                    .addComponents(ActionRow.of(TextInput.create("budget", "Budget", TextInputStyle.SHORT)
                            .setPlaceholder("2b, 500m or 1,500,000").setRequired(true).setMaxLength(30).build()))
                    .build()).queue();
            case BTN_STOCK -> event.replyModal(Modal.create(MODAL_STOCK, "Stock lookup")
                    .addComponents(ActionRow.of(stockInput()))
                    .build()).queue();
            case BTN_BACKTEST -> event.replyModal(Modal.create(MODAL_BACKTEST, "Backtest a stock")
                    .addComponents(ActionRow.of(stockInput()),
                            ActionRow.of(TextInput.create("strategy", "Strategy (leave blank for all)", TextInputStyle.SHORT)
                                    .setPlaceholder("meanrev-168-2.0").setRequired(false).setMaxLength(40).build()))
                    .build()).queue();
            default -> {
                // Buttons from other bots or old panels: ignore.
            }
        }
    }

    private static TextInput stockInput() {
        return TextInput.create("stock", "Stock acronym", TextInputStyle.SHORT)
                .setPlaceholder("FHG").setRequired(true).setMaxLength(8).build();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        switch (event.getModalId()) {
            case MODAL_SIZE -> answer(event.deferReply(true),
                    () -> Embeds.size(portfolio.snapshot(), Fmt.parseBudget(value(event, "budget"))), event.getModalId());
            case MODAL_STOCK -> answer(event.deferReply(true), () -> stock(value(event, "stock")), event.getModalId());
            case MODAL_BACKTEST -> answer(event.deferReply(true), () -> {
                String strategy = value(event, "strategy");
                return backtest(value(event, "stock"), strategy.isBlank() ? null : strategy.trim());
            }, event.getModalId());
            default -> {
            }
        }
    }

    private static String value(ModalInteractionEvent event, String id) {
        ModalMapping m = event.getValue(id);
        return m == null ? "" : m.getAsString();
    }

    /** Acknowledges privately first (Discord allows 3 seconds), then builds the reply and reports errors kindly. */
    private void answer(ReplyCallbackAction defer,
                        Supplier<MessageEmbed> build, String what) {
        defer.queue(hook -> reply(hook, build, what));
    }

    private void reply(InteractionHook hook, Supplier<MessageEmbed> build, String what) {
        MessageEmbed embed;
        try {
            embed = build.get();
        } catch (IllegalArgumentException e) {
            embed = Embeds.error(e.getMessage());
        } catch (Exception e) {
            log.error("Panel action {} failed", what, e);
            embed = Embeds.error("Something went wrong. Try again in a minute.");
        }
        // Replace the "thinking" placeholder, then remove the reply before the interaction token expires.
        hook.editOriginal(new MessageEditBuilder().setContent(EXPIRY_NOTE).setEmbeds(embed).build())
                .queue(sent -> hook.deleteOriginal().queueAfter(PRIVATE_REPLY_SECONDS, TimeUnit.SECONDS, null,
                        err -> log.debug("Private reply already gone: {}", err.getMessage())));
    }

    // ---------- export ----------

    private boolean mayExport(ButtonInteractionEvent event) {
        if (!blank(cfg.ownerId())) {
            return cfg.ownerId().equals(event.getUser().getId());
        }
        Member m = event.getMember();
        return m != null && m.hasPermission(Permission.ADMINISTRATOR);
    }

    /** Sends the owner a private JSON file to upload to Claude for analysis. */
    private void export(ButtonInteractionEvent event) {
        if (!mayExport(event)) {
            answer(event.deferReply(true), () -> Embeds.error("Export is only available to the bot owner."), BTN_EXPORT);
            return;
        }
        event.deferReply(true).queue(hook -> {
            MessageEditBuilder msg = new MessageEditBuilder().setContent(EXPIRY_NOTE);
            try {
                ExportService.Export ex = exports.build();
                String name = "stock-bot-export-" + EXPORT_NAME.format(Instant.now()) + ".json";
                msg.setEmbeds(Embeds.exportReady(ex)).setFiles(FileUpload.fromData(ex.json(), name));
            } catch (Exception e) {
                log.error("Export failed", e);
                msg.setEmbeds(Embeds.error("Export failed. Check the bot logs."));
            }
            hook.editOriginal(msg.build()).queue(sent -> hook.deleteOriginal().queueAfter(PRIVATE_REPLY_SECONDS,
                    TimeUnit.SECONDS, null, err -> log.debug("Private reply already gone: {}", err.getMessage())));
        });
    }

    // ---------- alerts ----------

    @EventListener
    public void onOpened(TradeEvents.Opened e) {
        long positionId = e.position().id();
        post(Embeds.buy(e.position(), e.stock(), e.evidence()), true,
                msg -> repo.setAlertMessageId(positionId, msg.getId()));
    }

    @EventListener
    public void onClosed(TradeEvents.Closed e) {
        post(Embeds.sell(e.position(), e.stock()), true, null);
        markBuyAlertClosed(e.position());
    }

    /** Greys out the position's original buy alert and adds the result. Alerts from before this feature are skipped. */
    private void markBuyAlertClosed(Position closed) {
        Optional<String> messageId = repo.alertMessageId(closed.id());
        TextChannel ch = jda == null || blank(cfg.alertChannelId()) ? null : jda.getTextChannelById(cfg.alertChannelId());
        if (messageId.isEmpty() || ch == null) {
            return;
        }
        ch.retrieveMessageById(messageId.get()).queue(msg -> {
            if (!msg.getEmbeds().isEmpty()) {
                msg.editMessageEmbeds(Embeds.buyClosed(msg.getEmbeds().get(0), closed)).queue(null,
                        err -> log.warn("Couldn't mark buy alert closed: {}", err.getMessage()));
            }
        }, err -> log.info("Buy alert for position #{} not found; skipping", closed.id()));
    }

    /** Tells the owner by direct message when price collection stops and when it recovers. */
    @EventListener
    public void onCollectionHealth(TradeEvents.CollectionHealth h) {
        MessageEmbed embed = h.down() ? Embeds.collectionDown(h.lastError(), h.sinceTs())
                : Embeds.collectionUp(h.sinceTs(), h.nowTs());
        if (jda == null) {
            return;
        }
        if (!blank(cfg.ownerId())) {
            jda.retrieveUserById(cfg.ownerId()).flatMap(User::openPrivateChannel)
                    .flatMap(dm -> dm.sendMessageEmbeds(embed))
                    .queue(null, err -> {
                        log.warn("Couldn't DM the owner ({}); posting the health notice in the alerts channel", err.getMessage());
                        post(embed, false, null);
                    });
        } else {
            post(embed, false, null);
        }
    }

    private void post(MessageEmbed embed, boolean pingRole, Consumer<Message> onSent) {
        if (jda == null || blank(cfg.alertChannelId())) {
            return;
        }
        TextChannel ch = jda.getTextChannelById(cfg.alertChannelId());
        if (ch == null) {
            log.warn("Alert channel {} not found", cfg.alertChannelId());
            return;
        }
        try {
            var msg = ch.sendMessageEmbeds(embed);
            if (pingRole && !blank(cfg.alertRoleId())) {
                // mentionRoles whitelists just this role, so the ping works without allowing @everyone.
                msg = msg.setContent("<@&" + cfg.alertRoleId() + ">").mentionRoles(cfg.alertRoleId());
            }
            msg.queue(onSent, err -> log.warn("Couldn't post alert: {}", err.getMessage()));
        } catch (InsufficientPermissionException e) {
            log.warn("Can't post alerts in #{}: missing {}", ch.getName(), e.getPermission().getName());
        }
    }

    // ---------- replies ----------

    private MessageEmbed performance() {
        var snap = portfolio.snapshot();
        var stocks = portfolio.stocksById();
        return Embeds.performance(snap, portfolio.benchmarkSinceInception(snap.inceptionTs()), repo.closedCounts(),
                repo.maxDrawdown(), repo.assignments(), repo.closedPositions(5), stocks);
    }

    private StockRow requireStock(String acronym) {
        return repo.stockByAcronym(acronym.trim())
                .orElseThrow(() -> new IllegalArgumentException("No stock called " + acronym.trim() + ". Try an acronym like FHG."));
    }

    private MessageEmbed stock(String acronym) {
        StockRow s = requireStock(acronym);
        long now = System.currentTimeMillis() / 1000;
        long hour = now / 3600 * 3600;
        List<Bar> bars = repo.bars(s.id(), hour, 168);
        double z = bars.size() >= 168 ? Indicators.zScore(bars, 168) : Double.NaN;
        var holding = portfolio.snapshot().holdings().stream().filter(h -> h.stock().id() == s.id()).findFirst().orElse(null);
        return Embeds.stock(s, change(s, now - 3600), change(s, now - 86400), change(s, now - 7 * 86400), z,
                repo.assignment(s.id()).orElse(null), holding);
    }

    private Double change(StockRow s, long ts) {
        return repo.closeAtOrBefore(s.id(), ts).map(p -> s.price() / p - 1).orElse(null);
    }

    private MessageEmbed backtest(String acronym, String strategyId) {
        StockRow s = requireStock(acronym);
        long hour = System.currentTimeMillis() / 1000 / 3600 * 3600;
        List<Bar> bars = repo.bars(s.id(), hour, 24 * 366);
        List<Strategy> strategies;
        if (strategyId != null) {
            strategies = List.of(Strategies.byId(strategyId).orElseThrow(() -> new IllegalArgumentException(
                    "No strategy called " + strategyId + ". The Strategies button lists the ids in use.")));
        } else {
            strategies = Strategies.all();
        }
        if (bars.size() < 200) {
            throw new IllegalArgumentException("Not enough history for " + s.acronym() + " yet (" + bars.size() + " hours).");
        }
        List<BacktestResult> results = strategies.stream().map(st -> Backtester.run(st, bars))
                .sorted(Comparator.comparingDouble(BacktestResult::totalReturn).reversed()).toList();
        return Embeds.backtest(s, results, bars.size());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
