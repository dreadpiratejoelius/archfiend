package dev.archfiend.gui;

import com.mojang.serialization.Codec;
import dev.archfiend.config.ArchfiendConfig;
import dev.archfiend.config.ConfigManager;
import dev.archfiend.drop.DropTier;
import dev.archfiend.drop.TestDrops;
import dev.archfiend.repo.NeuRepoManager;
import dev.archfiend.sequence.SequencePlayer;
import dev.archfiend.sequence.SequenceStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Vanilla-style settings screen. Every control writes straight into the live config so changes take
 * effect immediately; the file is saved (and the repo refreshed if its settings changed) when the
 * screen closes.
 */
public final class ArchfiendSettingsScreen extends OptionsSubScreen
{
    private static final String KEY = "archfiend.settings.";
    private static final int FOOTER_BUTTON_WIDTH = 150;
    /** Slider steps, so the sliders snap to round values instead of raw ticks/floats. */
    private static final int TICKS_PER_STEP = 5;
    private static final float SCALE_PER_STEP = 0.05f;
    private static final float VOLUME_PER_STEP = 0.1f;

    private static final int READY_COLOR = 0x55FF55;
    private static final int BUSY_COLOR = 0xFFFF55;
    private static final int FAILED_COLOR = 0xFF5555;
    private static final int DISABLED_COLOR = 0xAAAAAA;

    private final ConfigManager configManager;
    private final SequencePlayer player;
    private final NeuRepoManager repoManager;
    private final ArchfiendConfig config;
    private final RepoSnapshot repoAtOpen;

    private @Nullable Button repoButton;
    private String lastRepoStatus = "";

    /** Repo settings that require a refresh when changed. */
    private record RepoSnapshot(boolean enabled, boolean autoUpdate)
    {
        static RepoSnapshot of(ArchfiendConfig.Repo repo)
        {
            return new RepoSnapshot(repo.enabled, repo.autoUpdate);
        }
    }

    public ArchfiendSettingsScreen(@Nullable Screen parent, ConfigManager configManager, SequencePlayer player, NeuRepoManager repoManager)
    {
        super(parent, Minecraft.getInstance().options, Component.translatable(KEY + "title"));
        this.configManager = configManager;
        this.player = player;
        this.repoManager = repoManager;
        this.config = configManager.get();
        this.repoAtOpen = RepoSnapshot.of(config.repo);
    }

    @Override
    protected void addOptions()
    {
        if (list == null) return;
        ArchfiendConfig c = config;

        list.addHeader(Component.translatable(KEY + "section.sequence"));
        list.addSmall(
            bool("enabled", c.enabled, v -> c.enabled = v),
            cycle("style", SequenceStyle.values(), c.style,
                style -> Component.translatable(KEY + "style." + style.commandName()), v -> c.style = v)
        );
        list.addSmall(
            cycle("min_tier", DropTier.values(), c.minTier,
                tier -> Component.literal(tier.shortName()).withColor(tier.defaultColor), v -> c.minTier = v),
            bool("delay_chat", c.delayChatMessage, v -> c.delayChatMessage = v)
        );
        list.addSmall(
            slider("spin", ArchfiendConfig.MIN_SPIN_TICKS / TICKS_PER_STEP, ArchfiendConfig.MAX_SPIN_TICKS / TICKS_PER_STEP,
                Math.round((float) c.spinTicks / TICKS_PER_STEP), step -> seconds(step * TICKS_PER_STEP),
                step -> c.spinTicks = step * TICKS_PER_STEP),
            slider("reveal", 2, 40, Math.round((float) c.revealTicks / TICKS_PER_STEP),
                step -> seconds(step * TICKS_PER_STEP), step -> c.revealTicks = step * TICKS_PER_STEP)
        );
        list.addSmall(
            slider("scale", Math.round(ArchfiendConfig.MIN_SCALE / SCALE_PER_STEP), Math.round(ArchfiendConfig.MAX_SCALE / SCALE_PER_STEP),
                Math.round(c.scale / SCALE_PER_STEP), step -> String.format(Locale.ROOT, "%.2fx", step * SCALE_PER_STEP),
                step -> c.scale = step * SCALE_PER_STEP),
            slider("volume", 0, 20, Math.round(c.volume / VOLUME_PER_STEP),
                step -> Math.round(step * VOLUME_PER_STEP * 100) + "%", step -> c.volume = step * VOLUME_PER_STEP)
        );
        list.addBig(bool("hide_hud", c.hideHud, v -> c.hideHud = v));

        list.addHeader(Component.translatable(KEY + "section.repo"));
        list.addSmall(
            bool("repo_enabled", c.repo.enabled, v -> c.repo.enabled = v),
            bool("repo_auto_update", c.repo.autoUpdate, v -> c.repo.autoUpdate = v)
        );
        repoButton = Button.builder(repoLabel(), button -> repoManager.refreshAsync(true)).build();
        list.addBig(repoButton);
        refreshRepoButton();
    }

