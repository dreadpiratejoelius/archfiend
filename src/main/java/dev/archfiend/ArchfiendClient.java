package dev.archfiend;

import com.mojang.blaze3d.platform.InputConstants;
import dev.archfiend.command.ArchfiendCommand;
import dev.archfiend.config.ConfigManager;
import dev.archfiend.drop.DropTier;
import dev.archfiend.gui.ArchfiendSettingsScreen;
import dev.archfiend.repo.NeuRepoManager;
import dev.archfiend.sequence.SequencePlayer;
import dev.archfiend.track.DropLog;
import dev.archfiend.track.DropTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class ArchfiendClient implements ClientModInitializer
{
    public static final String MOD_ID = "archfiend";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Identifier[] VANILLA_HUD = {
        VanillaHudElements.MISC_OVERLAYS, VanillaHudElements.CROSSHAIR, VanillaHudElements.SPECTATOR_MENU,
        VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR,
        VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH,
        VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP,
        VanillaHudElements.SPECTATOR_TOOLTIP, VanillaHudElements.MOB_EFFECTS, VanillaHudElements.BOSS_BAR,
        VanillaHudElements.SLEEP, VanillaHudElements.DEMO_TIMER, VanillaHudElements.SCOREBOARD,
        VanillaHudElements.OVERLAY_MESSAGE, VanillaHudElements.TITLE_AND_SUBTITLE, VanillaHudElements.CHAT,
        VanillaHudElements.PLAYER_LIST, VanillaHudElements.SUBTITLES
    };

    private static @Nullable ArchfiendClient instance;

    private ConfigManager configManager;
    private NeuRepoManager repoManager;
    private SequencePlayer player;
    private DropLog dropLog;
    /** Set by commands; opened on the next tick because the closing chat screen would replace it. */
    private boolean settingsRequested;

    @Override
    public void onInitializeClient()
    {
        instance = this;

        configManager = new ConfigManager();
        configManager.load();

        Path dataDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        repoManager = new NeuRepoManager(
            dataDir,
            () -> configManager.get().repo,
            userAgent()
        );
        repoManager.refreshAsync(false);

        dropLog = new DropLog(dataDir.resolve("drops.json"));
        dropLog.load();
        DropTracker tracker = new DropTracker(dropLog, record -> record.tier != DropTier.PET
            && (record.skyblockId == null || repoManager.repo().map(repo -> repo.canGoInSack(record.skyblockId)).orElse(true)));

        player = new SequencePlayer(configManager, repoManager, tracker);
        ArchfiendCommand command = new ArchfiendCommand(configManager, player, repoManager, tracker, () -> settingsRequested = true);

        KeyMapping.Category category = KeyMapping.Category.register(id(MOD_ID));
        KeyMapping skipKey = KeyMappingHelper.registerKeyMapping(unboundKey("key.archfiend.skip", category));
        KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(unboundKey("key.archfiend.settings", category));

        ClientReceiveMessageEvents.ALLOW_GAME.register(player::onGameMessage);
        ClientReceiveMessageEvents.GAME.register(tracker::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (skipKey.consumeClick()) player.skip();
            while (settingsKey.consumeClick()) settingsRequested = true;
            if (settingsRequested)
            {
                settingsRequested = false;
                minecraft.gui.setScreen(createSettingsScreen(null));
            }
            tracker.tick(minecraft);
            player.tick(minecraft);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
            player.reset();
            tracker.reset();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
            repoManager.close();
            dropLog.close();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> command.register(dispatcher));

        // Above chat and the player list so the reveal is never covered.
        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, id("drop_sequence"), player::render);
        hideVanillaHudDuringSequences();

        LOGGER.info("Archfiend loaded");
    }

    /**
     * Settings screen factory, also used by the optional Mod Menu integration.
     *
     * @param parent screen to return to when closed, or {@code null} to return to the game
     */
    public static Screen createSettingsScreen(@Nullable Screen parent)
    {
        ArchfiendClient mod = instance;
        if (mod == null) throw new IllegalStateException("Archfiend is not initialised");
        return new ArchfiendSettingsScreen(parent, mod.configManager, mod.player, mod.repoManager);
    }

    /** True while a sequence is playing with "Hide HUD" on; also read by the hand-hiding mixin. */
    public static boolean hudHidden()
    {
        ArchfiendClient mod = instance;
        return mod != null && mod.player != null && mod.player.hidesHud();
    }

    /** Wraps every vanilla HUD element so it is skipped while a sequence plays. Our own element is untouched. */
    private static void hideVanillaHudDuringSequences()
    {
        for (Identifier element : VANILLA_HUD)
        {
            HudElementRegistry.replaceElement(element, original -> (graphics, deltaTracker) -> {
                if (!hudHidden()) original.extractRenderState(graphics, deltaTracker);
            });
        }
    }

    private static KeyMapping unboundKey(String name, KeyMapping.Category category)
    {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category);
    }

    private static String userAgent()
    {
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        return MOD_ID + "/" + version;
    }

    public static Identifier id(String path)
    {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
