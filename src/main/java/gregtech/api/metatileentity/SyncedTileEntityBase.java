package gregtech.api.metatileentity;

import gregtech.api.block.BlockStateTileEntity;
import gregtech.api.metatileentity.interfaces.ISyncedTileEntity;
import gregtech.api.network.PacketDataList;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Consumer;

public abstract class SyncedTileEntityBase extends BlockStateTileEntity implements ISyncedTileEntity {

    public static final int SIZE_THRESHOLD = 10;
    private final PacketDataList updates = new PacketDataList();

    public @Nullable TileEntity getNeighbor(EnumFacing facing) {
        if (world == null || pos == null) return null;
        return world.getTileEntity(pos.offset(facing));
    }

    @Override
    public final void writeCustomData(int discriminator, @NotNull Consumer<@NotNull PacketBuffer> dataWriter) {
        ByteBuf backedBuffer = Unpooled.buffer();
        dataWriter.accept(new PacketBuffer(backedBuffer));
        byte[] updateData = Arrays.copyOfRange(backedBuffer.array(), 0, backedBuffer.writerIndex());
        this.updates.add(discriminator, updateData);
        notifyWorld();
    }

    /**
     * Adds all data packets from another synced tile entity. Useful when the old tile is replaced with a new one.
     *
     * @param syncedTileEntityBase other synced tile entity
     */
    public void addPacketsFrom(SyncedTileEntityBase syncedTileEntityBase) {
        if (this == syncedTileEntityBase || syncedTileEntityBase.updates.isEmpty()) return;
        boolean wasEmpty = this.updates.isEmpty();
        this.updates.addAll(syncedTileEntityBase.updates);
        syncedTileEntityBase.updates.clear();
        if (wasEmpty) notifyWorld(); // if the data is not empty we already notified the world
    }

    private void notifyWorld() {
        @SuppressWarnings("deprecation")
        IBlockState blockState = getBlockType().getStateFromMeta(getBlockMetadata());
        if (canNotifyWorld()) {
            world.notifyBlockUpdate(getPos(), blockState, blockState, 0);
        }
    }

    private boolean canNotifyWorld() {
        // short circuit with packet size to avoid too many hash lookups and instanceof casts
        if (updates.size() > SIZE_THRESHOLD && getWorld() instanceof WorldServer server) {
            int x = getPos().getX() >> 4;
            int z = getPos().getZ() >> 4;
            if (server.getPlayerChunkMap().contains(x, z)) {
                return true;
            } else {
                // cannot send, so clear
                updates.clear();
                return false;
            }
        }
        // assume we can send data regardless
        return true;
    }

    @Override
    public final @Nullable SPacketUpdateTileEntity getUpdatePacket() {
        if (this.updates.isEmpty()) {
            return null;
        }
        NBTTagCompound updateTag = new NBTTagCompound();
        updateTag.setTag("d", this.updates.dumpToNbt());
        return new SPacketUpdateTileEntity(getPos(), 0, updateTag);
    }

    @Override
    public final void onDataPacket(@NotNull NetworkManager net, @NotNull SPacketUpdateTileEntity pkt) {
        NBTTagCompound updateTag = pkt.getNbtCompound();
        NBTTagList listTag = updateTag.getTagList("d", Constants.NBT.TAG_COMPOUND);
        for (NBTBase entryBase : listTag) {
            NBTTagCompound entryTag = (NBTTagCompound) entryBase;
            for (String discriminatorKey : entryTag.getKeySet()) {
                ByteBuf backedBuffer = Unpooled.copiedBuffer(entryTag.getByteArray(discriminatorKey));
                int dataId = Integer.parseInt(discriminatorKey);
                ISyncedTileEntity.addCode(dataId, this);
                receiveCustomData(dataId, new PacketBuffer(backedBuffer));
                ISyncedTileEntity.checkData(backedBuffer);
            }
        }
    }

    @Override
    public final @NotNull NBTTagCompound getUpdateTag() {
        NBTTagCompound updateTag = super.getUpdateTag();
        ByteBuf backedBuffer = Unpooled.buffer();
        writeInitialSyncData(new PacketBuffer(backedBuffer));
        byte[] updateData = Arrays.copyOfRange(backedBuffer.array(), 0, backedBuffer.writerIndex());
        updateTag.setByteArray("d", updateData);
        return updateTag;
    }

    @Override
    public final void handleUpdateTag(@NotNull NBTTagCompound tag) {
        super.readFromNBT(tag); // deserializes Forge data and capabilities
        byte[] updateData = tag.getByteArray("d");
        ByteBuf backedBuffer = Unpooled.copiedBuffer(updateData);
        ISyncedTileEntity.track(this);
        receiveInitialSyncData(new PacketBuffer(backedBuffer));
        ISyncedTileEntity.checkData(backedBuffer);
    }
}
