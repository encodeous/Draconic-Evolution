package com.brandon3055.draconicevolution.integration.computers;

import com.brandon3055.draconicevolution.blocks.reactor.tileentity.TileReactorStabilizer;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * CC Tweaked peripheral for the reactor stabilizer.
 * Extends the base reactor peripheral (control methods) with inventory methods so
 * ComputerCraft programs can push and pull fuel items to/from the reactor when it
 * is in the COLD state.
 */
public class PeripheralReactorStabilizer extends PeripheralReactorComponent {

    private final TileReactorStabilizer stabilizer;

    public PeripheralReactorStabilizer(TileReactorStabilizer tile) {
        super(tile);
        this.stabilizer = tile;
    }

    @Override
    public String getType() {
        return "draconic_reactor";
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof PeripheralReactorStabilizer o && stabilizer == o.stabilizer;
    }

    /** Allow other peripherals to resolve our IItemHandler via getTarget(). */
    @Override
    public Object getTarget() {
        return stabilizer;
    }

    // ── Inventory introspection ───────────────────────────────────────────────

    /** Returns the total number of fuel slots exposed by this inventory (always 6). */
    @LuaFunction(mainThread = true)
    public final int size() {
        return stabilizer.fuelInventory.getSlots();
    }

    /**
     * Returns a sparse table (1-indexed) mapping occupied slot numbers to
     * {@code {name, count}} pairs.
     */
    @LuaFunction(mainThread = true)
    public final Map<Integer, Object> list() {
        Map<Integer, Object> result = new HashMap<>();
        IItemHandler inv = stabilizer.fuelInventory;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("name", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                entry.put("count", stack.getCount());
                result.put(i + 1, entry);
            }
        }
        return result;
    }

    /**
     * Returns detailed information about the item in the given slot, or {@code null}
     * if the slot is empty.
     *
     * @param slot 1-indexed slot number.
     */
    @LuaFunction(mainThread = true)
    public final @Nullable Map<String, Object> getItemDetail(int slot) throws LuaException {
        IItemHandler inv = stabilizer.fuelInventory;
        assertSlotInRange(slot, inv.getSlots());
        ItemStack stack = inv.getStackInSlot(slot - 1);
        if (stack.isEmpty()) return null;
        Map<String, Object> result = new HashMap<>();
        result.put("name", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        result.put("count", stack.getCount());
        result.put("maxCount", stack.getMaxStackSize());
        return result;
    }

    /**
     * Returns the maximum number of items that can occupy the given slot.
     *
     * @param slot 1-indexed slot number.
     */
    @LuaFunction(mainThread = true)
    public final int getItemLimit(int slot) throws LuaException {
        IItemHandler inv = stabilizer.fuelInventory;
        assertSlotInRange(slot, inv.getSlots());
        return inv.getSlotLimit(slot - 1);
    }

    // ── Inter-peripheral item transfer ────────────────────────────────────────

    /**
     * Pushes items from this reactor's fuel inventory to another inventory peripheral.
     * Items can only be moved when the reactor is in the COLD state.
     *
     * @param computer  The computer making the request.
     * @param toName    The name of the target peripheral on the wired network.
     * @param fromSlot  The 1-indexed slot to push items from.
     * @param limit     Maximum number of items to move (optional).
     * @param toSlot    Target slot in the destination inventory (optional; tries all slots if absent).
     * @return Number of items successfully transferred.
     */
    @LuaFunction(mainThread = true)
    public final int pushItems(IComputerAccess computer, String toName, int fromSlot,
                               Optional<Integer> limit, Optional<Integer> toSlot) throws LuaException {
        IItemHandler from = stabilizer.fuelInventory;
        assertSlotInRange(fromSlot, from.getSlots());

        IPeripheral toPeripheral = computer.getAvailablePeripheral(toName);
        if (toPeripheral == null) throw new LuaException("Target '" + toName + "' does not exist");

        IItemHandler to = extractHandler(toPeripheral);
        if (to == null) throw new LuaException("Target '" + toName + "' is not an inventory");

        if (toSlot.isPresent()) assertSlotInRange(toSlot.get(), to.getSlots());

        return moveItems(from, fromSlot - 1, to, toSlot.map(s -> s - 1).orElse(-1),
                limit.orElse(Integer.MAX_VALUE));
    }

    /**
     * Pulls items from another inventory peripheral into this reactor's fuel inventory.
     * Items can only be moved when the reactor is in the COLD state.
     *
     * @param computer  The computer making the request.
     * @param fromName  The name of the source peripheral on the wired network.
     * @param fromSlot  The 1-indexed slot to pull items from.
     * @param limit     Maximum number of items to move (optional).
     * @param toSlot    Target slot in this inventory (optional; tries all slots if absent).
     * @return Number of items successfully transferred.
     */
    @LuaFunction(mainThread = true)
    public final int pullItems(IComputerAccess computer, String fromName, int fromSlot,
                               Optional<Integer> limit, Optional<Integer> toSlot) throws LuaException {
        IItemHandler to = stabilizer.fuelInventory;
        if (toSlot.isPresent()) assertSlotInRange(toSlot.get(), to.getSlots());

        IPeripheral fromPeripheral = computer.getAvailablePeripheral(fromName);
        if (fromPeripheral == null) throw new LuaException("Source '" + fromName + "' does not exist");

        IItemHandler from = extractHandler(fromPeripheral);
        if (from == null) throw new LuaException("Source '" + fromName + "' is not an inventory");

        assertSlotInRange(fromSlot, from.getSlots());

        return moveItems(from, fromSlot - 1, to, toSlot.map(s -> s - 1).orElse(-1),
                limit.orElse(Integer.MAX_VALUE));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertSlotInRange(int slot, int size) throws LuaException {
        if (slot < 1 || slot > size) {
            throw new LuaException("Slot out of range (expected 1 to " + size + ", got " + slot + ")");
        }
    }

    /**
     * Attempts to obtain an {@link IItemHandler} from an {@link IPeripheral} by looking up
     * the {@link Capabilities#ItemHandler BLOCK} capability on its backing block entity.
     */
    @Nullable
    private static IItemHandler extractHandler(IPeripheral peripheral) {
        Object target = peripheral.getTarget();
        if (target instanceof BlockEntity be && be.getLevel() != null) {
            return be.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), null);
        }
        return null;
    }

    /**
     * Moves up to {@code limit} items from slot {@code fromSlot} of {@code from} into
     * {@code to}.  When {@code toSlot} is {@code -1} all destination slots are tried in order.
     */
    private static int moveItems(IItemHandler from, int fromSlot, IItemHandler to, int toSlot, int limit) {
        int moved = 0;
        if (toSlot < 0) {
            for (int i = 0; i < to.getSlots(); i++) {
                if (moved >= limit) break;
                ItemStack available = from.extractItem(fromSlot, limit - moved, true);
                if (available.isEmpty()) break;
                ItemStack remaining = to.insertItem(i, available, true);
                int canInsert = available.getCount() - remaining.getCount();
                if (canInsert > 0) {
                    ItemStack actual = from.extractItem(fromSlot, canInsert, false);
                    to.insertItem(i, actual, false);
                    moved += actual.getCount();
                }
            }
        } else {
            ItemStack available = from.extractItem(fromSlot, limit, true);
            if (!available.isEmpty()) {
                ItemStack remaining = to.insertItem(toSlot, available, true);
                int canInsert = available.getCount() - remaining.getCount();
                if (canInsert > 0) {
                    ItemStack actual = from.extractItem(fromSlot, canInsert, false);
                    to.insertItem(toSlot, actual, false);
                    moved = actual.getCount();
                }
            }
        }
        return moved;
    }
}
