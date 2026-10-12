package dev.archfiend.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.archfiend.config.ArchfiendConfig;
import dev.archfiend.config.ConfigManager;
import dev.archfiend.drop.DropTier;
import dev.archfiend.drop.TestDrops;
import dev.archfiend.repo.NeuRepoManager;
import dev.archfiend.sequence.SequencePlayer;
import dev.archfiend.sequence.SequenceStyle;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** {@code /archfiend} — test the sequence and tweak settings in-game. */
public final class ArchfiendCommand
{
    private static final int PREFIX_COLOR = 0xAA00AA;
    private static final int GRAY = 0xAAAAAA;

    private static final SuggestionProvider<FabricClientCommandSource> TIER_SUGGESTIONS = (ctx, builder) ->
        SharedSuggestionProvider.suggest(Arrays.stream(DropTier.values()).map(DropTier::commandName), builder);
    private static final SuggestionProvider<FabricClientCommandSource> STYLE_SUGGESTIONS = (ctx, builder) ->
        SharedSuggestionProvider.suggest(Arrays.stream(SequenceStyle.values()).map(SequenceStyle::commandName), builder);

    private final ConfigManager configManager;
    private final SequencePlayer player;
    private final NeuRepoManager repoManager;
    private final Runnable openSettings;

    /**
     * @param openSettings opens the settings screen; must defer to the next tick, because the chat
     *                     screen closes right after a command runs and would replace it
     */
    public ArchfiendCommand(ConfigManager configManager, SequencePlayer player, NeuRepoManager repoManager, Runnable openSettings)
    {
        this.configManager = configManager;
        this.player = player;
        this.repoManager = repoManager;
        this.openSettings = openSettings;
    }

    public void register(CommandDispatcher<FabricClientCommandSource> dispatcher)
    {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("archfiend")
            .executes(this::status)
            .then(literal("config").executes(ctx -> {
                openSettings.run();
                return 1;
            }))
            .then(literal("test")
                .executes(ctx -> test(ctx, DropTier.CRAZY_RARE, null))
                .then(argument("tier", StringArgumentType.word()).suggests(TIER_SUGGESTIONS)
                    .executes(ctx -> withTier(ctx, tier -> test(ctx, tier, null)))
                    .then(argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> withTier(ctx, tier -> test(ctx, tier, StringArgumentType.getString(ctx, "item")))))))
            .then(literal("skip").executes(ctx -> {
                player.skip();
                return 1;
            }))
            .then(literal("toggle").executes(ctx -> update(ctx, c -> c.enabled = !c.enabled,
                c -> "Sequences " + (c.enabled ? "enabled" : "disabled"))))
            .then(literal("chatdelay").executes(ctx -> update(ctx, c -> c.delayChatMessage = !c.delayChatMessage,
                c -> "Delay drop message until reveal: " + c.delayChatMessage)))
            .then(literal("style")
                .then(argument("style", StringArgumentType.word()).suggests(STYLE_SUGGESTIONS)
                    .executes(this::setStyle)))
            .then(literal("mintier")
                .then(argument("tier", StringArgumentType.word()).suggests(TIER_SUGGESTIONS)
                    .executes(ctx -> withTier(ctx, tier -> update(ctx, c -> c.minTier = tier,
                        c -> "Minimum tier: " + tier.commandName())))))
            .then(literal("spin")
                .then(argument("ticks", IntegerArgumentType.integer(ArchfiendConfig.MIN_SPIN_TICKS, ArchfiendConfig.MAX_SPIN_TICKS))
                    .executes(ctx -> {
                        int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
                        return update(ctx, c -> c.spinTicks = ticks, c -> "Spin duration: " + ticks + " ticks");
                    })))
            .then(literal("scale")
                .then(argument("scale", FloatArgumentType.floatArg(ArchfiendConfig.MIN_SCALE, ArchfiendConfig.MAX_SCALE))
                    .executes(ctx -> {
                        float scale = FloatArgumentType.getFloat(ctx, "scale");
                        return update(ctx, c -> c.scale = scale, c -> "Scale: " + scale);
                    })))
            .then(literal("volume")
                .then(argument("volume", FloatArgumentType.floatArg(0f, 2f))
                    .executes(ctx -> {
                        float volume = FloatArgumentType.getFloat(ctx, "volume");
                        return update(ctx, c -> c.volume = volume, c -> "Volume: " + volume);
                    })))
            .then(literal("repo")
                .executes(ctx -> {
                    feedback(ctx, "NEU repo: " + repoManager.describe());
                    return 1;
                })
                .then(literal("update").executes(this::updateRepo))
                .then(literal("loot").executes(ctx -> update(ctx, c -> c.repo.useLootTables = !c.repo.useLootTables,
                    c -> "Use mob loot tables for reels: " + c.repo.useLootTables))))
            .then(literal("reload").executes(this::reload));

