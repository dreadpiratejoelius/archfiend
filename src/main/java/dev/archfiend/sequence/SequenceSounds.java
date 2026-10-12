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

    public void chestThud(float impactSpeed)
    {
        float strength = Math.min(1f, impactSpeed / 14f);
        play(SoundEvents.WOOD_PLACE, 0.55f + 0.25f * (1f - strength), 0.4f + 0.6f * strength);
        if (strength > 0.6f) play(SoundEvents.NOTE_BLOCK_BASEDRUM, 0.6f, 0.6f * strength);
    }

    public void chestRattle()
    {
        play(SoundEvents.CHEST_LOCKED, 0.9f + (float) Math.random() * 0.3f, 0.5f);
    }

    public void chestOpen()
    {
        play(SoundEvents.CHEST_OPEN, 0.8f, 1f);
        play(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, 0.7f);
    }

    public void questionRise()
    {
        play(SoundEvents.ENCHANTMENT_TABLE_USE, 1.2f, 0.8f);
    }

    public void colorTick(float pitch)
    {
        play(SoundEvents.AMETHYST_BLOCK_CHIME, pitch, 0.45f);
    }

    public void colorSettle()
    {
        play(SoundEvents.BEACON_POWER_SELECT, 1.3f, 0.5f);
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
