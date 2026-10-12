package dev.archfiend.sequence;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

/** UI sounds for a sequence, scaled by the configured master volume. */
public record SequenceSounds(float volume)
{
    public void tick(float pitch)
    {
        play(SoundEvents.UI_BUTTON_CLICK, pitch, 0.25f);
    }

    public void slotTick(float pitch)
    {
        play(SoundEvents.NOTE_BLOCK_HAT, pitch, 0.35f);
    }

    public void reelStop(int reelIndex)
    {
        play(SoundEvents.NOTE_BLOCK_BASEDRUM, 0.8f + reelIndex * 0.12f, 0.7f);
        play(SoundEvents.NOTE_BLOCK_CHIME, 1.0f + reelIndex * 0.25f, 0.35f);
    }

    public void lever()
    {
        play(SoundEvents.VAULT_INSERT_ITEM, 0.6f, 0.6f);
    }

    public void reveal(boolean jackpot)
    {
        if (jackpot)
        {
            play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
            play(SoundEvents.BEACON_ACTIVATE, 1.4f, 0.5f);
        }
        else
        {
            play(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.6f);
            play(SoundEvents.NOTE_BLOCK_PLING, 2f, 0.5f);
        }
    }

    private void play(Holder<SoundEvent> sound, float pitch, float baseVolume)
    {
        play(sound.value(), pitch, baseVolume);
    }

    private void play(SoundEvent sound, float pitch, float baseVolume)
    {
        float effective = baseVolume * volume;
        if (effective <= 0f) return;
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(sound, Mth.clamp(pitch, 0.5f, 2f), effective)
        );
    }
}
