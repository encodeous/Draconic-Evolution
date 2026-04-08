package com.brandon3055.draconicevolution.blocks.reactor.tileentity;

import com.brandon3055.brandonscore.api.power.IOPStorage;
import com.brandon3055.brandonscore.capability.CapabilityOP;
import com.brandon3055.brandonscore.utils.EnergyUtils;
import com.brandon3055.draconicevolution.init.DEContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Created by brandon3055 on 18/01/2017.
 */
public class TileReactorStabilizer extends TileReactorComponent {

    public final IItemHandler fuelInventory = new FuelItemHandler();

    public TileReactorStabilizer(BlockPos pos, BlockState state) {
        super(DEContent.TILE_REACTOR_STABILIZER.get(), pos, state);
        OPExtractor opExtractor = new OPExtractor(this);
        capManager.set(CapabilityOP.BLOCK, opExtractor);
        capManager.setCapSideValidator(opExtractor, face -> face == this.facing.get().getOpposite());
        capManager.set(Capabilities.ItemHandler.BLOCK, fuelInventory);
    }

    public static void register(RegisterCapabilitiesEvent event) {
        energyCapability(event, DEContent.TILE_REACTOR_STABILIZER);
        capability(event, DEContent.TILE_REACTOR_STABILIZER, Capabilities.ItemHandler.BLOCK);
    }

    @Override
    public void tick() {
        super.tick();

        if (level.isClientSide) {
            return;
        }

        TileReactorCore tile = getCachedCore();

        if (tile != null && tile.reactorState.get() == TileReactorCore.ReactorState.RUNNING) {
            BlockEntity output = level.getBlockEntity(worldPosition.relative(facing.get().getOpposite()));
            if (output != null && EnergyUtils.canReceiveEnergy(output, facing.get())) {
                long sent = EnergyUtils.insertEnergy(output, tile.saturation.get(), facing.get(), false);
                tile.saturation.subtract(sent);
            }
        }
    }

    private class OPExtractor implements IOPStorage {
        private TileReactorStabilizer tile;

        public OPExtractor(TileReactorStabilizer tile) {
            this.tile = tile;
        }

        @Override
        public long extractOP(long maxExtract, boolean simulate) {
            TileReactorCore core = getCachedCore();
            if (core != null && core.reactorState.get() == TileReactorCore.ReactorState.RUNNING) {
                long subtracted = Math.min(core.saturation.get(), maxExtract);
                if (!simulate) {
                    core.saturation.subtract(subtracted);
                }
                return subtracted;
            }

            return 0;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return (int) extractOP(maxExtract, simulate);
        }

        @Override
        public long getMaxOPStored() {
            return Long.MAX_VALUE;
        }

        @Override
        public int getEnergyStored() {
            return 0;
        }

        @Override
        public int getMaxEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public long modifyEnergyStored(long amount) {
            return 0; //Invalid operation for this device
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    }

    /**
     * Exposes the reactor's fuel inventory for automation when the multiblock is formed and
     * the reactor is in the COLD state. Slots 0-2 represent reactable fuel (awakened draconium
     * block, ingot, nugget) and slots 3-5 represent converted fuel (large, medium, small chaos
     * fragment).
     */
    private class FuelItemHandler implements IItemHandler {

        private static final int SLOTS = 6;
        private static final int MAX_FUEL = 10368 + 15;

