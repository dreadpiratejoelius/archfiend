package dev.archfiend.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.archfiend.ArchfiendClient;

/**
 * Adds the settings screen to Mod Menu's config button. Only loaded when Mod Menu is installed:
 * Fabric instantiates {@code modmenu} entrypoints solely on Mod Menu's request.
 */
public final class ModMenuIntegration implements ModMenuApi
{
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory()
    {
        return ArchfiendClient::createSettingsScreen;
    }
}
