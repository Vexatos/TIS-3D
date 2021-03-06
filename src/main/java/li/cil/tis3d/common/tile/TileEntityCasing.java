package li.cil.tis3d.common.tile;

import li.cil.tis3d.api.infrared.InfraredPacket;
import li.cil.tis3d.api.infrared.InfraredReceiver;
import li.cil.tis3d.api.machine.Casing;
import li.cil.tis3d.api.machine.Face;
import li.cil.tis3d.api.module.Module;
import li.cil.tis3d.common.Settings;
import li.cil.tis3d.common.inventory.InventoryCasing;
import li.cil.tis3d.common.inventory.SidedInventoryProxy;
import li.cil.tis3d.common.machine.CasingImpl;
import li.cil.tis3d.common.machine.CasingProxy;
import li.cil.tis3d.common.module.ModuleForwarder;
import li.cil.tis3d.common.network.Network;
import li.cil.tis3d.common.network.message.MessageCasingState;
import li.cil.tis3d.util.InventoryUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Tile entity for casings.
 * <p>
 * Manages modules installed in it and takes care of maintaining network state
 * with other casings (i.e. injects virtual forwarding module in slots between
 * two casing blocks to relay data between the casings).
 * <p>
 * Also takes care of notifying a connected controller if some state changed,
 * so that the controller can re-scan for a multi-block.
 * <p>
 * Casings do not tick. The modules installed in them are driven by a
 * controller (transitively) connected to their casing.
 */
public final class TileEntityCasing extends TileEntity implements SidedInventoryProxy, CasingProxy, InfraredReceiver {
    // --------------------------------------------------------------------- //
    // Persisted data

    private final InventoryCasing inventory = new InventoryCasing(this);
    private final CasingImpl casing = new CasingImpl(this);

    // --------------------------------------------------------------------- //
    // Computed data

    // NBT tag names.
    private static final String TAG_INVENTORY = "inventory";
    private static final String TAG_CASING = "casing";
    private static final String TAG_ENABLED = "enabled";

    private final TileEntityCasing[] neighbors = new TileEntityCasing[Face.VALUES.length];
    private TileEntityController controller;
    private boolean isEnabledClient = false;

    // --------------------------------------------------------------------- //
    // Networking

    public TileEntityController getController() {
        return controller;
    }

    public void setController(final TileEntityController controller) {
        this.controller = controller;
    }

    public boolean isEnabled() {
        if (getWorld() == null) {
            return false;
        }
        if (getWorld().isRemote) {
            return isEnabledClient;
        } else {
            return getController() != null && getController().getState() == TileEntityController.ControllerState.RUNNING;
        }
    }

    @SideOnly(Side.CLIENT)
    public void setEnabled(final boolean value) {
        isEnabledClient = value;
    }

    public void scheduleScan() {
        if (getWorld().isRemote) {
            return;
        }
        if (controller != null) {
            controller.scheduleScan();
        } else {
            // If we don't have a controller there either isn't one, or
            // the controller is in an error state. In the latter case we
            // have ot actively look for a controller and notify it.
            final TileEntityController controller = findController();
            if (controller != null) {
                controller.scheduleScan();
            }
        }
    }

    public void checkNeighbors() {
        // When a neighbor changed, check all neighbors and register them in
        // our tile entity. If a neighbor changed in that list, do a rescan
        // in our controller (if any).
        for (final EnumFacing facing : EnumFacing.VALUES) {
            final BlockPos neighborPos = getPos().offset(facing);
            if (getWorld().isBlockLoaded(neighborPos)) {
                // If we have a casing, set it as our neighbor.
                final TileEntity neighborTileEntity = getWorld().getTileEntity(neighborPos);
                if (neighborTileEntity instanceof TileEntityCasing) {
                    setNeighbor(Face.fromEnumFacing(facing), (TileEntityCasing) neighborTileEntity);
                } else {
                    setNeighbor(Face.fromEnumFacing(facing), null);
                }

                if (neighborTileEntity instanceof TileEntityController) {
                    // If we have a controller, clear the module on that face.
                    setModule(Face.fromEnumFacing(facing), null);

                    // If we have a controller and it's not our controller, tell our
                    // controller to do a re-scan (because now we have more than one
                    // controller, which is invalid).
                    if (getController() != neighborTileEntity && getController() != null) {
                        getController().scheduleScan();
                    }
                }
            } else {
                // Neighbor is in unloaded area.
                setNeighbor(Face.fromEnumFacing(facing), null);
            }
        }
    }

    public void onEnabled() {
        casing.onEnabled();
        sendState(true);
    }

    public void onDisabled() {
        casing.onDisabled();
        sendState(false);
    }

    public void stepModules() {
        casing.stepModules();
    }

    public void stepPipes() {
        casing.stepPipes();
    }

    // --------------------------------------------------------------------- //
    // IInventory

    @Override
    public boolean isUseableByPlayer(final EntityPlayer player) {
        if (worldObj.getTileEntity(pos) != this) return false;
        final double maxDistance = 64;
        return player.getDistanceSqToCenter(pos) <= maxDistance;
    }

    // --------------------------------------------------------------------- //
    // SidedInventoryProxy

    @Override
    public ISidedInventory getInventory() {
        return inventory;
    }

    // --------------------------------------------------------------------- //
    // CasingProxy

    @Override
    public Casing getCasing() {
        return casing;
    }

    // --------------------------------------------------------------------- //
    // InfraredReceiver