        @Override
        public int getSlots() {
            return SLOTS;
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            TileReactorCore core = getCachedCore();
            if (core == null) return ItemStack.EMPTY;
            int fuel = (int) core.reactableFuel.get();
            int chaos = (int) core.convertedFuel.get();
            return switch (slot) {
                case 0 -> {
                    int count = fuel / 1296;
                    yield count > 0 ? new ItemStack(DEContent.AWAKENED_DRACONIUM_BLOCK.get().asItem(), count) : ItemStack.EMPTY;
                }
                case 1 -> {
                    int count = (fuel % 1296) / 144;
                    yield count > 0 ? new ItemStack(DEContent.INGOT_DRACONIUM_AWAKENED.get(), count) : ItemStack.EMPTY;
                }
                case 2 -> {
                    int count = ((fuel % 1296) % 144) / 16;
                    yield count > 0 ? new ItemStack(DEContent.NUGGET_DRACONIUM_AWAKENED.get(), count) : ItemStack.EMPTY;
                }
                case 3 -> {
                    int count = chaos / 1296;
                    yield count > 0 ? new ItemStack(DEContent.CHAOS_FRAG_LARGE.get(), count) : ItemStack.EMPTY;
                }
                case 4 -> {
                    int count = (chaos % 1296) / 144;
                    yield count > 0 ? new ItemStack(DEContent.CHAOS_FRAG_MEDIUM.get(), count) : ItemStack.EMPTY;
                }
                case 5 -> {
                    int count = ((chaos % 1296) % 144) / 16;
                    yield count > 0 ? new ItemStack(DEContent.CHAOS_FRAG_SMALL.get(), count) : ItemStack.EMPTY;
                }
                default -> ItemStack.EMPTY;
            };
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            TileReactorCore core = getCachedCore();
            if (core == null || core.reactorState.get() != TileReactorCore.ReactorState.COLD) {
                return stack;
            }
            if (!isItemValid(slot, stack)) {
                return stack;
            }
            int fuelValue = fuelValueForSlot(slot);
            int free = MAX_FUEL - (int) (core.reactableFuel.get() + core.convertedFuel.get());
            if (free <= 0) return stack;
            int maxInsert = free / fuelValue;
            int toInsert = Math.min(stack.getCount(), maxInsert);
            if (toInsert <= 0) return stack;
            if (!simulate) {
                if (slot < 3) {
                    core.reactableFuel.add((double) toInsert * fuelValue);
                } else {
                    core.convertedFuel.add((double) toInsert * fuelValue);
                }
                core.setChanged();
            }
            if (toInsert == stack.getCount()) return ItemStack.EMPTY;
            ItemStack remaining = stack.copy();
            remaining.shrink(toInsert);
            return remaining;
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            TileReactorCore core = getCachedCore();
            if (core == null || core.reactorState.get() != TileReactorCore.ReactorState.COLD) {
                return ItemStack.EMPTY;
            }
            ItemStack current = getStackInSlot(slot);
            if (current.isEmpty()) return ItemStack.EMPTY;
            int toExtract = Math.min(amount, current.getCount());
            if (toExtract <= 0) return ItemStack.EMPTY;
            int fuelValue = fuelValueForSlot(slot);
            if (!simulate) {
                if (slot < 3) {
                    core.reactableFuel.subtract((double) toExtract * fuelValue);
                } else {
                    core.convertedFuel.subtract((double) toExtract * fuelValue);
                }
                core.setChanged();
            }
            ItemStack extracted = current.copy();
            extracted.setCount(toExtract);
            return extracted;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return switch (slot) {
                case 0 -> stack.getItem() == DEContent.AWAKENED_DRACONIUM_BLOCK.get().asItem();
                case 1 -> stack.getItem() == DEContent.INGOT_DRACONIUM_AWAKENED.get();
                case 2 -> stack.getItem() == DEContent.NUGGET_DRACONIUM_AWAKENED.get();
                case 3 -> stack.getItem() == DEContent.CHAOS_FRAG_LARGE.get();
                case 4 -> stack.getItem() == DEContent.CHAOS_FRAG_MEDIUM.get();
                case 5 -> stack.getItem() == DEContent.CHAOS_FRAG_SMALL.get();
                default -> false;
            };
        }

        private int fuelValueForSlot(int slot) {
            return switch (slot) {
                case 0, 3 -> 1296;
                case 1, 4 -> 144;
                case 2, 5 -> 16;
                default -> 0;
            };
        }
    }
}