        dispatcher.register(root);
    }

    private int status(CommandContext<FabricClientCommandSource> ctx)
    {
        ArchfiendConfig c = configManager.get();
        feedback(ctx, String.format(
            "%s | style=%s | minTier=%s | spin=%dt | scale=%.2f | volume=%.2f | chatDelay=%s | queued=%d",
            c.enabled ? "enabled" : "disabled", c.style.commandName(), c.minTier.commandName(),
            c.spinTicks, c.scale, c.volume, c.delayChatMessage, player.queuedCount()
        ));
        feedback(ctx, "NEU repo: " + repoManager.describe());
        feedback(ctx, "Subcommands: config, test [tier] [item], skip, toggle, chatdelay, style, mintier, spin, scale, volume, repo [update|loot], reload");
        return 1;
    }

    private int test(CommandContext<FabricClientCommandSource> ctx, DropTier tier, String itemName)
    {
        if (!player.enqueue(TestDrops.create(configManager.get(), tier, itemName), true))
        {
            ctx.getSource().sendError(Component.literal("Sequence queue is full."));
            return 0;
        }
        return 1;
    }

    private int setStyle(CommandContext<FabricClientCommandSource> ctx)
    {
        String raw = StringArgumentType.getString(ctx, "style");
        Optional<SequenceStyle> style = SequenceStyle.byCommandName(raw);
        if (style.isEmpty())
        {
            ctx.getSource().sendError(Component.literal("Unknown style '" + raw + "'. Use reel or slots."));
            return 0;
        }
        return update(ctx, c -> c.style = style.get(), c -> "Style: " + c.style.commandName());
    }

    private int updateRepo(CommandContext<FabricClientCommandSource> ctx)
    {
        feedback(ctx, "Updating NEU repo...");
        FabricClientCommandSource source = ctx.getSource();
        repoManager.refreshAsync(true).thenRun(() -> Minecraft.getInstance().execute(() ->
            source.sendFeedback(prefixed("NEU repo: " + repoManager.describe()))));
        return 1;
    }

    private int reload(CommandContext<FabricClientCommandSource> ctx)
    {
        boolean ok = configManager.load();
        player.reloadParser();
        repoManager.refreshAsync(false);
        if (ok) feedback(ctx, "Config reloaded.");
        else ctx.getSource().sendError(Component.literal("Config was invalid; defaults restored (old file saved as .corrupt)."));
        return ok ? 1 : 0;
    }

    private int withTier(CommandContext<FabricClientCommandSource> ctx, Function<DropTier, Integer> action)
    {
        String raw = StringArgumentType.getString(ctx, "tier");
        return DropTier.byCommandName(raw).map(action).orElseGet(() -> {
            ctx.getSource().sendError(Component.literal("Unknown tier '" + raw + "'."));
            return 0;
        });
    }

    private int update(CommandContext<FabricClientCommandSource> ctx, Consumer<ArchfiendConfig> change, Function<ArchfiendConfig, String> message)
    {
        ArchfiendConfig config = configManager.get();
        change.accept(config);
        config.sanitize();
        configManager.save();
        feedback(ctx, message.apply(config));
        return 1;
    }

    private static void feedback(CommandContext<FabricClientCommandSource> ctx, String text)
    {
        ctx.getSource().sendFeedback(prefixed(text));
    }

    private static Component prefixed(String text)
    {
        MutableComponent prefix = Component.literal("[Archfiend] ").withColor(PREFIX_COLOR);
        return prefix.append(Component.literal(text).withColor(GRAY));
    }
}