    @Override
    public void onInfraredPacket(final InfraredPacket packet, final MovingObjectPosition hit) {
        final Module module = getModule(Face.fromEnumFacing(hit.sideHit));
        if (module instanceof InfraredReceiver) {
            ((InfraredReceiver) module).onInfraredPacket(packet, hit);
        }
    }

    // --------------------------------------------------------------------- //
    // TileEntity

    @Override
    public void invalidate() {
        super.invalidate();
        if (controller != null) {
            controller.scheduleScan();
        }
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (controller != null) {
            controller.scheduleScan();
        }
    }

    @Override
    public void readFromNBT(final NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        load(nbt);
    }

    @Override
    public void writeToNBT(final NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        save(nbt);
    }

    @Override
    public void onDataPacket(final NetworkManager manager, final S35PacketUpdateTileEntity packet) {
        final NBTTagCompound nbt = packet.getNbtCompound();
        load(nbt);
        isEnabledClient = nbt.getBoolean(TAG_ENABLED);
    }

    @Override
    public Packet getDescriptionPacket() {
        final NBTTagCompound nbt = new NBTTagCompound();
        save(nbt);
        nbt.setBoolean(TAG_ENABLED, isEnabled());
        return new S35PacketUpdateTileEntity(pos, -1, nbt);
    }

    // --------------------------------------------------------------------- //

    private void setNeighbor(final Face face, final TileEntityCasing neighbor) {
        final TileEntityCasing oldNeighbor = neighbors[face.ordinal()];
        if (neighbor != oldNeighbor) {
            neighbors[face.ordinal()] = neighbor;
            scheduleScan();
        }

        // Ensure there are no modules installed between two casings.
        if (neighbors[face.ordinal()] != null) {
            InventoryUtils.drop(getWorld(), getPos(), this, face.ordinal(), getInventoryStackLimit(), Face.toEnumFacing(face));
            InventoryUtils.drop(neighbor.getWorld(), neighbor.getPos(), neighbor, face.getOpposite().ordinal(), neighbor.getInventoryStackLimit(), Face.toEnumFacing(face.getOpposite()));
        }

        // Adjust ports, connecting multiple casings.
        if (neighbor == null) {
            // No neighbor, remove the virtual connector module.
            if (casing.getModule(face) instanceof ModuleForwarder) {
                casing.setModule(face, null);
            }
            // Also remove it from our old neighbor, if we had one.
            if (oldNeighbor != null && oldNeighbor.casing.getModule(face.getOpposite()) instanceof ModuleForwarder) {
                oldNeighbor.casing.setModule(face.getOpposite(), null);
            }
        } else if (!(casing.getModule(face) instanceof ModuleForwarder)) {
            // Got a new connection, and we have not yet been set up by our
            // neighbor. Create a virtual module that will be responsible
            // for transferring data between the two casings.
            final ModuleForwarder forwarder = new ModuleForwarder(casing, face);
            final ModuleForwarder neighborForwarder = new ModuleForwarder(neighbor.casing, face.getOpposite());
            forwarder.setSink(neighborForwarder);
            neighborForwarder.setSink(forwarder);
            casing.setModule(face, forwarder);
            neighbor.casing.setModule(face.getOpposite(), neighborForwarder);
        }
    }

    private TileEntityController findController() {
        // List of processed tile entities to avoid loops.
        final Set<TileEntity> processed = new HashSet<>();
        // List of pending tile entities that still need to be scanned.
        final Queue<TileEntity> queue = new ArrayDeque<>();

        // Number of casings we encountered for optional early exit.
        int casings = 1;

        // Start at our location, keep going until there's nothing left to do.
        processed.add(this);
        queue.add(this);
        while (!queue.isEmpty()) {
            final TileEntity tileEntity = queue.remove();

            // Check what we have. We only add controllers and casings to this list,
            // so we can skip the type check in the else branch.
            if (tileEntity instanceof TileEntityController) {
                return (TileEntityController) tileEntity;
            } else /* if (tileEntity instanceof TileEntityCasing) */ {
                // We only allow a certain number of casings per multi-block, so
                // we can early exit if there are too many (because even if we
                // notified the controller, it'd enter an error state again anyway).
                if (++casings > Settings.maxCasingsPerController) {
                    return null;
                }

                // Keep looking...
                if (!TileEntityController.addNeighbors(tileEntity, processed, queue)) {
                    // Hit end of loaded area, so scheduling would just result in
                    // error again anyway.
                    return null;
                }
            }
        }
        return null;
    }

    private void sendState(final boolean state) {
        final MessageCasingState message = new MessageCasingState(this, state);
        Network.INSTANCE.getWrapper().sendToDimension(message, getWorld().provider.getDimensionId());
    }

    private void load(final NBTTagCompound nbt) {
        final NBTTagCompound inventoryNbt = nbt.getCompoundTag(TAG_INVENTORY);
        inventory.readFromNBT(inventoryNbt);

        final NBTTagCompound casingNbt = nbt.getCompoundTag(TAG_CASING);
        casing.readFromNBT(casingNbt);
    }

    private void save(final NBTTagCompound nbt) {
        final NBTTagCompound inventoryNbt = new NBTTagCompound();
        inventory.writeToNBT(inventoryNbt);
        nbt.setTag(TAG_INVENTORY, inventoryNbt);

        final NBTTagCompound casingNbt = new NBTTagCompound();
        casing.writeToNBT(casingNbt);
        nbt.setTag(TAG_CASING, casingNbt);
    }
}
