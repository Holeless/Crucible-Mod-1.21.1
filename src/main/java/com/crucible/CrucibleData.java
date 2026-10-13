package com.crucible;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world storage of which crucible this world was created with (0 = none). */
public class CrucibleData extends SavedData {
    public static final String NAME = "crucible";
    public static final SavedData.Factory<CrucibleData> FACTORY =
            new SavedData.Factory<>(() -> new CrucibleData(0), CrucibleData::load, DataFixTypes.LEVEL);

    private final int level;

    public CrucibleData(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Level", level);
        return tag;
    }

    public static CrucibleData load(CompoundTag tag, HolderLookup.Provider registries) {
        return new CrucibleData(tag.getInt("Level"));
    }
}
