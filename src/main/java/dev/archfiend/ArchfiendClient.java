package dev.archfiend;

import com.mojang.blaze3d.platform.InputConstants;
import dev.archfiend.command.ArchfiendCommand;
import dev.archfiend.config.ConfigManager;
import dev.archfiend.repo.NeuRepoManager;
import dev.archfiend.sequence.SequencePlayer;
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
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArchfiendClient implements ClientModInitializer
{
    public static final String MOD_ID = "archfiend";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient()
    {
        ConfigManager configManager = new ConfigManager();
        configManager.load();

        NeuRepoManager repoManager = new NeuRepoManager(
            FabricLoader.getInstance().getConfigDir().resolve(MOD_ID),
            () -> configManager.get().repo,
            userAgent()
        );
        repoManager.refreshAsync(false);

        SequencePlayer player = new SequencePlayer(configManager, repoManager);
        ArchfiendCommand command = new ArchfiendCommand(configManager, player, repoManager);

        KeyMapping skipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.archfiend.skip",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            KeyMapping.Category.register(id(MOD_ID))
        ));

        ClientReceiveMessageEvents.ALLOW_GAME.register(player::onGameMessage);
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (skipKey.consumeClick()) player.skip();
            player.tick(minecraft);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> player.reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> repoManager.close());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> command.register(dispatcher));

        // Above chat and the player list so the reveal is never covered.
        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, id("drop_sequence"), player::render);

        LOGGER.info("Archfiend loaded");
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