    @Override
    protected void addFooter()
    {
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        Button preview = footer.addChild(Button.builder(Component.translatable(KEY + "preview"), button -> preview())
            .width(FOOTER_BUTTON_WIDTH)
            .tooltip(Tooltip.create(Component.translatable(KEY + "preview.tooltip")))
            .build());
        preview.active = minecraft.player != null;
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(FOOTER_BUTTON_WIDTH).build());
    }

    @Override
    public void tick()
    {
        super.tick();
        refreshRepoButton();
    }

    @Override
    public void removed()
    {
        super.removed();
        config.sanitize();
        configManager.save();
        if (!RepoSnapshot.of(config.repo).equals(repoAtOpen)) repoManager.refreshAsync(false);
    }

    /** Closes every screen so the spin is visible over the game, then plays the configured minimum tier. */
    private void preview()
    {
        DropTier tier = config.minTier;
        minecraft.gui.setScreen(null); // triggers removed(), which saves the settings first
        player.enqueuePreview(TestDrops.create(config, tier, null));
    }

    private void refreshRepoButton()
    {
        if (repoButton == null) return;
        String status = repoManager.describe();
        if (status.equals(lastRepoStatus)) return;
        lastRepoStatus = status;
        repoButton.setMessage(repoLabel());
        repoButton.setTooltip(Tooltip.create(Component.literal(status)
            .append("\n\n")
            .append(Component.translatable(KEY + "repo_update.tooltip"))));
    }

    private Component repoLabel()
    {
        NeuRepoManager.State state = repoManager.state();
        int color = switch (state)
        {
            case READY -> READY_COLOR;
            case CHECKING, DOWNLOADING, LOADING -> BUSY_COLOR;
            case FAILED -> FAILED_COLOR;
            case DISABLED, IDLE -> DISABLED_COLOR;
        };
        Component stateName = Component.translatable(KEY + "repo_state." + state.name().toLowerCase(Locale.ROOT)).withColor(color);
        return Options.genericValueLabel(Component.translatable(KEY + "repo_update"), stateName);
    }

    private static OptionInstance<Boolean> bool(String key, boolean initial, Consumer<Boolean> onChange)
    {
        return OptionInstance.createBoolean(KEY + key, tooltip(key), initial, onChange::accept);
    }

    private static <T extends Enum<T>> OptionInstance<T> cycle(String key, T[] values, T initial, Function<T, Component> name, Consumer<T> onChange)
    {
        Codec<T> codec = Codec.INT.xmap(index -> values[Math.floorMod(index, values.length)], Enum::ordinal);
        return new OptionInstance<>(
            KEY + key,
            tooltip(key),
            (caption, value) -> name.apply(value), // CycleButton prepends the caption itself
            new OptionInstance.Enum<>(List.copyOf(Arrays.asList(values)), codec),
            initial,
            onChange::accept
        );
    }

    private static OptionInstance<Integer> slider(String key, int minStep, int maxStep, int initialStep, IntFunction<String> label, Consumer<Integer> onChange)
    {
        int initial = Math.clamp(initialStep, minStep, maxStep);
        return new OptionInstance<>(
            KEY + key,
            tooltip(key),
            (caption, value) -> Options.genericValueLabel(caption, Component.literal(label.apply(value))),
            new OptionInstance.IntRange(minStep, maxStep, true),
            initial,
            onChange::accept
        );
    }

    private static <T> OptionInstance.TooltipSupplier<T> tooltip(String key)
    {
        return OptionInstance.cachedConstantTooltip(Component.translatable(KEY + key + ".tooltip"));
    }

    private static String seconds(int ticks)
    {
        return String.format(Locale.ROOT, "%.2fs", ticks / 20f);
    }
}
